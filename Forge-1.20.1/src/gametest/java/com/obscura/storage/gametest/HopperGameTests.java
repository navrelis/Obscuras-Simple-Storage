package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import com.obscura.storage.StorageMod;
import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.block.BasicInventoryHopperBlock;
import com.obscura.storage.tile.BasicInventoryHopperBlockEntity;
import com.obscura.storage.util.FilterCondition;
import com.obscura.storage.util.HopperMode;

/**
 * Basic Inventory Hopper modes and the Condition Filter, in a real world. A Basic Inventory Hopper
 * only moves items when one of its two sides is an inventory network reached through a cable, so
 * every setup has a connector + chest ("the network") behind a cable on one side. The hopper looks
 * its two sides up once every 20 ticks, so the first move can take up to 20 ticks.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class HopperGameTests {

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
		h.setBlock(EX_HOPPER, facing(Content.invHopperBasic.get(), Direction.DOWN));
		place(h, EX_CABLE, Content.invCable.get());
		return be(h, EX_HOPPER);
	}

	// --- Import: cable -> connector -> chest behind the hopper, hopper facing east into a target block ---
	private static final BlockPos IM_NET_CHEST = new BlockPos(1, 2, 3);
	private static final BlockPos IM_CONNECTOR = new BlockPos(2, 2, 3);
	private static final BlockPos IM_CABLE = new BlockPos(2, 3, 3);
	private static final BlockPos IM_HOPPER = new BlockPos(3, 3, 3);
	private static final BlockPos IM_TARGET = new BlockPos(4, 3, 3);
	private static final BlockPos IM_REDSTONE = new BlockPos(3, 4, 3);
	/** Powers the hopper from the side, not next to the target: a dropper at the target must not fire. */
	private static final BlockPos IM_REDSTONE_SIDE = new BlockPos(3, 3, 2);

	/** Hopper that imports from the network into the block at {@link #IM_TARGET} (placed by the caller first). */
	private static BasicInventoryHopperBlockEntity buildImport(GameTestHelper h, ItemStack... network) {
		chest(h, IM_NET_CHEST, network);
		h.setBlock(IM_CONNECTOR, Content.connector.get());
		h.setBlock(IM_HOPPER, facing(Content.invHopperBasic.get(), Direction.EAST));
		place(h, IM_CABLE, Content.invCable.get());
		return be(h, IM_HOPPER);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200)
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
				eq(d, (long) cd, "ticks between two moves (config basicHopperCooldown), moves at " + moves);
			}
			// Over any 40 tick window after the first move: 40 / cd items
			long first = moves.get(0);
			long in40 = moves.stream().filter(t -> t >= first && t < first + 40).count();
			eq(in40, (long) (40 / cd), "items moved in the 40 ticks after the first move");
			int moved = 64 - count(h, EX_SOURCE, Items.COBBLESTONE);
			eq(count(h, EX_NET_CHEST, Items.COBBLESTONE), moved, "no loss or duplication");
			h.succeed();
		});
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void keepStockStopsAtAmount(GameTestHelper h) {
		h.setBlock(IM_TARGET, Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, Direction.NORTH));
		BasicInventoryHopperBlockEntity hopper = buildImport(h, new ItemStack(Items.COAL, 64));
		hopper.setFilter(new ItemStack(Items.COAL));
		hopper.setMode(HopperMode.KEEP_STOCK);
		hopper.setAmount(5);
		Container furnace = container(h, IM_TARGET);
		h.startSequence()
		.thenWaitUntil(() -> eq(count(furnace, Items.COAL), 5, "coal in the furnace"))
		.thenIdle(60)
		.thenExecute(() -> {
			eq(count(furnace, Items.COAL), 5, "coal in the furnace after waiting (must stop at the amount)");
			eq(furnace.getItem(1).getCount(), 5, "coal in the fuel slot");
			eq(count(h, IM_NET_CHEST, Items.COAL), 59, "coal left in the network");
			// Use some: Keep Stock tops it up again
			furnace.removeItem(1, 3);
		})
		.thenWaitUntil(() -> eq(count(furnace, Items.COAL), 5, "coal after topping up"))
		.thenIdle(40)
		.thenExecute(() -> {
			eq(count(furnace, Items.COAL), 5, "coal after topping up and waiting");
			eq(count(h, IM_NET_CHEST, Items.COAL), 56, "coal left in the network after topping up");
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void pulseMovesAmountPerRisingEdge(GameTestHelper h) {
		chest(h, IM_TARGET);
		BasicInventoryHopperBlockEntity hopper = buildImport(h, new ItemStack(Items.IRON_INGOT, 64));
		hopper.setFilter(new ItemStack(Items.IRON_INGOT));
		hopper.setMode(HopperMode.PULSE);
		hopper.setAmount(4);
		h.startSequence()
		.thenIdle(40)
		.thenExecute(() -> eq(count(h, IM_TARGET, Items.IRON_INGOT), 0, "nothing may move without a pulse"))
		.thenExecute(() -> h.setBlock(IM_REDSTONE, Blocks.REDSTONE_BLOCK))
		.thenIdle(10)
		.thenExecute(() -> eq(count(h, IM_TARGET, Items.IRON_INGOT), 4, "items after the first pulse"))
		.thenIdle(30)
		.thenExecute(() -> {
			eq(count(h, IM_TARGET, Items.IRON_INGOT), 4, "a held signal must not move more");
			h.assertTrue(h.getBlockState(IM_HOPPER).getValue(BasicInventoryHopperBlock.ENABLED), "Pulse mode must stay enabled while powered");
			h.setBlock(IM_REDSTONE, Blocks.AIR);
		})
		.thenIdle(10)
		.thenExecute(() -> eq(count(h, IM_TARGET, Items.IRON_INGOT), 4, "a falling edge must not move items"))
		.thenExecute(() -> h.setBlock(IM_REDSTONE, Blocks.REDSTONE_BLOCK))
		.thenIdle(10)
		.thenExecute(() -> {
			eq(count(h, IM_TARGET, Items.IRON_INGOT), 8, "items after the second pulse");
			eq(count(h, IM_NET_CHEST, Items.IRON_INGOT), 56, "iron left in the network");
		})
		.thenSucceed();
	}

	/** 1.20.1 has no Crafter: a dropper (9 slots) takes the pulse, exactly {@code amount} per rising edge. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200)
	public void pulseNineIronIntoDropper(GameTestHelper h) {
		h.setBlock(IM_TARGET, Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.UP));
		BasicInventoryHopperBlockEntity hopper = buildImport(h, new ItemStack(Items.IRON_INGOT, 64));
		hopper.setFilter(new ItemStack(Items.IRON_INGOT));
		hopper.setMode(HopperMode.PULSE);
		hopper.setAmount(9);
		h.startSequence()
		.thenIdle(30)
		.thenExecute(() -> eq(count(h, IM_TARGET, Items.IRON_INGOT), 0, "nothing may move without a pulse"))
		.thenExecute(() -> h.setBlock(IM_REDSTONE_SIDE, Blocks.REDSTONE_BLOCK))
		.thenIdle(10)
		.thenExecute(() -> {
			eq(count(h, IM_TARGET, Items.IRON_INGOT), 9, "iron in the dropper after one pulse");
			eq(count(h, IM_NET_CHEST, Items.IRON_INGOT), 55, "iron left in the network");
			h.setBlock(IM_REDSTONE_SIDE, Blocks.AIR);
		})
		.thenIdle(10)
		.thenExecute(() -> h.setBlock(IM_REDSTONE_SIDE, Blocks.REDSTONE_BLOCK))
		.thenIdle(10)
		.thenExecute(() -> {
			eq(count(h, IM_TARGET, Items.IRON_INGOT), 18, "iron in the dropper after the second pulse");
			eq(count(h, IM_NET_CHEST, Items.IRON_INGOT), 46, "iron left in the network after the second pulse");
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void conditionFilterDamagedTools(GameTestHelper h) {
		ItemStack damaged = new ItemStack(Items.IRON_PICKAXE);
		damaged.setDamageValue(10);
		BasicInventoryHopperBlockEntity hopper = buildExport(h, new ItemStack(Items.IRON_PICKAXE), damaged, new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.STONE, 3));
		hopper.setFilter(conditionFilter(FilterCondition.DAMAGED));
		h.startSequence()
		.thenWaitUntil(() -> eq(count(h, EX_NET_CHEST, Items.IRON_PICKAXE), 1, "damaged pickaxe moved"))
		.thenIdle(40)
		.thenExecute(() -> {
			Container net = container(h, EX_NET_CHEST);
			eq(count(net, Items.IRON_PICKAXE), 1, "only the damaged pickaxe may move");
			for (int i = 0; i < net.getContainerSize(); i++) {
				ItemStack s = net.getItem(i);
				if (s.is(Items.IRON_PICKAXE))h.assertTrue(s.isDamaged(), "the moved pickaxe must be the damaged one");
			}
			eq(count(net, Items.IRON_SHOVEL), 0, "undamaged shovel must stay");
			eq(count(net, Items.STONE), 0, "stone must stay");
			eq(count(h, EX_SOURCE, Items.IRON_PICKAXE), 1, "undamaged pickaxe stays in the source");
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void conditionFilterFuel(GameTestHelper h) {
		BasicInventoryHopperBlockEntity hopper = buildExport(h, new ItemStack(Items.STONE, 3), new ItemStack(Items.COAL, 3), new ItemStack(Items.IRON_INGOT, 3));
		hopper.setFilter(conditionFilter(FilterCondition.FUEL));
		h.startSequence()
		.thenWaitUntil(() -> eq(count(h, EX_NET_CHEST, Items.COAL), 3, "coal moved"))
		.thenIdle(40)
		.thenExecute(() -> {
			eq(count(h, EX_NET_CHEST, Items.STONE), 0, "stone is not fuel");
			eq(count(h, EX_NET_CHEST, Items.IRON_INGOT), 0, "iron is not fuel");
			h.assertTrue(AbstractFurnaceBlockEntity.isFuel(new ItemStack(Items.COAL)), "sanity: coal is fuel");
		})
		.thenSucceed();
	}

	/**
	 * A rising edge while the hopper has nothing to move between (no network on either side) is dropped: it
	 * must not fire later, when a cable and an inventory get attached.
	 */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400)
	public void pulseWithoutNetworkIsDroppedNotKept(GameTestHelper h) {
		chest(h, IM_TARGET);
		// The network exists (and has scanned its chest) but is not connected to the hopper yet: the cable comes last
		chest(h, IM_NET_CHEST, new ItemStack(Items.IRON_INGOT, 64));
		h.setBlock(IM_CONNECTOR, Content.connector.get());
		h.setBlock(IM_HOPPER, facing(Content.invHopperBasic.get(), Direction.EAST));
		BasicInventoryHopperBlockEntity hopper = be(h, IM_HOPPER);
		hopper.setFilter(new ItemStack(Items.IRON_INGOT));
		hopper.setMode(HopperMode.PULSE);
		hopper.setAmount(4);
		h.startSequence()
		.thenIdle(40)
		.thenExecute(() -> h.setBlock(IM_REDSTONE, Blocks.REDSTONE_BLOCK))
		.thenIdle(10)
		.thenExecute(() -> {
			// Now the hopper gets connected to the network, with the signal still held
			place(h, IM_CABLE, Content.invCable.get());
		})
		.thenIdle(60)
		.thenExecute(() -> {
			eq(count(h, IM_TARGET, Items.IRON_INGOT), 0, "the pulse from before the network existed must not fire");
			eq(count(h, IM_NET_CHEST, Items.IRON_INGOT), 64, "iron left in the network");
			h.setBlock(IM_REDSTONE, Blocks.AIR);
		})
		.thenIdle(10)
		.thenExecute(() -> h.setBlock(IM_REDSTONE, Blocks.REDSTONE_BLOCK))
		.thenIdle(10)
		.thenExecute(() -> eq(count(h, IM_TARGET, Items.IRON_INGOT), 4, "a new rising edge with the network in place still pulses"))
		.thenSucceed();
	}

	/** Keep Stock into a network counts what the network holds (through its shared totals) and stops at the amount. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void keepStockIntoNetworkStopsAtAmount(GameTestHelper h) {
		BasicInventoryHopperBlockEntity hopper = buildExport(h, new ItemStack(Items.IRON_INGOT, 64));
		hopper.setFilter(new ItemStack(Items.IRON_INGOT));
		hopper.setMode(HopperMode.KEEP_STOCK);
		hopper.setAmount(5);
		h.startSequence()
		.thenWaitUntil(() -> eq(count(h, EX_NET_CHEST, Items.IRON_INGOT), 5, "iron in the network"))
		.thenIdle(60)
		.thenExecute(() -> {
			eq(count(h, EX_NET_CHEST, Items.IRON_INGOT), 5, "iron in the network after waiting (must stop at the amount)");
			eq(count(h, EX_SOURCE, Items.IRON_INGOT), 59, "iron left in the source");
			// Take some out: Keep Stock tops it up again
			container(h, EX_NET_CHEST).removeItem(0, 3);
		})
		.thenWaitUntil(() -> eq(count(h, EX_NET_CHEST, Items.IRON_INGOT), 5, "iron after topping up"))
		.thenIdle(40)
		.thenExecute(() -> eq(count(h, EX_NET_CHEST, Items.IRON_INGOT), 5, "iron after topping up and waiting"))
		.thenSucceed();
	}

	/** What the hopper saves is what it loads: filter, mode, amount and the stored redstone signal. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100)
	public void hopperSettingsSurviveSaveAndLoad(GameTestHelper h) {
		h.setBlock(IM_HOPPER, facing(Content.invHopperBasic.get(), Direction.EAST));
		BasicInventoryHopperBlockEntity hopper = be(h, IM_HOPPER);
		hopper.setFilter(new ItemStack(Items.IRON_INGOT));
		hopper.setMode(HopperMode.KEEP_STOCK);
		hopper.setAmount(7);
		// Sets the stored signal (Keep Stock is disabled by redstone, so the return value is not of interest here)
		hopper.onRedstoneUpdate(true);
		CompoundTag saved = hopper.saveWithoutMetadata();
		eq(saved.getByte("Mode"), (byte) HopperMode.KEEP_STOCK.ordinal(), "saved Mode");
		eq((int) saved.getByte("Amount"), 7, "saved Amount");
		h.assertTrue(saved.getBoolean("Powered"), "saved Powered");
		h.assertTrue(ItemStack.of(saved.getCompound("Filter")).is(Items.IRON_INGOT), "saved Filter");

		BasicInventoryHopperBlockEntity loaded = new BasicInventoryHopperBlockEntity(h.absolutePos(IM_HOPPER), Content.invHopperBasic.get().defaultBlockState());
		loaded.load(saved);
		h.assertTrue(loaded.getFilter().is(Items.IRON_INGOT), "loaded Filter, is " + loaded.getFilter());
		eq(loaded.getMode(), HopperMode.KEEP_STOCK, "loaded Mode");
		eq(loaded.getAmount(), 7, "loaded Amount");
		CompoundTag again = loaded.saveWithoutMetadata();
		h.assertTrue(again.getBoolean("Powered"), "Powered after a load and save");
		eq(again.getByte("Mode"), saved.getByte("Mode"), "Mode after a load and save");
		h.succeed();
	}

	/** Forge persistent data (ForgeData) of the block entity is part of what the hopper saves. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100)
	public void hopperKeepsForgePersistentData(GameTestHelper h) {
		h.setBlock(IM_HOPPER, facing(Content.invHopperBasic.get(), Direction.EAST));
		BasicInventoryHopperBlockEntity hopper = be(h, IM_HOPPER);
		hopper.getPersistentData().putString("test_key", "test_value");
		CompoundTag saved = hopper.saveWithoutMetadata();
		BasicInventoryHopperBlockEntity loaded = new BasicInventoryHopperBlockEntity(h.absolutePos(IM_HOPPER), Content.invHopperBasic.get().defaultBlockState());
		loaded.load(saved);
		eq(loaded.getPersistentData().getString("test_key"), "test_value", "persistent data after a save and load");
		h.succeed();
	}
}
