package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.LevelEmitterBlock;
import com.obscura.storage.block.entity.FilingCabinetBlockEntity;
import com.obscura.storage.block.entity.LevelEmitterBlockEntity;

/**
 * Level Emitter, Filing Cabinet and Open Crate, in a real world. Same checks as the Fabric
 * DeviceGameTests, through NeoForge's IItemHandler capability instead of the Transfer API.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class DeviceGameTests {
	private static final String EMPTY_STRUCTURE = GameTestUtil.EMPTY_STRUCTURE;

	private static IItemHandler handler(GameTestHelper h, BlockPos rel, Direction side) {
		return h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(rel), side);
	}

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
		IItemHandler storage = handler(h, pos, Direction.UP);
		h.assertTrue(storage != null, "filing cabinet exposes no item handler");
		int total = 300;
		for (int i = 0; i < total; i++) {
			ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
			sword.set(DataComponents.DAMAGE, i); // makes every item distinct
			ItemStack rest = ItemHandlerHelper.insertItem(storage, sword, false);
			h.assertTrue(rest.isEmpty(), "insert of sword " + i);
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
		IItemHandler empty = handler(h, pos2, Direction.UP);
		for (Item rejected : List.of(Items.SHULKER_BOX, Items.RED_SHULKER_BOX, Items.BUNDLE, Items.COBBLESTONE)) {
			ItemStack rest = ItemHandlerHelper.insertItem(empty, new ItemStack(rejected), false);
			h.assertValueEqual(rest.getCount(), 1, rejected + " must be rejected");
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
	public void openCrateStackSizesAndExtract(GameTestHelper h) {
		BlockPos pos = new BlockPos(3, 2, 3);
		h.setBlock(pos, facing(Content.openCrate.get(), Direction.UP));
		AABB above = new AABB(h.absolutePos(pos.above())).inflate(1);
		h.startSequence()
		.thenIdle(2)
		.thenExecute(() -> {
			IItemHandler st = handler(h, pos, Direction.UP);
			h.assertTrue(st != null, "open crate exposes no item handler");
			// The free slot is always the last one; one insert makes at most one full stack
			ItemStack rest = new ItemStack(Items.COBBLESTONE, 128);
			for (int i = 0; i < 4 && !rest.isEmpty(); i++)rest = st.insertItem(st.getSlots() - 1, rest, false);
			h.assertTrue(rest.isEmpty(), "cobblestone left over: " + rest);
			ItemStack pearls = st.insertItem(st.getSlots() - 1, new ItemStack(Items.ENDER_PEARL, 40), false);
			h.assertValueEqual(pearls.getCount(), 24, "one insert gives at most one ender pearl stack (16), rest returned");
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
			IItemHandler st = handler(h, pos, Direction.UP);
			long out = 0;
			for (int i = 0; i < st.getSlots(); i++) {
				if (st.getStackInSlot(i).is(Items.COBBLESTONE))out += st.extractItem(i, 64, false).getCount();
			}
			h.assertValueEqual(out, 128L, "cobblestone extracted");
			// Partial extract writes the rest back to the entity
			for (int i = 0; i < st.getSlots(); i++) {
				if (st.getStackInSlot(i).is(Items.ENDER_PEARL)) {
					h.assertValueEqual(st.extractItem(i, 5, false).getCount(), 5, "partial pearl extract");
				}
			}
		})
		.thenIdle(1)
		.thenExecute(() -> {
			long left = h.getLevel().getEntitiesOfClass(ItemEntity.class, above).stream().filter(e -> e.isAlive() && e.getItem().is(Items.COBBLESTONE)).mapToLong(e -> e.getItem().getCount()).sum();
			h.assertValueEqual(left, 0L, "cobblestone entities left after extracting everything");
			long pearls = h.getLevel().getEntitiesOfClass(ItemEntity.class, above).stream().filter(e -> e.isAlive() && e.getItem().is(Items.ENDER_PEARL)).mapToLong(e -> e.getItem().getCount()).sum();
			h.assertValueEqual(pearls, 11L, "ender pearls left in the entity");
			h.killAllEntitiesOfClass(ItemEntity.class);
		})
		.thenSucceed();
	}
}
