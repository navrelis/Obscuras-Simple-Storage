package com.obscura.storage.inventory;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Config;
import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.util.BlockFace;
import com.obscura.storage.util.WorldStates;

/**
 * Cable graphs of one dimension, cached per network.
 *
 * <p>A cached network is only dropped when something changes in or right next to it: a member is
 * removed ({@link #markNodeInvalid}), or a neighbour update reports that a position next to a
 * member now looks different to the cable graph ({@link #onNeighborChanged}). Every cached network
 * remembers the "shape" (see {@link #signature}) of each member and of each position adjacent to a
 * member, so a redstone update, a level emitter switching its output or a chest being filled next
 * to a cable no longer invalidates anything, and a real change only invalidates the network(s) it
 * touches instead of every network in the dimension.
 *
 * <p>{@link #getGeneration()} still moves on every real topology change anywhere in the dimension;
 * it tells connectors to look at their network again, which is cheap for the networks that stayed
 * cached.
 */
public class InventoryCableNetwork {
	/** Safety net: even an untouched network is re-traced after this many ticks. */
	private static final long MAX_AGE = 600;
	/** A network that ran into an unloaded chunk or the cable limit may grow once more is loaded. */
	private static final long PARTIAL_MAX_AGE = 40;
	/** {@link #signature} of a position without a network block, and of an unloaded one. */
	static final int NONE = 0, UNLOADED = -1;
	private static final Direction[] DIRS = Direction.values();

	/** What the cable graph is computed from; split out of Level so the graph logic can be tested. */
	public interface CableView {
		long getGameTime();
		boolean isLoaded(BlockPos pos);
		/** The network block at {@code pos}, or null when there is none. */
		@Nullable Node getNode(BlockPos pos);
	}

	/** One network block ({@link IInventoryCable}) in its current state. */
	public interface Node {
		boolean canConnectFrom(Direction dir);
		boolean isFunctionalNode();
		List<BlockFace> nextScan();
	}

	private final CableView view;
	private final IntSupplier maxCables;
	/** Cable position -> the latest cached network containing it. */
	private final Map<BlockPos, CableCache> byCable = new ConcurrentHashMap<>();
	/** Start position of a query (connectors, terminals... are not cables) -> its cached result. */
	private final Map<BlockPos, CableCache> byStart = new ConcurrentHashMap<>();
	/** Every cache that is still valid, for invalidation by position. */
	private final Set<CableCache> live = ConcurrentHashMap.newKeySet();
	private volatile long generation = 0;

	public InventoryCableNetwork(Level level) {
		this(new LevelView(level), () -> Config.get().invConnectorMaxCables);
	}

	public InventoryCableNetwork(CableView view, IntSupplier maxCables) {
		this.view = view;
		this.maxCables = maxCables;
	}

	public Collection<BlockPos> getNetworkNodes(BlockPos from) {
		long now = view.getGameTime();
		CableCache cache = byCable.get(from);
		if (cache == null || !cache.isValid(now))cache = byStart.get(from);
		if (cache != null && cache.isValid(now))return cache.attached;
		Set<BlockPos> checked = new HashSet<>();
		ArrayDeque<BlockFace> next = new ArrayDeque<>();
		Set<BlockPos> cables = new HashSet<>();
		Set<BlockPos> attached = new HashSet<>();
		Map<BlockPos, Integer> shapes = new HashMap<>();
		boolean partial = false;
		int max = maxCables.getAsInt();
		next.push(new BlockFace(from, null));
		while (!next.isEmpty()) {
			if (checked.size() >= max) {
				com.obscura.storage.StorageMod.LOGGER.warn("Cable network BFS reached max cable limit ({}) starting from {}. Network may be truncated.", max, from);
				partial = true;
				break;
			}
			BlockFace p = next.pop();
			if (checked.contains(p.pos()))continue;
			checked.add(p.pos());
			if (!view.isLoaded(p.pos())) {
				partial = true;
				continue;
			}
			Node n = view.getNode(p.pos());
			if (n != null && (p.from() == null || n.canConnectFrom(p.from()))) {
				CableCache cc = byCable.get(p.pos());
				if (cc != null && cc.isValid(now)) {
					cables.addAll(cc.cables);
					attached.addAll(cc.attached);
					checked.addAll(cc.cables);
					shapes.putAll(cc.shapes);
					partial |= cc.partial;
					continue;
				}
				if (n.isFunctionalNode())attached.add(p.pos());
				else cables.add(p.pos());
				recordShapes(shapes, p.pos(), n);
				next.addAll(n.nextScan());
			}
		}
		CableCache cc = new CableCache(from, cables, attached, shapes, now, partial);
		if (cables.isEmpty())return cc.attached;
		// Only cached when cables were reached. Every cable listens to its neighbours, so any change
		// that matters to this result is reported through a member. A result without cables can
		// depend on blocks that report nothing (a terminal or hopper with a cable placed in front of
		// it), and is cheap to trace again anyway.
		cables.forEach(p -> byCable.put(p, cc));
		byStart.put(from, cc);
		live.add(cc);
		for (CableCache c : live) {
			if (!c.isValid(now))drop(c);
		}
		return cc.attached;
	}

	/** Remembers how a member and its six neighbours look, see {@link #onNeighborChanged}. */
	private void recordShapes(Map<BlockPos, Integer> shapes, BlockPos pos, Node n) {
		shapes.put(pos, signature(n, pos));
		for (Direction d : DIRS) {
			BlockPos q = pos.relative(d);
			if (!shapes.containsKey(q))shapes.put(q, shapeAt(q));
		}
	}

	private int shapeAt(BlockPos pos) {
		return view.isLoaded(pos) ? signature(view.getNode(pos), pos) : UNLOADED;
	}

	/**
	 * Everything about a network block that the graph depends on: whether there is one, whether it is
	 * a functional node, which sides it accepts connections from and which positions it continues to.
	 * Anything else about the block (its redstone output, contents, ...) is irrelevant here.
	 */
	static int signature(@Nullable Node n, BlockPos pos) {
		if (n == null)return NONE;
		int s = 1;
		if (n.isFunctionalNode())s |= 2;
		for (Direction d : DIRS) {
			if (n.canConnectFrom(d))s |= 4 << d.ordinal();
		}
		int remote = 0;
		for (BlockFace f : n.nextScan()) {
			int dir = -1;
			for (Direction d : DIRS) {
				if (pos.relative(d).equals(f.pos())) {
					dir = d.ordinal();
					break;
				}
			}
			if (dir >= 0)s |= 256 << dir;
			else remote++;
		}
		return s | (remote << 14);
	}

	public long getGeneration() {
		return generation;
	}

	/** Drops every cached network {@code pos} is a member (or the query start) of. For removed or changed network blocks. */
	public void markNodeInvalid(BlockPos pos) {
		for (CableCache c : live) {
			if (c.contains(pos) || c.start.equals(pos))drop(c);
		}
		generation++;
	}

	public void markNodeInvalid(BlockFace pos) {
		markNodeInvalid(pos.pos());
	}

	/** Block#neighborChanged of a network block: see {@link #onNeighborChanged(BlockPos, boolean, BlockPos)}. */
	public void onNeighborChanged(BlockPos pos, Block neighborBlock, BlockPos fromPos) {
		onNeighborChanged(pos, neighborBlock instanceof IInventoryCable, fromPos);
	}

	/**
	 * The block at {@code fromPos}, next to the network block at {@code pos}, was updated.
	 * {@code neighborWasNetworkBlock}: the block that caused the update (the previous block when one
	 * was placed or removed) is a network block. Only invalidates when the position now looks
	 * different to the cable graph than when the network was traced.
	 */
	public void onNeighborChanged(BlockPos pos, boolean neighborWasNetworkBlock, BlockPos fromPos) {
		int now = shapeAt(fromPos);
		boolean member = false, changed = false;
		for (CableCache c : live) {
			if (!c.contains(pos))continue;
			member = true;
			Integer before = c.shapes.get(fromPos);
			if (before == null ? (now != NONE || neighborWasNetworkBlock) : before != now) {
				changed = true;
				break;
			}
		}
		// Nothing cached around pos: only a network block appearing, disappearing or changing next to
		// it can matter (a connector that had no cable yet must notice the first one).
		if (!member)changed = now != NONE || neighborWasNetworkBlock;
		if (changed) {
			markNodeInvalid(pos);
			markNodeInvalid(fromPos);
		}
	}

	private void drop(CableCache cc) {
		if (!live.remove(cc))return;
		cc.valid = false;
		cc.cables.forEach(p -> byCable.remove(p, cc));
		byStart.remove(cc.start, cc);
	}

	public static InventoryCableNetwork getNetwork(Level level) {
		return WorldStates.cableNetworks.computeIfAbsent(level, InventoryCableNetwork::new);
	}

	private static final class CableCache {
		private final BlockPos start;
		private final Set<BlockPos> cables, attached;
		/** {@link #signature} of every member and every position next to one, when traced. */
		private final Map<BlockPos, Integer> shapes;
		private final long created;
		private final boolean partial;
		private volatile boolean valid = true;

		CableCache(BlockPos start, Set<BlockPos> cables, Set<BlockPos> attached, Map<BlockPos, Integer> shapes, long created, boolean partial) {
			this.start = start;
			this.cables = cables;
			this.attached = attached;
			this.shapes = shapes;
			this.created = created;
			this.partial = partial;
		}

		boolean contains(BlockPos p) {
			return cables.contains(p) || attached.contains(p);
		}

		boolean isValid(long now) {
			long age = now - created;
			return valid && age >= 0 && age <= (partial ? PARTIAL_MAX_AGE : MAX_AGE);
		}
	}

	private static final class LevelView implements CableView {
		private final Level level;

		LevelView(Level level) {
			this.level = level;
		}

		@Override
		public long getGameTime() {
			return level.getGameTime();
		}

		@Override
		public boolean isLoaded(BlockPos pos) {
			return level.isLoaded(pos);
		}

		@Override
		public Node getNode(BlockPos pos) {
			BlockState st = level.getBlockState(pos);
			if (st.getBlock() instanceof IInventoryCable c)return new LevelNode(level, pos, st, c);
			return null;
		}
	}

	private static record LevelNode(Level level, BlockPos pos, BlockState state, IInventoryCable cable) implements Node {

		@Override
		public boolean canConnectFrom(Direction dir) {
			return cable.canConnectFrom(state, dir);
		}

		@Override
		public boolean isFunctionalNode() {
			return cable.isFunctionalNode();
		}

		@Override
		public List<BlockFace> nextScan() {
			return cable.nextScan(level, state, pos);
		}
	}
}
