package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.menu.TerminalCraftingFiller;
import com.obscura.storage.network.NetworkHandler;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

public class TerminalSyncManager {
	private static final int MAX_PACKET_SIZE = 64000;
	private static final int SYNC_INTERVAL = 5;
	private Object2IntMap<StoredItemStack> idMap = new Object2IntOpenHashMap<>();
	/**
	 * Reverse of {@link #idMap}. A hash map, not an array map: every put/get on an array map is a
	 * linear scan, which made a full sync of n items O(n^2) on both the server and the client.
	 */
	private Int2ObjectMap<StoredItemStack> idMap2 = new Int2ObjectOpenHashMap<>();
	private Map<StoredItemStack, StoredItemStack> items = new HashMap<>();
	private Map<StoredItemStack, StoredItemStack> itemList = new HashMap<>();
	private int lastId = 1, lastChangeID = -1;
	private int tickCounter;
	private RegistryFriendlyByteBuf workBuf;
	private String lastSearch = "";
	private String lastFilteredSearch = "";
	private int lastFilteredChangeCount = -1;
	private boolean lastUpdateCountsOnly;
	/** Server: packets and payload bytes sent since the last {@link #takeSentPackets}, for the Network Doctor. */
	private int sentPackets;
	private long sentBytes;

	public TerminalSyncManager(RegistryAccess reg) {
		// Constructed directly (same as Platform.makeRegByteBuf): keeps this class usable without the
		// mod's registration side effects, e.g. in unit tests.
		workBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(MAX_PACKET_SIZE, MAX_PACKET_SIZE * 2), reg);
	}

	public void setSearch(String search) {
		this.lastSearch = search != null ? search : "";
	}

	private void write(RegistryFriendlyByteBuf buf, StoredItemStack stack) {
		ItemStack st = stack.getStack();
		Item item = st.getItem();
		DataComponentPatch components = st.getComponentsPatch();
		byte flags = (byte) ((stack.getQuantity() == 0 ? 1 : 0) | (!components.isEmpty() ? 2 : 0));
		boolean wr = true;
		int id = idMap.getInt(stack);
		if(id != 0) {
			flags |= 4;
			wr = false;
		}
		buf.writeByte(flags);
		buf.writeVarInt(idMap.computeIfAbsent(stack, s -> {
			int i = lastId++;
			idMap2.put(i, (StoredItemStack) s);
			return i;
		}));
		if(wr)buf.writeVarInt(BuiltInRegistries.ITEM.getId(item));
		if(stack.getQuantity() != 0)buf.writeVarLong(stack.getQuantity());
		if(wr && !components.isEmpty())DataComponentPatch.STREAM_CODEC.encode(buf, components);
	}

	private void writeMiniStack(RegistryFriendlyByteBuf buf, StoredItemStack stack) {
		int id = idMap.getInt(stack);
		byte flags = (byte) ((stack.getQuantity() == 0 ? 1 : 0) | 2);
		buf.writeByte(flags);
		buf.writeVarInt(id);
		buf.writeVarInt(BuiltInRegistries.ITEM.getId(stack.getStack().getItem()));
		if(stack.getQuantity() != 0)buf.writeVarLong(stack.getQuantity());
		DataComponentPatch tag = DataComponentPatch.builder().set(DataComponents.LORE, new ItemLore(List.of(Component.translatable("tooltip.obscuras_storage.nbt_overflow").withStyle(ChatFormatting.RED)))).build();
		DataComponentPatch.STREAM_CODEC.encode(buf, tag);
	}

	private StoredItemStack read(RegistryFriendlyByteBuf buf) {
		byte flags = buf.readByte();
		int id = buf.readVarInt();
		boolean rd = (flags & 4) == 0;
		StoredItemStack stack;
		if(rd) {
			stack = new StoredItemStack(new ItemStack(BuiltInRegistries.ITEM.byId(buf.readVarInt())));
		} else {
			StoredItemStack cached = idMap2.get(id);
			if (cached == null) {
				stack = new StoredItemStack(ItemStack.EMPTY);
			} else {
				stack = new StoredItemStack(cached.getStack());
				// Same item and components: keep the lazily computed sort/search keys instead of
				// recomputing them for every count update.
				stack.copySortKeysFrom(cached);
			}
		}
		long count = (flags & 1) != 0 ? 0 : buf.readVarLong();
		stack.setCount(count);
		if(rd && (flags & 2) != 0) {
			DataComponentPatch dataComponentPatch = DataComponentPatch.STREAM_CODEC.decode(buf);
			stack.getStack().applyComponents(dataComponentPatch);
		}
		idMap.put(stack, id);
		idMap2.put(id, stack);
		return stack;
	}

	/**
	 * Drops the ids of stacks that are no longer in the network, in O(n). It is a no-op unless an id
	 * actually went stale: every stack in {@link #items} has been written at least once and so has
	 * an id, which means {@code idMap} only holds more entries than {@code items} once something
	 * left the network.
	 *
	 * @return whether anything was removed
	 */
	boolean compactIdMap() {
		if (idMap.size() <= items.size())return false;
		boolean removed = false;
		for (var itr = idMap.object2IntEntrySet().iterator(); itr.hasNext();) {
			Object2IntMap.Entry<StoredItemStack> e = itr.next();
			if (!items.containsKey(e.getKey())) {
				idMap2.remove(e.getIntValue());
				itr.remove();
				removed = true;
			}
		}
		return removed;
	}

	public void update(int changeID, Map<StoredItemStack, StoredItemStack> items, ServerPlayer player, Consumer<CompoundTag> extraSync) {
		updateTo(changeID, items, extraSync, t -> NetworkHandler.sendTo(player, t));
	}

	/** {@link #update(int, Map, ServerPlayer, Consumer)} with the packet sink split out, so it can be tested. */
	public void updateTo(int changeID, Map<StoredItemStack, StoredItemStack> items, Consumer<CompoundTag> extraSync, Consumer<CompoundTag> sender) {
		tickCounter++;
		if (tickCounter % 200 == 0) {
			compactIdMap();
		}
		if (changeID != lastChangeID) {
			lastChangeID = changeID;
			List<StoredItemStack> toWrite = new ArrayList<>();
			Set<StoredItemStack> found = new HashSet<>();
			items.forEach((s, c) -> {
				StoredItemStack pc = this.items.get(s);
				if(pc != null)found.add(s);
				if(pc == null || !c.equalDetails(pc)) {
					toWrite.add(c);
				}
			});
			this.items.forEach((s, c) -> {
				if(!found.contains(s))
					toWrite.add(new StoredItemStack(s.getStack(), 0L));
			});
			this.items.clear();
			this.items.putAll(items);
			// HUGE-005: Sort items so search-matching items are synced first
			// OPT-6.4: Only re-sort if search or item list actually changed
			if (!lastSearch.isEmpty() && (!lastSearch.equals(lastFilteredSearch) || changeID != lastFilteredChangeCount)) {
				lastFilteredSearch = lastSearch;
				lastFilteredChangeCount = changeID;
				String searchLower = lastSearch.toLowerCase();
				toWrite.sort((a, b) -> {
					boolean aMatches = a.getDisplayNameLower().contains(searchLower);
					boolean bMatches = b.getDisplayNameLower().contains(searchLower);
					if (aMatches == bMatches) return 0;
					return aMatches ? -1 : 1;
				});
			}
			if(!toWrite.isEmpty()) {
				workBuf.writerIndex(0);
				int j = 0;
				for (int i = 0; i < toWrite.size(); i++, j++) {
					StoredItemStack stack = toWrite.get(i);
					int li = workBuf.writerIndex();
					try {
						write(workBuf, stack);
					} catch (IndexOutOfBoundsException e) {
						workBuf.writerIndex(li);
						writeMiniStack(workBuf, stack);
					}
					int s = workBuf.writerIndex();
					if((s > MAX_PACKET_SIZE || j > 32000) && j > 1) {
						CompoundTag t = writeBuf("d", workBuf, li);
						t.putShort("l", (short) j);
						send(sender, t);
						j = 0;
						workBuf.writerIndex(0);
						if(s - li > MAX_PACKET_SIZE) {
							writeMiniStack(workBuf, stack);
						} else {
							workBuf.writeBytes(workBuf, li, s - li);
						}
					}
				}
				if(j > 0 || extraSync != null) {
					CompoundTag t;
					if(j > 0) {
						t = writeBuf("d", workBuf, workBuf.writerIndex());
						t.putShort("l", (short) j);
					} else t = new CompoundTag();
					if(extraSync != null)extraSync.accept(t);
					send(sender, t);
					return;
				}
			}
		}
		if(extraSync != null) {
			CompoundTag t = new CompoundTag();
			extraSync.accept(t);
			send(sender, t);
		}
	}

	private void send(Consumer<CompoundTag> sender, CompoundTag t) {
		sentPackets++;
		sentBytes += t.sizeInBytes();
		sender.accept(t);
	}

	/** Sync packets sent since the last call (then resets the count). */
	public int takeSentPackets() {
		int p = sentPackets;
		sentPackets = 0;
		return p;
	}

	/** Approximate payload bytes (NBT size) sent since the last call (then resets the count). */
	public long takeSentBytes() {
		long b = sentBytes;
		sentBytes = 0;
		return b;
	}

	public boolean receiveUpdate(RegistryAccess registryAccess, CompoundTag tag) {
		if(tag.contains("d")) {
			byte[] data = readBufData(tag, "d");
			if (data == null)return false;
			RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registryAccess);
			List<StoredItemStack> in = new ArrayList<>();
			short len = tag.getShort("l");
			for (int i = 0; i < len; i++) {
				in.add(read(buf));
			}
			boolean structural = false;
			for (StoredItemStack s : in) {
				if(s.getQuantity() == 0) {
					structural |= this.itemList.remove(s) != null;
				} else {
					structural |= this.itemList.put(s, s) == null;
				}
			}
			lastUpdateCountsOnly = !structural;
			return true;
		}
		return false;
	}

	/**
	 * Client: whether the last {@link #receiveUpdate} only changed the counts of items that were
	 * already listed (nothing added, nothing removed).
	 */
	public boolean wasLastUpdateCountsOnly() {
		return lastUpdateCountsOnly;
	}

	public void sendInteract(StoredItemStack intStack, SlotAction action, boolean mod) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		int flags = mod ? 1 : 0;
		if(intStack == null) {
			buf.writeByte(flags | 2);
		} else {
			buf.writeByte(flags);
			buf.writeVarInt(idMap.getInt(intStack));
			buf.writeVarLong(intStack.getQuantity());
		}
		buf.writeEnum(action);
		NetworkHandler.sendDataToServer(writeBuf("a", buf, buf.writerIndex()));
	}

	/**
	 * Payloads are written uncompressed: the connection already compresses every packet, and
	 * {@link #update} splits sync data into packets of about {@link #MAX_PACKET_SIZE} bytes, far
	 * below the custom payload limit. Inflating peer-supplied gzip data was an unbounded
	 * decompression bomb, so it is neither produced nor accepted.
	 */
	private CompoundTag writeBuf(String id, FriendlyByteBuf buf, int len) {
		byte[] data = new byte[len];
		buf.getBytes(0, data);
		CompoundTag tag = new CompoundTag();
		tag.putByteArray(id, data);
		return tag;
	}

	/** The raw payload, or null when it is flagged as compressed (sent by an older version): dropped. */
	private static byte[] readBufData(CompoundTag tag, String id) {
		if (tag.contains(id + "_c"))return null;
		return tag.getByteArray(id);
	}

	public void receiveInteract(CompoundTag tag, InteractHandler handler) {
		if(tag.contains("a")) {
			byte[] data = readBufData(tag, "a");
			if (data == null)return;
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
			byte flags = buf.readByte();
			StoredItemStack stack;
			if((flags & 2) != 0) {
				stack = null;
			} else {
				int interactId = buf.readVarInt();
				long count = buf.readVarLong();
				StoredItemStack cached = idMap2.get(interactId);
				if (cached == null) {
					stack = null;
				} else {
					stack = new StoredItemStack(cached.getStack());
					stack.setCount(count);
				}
			}
			handler.onInteract(stack, buf.readEnum(SlotAction.class), (flags & 1) != 0);
		}
	}

	public List<StoredItemStack> getAsList() {
		return new ArrayList<>(this.itemList.values());
	}

	public void fillStackedContents(StackedContents stc) {
		items.forEach((s, c) -> stc.accountSimpleStack(c.getActualStack()));
	}

	public long getAmount(StoredItemStack stack) {
		StoredItemStack s = itemList.get(stack);
		return s != null ? s.getQuantity() : 0L;
	}

	public static interface InteractHandler {
		void onInteract(StoredItemStack intStack, SlotAction action, boolean mod);
	}

	public static enum SlotAction {
		PULL_OR_PUSH_STACK, PULL_ONE, SPACE_CLICK, SHIFT_PULL, GET_HALF, GET_QUARTER, CRAFT,
		/** Item Locator: highlight every inventory holding the clicked item. Appended last to keep the ordinals stable. */
		LOCATE;
		public static final SlotAction[] VALUES = values();
	}

	public void fillCraftingFiller(TerminalCraftingFiller terminalCraftingFiller) {
		items.forEach((s, c) -> terminalCraftingFiller.accountStack(c.getActualStack()));
	}
}
