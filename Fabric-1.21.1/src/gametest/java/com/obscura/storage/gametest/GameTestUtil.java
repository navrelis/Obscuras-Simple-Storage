package com.obscura.storage.gametest;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

import com.obscura.storage.Content;
import com.obscura.storage.block.AbstractStorageTerminalBlock;
import com.obscura.storage.block.AbstractStorageTerminalBlock.TerminalPos;
import com.obscura.storage.block.entity.StorageTerminalBlockEntity;
import com.obscura.storage.components.ConditionFilterComponent;
import com.obscura.storage.components.SimpleItemFilterComponent;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.util.FilterCondition;

/**
 * Shared helpers for the in-world tests. All positions are relative to the test (the empty 8x8x8
 * template); blocks are placed at y >= 2, inside x/z 1..7, so neighbouring tests never touch.
 */
public final class GameTestUtil {
	private GameTestUtil() {}

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
		return h.getBlockEntity(rel);
	}

	/** Single chest at {@code rel} with the given stacks in its first slots. */
	public static void chest(GameTestHelper h, BlockPos rel, ItemStack... stacks) {
		h.setBlock(rel, Blocks.CHEST.defaultBlockState());
		fill(h, rel, stacks);
	}

	/**
	 * Double chest, facing north: the left half at {@code left}, the right half one block east of it.
	 */
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

	/** What the terminal lists for {@code item}. Also asks the terminal to refresh its list on its next tick. */
	public static long terminalCount(StorageTerminalBlockEntity te, Item item) {
		return te.getStacks().values().stream().filter(s -> s.getStack().is(item)).mapToLong(StoredItemStack::getQuantity).sum();
	}

	/** An Item Filter (allow list, components ignored) holding one of each given item. */
	public static ItemStack itemFilter(Item... allowed) {
		List<ItemStack> list = new ArrayList<>();
		for (Item i : allowed)list.add(new ItemStack(i));
		while (list.size() < 9)list.add(ItemStack.EMPTY);
		ItemStack s = new ItemStack(Content.itemFilter.get());
		s.set(Content.simpleItemFilterComponent.get(), new SimpleItemFilterComponent(list, false, true));
		return s;
	}

	/** A Condition Filter with the given conditions set to Required. */
	public static ItemStack conditionFilter(FilterCondition... required) {
		ConditionFilterComponent c = ConditionFilterComponent.EMPTY;
		for (FilterCondition f : required)c = c.withState(f.ordinal(), ConditionFilterComponent.STATE_REQUIRED);
		ItemStack s = new ItemStack(Content.conditionFilter.get());
		s.set(Content.conditionFilterComponent.get(), c);
		return s;
	}

	public static BlockState facing(Block b, Direction d) {
		return b.defaultBlockState().setValue(BlockStateProperties.FACING, d);
	}
}
