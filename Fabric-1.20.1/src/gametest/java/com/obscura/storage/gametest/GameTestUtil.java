package com.obscura.storage.gametest;

import java.util.Objects;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

import com.obscura.storage.Content;
import com.obscura.storage.block.AbstractStorageTerminalBlock;
import com.obscura.storage.block.AbstractStorageTerminalBlock.TerminalPos;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.ConditionFilterState;
import com.obscura.storage.util.FilterCondition;
import com.obscura.storage.util.SimpleItemFilter;

/**
 * Shared helpers for the in-world tests. All positions are relative to the test (the empty 8x8x8
 * template); blocks are placed at y >= 2, inside x/z 1..7, so neighbouring tests never touch.
 */
public final class GameTestUtil {
	private GameTestUtil() {}

	/** 1.20.1's GameTestHelper has no assertValueEqual. */
	public static void eq(Object actual, Object expected, String what) {
		if (!Objects.equals(actual, expected))throw new GameTestAssertException(what + ": expected " + expected + ", got " + actual);
	}

	/** Typed block entity lookup (1.20.1's GameTestHelper#getBlockEntity is not generic). */
	@SuppressWarnings("unchecked")
	public static <T extends BlockEntity> T be(GameTestHelper h, BlockPos rel) {
		return (T) h.getBlockEntity(rel);
	}

	/** Places a block and then lets it compute its own connection state from its neighbours (cables). */
	public static void place(GameTestHelper h, BlockPos rel, BlockState state) {
		h.setBlock(rel, state);
		refreshShape(h, rel);
	}

	public static void place(GameTestHelper h, BlockPos rel, Block block) {
		place(h, rel, block.defaultBlockState());
	}

	/** Recomputes the shape (cable connections) of the block at {@code rel}, like placing it by hand would. */
	public static void refreshShape(GameTestHelper h, BlockPos rel) {
		ServerLevel l = h.getLevel();
		BlockPos a = h.absolutePos(rel);
		BlockState s = l.getBlockState(a);
		BlockState n = Block.updateFromNeighbourShapes(s, l, a);
		if (n != s)l.setBlock(a, n, 3);
	}

	/** A Storage Terminal that reads the block directly below it (the usual "terminal on top of a connector"). */
	public static void terminalAbove(GameTestHelper h, BlockPos rel) {
		h.setBlock(rel, Content.terminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.TERMINAL_POS, TerminalPos.DOWN));
	}

	public static StorageTerminalBlockEntity terminal(GameTestHelper h, BlockPos rel) {
		return be(h, rel);
	}

	/** Single chest at {@code rel} with the given stacks in its first slots. */
	public static void chest(GameTestHelper h, BlockPos rel, ItemStack... stacks) {
		h.setBlock(rel, Blocks.CHEST.defaultBlockState());
		fill(h, rel, stacks);
	}

	/** Double chest, facing north: the left half at {@code left}, the right half one block east of it. */
	public static void doubleChest(GameTestHelper h, BlockPos left) {
		BlockState base = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
		h.setBlock(left, base.setValue(ChestBlock.TYPE, ChestType.LEFT));
		h.setBlock(left.east(), base.setValue(ChestBlock.TYPE, ChestType.RIGHT));
	}

	public static void fill(GameTestHelper h, BlockPos rel, ItemStack... stacks) {
		Container c = container(h, rel);
		for (int i = 0; i < stacks.length; i++) {
			c.setItem(i, stacks[i].copy());
		}
		c.setChanged();
	}

	public static Container container(GameTestHelper h, BlockPos rel) {
		if (h.getBlockEntity(rel) instanceof BaseContainerBlockEntity be)return be;
		throw new IllegalStateException("No container at " + rel);
	}

	/** Ground truth: the items of {@code item} directly in a block's container. */
	public static int count(GameTestHelper h, BlockPos rel, Item item) {
		return count(container(h, rel), item);
	}

	public static int count(Container c, Item item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.is(item))n += s.getCount();
		}
		return n;
	}

	/**
	 * What the terminal lists for {@code item} (its synced item totals). Also asks the terminal to read
	 * its network on its next tick, like an open terminal screen does.
	 */
	public static long terminalCount(StorageTerminalBlockEntity te, Item item) {
		return te.getSnapshot().getCount(ItemVariant.of(item));
	}

	/** An Item Filter (allow list, NBT ignored) holding one of each given item. */
	public static ItemStack itemFilter(Item... allowed) {
		ItemStack s = new ItemStack(Content.itemFilter.get());
		SimpleItemFilter f = new SimpleItemFilter(s);
		for (int i = 0; i < allowed.length; i++)f.getContainer().setItem(i, new ItemStack(allowed[i]));
		f.setAllowList(true);
		f.setMatchNBT(false);
		f.flush();
		return s;
	}

	/** A Condition Filter with the given conditions set to Required. */
	public static ItemStack conditionFilter(FilterCondition... required) {
		ConditionFilterState c = ConditionFilterState.EMPTY;
		for (FilterCondition f : required)c = c.withState(f.ordinal(), ConditionFilterState.STATE_REQUIRED);
		ItemStack s = new ItemStack(Content.conditionFilter.get());
		c.write(s);
		return s;
	}

	public static BlockState facing(Block b, Direction d) {
		return b.defaultBlockState().setValue(BlockStateProperties.FACING, d);
	}
}
