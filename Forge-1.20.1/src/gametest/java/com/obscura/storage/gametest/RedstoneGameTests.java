package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.LevelEmitterBlock;
import com.obscura.storage.tile.FilingCabinetBlockEntity;
import com.obscura.storage.tile.LevelEmitterBlockEntity;

/** Level Emitter analog mode and the comparator outputs, in a real world. */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class RedstoneGameTests {

	/** A comparator at {@code pos} (on a stone block, it needs support) that reads the block to its west. */
	private static void comparatorEast(GameTestHelper h, BlockPos pos) {
		h.setBlock(pos.below(), Blocks.STONE);
		h.setBlock(pos, Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST));
	}

	private static void assertOutput(GameTestHelper h, BlockPos comparator, int expected, String what) {
		eq(((ComparatorBlockEntity) h.getBlockEntity(comparator)).getOutputSignal(), expected, what);
	}

	private static ItemStack[] stacks(int n, ItemStack each) {
		ItemStack[] out = new ItemStack[n];
		Arrays.fill(out, each);
		return out;
	}

	private static void empty(GameTestHelper h, BlockPos chest) {
		Container c = container(h, chest);
		for (int i = 0; i < c.getContainerSize(); i++)c.setItem(i, ItemStack.EMPTY);
		c.setChanged();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 900)
	public void levelEmitterAnalogSignal(GameTestHelper h) {
		BlockPos chest = new BlockPos(1, 3, 3);
		BlockPos connector = new BlockPos(2, 3, 3);
		BlockPos emitter = new BlockPos(3, 3, 3);
		BlockPos wire = new BlockPos(4, 3, 3);
		chest(h, chest);
		h.setBlock(connector, Content.connector.get());
		h.setBlock(emitter, facing(Content.levelEmitter.get(), Direction.EAST));
		h.setBlock(new BlockPos(4, 2, 3), Blocks.STONE);
		h.setBlock(wire, Blocks.REDSTONE_WIRE);
		LevelEmitterBlockEntity be = be(h, emitter);
		be.setFilter(new ItemStack(Items.IRON_INGOT));
		be.setCount(20);
		// amount, expected signal of "more than" and of "less than", threshold 20
		int[][] steps = {{0, 0, 15}, {1, 1, 15}, {10, 8, 8}, {19, 14, 1}, {20, 15, 0}, {30, 15, 0}};
		var seq = h.startSequence();
		// On/Off mode stays strict, and 15: 30 > 20 is on, 20 is not more than 20
		seq.thenExecute(() -> be.setAnalog(false))
		.thenExecute(() -> fill(h, chest, new ItemStack(Items.IRON_INGOT, 30)))
		.thenIdle(12)
		.thenWaitUntil(() -> {
			eq(h.getBlockState(emitter).getValue(LevelEmitterBlock.POWER), 15, "on/off mode, 30 > 20");
			h.assertTrue(h.getBlockState(emitter).getValue(LevelEmitterBlock.POWERED), "powered follows the signal");
		})
		.thenExecute(() -> fill(h, chest, new ItemStack(Items.IRON_INGOT, 20)))
		.thenIdle(12)
		.thenWaitUntil(() -> {
			eq(h.getBlockState(emitter).getValue(LevelEmitterBlock.POWER), 0, "on/off mode, 20 is not more than 20");
			h.assertFalse(h.getBlockState(emitter).getValue(LevelEmitterBlock.POWERED), "powered follows the signal");
		})
		.thenExecute(() -> be.setAnalog(true));
		for (int mode = 0; mode < 2; mode++) {
			boolean lessThan = mode == 1;
			seq.thenExecute(() -> be.setLessThan(lessThan));
			for (int[] step : steps) {
				int amount = step[0];
				int expected = step[lessThan ? 2 : 1];
				seq.thenExecute(() -> {
					if (amount == 0)empty(h, chest);
					else fill(h, chest, new ItemStack(Items.IRON_INGOT, amount));
				})
				.thenIdle(12)
				.thenWaitUntil(() -> {
					String what = (lessThan ? "less than" : "more than") + " 20 with " + amount + " iron";
					BlockState s = h.getBlockState(emitter);
					eq(s.getValue(LevelEmitterBlock.POWER), expected, what);
					eq(s.getValue(LevelEmitterBlock.POWERED), expected > 0, what + ": powered");
					eq(h.getLevel().getSignal(h.absolutePos(emitter), Direction.EAST), expected, what + ": signal");
					// The neighbours were told: a redstone wire next to the emitter carries the strength
					eq(h.getBlockState(wire).getValue(RedStoneWireBlock.POWER), expected, what + ": wire");
				});
			}
		}
		seq.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100)
	public void levelEmitterAnalogModeIsSaved(GameTestHelper h) {
		BlockPos pos = new BlockPos(3, 3, 3);
		h.setBlock(pos, facing(Content.levelEmitter.get(), Direction.EAST));
		LevelEmitterBlockEntity be = be(h, pos);
		h.assertFalse(be.isAnalog(), "a new emitter is On/Off");
		be.setFilter(new ItemStack(Items.IRON_INGOT));
		be.setCount(77);
		be.setLessThan(true);
		be.setAnalog(true);
		CompoundTag tag = be.saveWithFullMetadata();
		LevelEmitterBlockEntity loaded = reload(h, pos, tag);
		h.assertTrue(loaded.isAnalog(), "analog mode survives save and load");
		h.assertTrue(loaded.isLessThan(), "less than survives");
		eq(loaded.getCount(), 77, "count survives");
		h.assertTrue(loaded.getFilter().is(Items.IRON_INGOT), "filter survives");

		// A block entity saved before the analog mode has no such field and loads as On/Off
		tag.remove("analog");
		LevelEmitterBlockEntity old = reload(h, pos, tag);
		h.assertFalse(old.isAnalog(), "old NBT without the field loads as On/Off");
		h.assertTrue(old.isLessThan() && old.getCount() == 77, "the rest of the old NBT is read as before");

		// A block state saved before the power property is 'powered' with power 0, which means 15
		BlockState legacy = h.getBlockState(pos).setValue(LevelEmitterBlock.POWERED, true).setValue(LevelEmitterBlock.POWER, 0);
		eq(LevelEmitterBlock.signalOf(legacy), 15, "a legacy powered emitter still emits 15");
		eq(LevelEmitterBlock.signalOf(legacy.setValue(LevelEmitterBlock.POWERED, false)), 0, "a legacy unpowered emitter emits 0");
		eq(LevelEmitterBlock.signalOf(legacy.setValue(LevelEmitterBlock.POWER, 6)), 6, "an analog emitter emits its power");
		h.succeed();
	}

	private static LevelEmitterBlockEntity reload(GameTestHelper h, BlockPos pos, CompoundTag tag) {
		BlockEntity loaded = BlockEntity.loadStatic(h.absolutePos(pos), h.getBlockState(pos), tag);
		h.assertTrue(loaded instanceof LevelEmitterBlockEntity, "reloaded block entity type");
		return (LevelEmitterBlockEntity) loaded;
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 500)
	public void connectorComparatorReadsNetworkFill(GameTestHelper h) {
		BlockPos chest = new BlockPos(1, 3, 3);
		BlockPos connector = new BlockPos(2, 3, 3);
		BlockPos comparator = new BlockPos(3, 3, 3);
		chest(h, chest);
		h.setBlock(connector, Content.connector.get());
		comparatorEast(h, comparator);
		h.startSequence()
		.thenIdle(30)
		.thenExecute(() -> assertOutput(h, comparator, 0, "an empty network"))
		// 10 of 27 stacks: 1 + floor(14 * 10 / 27) = 6
		.thenExecute(() -> fill(h, chest, stacks(10, new ItemStack(Items.COBBLESTONE, 64))))
		.thenIdle(10)
		.thenWaitUntil(() -> assertOutput(h, comparator, 6, "10 of 27 slots full"))
		// 16 ender pearls are a full stack too, 10 of them count like 10 cobblestone stacks
		.thenExecute(() -> {
			fill(h, chest, stacks(10, new ItemStack(Items.ENDER_PEARL, 16)));
			eq(AbstractContainerMenu.getRedstoneSignalFromContainer(container(h, chest)), 6, "vanilla reads the chest the same");
		})
		.thenIdle(10)
		.thenWaitUntil(() -> assertOutput(h, comparator, 6, "10 of 27 slots of pearls"))
		.thenExecute(() -> {
			empty(h, chest);
			fill(h, chest, new ItemStack(Items.COBBLESTONE, 1));
		})
		.thenIdle(10)
		.thenWaitUntil(() -> assertOutput(h, comparator, 1, "a single item"))
		.thenExecute(() -> fill(h, chest, stacks(27, new ItemStack(Items.COBBLESTONE, 64))))
		.thenIdle(10)
		.thenWaitUntil(() -> assertOutput(h, comparator, 15, "a full network"))
		.thenExecute(() -> empty(h, chest))
		.thenIdle(10)
		.thenWaitUntil(() -> assertOutput(h, comparator, 0, "emptied again"))
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 500)
	public void interfaceComparatorReadsNetworkFill(GameTestHelper h) {
		BlockPos chest = new BlockPos(1, 3, 3);
		BlockPos connector = new BlockPos(2, 3, 3);
		BlockPos cable = new BlockPos(3, 3, 3);
		BlockPos cable2 = new BlockPos(4, 3, 3);
		BlockPos iface = new BlockPos(5, 3, 3);
		BlockPos comparator = new BlockPos(6, 3, 3);
		chest(h, chest);
		h.setBlock(connector, Content.connector.get());
		h.setBlock(iface, Content.invInterface.get());
		// An interface finds its network over cables (right next to the connector it would be a member of it instead)
		place(h, cable, Content.invCable.get());
		place(h, cable2, Content.invCable.get());
		refreshShape(h, cable);
		comparatorEast(h, comparator);
		h.startSequence()
		// the interface finds its network within a second
		.thenIdle(40)
		.thenExecute(() -> fill(h, chest, stacks(14, new ItemStack(Items.COBBLESTONE, 64))))
		// 14 of 27: 1 + floor(14 * 14 / 27) = 8
		.thenIdle(10)
		.thenWaitUntil(() -> assertOutput(h, comparator, 8, "14 of 27 slots full, read through the interface"))
		.thenExecute(() -> empty(h, chest))
		.thenIdle(10)
		.thenWaitUntil(() -> assertOutput(h, comparator, 0, "emptied again"))
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200)
	public void filingCabinetComparator(GameTestHelper h) {
		BlockPos pos = new BlockPos(3, 3, 3);
		BlockPos comparator = new BlockPos(4, 3, 3);
		h.setBlock(pos, Content.filingCabinet.get());
		comparatorEast(h, comparator);
		FilingCabinetBlockEntity be = be(h, pos);
		h.startSequence()
		.thenIdle(5)
		.thenExecute(() -> assertOutput(h, comparator, 0, "an empty cabinet"))
		// 100 of 512 single-item slots: 1 + floor(14 * 100 / 512) = 3
		.thenExecute(() -> {
			fillSwords(be, 100);
			eq(AbstractContainerMenu.getRedstoneSignalFromContainer(be.getInv()), 3, "vanilla formula on the cabinet");
		})
		.thenIdle(5)
		.thenWaitUntil(() -> assertOutput(h, comparator, 3, "100 of 512 slots"))
		.thenExecute(() -> fillSwords(be, 1))
		.thenIdle(5)
		.thenWaitUntil(() -> assertOutput(h, comparator, 1, "a single item"))
		.thenExecute(() -> fillSwords(be, 512))
		.thenIdle(5)
		.thenWaitUntil(() -> assertOutput(h, comparator, 15, "a full cabinet"))
		.thenExecute(() -> fillSwords(be, 0))
		.thenIdle(5)
		.thenWaitUntil(() -> assertOutput(h, comparator, 0, "emptied again"))
		.thenSucceed();
	}

	private static void fillSwords(FilingCabinetBlockEntity be, int n) {
		for (int i = 0; i < be.getInv().getContainerSize(); i++) {
			be.getInv().setItem(i, i < n ? new ItemStack(Items.DIAMOND_SWORD) : ItemStack.EMPTY);
		}
		be.setChanged();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = "entities_crate_comparator")
	public void openCrateComparator(GameTestHelper h) {
		BlockPos pos = new BlockPos(3, 3, 3);
		BlockPos comparator = new BlockPos(4, 3, 3);
		h.setBlock(pos, facing(Content.openCrate.get(), Direction.UP));
		comparatorEast(h, comparator);
		Vec3 above = h.absoluteVec(new Vec3(3.5, 4.5, 3.5));
		// Only the entities this test spawned are counted and removed, never anything by area
		List<ItemEntity> dropped = new ArrayList<>();
		h.startSequence()
		.thenIdle(10)
		.thenExecute(() -> assertOutput(h, comparator, 0, "no items in front of the crate"))
		// every item entity is a slot out of 27, like a chest: 1 + floor(14 * 1 / 27) = 1
		.thenExecute(() -> stacks(h, above, 1, dropped))
		.thenIdle(12)
		.thenWaitUntil(() -> {
			stacks(h, above, 1, dropped);
			assertOutput(h, comparator, 1, "one full stack");
		})
		// 15 stacks: 1 + floor(14 * 15 / 27) = 8
		.thenExecute(() -> stacks(h, above, 15, dropped))
		.thenIdle(12)
		.thenWaitUntil(() -> {
			stacks(h, above, 15, dropped);
			assertOutput(h, comparator, 8, "15 of 27 stacks");
		})
		.thenExecute(() -> stacks(h, above, 27, dropped))
		.thenIdle(12)
		.thenWaitUntil(() -> {
			stacks(h, above, 27, dropped);
			assertOutput(h, comparator, 15, "27 stacks");
		})
		.thenExecute(() -> stacks(h, above, 0, dropped))
		.thenIdle(12)
		.thenWaitUntil(() -> assertOutput(h, comparator, 0, "the items are gone"))
		.thenSucceed();
	}

	/**
	 * Makes exactly {@code n} full stacks of items this test spawned exist in front of the crate: dead ones
	 * are dropped from the list (and logged, they should not vanish on their own), missing ones are added,
	 * extra ones removed.
	 */
	private static void stacks(GameTestHelper h, Vec3 at, int n, List<ItemEntity> dropped) {
		int before = dropped.size();
		dropped.removeIf(e -> !e.isAlive());
		if (dropped.size() != before)StorageMod.LOGGER.warn("[RedstoneGameTests] {} spawned item entities disappeared on their own", before - dropped.size());
		while (dropped.size() > n)dropped.remove(dropped.size() - 1).discard();
		while (dropped.size() < n) {
			ItemEntity e = new ItemEntity(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.COBBLESTONE, 64));
			e.setDeltaMovement(Vec3.ZERO);
			h.getLevel().addFreshEntity(e);
			dropped.add(e);
		}
	}
}
