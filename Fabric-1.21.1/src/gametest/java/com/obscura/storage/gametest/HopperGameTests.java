package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.block.AbstractInventoryHopperBlock;
import com.obscura.storage.block.entity.BasicInventoryHopperBlockEntity;
import com.obscura.storage.util.FilterCondition;
import com.obscura.storage.util.HopperMode;

/**
 * Basic Inventory Hopper modes and the Condition Filter, in a real world. A Basic Inventory Hopper
 * only moves items when one of its two sides is an inventory network reached through a cable, so
 * every setup has a connector + chest ("the network") behind a cable on one side.
 */
public class HopperGameTests implements FabricGameTest {

	// --- Export: chest above the hopper, hopper facing down into a cable -> connector -> chest ---
	private static final BlockPos EX_SOURCE = new BlockPos(2, 4, 3);
	private static final BlockPos EX_HOPPER = new BlockPos(2, 3, 3);
	private static final BlockPos EX_CABLE = new BlockPos(2, 2, 3);
	private static final BlockPos EX_CONNECTOR = new BlockPos(3, 2, 3);
	private static final BlockPos EX_NET_CHEST = new BlockPos(4, 2, 3);

	/** Hopper that exports from a chest into the network. */
	private static BasicInventoryHopperBlockEntity buildExport(GameTestHelper h, ItemStack... source) {
		chest(h, EX_NET_CHEST);
		h.setBlock(EX_CONNECTOR, Content.connector.get());
		chest(h, EX_SOURCE, source);
		h.setBlock(EX_HOPPER, facing(Content.basicInvHopper.get(), Direction.DOWN));
		place(h, EX_CABLE, Content.invCable.get());
		return h.getBlockEntity(EX_HOPPER);
	}

	// --- Import: cable -> connector -> chest behind the hopper, hopper facing east into a target block ---
	private static final BlockPos IM_NET_CHEST = new BlockPos(1, 2, 3);
	private static final BlockPos IM_CONNECTOR = new BlockPos(2, 2, 3);
	private static final BlockPos IM_CABLE = new BlockPos(2, 3, 3);
	private static final BlockPos IM_HOPPER = new BlockPos(3, 3, 3);
	private static final BlockPos IM_TARGET = new BlockPos(4, 3, 3);
	private static final BlockPos IM_REDSTONE = new BlockPos(3, 4, 3);

	/** Hopper that imports from the network into the block at {@link #IM_TARGET} (placed by the caller first). */
	private static BasicInventoryHopperBlockEntity buildImport(GameTestHelper h, ItemStack... network) {
		chest(h, IM_NET_CHEST, network);
		h.setBlock(IM_CONNECTOR, Content.connector.get());
		h.setBlock(IM_HOPPER, facing(Content.basicInvHopper.get(), Direction.EAST));
		place(h, IM_CABLE, Content.invCable.get());
		return h.getBlockEntity(IM_HOPPER);
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void normalModeMovesOneItemPerCooldown(GameTestHelper h) {
		int cd = Math.max(1, Config.get().basicHopperCooldown);
		buildExport(h, new ItemStack(Items.COBBLESTONE, 64));
		List<Long> moves = new ArrayList<>();
		int[] last = {64};
		h.onEachTick(() -> {
			int now = count(h, EX_SOURCE, Items.COBBLESTONE);
			for (int i = now; i < last[0]; i++)moves.add(h.getTick());
			last[0] = now;
		});
		h.runAtTickTime(100, () -> {
			h.assertTrue(moves.size() >= 5, "too few moves: " + moves);
			for (int i = 1; i < moves.size(); i++) {
				long d = moves.get(i) - moves.get(i - 1);
				h.assertValueEqual(d, (long) cd, "ticks between two moves (config basicHopperCooldown), moves at " + moves);
			}
			// Over any 40 tick window after the first move: 40 / cd items
			long first = moves.get(0);
			long in40 = moves.stream().filter(t -> t >= first && t < first + 40).count();
			h.assertValueEqual(in40, (long) (40 / cd), "items moved in the 40 ticks after the first move");
			int moved = 64 - count(h, EX_SOURCE, Items.COBBLESTONE);
			h.assertValueEqual(count(h, EX_NET_CHEST, Items.COBBLESTONE), moved, "no loss or duplication");
			h.succeed();
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void keepStockStopsAtAmount(GameTestHelper h) {
		h.setBlock(IM_TARGET, Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, Direction.NORTH));
		BasicInventoryHopperBlockEntity hopper = buildImport(h, new ItemStack(Items.COAL, 64));
		hopper.setFilter(new ItemStack(Items.COAL));
		hopper.setMode(HopperMode.KEEP_STOCK);
		hopper.setAmount(5);
		Container furnace = container(h, IM_TARGET);
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(count(furnace, Items.COAL), 5, "coal in the furnace"))
		.thenIdle(60)
		.thenExecute(() -> {
			h.assertValueEqual(count(furnace, Items.COAL), 5, "coal in the furnace after waiting (must stop at the amount)");
			h.assertValueEqual(furnace.getItem(1).getCount(), 5, "coal in the fuel slot");
			h.assertValueEqual(count(h, IM_NET_CHEST, Items.COAL), 59, "coal left in the network");
			// Use some: Keep Stock tops it up again
			furnace.removeItem(1, 3);
		})
		.thenWaitUntil(() -> h.assertValueEqual(count(furnace, Items.COAL), 5, "coal after topping up"))
		.thenIdle(40)
		.thenExecute(() -> {
			h.assertValueEqual(count(furnace, Items.COAL), 5, "coal after topping up and waiting");
			h.assertValueEqual(count(h, IM_NET_CHEST, Items.COAL), 56, "coal left in the network after topping up");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void pulseMovesAmountPerRisingEdge(GameTestHelper h) {
		chest(h, IM_TARGET);
		BasicInventoryHopperBlockEntity hopper = buildImport(h, new ItemStack(Items.IRON_INGOT, 64));
		hopper.setFilter(new ItemStack(Items.IRON_INGOT));
		hopper.setMode(HopperMode.PULSE);
		hopper.setAmount(4);
		h.startSequence()
		.thenIdle(40)
		.thenExecute(() -> h.assertValueEqual(count(h, IM_TARGET, Items.IRON_INGOT), 0, "nothing may move without a pulse"))
		.thenExecute(() -> h.setBlock(IM_REDSTONE, Blocks.REDSTONE_BLOCK))
		.thenIdle(10)
		.thenExecute(() -> h.assertValueEqual(count(h, IM_TARGET, Items.IRON_INGOT), 4, "items after the first pulse"))
		.thenIdle(30)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, IM_TARGET, Items.IRON_INGOT), 4, "a held signal must not move more");
			h.assertTrue(h.getBlockState(IM_HOPPER).getValue(AbstractInventoryHopperBlock.ENABLED), "Pulse mode must stay enabled while powered");
			h.setBlock(IM_REDSTONE, Blocks.AIR);
		})
		.thenIdle(10)
		.thenExecute(() -> h.assertValueEqual(count(h, IM_TARGET, Items.IRON_INGOT), 4, "a falling edge must not move items"))
		.thenExecute(() -> h.setBlock(IM_REDSTONE, Blocks.REDSTONE_BLOCK))
		.thenIdle(10)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, IM_TARGET, Items.IRON_INGOT), 8, "items after the second pulse");
			h.assertValueEqual(count(h, IM_NET_CHEST, Items.IRON_INGOT), 56, "iron left in the network");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void pulseNineIronIntoCrafterGivesOnePerSlot(GameTestHelper h) {
		h.setBlock(IM_TARGET, Blocks.CRAFTER);
		BasicInventoryHopperBlockEntity hopper = buildImport(h, new ItemStack(Items.IRON_INGOT, 64));
		hopper.setFilter(new ItemStack(Items.IRON_INGOT));
		hopper.setMode(HopperMode.PULSE);
		hopper.setAmount(9);
		h.startSequence()
		.thenIdle(20)
		.thenExecute(() -> h.setBlock(IM_REDSTONE, Blocks.REDSTONE_BLOCK))
		.thenIdle(10)
		.thenExecute(() -> {
			CrafterBlockEntity crafter = h.getBlockEntity(IM_TARGET);
			StringBuilder slots = new StringBuilder();
			for (int i = 0; i < 9; i++)slots.append(crafter.getItem(i).getCount()).append(' ');
			for (int i = 0; i < 9; i++) {
				ItemStack s = crafter.getItem(i);
				h.assertTrue(s.is(Items.IRON_INGOT) && s.getCount() == 1, "crafter slot " + i + " must hold 1 iron, slots: " + slots);
			}
			h.assertValueEqual(count(h, IM_NET_CHEST, Items.IRON_INGOT), 55, "iron left in the network");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void conditionFilterDamagedTools(GameTestHelper h) {
		ItemStack damaged = new ItemStack(Items.IRON_PICKAXE);
		damaged.set(DataComponents.DAMAGE, 10);
		BasicInventoryHopperBlockEntity hopper = buildExport(h, new ItemStack(Items.IRON_PICKAXE), damaged, new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.STONE, 3));
		hopper.setFilter(conditionFilter(FilterCondition.DAMAGED));
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(count(h, EX_NET_CHEST, Items.IRON_PICKAXE), 1, "damaged pickaxe moved"))
		.thenIdle(40)
		.thenExecute(() -> {
			Container net = container(h, EX_NET_CHEST);
			h.assertValueEqual(count(net, Items.IRON_PICKAXE), 1, "only the damaged pickaxe may move");
			for (int i = 0; i < net.getContainerSize(); i++) {
				ItemStack s = net.getItem(i);
				if (s.is(Items.IRON_PICKAXE))h.assertTrue(s.isDamaged(), "the moved pickaxe must be the damaged one");
			}
			h.assertValueEqual(count(net, Items.IRON_SHOVEL), 0, "undamaged shovel must stay");
			h.assertValueEqual(count(net, Items.STONE), 0, "stone must stay");
			h.assertValueEqual(count(h, EX_SOURCE, Items.IRON_PICKAXE), 1, "undamaged pickaxe stays in the source");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void conditionFilterFuel(GameTestHelper h) {
		BasicInventoryHopperBlockEntity hopper = buildExport(h, new ItemStack(Items.STONE, 3), new ItemStack(Items.COAL, 3), new ItemStack(Items.IRON_INGOT, 3));
		hopper.setFilter(conditionFilter(FilterCondition.FUEL));
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(count(h, EX_NET_CHEST, Items.COAL), 3, "coal moved"))
		.thenIdle(40)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, EX_NET_CHEST, Items.STONE), 0, "stone is not fuel");
			h.assertValueEqual(count(h, EX_NET_CHEST, Items.IRON_INGOT), 0, "iron is not fuel");
			h.assertTrue(AbstractFurnaceBlockEntity.isFuel(new ItemStack(Items.COAL)), "sanity: coal is fuel");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void savedStateSurvivesReloadWithoutPulsing(GameTestHelper h) {
		chest(h, IM_TARGET);
		BasicInventoryHopperBlockEntity hopper = buildImport(h, new ItemStack(Items.IRON_INGOT, 64));
		ItemStack filter = itemFilter(Items.IRON_INGOT);
		hopper.setFilter(filter);
		hopper.setMode(HopperMode.PULSE);
		hopper.setAmount(4);
		BlockPos abs = h.absolutePos(IM_HOPPER);
		h.startSequence()
		.thenIdle(20)
		.thenExecute(() -> h.setBlock(IM_REDSTONE, Blocks.REDSTONE_BLOCK))
		.thenIdle(10)
		.thenExecute(() -> h.assertValueEqual(count(h, IM_TARGET, Items.IRON_INGOT), 4, "items after the first pulse"))
		.thenExecute(() -> {
			// Saved like a chunk save does, then read back into a fresh block entity that replaces the old one
			CompoundTag tag = hopper.saveWithFullMetadata(h.getLevel().registryAccess());
			h.assertTrue(tag.getBoolean("Powered"), "the last seen redstone signal must be saved, tag: " + tag);
			BlockEntity loaded = BlockEntity.loadStatic(abs, h.getLevel().getBlockState(abs), tag, h.getLevel().registryAccess());
			h.assertTrue(loaded instanceof BasicInventoryHopperBlockEntity, "the saved tag must load into a hopper block entity, got " + loaded);
			BasicInventoryHopperBlockEntity re = (BasicInventoryHopperBlockEntity) loaded;
			// The filter's component holds stack lists (no value equality), so compare what they save as
			h.assertValueEqual(re.getFilter().save(h.getLevel().registryAccess()), filter.save(h.getLevel().registryAccess()), "filter after reload");
			h.assertValueEqual(re.getMode(), HopperMode.PULSE, "mode after reload");
			h.assertValueEqual(re.getAmount(), 4, "amount after reload");
			h.getLevel().setBlockEntity(re);
			// The neighbour update a loaded block gets: the stored signal is still powered, so this is no rising edge
			BlockState state = h.getLevel().getBlockState(abs);
			((AbstractInventoryHopperBlock) state.getBlock()).updateState(h.getLevel(), abs, state);
		})
		.thenIdle(20)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, IM_TARGET, Items.IRON_INGOT), 4, "reloading while powered must not pulse");
			h.assertTrue(h.getBlockState(IM_HOPPER).getValue(AbstractInventoryHopperBlock.ENABLED), "Pulse mode must stay enabled while powered");
			h.setBlock(IM_REDSTONE, Blocks.AIR);
		})
		.thenIdle(10)
		.thenExecute(() -> h.setBlock(IM_REDSTONE, Blocks.REDSTONE_BLOCK))
		.thenIdle(10)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, IM_TARGET, Items.IRON_INGOT), 8, "the reloaded hopper must pulse on the next rising edge");
			h.assertValueEqual(count(h, IM_NET_CHEST, Items.IRON_INGOT), 56, "iron left in the network");
		})
		.thenSucceed();
	}
}
