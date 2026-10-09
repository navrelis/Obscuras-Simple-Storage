package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.gui.StorageTerminalMenu.SlotAction;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.platform.Platform;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;

public class TerminalSyncManager {
	private static final int MAX_PACKET_SIZE = 64000;
	/** Client: stack to id. */
	private Object2IntMap<StoredItemStack> idMap = new Object2IntOpenHashMap<>();
	/** Server: variant to id (keyed by variant, so no stack has to be built to look an id up). */
	private Object2IntMap<ItemKey> variantIds = new Object2IntOpenHashMap<>();
	// Open hash map: an array map makes every put/get linear, so a full sync of n items was O(n^2)
	private Int2ObjectMap<StoredItemStack> idMap2 = new Int2ObjectOpenHashMap<>();
	/** Server: what this viewer has been sent so far. */
	private Object2LongOpenHashMap<ItemKey> items = new Object2LongOpenHashMap<>();
	/** Server: the snapshot (and its version) the viewer is up to date with. */
	private StorageSnapshot lastSnapshot;
	private long lastVersion = -1;
	/** Client */
	private Map<StoredItemStack, StoredItemStack> itemList = new HashMap<>();
	private boolean structuralChange;
	/** Server: packets and payload bytes sent since the last {@link #takeSentPackets}, for the Network Doctor. */
	private int sentPackets;
	private long sentBytes;
	private int lastId = 1;
	private FriendlyByteBuf workBuf = new FriendlyByteBuf(Unpooled.buffer(MAX_PACKET_SIZE, MAX_PACKET_SIZE * 2));

	private int idOf(ItemKey variant, ItemStack stack) {
		int id = variantIds.getInt(variant);
		if(id == 0) {
			id = lastId++;
			variantIds.put(variant, id);
			idMap2.put(id, new StoredItemStack(stack, 1));
		}
		return id;
	}

	private void write(FriendlyByteBuf buf, ItemKey variant, long count) {
		ItemStack st = variant.toStack();
		Item item = st.getItem();
		CompoundTag compoundtag = Platform.getSyncTag(st);
		byte flags = (byte) ((count == 0 ? 1 : 0) | (compoundtag != null ? 2 : 0));
		boolean wr = true;
		int id = variantIds.getInt(variant);
		if(id != 0) {
			flags |= 4;
			wr = false;
		} else {
			id = idOf(variant, st);
		}
		buf.writeByte(flags);
		buf.writeVarInt(id);
		if(wr)buf.writeId(BuiltInRegistries.ITEM, item);
		if(count != 0)buf.writeVarLong(count);
		if(wr && compoundtag != null)buf.writeNbt(compoundtag);
	}

	private void writeMiniStack(FriendlyByteBuf buf, ItemKey variant, long count) {
		int id = idOf(variant, variant.toStack());
		byte flags = (byte) ((count == 0 ? 1 : 0) | 2);
		buf.writeByte(flags);
		buf.writeVarInt(id);
		buf.writeId(BuiltInRegistries.ITEM, variant.getItem());
		if(count != 0)buf.writeVarLong(count);
		CompoundTag tag = new CompoundTag();
		CompoundTag d = new CompoundTag();
		tag.put("display", d);
		ListTag lore = new ListTag();
		d.put("Lore", lore);
		lore.add(StringTag.valueOf("{\"translate\":\"tooltip.obscuras_storage.nbt_overflow\",\"color\":\"red\"}"));
		tag.putInt("uid", id);
		buf.writeNbt(tag);
	}

	private StoredItemStack read(FriendlyByteBuf buf) {
		byte flags = buf.readByte();
		int id = buf.readVarInt();
		boolean rd = (flags & 4) == 0;
		StoredItemStack stack;
		if(rd) {
			stack = new StoredItemStack(new ItemStack(buf.readById(BuiltInRegistries.ITEM)));
		} else {
			stack = new StoredItemStack(idMap2.get(id).getStack());
		}
		long count = (flags & 1) != 0 ? 0 : buf.readVarLong();
		stack.setCount(count);
		if(rd && (flags & 2) != 0) {
			stack.getStack().setTag(buf.readNbt());
		}
		idMap.put(stack, id);
		idMap2.put(id, stack);
		return stack;
	}

	/**
	 * Sends the difference between what this viewer has and {@code snapshot}.
	 *
	 * <p>While the snapshot is unchanged this does nothing at all, and after a change only the changed
	 * variants are looked at (the snapshot keeps a change log), so the cost per tick doesn't depend on
	 * the number of item types in the network.
	 */
	public void update(StorageSnapshot snapshot, ServerPlayer player, Consumer<CompoundTag> extraSync) {
		List<ItemKey> toWrite = null;
		if(snapshot != lastSnapshot || snapshot.getVersion() != lastVersion) {
			toWrite = new ObjectArrayList<>();
			if(snapshot == lastSnapshot && snapshot.canDiffSince(lastVersion)) {
				ObjectLinkedOpenHashSet<ItemKey> changed = new ObjectLinkedOpenHashSet<>();
				snapshot.collectChangedSince(lastVersion, changed);
				for (ItemKey v : changed) {
					long now = snapshot.getCount(v);
					long before = items.getLong(v);
					if(now != before) {
						toWrite.add(v);
						if(now == 0)items.removeLong(v);
						else items.put(v, now);
					}
				}
			} else {
				// First update, another snapshot (the terminal moved to a different network) or the log was trimmed
				for (Object2LongMap.Entry<ItemKey> e : Object2LongMaps.fastIterable(snapshot.counts())) {
					if(items.getLong(e.getKey()) != e.getLongValue())toWrite.add(e.getKey());
				}
				for (ItemKey v : items.keySet()) {
					if(!snapshot.contains(v))toWrite.add(v);
				}
				items.clear();
				items.putAll(snapshot.counts());
			}
			lastSnapshot = snapshot;
			lastVersion = snapshot.getVersion();
		}
		if(toWrite != null && !toWrite.isEmpty()) {
			workBuf.writerIndex(0);
			int j = 0;
			for (int i = 0; i < toWrite.size(); i++, j++) {
				ItemKey variant = toWrite.get(i);
				long count = snapshot.getCount(variant);
				int li = workBuf.writerIndex();
				try {
					write(workBuf, variant, count);
				} catch (IndexOutOfBoundsException e) {
					workBuf.writerIndex(li);
					writeMiniStack(workBuf, variant, count);
				}
				int s = workBuf.writerIndex();
				if((s > MAX_PACKET_SIZE || j > 32000) && j > 1) {
					CompoundTag t = writeBuf("d", workBuf, li);
					t.putShort("l", (short) j);
					send(player, t);
					j = 0;
					workBuf.writerIndex(0);
					if(s - li > MAX_PACKET_SIZE) {
						writeMiniStack(workBuf, variant, count);
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
				send(player, t);
			}
		} else if(extraSync != null) {
			CompoundTag t = new CompoundTag();
			extraSync.accept(t);
			send(player, t);
		}
	}

	private void send(ServerPlayer player, CompoundTag t) {
		sentPackets++;
		sentBytes += t.sizeInBytes();
		NetworkHandler.sendTo(player, t);
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

	public boolean receiveUpdate(CompoundTag tag) {
		if(tag.contains("d")) {
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(tag.getByteArray("d")));
			List<StoredItemStack> in = new ArrayList<>();
			short len = tag.getShort("l");
			for (int i = 0; i < len; i++) {
				in.add(read(buf));
			}
			structuralChange = false;
			in.forEach(s -> {
				if(s.getQuantity() == 0) {
					StoredItemStack old = this.itemList.remove(s);
					if(old != null) {
						// Lists that still hold the old object (ghost items) show it as empty
						old.setCount(0);
						structuralChange = true;
					}
				} else {
					StoredItemStack old = this.itemList.get(s);
					if(old != null) {
						// Only the amount changed: update in place, the object is shared by the sorted lists
						old.setCount(s.getQuantity());
					} else {
						this.itemList.put(s, s);
						structuralChange = true;
					}
				}
			});
			return true;
		}
		return false;
	}

	/** Client: whether the last {@link #receiveUpdate} added or removed an item type, as opposed to only changing amounts. */
	public boolean isStructuralChange() {
		return structuralChange;
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

	private CompoundTag writeBuf(String id, FriendlyByteBuf buf, int len) {
		byte[] data = new byte[len];
		buf.getBytes(0, data);
		CompoundTag tag = new CompoundTag();
		tag.putByteArray(id, data);
		return tag;
	}

	public void receiveInteract(CompoundTag tag, InteractHandler handler) {
		if(tag.contains("a")) {
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(tag.getByteArray("a")));
			byte flags = buf.readByte();
			StoredItemStack stack;
			if((flags & 2) != 0) {
				stack = null;
			} else {
				stack = new StoredItemStack(idMap2.get(buf.readVarInt()).getStack());
				long count = buf.readVarLong();
				stack.setCount(count);
			}
			handler.onInteract(stack, buf.readEnum(SlotAction.class), (flags & 1) != 0);
		}
	}

	public List<StoredItemStack> getAsList() {
		return new ArrayList<>(this.itemList.values());
	}

	public void fillStackedContents(StackedContents stc) {
		items.forEach((v, c) -> {
			ItemStack st = v.toStack();
			st.setCount((int) Math.min(c, Integer.MAX_VALUE));
			stc.accountSimpleStack(st);
		});
	}

	public long getAmount(StoredItemStack stack) {
		StoredItemStack s = itemList.get(stack);
		return s != null ? s.getQuantity() : 0L;
	}

	public static interface InteractHandler {
		void onInteract(StoredItemStack intStack, SlotAction action, boolean mod);
	}

	public void fillCraftingFiller(TerminalCraftingFiller terminalCraftingFiller) {
		items.forEach((v, c) -> {
			ItemStack st = v.toStack();
			st.setCount((int) Math.min(c, Integer.MAX_VALUE));
			terminalCraftingFiller.accountStack(st);
		});
	}
}
