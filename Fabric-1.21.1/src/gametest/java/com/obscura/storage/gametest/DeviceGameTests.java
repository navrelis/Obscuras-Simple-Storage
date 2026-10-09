package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
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
import com.obscura.storage.block.entity.FilingCabinetBlockEntity;
import com.obscura.storage.block.entity.LevelEmitterBlockEntity;
import com.obscura.storage.block.entity.OpenCrateBlockEntity;

/** Level Emitter, Filing Cabinet and Open Crate, in a real world. */
public class DeviceGameTests implements FabricGameTest {

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
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
		LevelEmitterBlockEntity be = h.getBlockEntity(emitter);
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

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void filingCabinetKeepsMoreThan256Items(GameTestHelper h) {
		BlockPos pos = new BlockPos(3, 2, 3);
		h.setBlock(pos, Content.filingCabinet.get());
		FilingCabinetBlockEntity be = h.getBlockEntity(pos);
		Storage<ItemVariant> storage = ItemStorage.SIDED.find(h.getLevel(), h.absolutePos(pos), Direction.UP);
		h.assertTrue(storage != null, "filing cabinet exposes no item storage");
		int total = 300;
		for (int i = 0; i < total; i++) {
			ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
			sword.set(DataComponents.DAMAGE, i); // makes every item distinct
			try (Transaction tx = Transaction.openOuter()) {
				long in = storage.insert(ItemVariant.of(sword), 1, tx);
				h.assertValueEqual(in, 1L, "insert of sword " + i);
				tx.commit();
			}
		}
		h.assertValueEqual(damages(be.getInv()).size(), total, "swords in the cabinet before saving");

		// Save and load into a fresh block entity, like a chunk reload
		CompoundTag tag = be.saveWithFullMetadata(h.getLevel().registryAccess());
		BlockEntity loaded = BlockEntity.loadStatic(h.absolutePos(pos), h.getBlockState(pos), tag, h.getLevel().registryAccess());
		h.assertTrue(loaded instanceof FilingCabinetBlockEntity, "reloaded block entity type");
		Set<Integer> after = damages(((FilingCabinetBlockEntity) loaded).getInv());
		h.assertValueEqual(after.size(), total, "distinct swords after reload");
		for (int i = 0; i < total; i++)h.assertTrue(after.contains(i), "sword " + i + " lost on reload");

		// Stackables and container items are rejected, on an empty cabinet too
		BlockPos pos2 = new BlockPos(5, 2, 3);
		h.setBlock(pos2, Content.filingCabinet.get());
		Storage<ItemVariant> empty = ItemStorage.SIDED.find(h.getLevel(), h.absolutePos(pos2), Direction.UP);
		for (Item rejected : List.of(Items.SHULKER_BOX, Items.RED_SHULKER_BOX, Items.BUNDLE, Items.COBBLESTONE)) {
			try (Transaction tx = Transaction.openOuter()) {
				h.assertValueEqual(empty.insert(ItemVariant.of(rejected), 1, tx), 0L, rejected + " must be rejected");
			}
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

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void openCrateStackSizesAndLongExtract(GameTestHelper h) {
		BlockPos pos = new BlockPos(3, 2, 3);
		h.setBlock(pos, facing(Content.openCrate.get(), Direction.UP));
		OpenCrateBlockEntity be = h.getBlockEntity(pos);
		Storage<ItemVariant> st = be.getItemStorage(Direction.UP);
		AABB above = new AABB(h.absolutePos(pos.above())).inflate(1);
		h.startSequence()
		.thenIdle(2)
		.thenExecute(() -> {
			long inserted = 0;
			try (Transaction tx = Transaction.openOuter()) {
				// One call may take at most a stack; keep inserting like a network would
				for (int i = 0; i < 4 && inserted < 128; i++)inserted += st.insert(ItemVariant.of(Items.COBBLESTONE), 128 - inserted, tx);
				long pearls = st.insert(ItemVariant.of(Items.ENDER_PEARL), 40, tx);
				h.assertValueEqual(pearls, 16L, "one insert call gives at most one ender pearl stack");
				tx.commit();
			}
			h.assertValueEqual(inserted, 128L, "cobblestone inserted in total");
			List<ItemEntity> items = h.getLevel().getEntitiesOfClass(ItemEntity.class, above);
			long cobble = 0;
			for (ItemEntity e : items) {
				ItemStack s = e.getItem();
				h.assertTrue(s.getCount() >= 1 && s.getCount() <= s.getMaxStackSize(), "item entity of " + s.getCount() + " " + s.getItem() + " exceeds the max stack size");
				if (s.is(Items.COBBLESTONE))cobble += s.getCount();
			}
			h.assertValueEqual(cobble, 128L, "cobblestone in item entities");
		})
		// The crate refreshes its entity list every 5 ticks
		.thenIdle(10)
		.thenExecute(() -> {
			try (Transaction tx = Transaction.openOuter()) {
				long out = st.extract(ItemVariant.of(Items.COBBLESTONE), Long.MAX_VALUE, tx);
				h.assertValueEqual(out, 128L, "extract with Long.MAX_VALUE");
				tx.commit();
			}
		})
		.thenIdle(1)
		.thenExecute(() -> {
			long left = h.getLevel().getEntitiesOfClass(ItemEntity.class, above).stream().filter(e -> e.isAlive() && e.getItem().is(Items.COBBLESTONE)).mapToLong(e -> e.getItem().getCount()).sum();
			h.assertValueEqual(left, 0L, "cobblestone entities left after extracting everything");
			h.killAllEntitiesOfClass(ItemEntity.class);
		})
		.thenSucceed();
	}
}
