package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

import com.obscura.storage.util.ItemLocator;
import com.obscura.storage.util.LocateSelection;
import com.obscura.storage.util.LocateSelection.Result;

/** Which inventories "Highlight Connected Inventories" sends: the player's dimension, nearest first, capped, each once. */
@DisplayName("Locate all: position selection")
public class LocateAllSelectionTest {
	private static final String OVERWORLD = "overworld";
	private static final String NETHER = "the_nether";

	/** A located inventory like the server finds it: a block and the dimension it is in. */
	private static record Loc(BlockPos pos, String dim) {}

	private static List<Loc> line(int count, String dim) {
		List<Loc> out = new ArrayList<>();
		for (int i = 0; i < count; i++)out.add(new Loc(new BlockPos(i, 64, 0), dim));
		return out;
	}

	private static Result select(List<Loc> located, String dimension, BlockPos origin, int cap) {
		return LocateSelection.select(located, Loc::pos, Loc::dim, dimension, origin, cap);
	}

	@Test
	@DisplayName("Item locate keeps its cap of 64, the highlight of the whole network sends up to 256")
	void caps() {
		assertEquals(64, ItemLocator.MAX_POSITIONS);
		assertEquals(256, ItemLocator.MAX_ALL_POSITIONS);
	}

	@Test
	@DisplayName("More inventories than the cap: the nearest 256 are sent, all are counted")
	void cappedToNearest() {
		List<Loc> all = line(300, OVERWORLD);
		Collections.shuffle(all, new java.util.Random(7));
		Result sel = select(all, OVERWORLD, new BlockPos(0, 64, 0), ItemLocator.MAX_ALL_POSITIONS);
		assertEquals(256, sel.positions().size());
		assertEquals(300, sel.inDimension());
		assertEquals(0, sel.otherDimensions());
		for (int i = 0; i < 256; i++)assertEquals(new BlockPos(i, 64, 0), sel.positions().get(i));
	}

	@Test
	@DisplayName("Positions are ordered by distance to the player")
	void nearestFirst() {
		List<Loc> all = List.of(
				new Loc(new BlockPos(40, 64, 0), OVERWORLD),
				new Loc(new BlockPos(-3, 64, 0), OVERWORLD),
				new Loc(new BlockPos(10, 64, 0), OVERWORLD),
				new Loc(new BlockPos(0, 70, 0), OVERWORLD));
		Result sel = select(all, OVERWORLD, new BlockPos(0, 64, 0), ItemLocator.MAX_ALL_POSITIONS);
		assertEquals(List.of(new BlockPos(-3, 64, 0), new BlockPos(0, 70, 0), new BlockPos(10, 64, 0), new BlockPos(40, 64, 0)), sel.positions());
	}

	@Test
	@DisplayName("Other dimensions are counted, never sent")
	void dimensionSplit() {
		List<Loc> all = new ArrayList<>(line(5, OVERWORLD));
		all.addAll(line(7, NETHER));
		Result sel = select(all, OVERWORLD, new BlockPos(0, 64, 0), ItemLocator.MAX_ALL_POSITIONS);
		assertEquals(5, sel.positions().size());
		assertEquals(5, sel.inDimension());
		assertEquals(7, sel.otherDimensions());
		assertTrue(sel.positions().stream().allMatch(p -> p.getZ() == 0));
		Result nether = select(all, NETHER, new BlockPos(0, 64, 0), ItemLocator.MAX_ALL_POSITIONS);
		assertEquals(7, nether.positions().size());
		assertEquals(5, nether.otherDimensions());
	}

	@Test
	@DisplayName("A location listed twice counts once; the same position in two dimensions is two inventories")
	void dedupe() {
		Loc a = new Loc(new BlockPos(1, 64, 1), OVERWORLD);
		Loc b = new Loc(new BlockPos(2, 64, 2), OVERWORLD);
		Result sel = select(List.of(a, b, a, new Loc(new BlockPos(1, 64, 1), OVERWORLD), b), OVERWORLD, BlockPos.ZERO, ItemLocator.MAX_ALL_POSITIONS);
		assertEquals(List.of(new BlockPos(1, 64, 1), new BlockPos(2, 64, 2)), sel.positions());
		assertEquals(2, sel.inDimension());
		Result both = select(List.of(a, new Loc(new BlockPos(1, 64, 1), NETHER)), OVERWORLD, BlockPos.ZERO, ItemLocator.MAX_ALL_POSITIONS);
		assertEquals(1, both.inDimension());
		assertEquals(1, both.otherDimensions());
	}

	@Test
	@DisplayName("Nothing connected: nothing is sent")
	void empty() {
		Result sel = select(List.of(), OVERWORLD, BlockPos.ZERO, ItemLocator.MAX_ALL_POSITIONS);
		assertTrue(sel.positions().isEmpty());
		assertEquals(0, sel.inDimension());
		assertEquals(0, sel.otherDimensions());
	}

	@Test
	@DisplayName("The item locator's own cap still applies when it is passed")
	void itemCap() {
		Result sel = select(line(100, OVERWORLD), OVERWORLD, BlockPos.ZERO, ItemLocator.MAX_POSITIONS);
		assertEquals(64, sel.positions().size());
		assertEquals(100, sel.inDimension());
	}
}
