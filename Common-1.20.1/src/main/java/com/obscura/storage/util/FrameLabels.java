package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;

import com.google.common.collect.MapMaker;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.network.NetworkHandler;

/**
 * Frame Labels: a glow item frame holding an item, hung on any face of a network inventory, makes
 * the network insert only that item there and fill it first (see {@link FrameLabel}).
 *
 * <h2>Cost</h2>
 * <ul>
 * <li>No entity scans on the server. Glow item frames are indexed per level by the block they hang
 * on, from the loaders' entity load/unload events (these also fire when a frame is placed, broken
 * by a player, an arrow or an explosion, or unloaded with its chunk).</li>
 * <li>The label is read from the indexed frames by the connectors' existing inventory scan: on
 * their 20 tick heartbeat, and within 5 ticks after a real change (a player changed a frame's item,
 * a frame was destroyed: it bumps {@link NetworkChanges}, like a block filter edit). A frame merely
 * loading with its chunk never bumps it: decorated chests far from any network would make every
 * connector rescan on every chunk load. One hash lookup per inventory, nothing when the level has
 * no glow frames. The heartbeat catches loaded labels and item changes nobody reports (dispensers,
 * commands, other mods, an arrow popping the item out): picked up within a second.</li>
 * <li>A player's frame interaction is looked at one tick later, which triggers the rescan right away
 * and the action bar message.</li>
 * <li>A label only replaces the network member when its contents changed; otherwise the connector
 * gets the same wrapper back and the network sees no change.</li>
 * </ul>
 * Labels are derived from the frames and never saved.
 */
public final class FrameLabels {
	/** Data packet key of the status list sent to players holding the Inventory Configurator. */
	public static final String STATUS_KEY = "obscuras_storage:frame_labels";
	/** Status flags: the labelled block is part of a network / a Configurator filter overrides the label. */
	public static final int CONNECTED = 1, OVERRIDDEN = 2;
	/** Configurator holders get the status of labels this close (blocks), the outline range. */
	public static final int RANGE = 16;
	/**
	 * How long a connector's "seen" mark counts, in ticks: two 20 tick heartbeats plus the 5 tick
	 * check, so a network that still has the block never lets it expire.
	 */
	static final long SEEN_TICKS = 45;
	private static final int STATUS_INTERVAL = 10;
	/** The action bar waits this long after a change, so the triggered rescan has marked the block. */
	private static final int MESSAGE_DELAY = 8;

	private static final Map<Level, LevelIndex> INDEX = new MapMaker().weakKeys().makeMap();
	/**
	 * The label wrapper in use per wrapped storage. Both sides weak, like BlockFilter's wrap cache:
	 * the wrapper references its storage, and stays reachable through the network using it.
	 */
	private static final Map<Object, Object> WRAPS = new MapMaker().weakKeys().weakValues().makeMap();
	private static final Map<ItemFrame, Pending> PENDING = new IdentityHashMap<>();
	private static final Map<UUID, ListTag> LAST_STATUS = new HashMap<>();

	private FrameLabels() {}

	/** The glow frames hanging on one block, and when a network last looked at it. */
	static final class Anchor {
		final List<ItemFrame> frames = new ArrayList<>(1);
		long connectedAt = Long.MIN_VALUE, overriddenAt = Long.MIN_VALUE;

		boolean connected(long now) {
			return now - connectedAt <= SEEN_TICKS;
		}

		boolean overridden(long now) {
			return now - overriddenAt <= SEEN_TICKS;
		}
	}

	static final class LevelIndex {
		final Map<BlockPos, Anchor> anchors = new HashMap<>();

		/**
		 * The anchors of the inventory at {@code pos}: its own and those of the other half of its
		 * double chest. Null when none.
		 */
		@Nullable
		List<Anchor> group(BlockPos pos, @Nullable BlockPos otherHalf) {
			Anchor self = anchors.get(pos);
			Anchor other = otherHalf != null ? anchors.get(otherHalf) : null;
			if (self == null && other == null)return null;
			List<Anchor> out = new ArrayList<>(2);
			if (self != null)out.add(self);
			if (other != null && other != self)out.add(other);
			return out;
		}
	}

	private record Pending(ServerPlayer player, ItemStack before, int due, ItemStack after) {}

	/** The block a frame hangs on. */
	public static BlockPos anchorOf(ItemFrame f) {
		return f.getPos().relative(f.getDirection().getOpposite());
	}

	/**
	 * The other half of the double chest at {@code pos}, with the rule the Inventory Connector uses
	 * (same block, opposite type, same facing); null for anything else.
	 */
	@Nullable
	public static BlockPos chestPartner(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock))return null;
		ChestType type = state.getValue(ChestBlock.TYPE);
		if (type == ChestType.SINGLE)return null;
		BlockPos opos = pos.relative(ChestBlock.getConnectedDirection(state));
		BlockState ostate = level.getBlockState(opos);
		if (ostate.getBlock() != state.getBlock())return null;
		ChestType otype = ostate.getValue(ChestBlock.TYPE);
		if (otype == ChestType.SINGLE || otype == type || state.getValue(ChestBlock.FACING) != ostate.getValue(ChestBlock.FACING))return null;
		return opos;
	}

	/** The level's index, or null when labels are off or no glow frame is loaded in it. */
	@Nullable
	private static LevelIndex index(Level level) {
		if (!Config.get().frameLabelsEnabled)return null;
		LevelIndex idx = INDEX.get(level);
		return idx == null || idx.anchors.isEmpty() ? null : idx;
	}

	// ------------------------------------------------------------------ index

	/**
	 * Entity loaded into a server level (Fabric ENTITY_LOAD, Forge EntityJoinLevelEvent). Only
	 * updates the index: the rescan trigger is global, and glow frames on chests far from any network
	 * are common decoration, so every chunk load would make every connector rescan. The connectors'
	 * 20 tick heartbeat picks a loaded label up (a newly loaded connector scans right away anyway).
	 */
	public static void onEntityLoad(Entity e, Level level) {
		if (!(e instanceof GlowItemFrame f) || level.isClientSide)return;
		BlockPos a = anchorOf(f);
		Anchor an = INDEX.computeIfAbsent(level, k -> new LevelIndex()).anchors.computeIfAbsent(a, k -> new Anchor());
		if (!an.frames.contains(f))an.frames.add(f);
	}

	/** Entity unloaded or removed from a server level (Fabric ENTITY_UNLOAD, Forge EntityLeaveLevelEvent). */
	public static void onEntityUnload(Entity e, Level level) {
		if (!(e instanceof GlowItemFrame f) || level.isClientSide)return;
		LevelIndex idx = INDEX.get(level);
		if (idx == null)return;
		BlockPos a = anchorOf(f);
		Anchor an = idx.anchors.get(a);
		if (an == null)return;
		an.frames.remove(f);
		if (an.frames.isEmpty())idx.anchors.remove(a);
		// A broken frame usually dropped its item just before: look again. Not for a frame that is
		// only unloaded with its chunk (the heartbeat still covers a chunk border between the two).
		Entity.RemovalReason r = f.getRemovalReason();
		if (r == null || r.shouldDestroy())changed(level, a);
	}

	/**
	 * A label may have changed: the connectors rescan at their next 5 tick check (same trigger as
	 * a block filter edit). Skipped for blocks known to hold no block entity (frames on walls); the
	 * heartbeat would still see a label on one of the rare inventories without a block entity.
	 */
	private static void changed(Level level, BlockPos a) {
		if (!Config.get().frameLabelsEnabled)return;
		LevelChunk c = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(a.getX()), SectionPos.blockToSectionCoord(a.getZ()));
		if (c != null && !c.getBlockState(a).hasBlockEntity())return;
		NetworkChanges.bump();
	}

	// ------------------------------------------------------------------ network

	/**
	 * {@code storage}, the network's view of the inventory at {@code pos}, with the label of the glow
	 * frames on it (or on the other half of its double chest), or {@code storage} itself when there is
	 * none. Called by the connectors' inventory scan, never when a Configurator filter covers the
	 * block (see {@link #markOverridden}). Marks the block as part of a network, for the feedback.
	 *
	 * <p>Hands back the wrapper already in use while the frames hold the same items, so an unchanged
	 * label never changes the network's members. Allocates nothing when the level has no glow frames.
	 *
	 * @param otherHalf the other half of a double chest when the caller knows it, else null
	 * @param findPartner whether to look for the other half itself (cable connectors)
	 * @param wrapper makes the loader's labelled storage
	 */
	@SuppressWarnings("unchecked")
	public static <S> S apply(Level level, BlockPos pos, @Nullable BlockPos otherHalf, boolean findPartner, S storage, BiFunction<S, FrameLabel, S> wrapper) {
		LevelIndex idx = index(level);
		if (idx == null || storage == null)return storage;
		if (otherHalf == null && findPartner)otherHalf = chestPartner(level, pos);
		List<Anchor> group = idx.group(pos, otherHalf);
		if (group == null)return storage;
		long now = level.getGameTime();
		Set<Item> items = null;
		List<ItemStack> filters = null;
		Map<ItemStack, ItemFrame> filterFrames = null;
		for (Anchor a : group) {
			a.connectedAt = now;
			for (ItemFrame f : a.frames) {
				if (!isLive(f))continue;
				ItemStack s = f.getItem();
				if (s.isEmpty())continue;
				if (FrameLabel.isFilterItem(s)) {
					if (filters == null) {
						filters = new ArrayList<>();
						filterFrames = new IdentityHashMap<>();
					}
					filters.add(s);
					filterFrames.put(s, f);
				} else {
					if (items == null)items = new HashSet<>();
					items.add(s.getItem());
				}
			}
		}
		if (items == null && filters == null)return storage;
		Set<Item> it = items != null ? items : Set.of();
		List<ItemStack> fl = filters != null ? filters : List.of();
		if (WRAPS.get(storage) instanceof FrameLabel.Labelled l && l.getLabel().sameContent(it, fl))return (S) l;
		Map<ItemStack, ItemFrame> ff = filterFrames;
		// The frame's own stack stays in the frame: filters read a copy
		FrameLabel label = new FrameLabel(it, fl, s -> createFilter(level, s.copy(), ff.get(s)));
		S w = wrapper.apply(storage, label);
		WRAPS.put(storage, w);
		return w;
	}

	/**
	 * Whether an indexed frame is really in its level. Forge reports a joining entity before it is
	 * added, and another mod could still refuse it; a hash lookup, only for frames on the scanned block.
	 */
	private static boolean isLive(ItemFrame f) {
		return !f.isRemoved() && f.level().getEntity(f.getId()) == f;
	}

	/** The filter of a framed filter item, looking at the block the frame hangs on (Polymorphic Filter). */
	@Nullable
	private static ItemPredicate createFilter(Level level, ItemStack s, @Nullable ItemFrame frame) {
		if (!(s.getItem() instanceof IItemFilter f) || frame == null)return null;
		return f.createFilter(new BlockFace(level, anchorOf(frame), frame.getDirection()), s);
	}

	/**
	 * A Configurator filter covers the block at {@code pos} (and the other half of its double chest):
	 * any label on them is ignored (the filter wins). Marks them for the feedback, which says
	 * "(overridden by filter)".
	 */
	public static void markOverridden(Level level, BlockPos pos, @Nullable BlockPos otherHalf, boolean findPartner) {
		LevelIndex idx = index(level);
		if (idx == null)return;
		if (otherHalf == null && findPartner)otherHalf = chestPartner(level, pos);
		long now = level.getGameTime();
		mark(idx.anchors.get(pos), now);
		if (otherHalf != null)mark(idx.anchors.get(otherHalf), now);
	}

	private static void mark(@Nullable Anchor a, long now) {
		if (a != null) {
			a.connectedAt = now;
			a.overriddenAt = now;
		}
	}

	/** Whether the block has glow frames that a network looked at recently (for tests and the feedback). */
	public static int statusAt(Level level, BlockPos pos) {
		LevelIndex idx = INDEX.get(level);
		if (idx == null)return 0;
		Anchor a = idx.anchors.get(pos);
		return a == null ? 0 : flags(a, level.getGameTime());
	}

	private static int flags(Anchor a, long now) {
		return (a.connected(now) ? CONNECTED : 0) | (a.overridden(now) ? OVERRIDDEN : 0);
	}

	// ------------------------------------------------------------------ player feedback

	/**
	 * A player right or left clicked an entity (the interaction itself happens right after). A glow
	 * frame's item is compared one tick later.
	 */
	public static void onFrameInteract(Player player, Entity target) {
		if (!(target instanceof GlowItemFrame f) || !(player instanceof ServerPlayer sp) || f.level().isClientSide)return;
		if (!Config.get().frameLabelsEnabled || PENDING.containsKey(f))return;
		PENDING.put(f, new Pending(sp, f.getItem().copy(), sp.server.getTickCount() + 1, null));
	}

	/** End of every server tick. Does nothing but a counter check unless a frame was clicked. */
	public static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		if (!PENDING.isEmpty())processPending(now);
		if (now % STATUS_INTERVAL == 0 && !server.getPlayerList().getPlayers().isEmpty())pushStatus(server);
	}

	private static void processPending(int now) {
		List<Map.Entry<ItemFrame, Pending>> later = null;
		for (Iterator<Map.Entry<ItemFrame, Pending>> itr = PENDING.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<ItemFrame, Pending> e = itr.next();
			Pending p = e.getValue();
			if (now < p.due())continue;
			itr.remove();
			ItemFrame f = e.getKey();
			if (p.after() == null) {
				ItemStack after = f.isRemoved() ? ItemStack.EMPTY : f.getItem();
				if (sameLabelItem(p.before(), after))continue;// rotated, or nothing happened
				changed(f.level(), anchorOf(f));
				if (later == null)later = new ArrayList<>();
				later.add(Map.entry(f, new Pending(p.player(), p.before(), now + MESSAGE_DELAY, after.copy())));
			} else {
				announce(f, p);
			}
		}
		if (later != null) {
			for (Map.Entry<ItemFrame, Pending> e : later)PENDING.putIfAbsent(e.getKey(), e.getValue());
		}
	}

	static boolean sameLabelItem(ItemStack a, ItemStack b) {
		if (a.isEmpty() || b.isEmpty())return a.isEmpty() == b.isEmpty();
		return ItemStack.isSameItemSameTags(a, b);
	}

	/** The action bar message, once, when the frame is on a block a network looked at. */
	private static void announce(ItemFrame f, Pending p) {
		if (p.player().hasDisconnected() || !Config.get().frameLabelsEnabled)return;
		Level level = f.level();
		LevelIndex idx = INDEX.get(level);
		if (idx == null)return;
		BlockPos a = anchorOf(f);
		List<Anchor> group = idx.group(a, chestPartner(level, a));
		if (group == null)return;
		long now = level.getGameTime();
		int flags = 0;
		for (Anchor an : group)flags |= flags(an, now);
		if ((flags & CONNECTED) == 0)return;
		MutableComponent msg = p.after().isEmpty()
				? Component.translatable("chat.obscuras_storage.frame_label.removed").withStyle(ChatFormatting.GRAY)
				: Component.translatable("chat.obscuras_storage.frame_label.set", p.after().getHoverName().copy().withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.AQUA);
		if ((flags & OVERRIDDEN) != 0)msg.append(" ").append(Component.translatable("tooltip.obscuras_storage.config_overlay.label.overridden").withStyle(ChatFormatting.DARK_GRAY));
		p.player().displayClientMessage(msg, true);
	}

	/**
	 * Every 10 ticks: players holding the Inventory Configurator get the status of the labelled
	 * blocks around them (is it on a network, does a Configurator filter override it), only when it
	 * changed. The client knows the frames itself; this is what it cannot know.
	 */
	private static void pushStatus(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ListTag st = holdsConfigurator(p) ? statusNear(p) : null;
			UUID id = p.getUUID();
			ListTag last = LAST_STATUS.get(id);
			if (st == null || st.isEmpty()) {
				if (last != null) {
					send(p, new ListTag());
					LAST_STATUS.remove(id);
				}
			} else if (!st.equals(last)) {
				send(p, st);
				LAST_STATUS.put(id, st);
			}
		}
	}

	private static boolean holdsConfigurator(Player p) {
		return p.getItemInHand(InteractionHand.MAIN_HAND).is(Content.invConfig.get()) || p.getItemInHand(InteractionHand.OFF_HAND).is(Content.invConfig.get());
	}

	private static ListTag statusNear(ServerPlayer p) {
		ListTag out = new ListTag();
		Level level = p.level();
		LevelIndex idx = index(level);
		if (idx == null)return out;
		long now = level.getGameTime();
		double r = RANGE + 2;
		for (Map.Entry<BlockPos, Anchor> e : idx.anchors.entrySet()) {
			BlockPos b = e.getKey();
			if (p.distanceToSqr(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5) > r * r)continue;
			out.add(new IntArrayTag(new int[] {b.getX(), b.getY(), b.getZ(), flags(e.getValue(), now)}));
		}
		return out;
	}

	private static void send(ServerPlayer p, ListTag list) {
		CompoundTag tag = new CompoundTag();
		tag.put(STATUS_KEY, list);
		NetworkHandler.sendTo(p, tag);
	}

	/** Nothing of a closed world may survive into the next one (integrated server: same JVM). */
	public static void clear() {
		INDEX.clear();
		WRAPS.clear();
		PENDING.clear();
		LAST_STATUS.clear();
	}
}
