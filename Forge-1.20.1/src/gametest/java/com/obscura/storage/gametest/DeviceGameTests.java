package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import com.obscura.storage.StorageMod;
import com.obscura.storage.util.ItemHandlers;
import com.obscura.storage.util.ItemKey;
import net.minecraftforge.items.IItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import com.obscura.storage.Content;
import com.obscura.storage.block.LevelEmitterBlock;
import com.obscura.storage.tile.FilingCabinetBlockEntity;
import com.obscura.storage.tile.LevelEmitterBlockEntity;
import com.obscura.storage.tile.OpenCrateBlockEntity;

/** Level Emitter, Filing Cabinet and Open Crate, in a real world. */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class DeviceGameTests {

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void levelEmitterFollowsThreshold(GameTestHelper h) {
		BlockPos chest = new BlockPos(1, 2, 3);
		BlockPos connector = new BlockPos(2, 2, 3);
		BlockPos emitter = new BlockPos(3, 2, 3);
		BlockPos lamp = new BlockPos(4, 2, 3);
		chest(h, chest, new ItemStack(Items.IRON_INGOT, 10));
		h.setBlock(connector, Content.connector.get());
		// Reads the block behind it (west, the connector) and powers the block in front of it (east)
		h.setBlock(emitter, facing(Content.levelEmitter.get(), Direction.EAST));
		h.setBlock(lamp, Blocks.REDSTONE_LAMP);
		LevelEmitterBlockEntity be = be(h, emitter);
		be.setFilter(new ItemStack(Items.IRON_INGOT));
		be.setCount(15);
		be.setLessThan(false);
		h.startSequence()
		.thenIdle(30)
		.thenExecute(() -> {
			h.assertFalse(h.getBlockState(emitter).getValue(LevelEmitterBlock.POWERED), "10 iron is not more than 15: must be off");
			h.assertFalse(h.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "lamp must be off");
			fill(h, chest, new ItemStack(Items.IRON_INGOT, 10), new ItemStack(Items.IRON_INGOT, 10));
		})
		.thenWaitUntil(() -> {
			h.assertTrue(h.getBlockState(emitter).getValue(LevelEmitterBlock.POWERED), "20 iron is more than 15: must be on");
			h.assertTrue(h.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "lamp must be lit");
		})
		.thenExecute(() -> container(h, chest).removeItem(1, 10))
		.thenWaitUntil(() -> {
			h.assertFalse(h.getBlockState(emitter).getValue(LevelEmitterBlock.POWERED), "back to 10 iron: must be off");
			h.assertFalse(h.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), "lamp must be off again");
		})
		// Less-than mode inverts it
		.thenExecute(() -> be.setLessThan(true))
		.thenWaitUntil(() -> h.assertTrue(h.getBlockState(emitter).getValue(LevelEmitterBlock.POWERED), "less than 15 with 10 iron: must be on"))
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100)
	public void filingCabinetKeepsMoreThan256Items(GameTestHelper h) {
		BlockPos pos = new BlockPos(3, 2, 3);
		h.setBlock(pos, Content.filingCabinet.get());
		FilingCabinetBlockEntity be = be(h, pos);
		IItemHandler storage = ItemHandlers.find(h.getLevel(), h.absolutePos(pos), Direction.UP);
		h.assertTrue(storage != null, "filing cabinet exposes no item storage");
		int total = 300;
		for (int i = 0; i < total; i++) {
			ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
			sword.setDamageValue(i); // makes every item distinct
			ItemStack rem = ItemHandlers.insert(storage, sword, false);
			eq(rem.isEmpty(), true, "insert of sword " + i);
		}
		eq(damages(be.getInv()).size(), total, "swords in the cabinet before saving");

		// Save and load into a fresh block entity, like a chunk reload
		CompoundTag tag = be.saveWithFullMetadata();
		BlockEntity loaded = BlockEntity.loadStatic(h.absolutePos(pos), h.getBlockState(pos), tag);
		h.assertTrue(loaded instanceof FilingCabinetBlockEntity, "reloaded block entity type");
		Set<Integer> after = damages(((FilingCabinetBlockEntity) loaded).getInv());
		eq(after.size(), total, "distinct swords after reload");
		for (int i = 0; i < total; i++)h.assertTrue(after.contains(i), "sword " + i + " lost on reload");

		// Stackables and container items are rejected, on an empty cabinet too
		BlockPos pos2 = new BlockPos(5, 2, 3);
		h.setBlock(pos2, Content.filingCabinet.get());
		IItemHandler empty = ItemHandlers.find(h.getLevel(), h.absolutePos(pos2), Direction.UP);
		for (Item rejected : List.of(Items.SHULKER_BOX, Items.RED_SHULKER_BOX, Items.BUNDLE, Items.COBBLESTONE)) {
			eq(ItemHandlers.insert(empty, new ItemStack(rejected), false).getCount(), 1, rejected + " must be rejected");
			h.assertFalse(((FilingCabinetBlockEntity) h.getBlockEntity(pos2)).getInv().canPlaceItem(0, new ItemStack(rejected)), rejected + " canPlaceItem");
		}
		h.succeed();
	}

	private static Set<Integer> damages(Container c) {
		Set<Integer> set = new HashSet<>();
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.is(Items.DIAMOND_SWORD))set.add(s.getDamageValue());
		}
		return set;
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200, batch = "entities_crate_stacks")
	public void openCrateStackSizesAndLongExtract(GameTestHelper h) {
		BlockPos pos = new BlockPos(3, 2, 3);
		h.setBlock(pos, facing(Content.openCrate.get(), Direction.UP));
		OpenCrateBlockEntity be = be(h, pos);
		IItemHandler st = ItemHandlers.find(h.getLevel(), h.absolutePos(pos), Direction.UP);
		AABB above = new AABB(h.absolutePos(pos.above())).inflate(1);
		h.startSequence()
		.thenIdle(2)
		.thenExecute(() -> {
			long inserted = 0;
			// One call may take at most a stack; keep inserting like a network would
			for (int i = 0; i < 4 && inserted < 128; i++) {
				int want = (int) (128 - inserted);
				inserted += want - ItemHandlers.insert(st, new ItemStack(Items.COBBLESTONE, want), false).getCount();
			}
			long pearls = 40 - ItemHandlers.insert(st, new ItemStack(Items.ENDER_PEARL, 40), false).getCount();
			eq(pearls, 16L, "one insert call gives at most one ender pearl stack");
			eq(inserted, 128L, "cobblestone inserted in total");
			List<ItemEntity> items = h.getLevel().getEntitiesOfClass(ItemEntity.class, above);
			long cobble = 0;
			for (ItemEntity e : items) {
				ItemStack s = e.getItem();
				h.assertTrue(s.getCount() >= 1 && s.getCount() <= s.getMaxStackSize(), "item entity of " + s.getCount() + " " + s.getItem() + " exceeds the max stack size");
				if (s.is(Items.COBBLESTONE))cobble += s.getCount();
			}
			eq(cobble, 128L, "cobblestone in item entities");
		})
		// The crate refreshes its entity list every 5 ticks
		.thenIdle(10)
		.thenExecute(() -> {
			long out = ItemHandlers.extract(st, ItemKey.of(Items.COBBLESTONE), Long.MAX_VALUE, false);
			eq(out, 128L, "extract with Long.MAX_VALUE");
		})
		.thenIdle(1)
		.thenExecute(() -> {
			long left = h.getLevel().getEntitiesOfClass(ItemEntity.class, above).stream().filter(e -> e.isAlive() && e.getItem().is(Items.COBBLESTONE)).mapToLong(e -> e.getItem().getCount()).sum();
			eq(left, 0L, "cobblestone entities left after extracting everything");
			h.killAllEntitiesOfClass(ItemEntity.class);
		})
		.thenSucceed();
	}
}
