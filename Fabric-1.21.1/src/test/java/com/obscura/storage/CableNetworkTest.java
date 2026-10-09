package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.inventory.InventoryCableNetwork;
import com.obscura.storage.inventory.InventoryCableNetwork.CableView;
import com.obscura.storage.inventory.InventoryCableNetwork.Node;
import com.obscura.storage.util.BlockFace;

/**
 * The cable graph logic of {@link InventoryCableNetwork} against a fake world, so the per-network
 * invalidation can be checked without a Level: a change only invalidates the network it touches,
 * and neighbour updates that do not change the graph (redstone, a level emitter switching its
 * output) invalidate nothing.
 */
@DisplayName("Cable network invalidation")
public class CableNetworkTest {

	enum Kind { CABLE, CONNECTOR, EMITTER }

	/** A network block in the fake world. {@code powered} stands for state the graph does not depend on. */
	static final class Block {
		final Kind kind;
		final Direction facing;
		boolean powered;

		Block(Kind kind, Direction facing) {
			this.kind = kind;
			this.facing = facing;
		}

		boolean canConnectFrom(Direction dir) {
			// Level emitter: only its back connects (LevelEmitterBlock.canConnectFrom)
			return kind != Kind.EMITTER || facing.getOpposite() == dir;
		}
	}

	/** Mirrors the real blocks' connection rules: cables only continue towards blocks that accept them. */
	static final class Grid implements CableView {
		final Map<BlockPos, Block> blocks = new HashMap<>();
		int lookups;

		@Override
		public long getGameTime() {
			return 0;
		}

		@Override
		public boolean isLoaded(BlockPos pos) {
			return true;
		}

		@Override
		public Node getNode(BlockPos pos) {
			lookups++;
			Block b = blocks.get(pos);
			if (b == null)return null;
			return new Node() {

				@Override
				public boolean canConnectFrom(Direction dir) {
					return b.canConnectFrom(dir);
				}

				@Override
				public boolean isFunctionalNode() {
					return b.kind != Kind.CABLE;
				}

				@Override
				public List<BlockFace> nextScan() {
					List<BlockFace> next = new ArrayList<>();
					if (b.kind == Kind.EMITTER) {
						next.add(new BlockFace(pos.relative(b.facing.getOpposite()), b.facing));
						return next;
					}
					for (Direction d : Direction.values()) {
						BlockPos q = pos.relative(d);
						Block o = blocks.get(q);
						// Cables only have a connection property towards blocks that accept them
						if (b.kind == Kind.CONNECTOR || (o != null && o.canConnectFrom(d.getOpposite())))
							next.add(new BlockFace(q, d.getOpposite()));
					}
					return next;
				}
			};
		}

		void line(BlockPos from, int length, Kind ends) {
			blocks.put(from, new Block(ends, null));
			for (int i = 1; i < length; i++)blocks.put(from.east(i), new Block(Kind.CABLE, null));
			blocks.put(from.east(length), new Block(ends, null));
		}
	}

	private static final BlockPos A = BlockPos.ZERO, B = new BlockPos(0, 0, 10);

	private static Set<BlockPos> nodes(InventoryCableNetwork net, BlockPos from) {
		return new HashSet<>(net.getNetworkNodes(from));
	}

	/** What the blocks around {@code pos} would report through neighborChanged. */
	private static void notifyNeighbours(InventoryCableNetwork net, Grid g, BlockPos pos, boolean wasNetworkBlock) {
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			if (g.blocks.containsKey(n))net.onNeighborChanged(n, wasNetworkBlock, pos);
		}
	}

	@Test
	@DisplayName("Breaking and re-placing a cable only re-traces that network")
	void breakAndJoinOnlyTouchesOneNetwork() {
		Grid g = new Grid();
		g.line(A, 6, Kind.CONNECTOR);
		g.line(B, 6, Kind.CONNECTOR);
		InventoryCableNetwork net = new InventoryCableNetwork(g, () -> 1000);
		assertEquals(Set.of(A, A.east(6)), nodes(net, A));
		assertEquals(Set.of(B, B.east(6)), nodes(net, B));

		g.lookups = 0;
		nodes(net, A);
		nodes(net, B);
		assertEquals(0, g.lookups, "both networks are cached");

		// Break the middle cable of network A (Block#destroy, then the neighbours' updates)
		BlockPos mid = A.east(3);
		g.blocks.remove(mid);
		long gen = net.getGeneration();
		net.markNodeInvalid(mid);
		notifyNeighbours(net, g, mid, true);
		assertNotEquals(gen, net.getGeneration());

		g.lookups = 0;
		assertEquals(Set.of(B, B.east(6)), nodes(net, B));
		assertEquals(0, g.lookups, "the other network must stay cached");
		assertEquals(Set.of(A), nodes(net, A), "split: each side only sees itself");
		assertEquals(Set.of(A.east(6)), nodes(net, A.east(6)));

		// Place it again: the two halves join
		g.blocks.put(mid, new Block(Kind.CABLE, null));
		notifyNeighbours(net, g, mid, false);
		assertEquals(Set.of(A, A.east(6)), nodes(net, A));
		assertEquals(Set.of(A, A.east(6)), nodes(net, A.east(6)));
		assertEquals(Set.of(A, A.east(6)), nodes(net, A.east(2)), "a query starting on a cable sees the same network");
	}

	@Test
	@DisplayName("Redstone next to a cable and a level emitter switching invalidate nothing")
	void noiseDoesNotInvalidate() {
		Grid g = new Grid();
		g.line(A, 6, Kind.CONNECTOR);
		// An emitter on top of cable 2 facing up: its back connects to the cable
		BlockPos emitter = A.east(2).above();
		g.blocks.put(emitter, new Block(Kind.EMITTER, Direction.UP));
		// And one on top of cable 4 facing down: adjacent, but not connected
		BlockPos away = A.east(4).above();
		g.blocks.put(away, new Block(Kind.EMITTER, Direction.DOWN));
		InventoryCableNetwork net = new InventoryCableNetwork(g, () -> 1000);
		assertEquals(Set.of(A, A.east(6), emitter), nodes(net, A));

		long gen = net.getGeneration();
		// Redstone dust next to a cable: not a network block before or after
		net.onNeighborChanged(A.east(1), false, A.east(1).north());
		// Both emitters switch their output (a block update from a network block, same shape)
		g.blocks.get(emitter).powered = true;
		notifyNeighbours(net, g, emitter, true);
		g.blocks.get(away).powered = true;
		notifyNeighbours(net, g, away, true);
		assertEquals(gen, net.getGeneration(), "nothing about the cable graph changed");

		g.lookups = 0;
		assertEquals(Set.of(A, A.east(6), emitter), nodes(net, A));
		assertEquals(0, g.lookups, "the network must still be cached");

		// Turning the far emitter around connects it: that is a real change
		g.blocks.put(away, new Block(Kind.EMITTER, Direction.UP));
		notifyNeighbours(net, g, away, true);
		assertNotEquals(gen, net.getGeneration());
		assertEquals(Set.of(A, A.east(6), emitter, away), nodes(net, A));
	}

	@Test
	@DisplayName("A cable placed next to a connector that had none is found")
	void firstCableNextToLoneConnector() {
		Grid g = new Grid();
		g.blocks.put(A, new Block(Kind.CONNECTOR, null));
		g.blocks.put(A.east(3), new Block(Kind.CONNECTOR, null));
		InventoryCableNetwork net = new InventoryCableNetwork(g, () -> 1000);
		assertEquals(Set.of(A), nodes(net, A));

		for (int i = 1; i <= 2; i++) {
			BlockPos p = A.east(i);
			g.blocks.put(p, new Block(Kind.CABLE, null));
			notifyNeighbours(net, g, p, false);
		}
		assertEquals(Set.of(A, A.east(3)), nodes(net, A));
	}
}
