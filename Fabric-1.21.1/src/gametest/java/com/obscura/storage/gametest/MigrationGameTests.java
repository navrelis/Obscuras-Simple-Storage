package com.obscura.storage.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.obscura.storage.Content;
import com.obscura.storage.StorageModComponents;
import com.obscura.storage.inventory.BlockFilter;
import com.obscura.storage.inventory.BlockFilterComponent;
import com.obscura.storage.migration.LegacyIds;

/**
 * Worlds and saved data written before v5.0.0 (mod id {@code toms_storage}) load in this version:
 * registry aliases (MappedRegistryMixin), block entities, item stacks, the Cardinal Components block
 * filter under its old key (BlockEntityMixin) and the Inventory Link saved data.
 */
public class MigrationGameTests implements FabricGameTest {

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldRegistryIdsResolve(GameTestHelper h) {
		LegacyMigrationChecks.checkVanillaRegistries(h);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldBlockStatesLoad(GameTestHelper h) {
		LegacyMigrationChecks.checkBlockStates(h);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldItemStacksLoad(GameTestHelper h) {
		LegacyMigrationChecks.checkItemStacks(h);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldBlockEntitiesLoad(GameTestHelper h) {
		BlockPos cabinet = new BlockPos(2, 2, 2);
		// The cabinet also carries a block filter: saved as cardinal_components/toms_storage:block_filter
		h.setBlock(cabinet, Content.filingCabinet.get());
		BlockFilter f = BlockFilter.getOrCreateFilterAt(h.getLevel(), h.absolutePos(cabinet));
		h.assertTrue(f != null, "no block filter at the cabinet");
		f.setKeepLast(true);
		f.filter.setItem(0, GameTestUtil.itemFilter(Items.DIAMOND));
		BlockEntity be = h.getBlockEntity(cabinet);
		h.assertTrue(be.saveWithFullMetadata(h.getLevel().registryAccess()).getCompound(BlockFilterComponent.CCA_KEY).contains(LegacyIds.blockFilterKey(), Tag.TAG_COMPOUND), "block filter not saved");
		LegacyMigrationChecks.checkBlockEntities(h, cabinet, new BlockPos(5, 2, 5));
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldBlockFilterOnVanillaChestLoads(GameTestHelper h) {
		// The component sits on every block entity; a vanilla chest goes through the same load path
		BlockPos src = new BlockPos(2, 2, 2);
		h.setBlock(src, Blocks.CHEST);
		BlockFilter f = BlockFilter.findBlockFilterAt(h.getLevel(), h.absolutePos(src));
		h.assertTrue(f != null, "no block filter at the chest");
		f.setSkip(true);
		f.setKeepLast(true);
		f.filter.setItem(0, GameTestUtil.itemFilter(Items.DIAMOND));
		HolderLookup.Provider reg = h.getLevel().registryAccess();
		BlockEntity chest = h.getBlockEntity(src);
		CompoundTag old = (CompoundTag) LegacyMigrationChecks.toLegacy(chest.saveWithFullMetadata(reg));
		LegacyMigrationChecks.assertLegacyOnly(h, old);
		h.assertTrue(old.getCompound(BlockFilterComponent.CCA_KEY).contains(LegacyIds.oldBlockFilterKey(), Tag.TAG_COMPOUND), "test data lacks the old key: " + old);

		BlockPos dst = new BlockPos(5, 2, 5);
		h.setBlock(dst, Blocks.CHEST);
		BlockEntity loaded = BlockEntity.loadStatic(h.absolutePos(dst), h.getBlockState(dst), old, reg);
		h.assertTrue(loaded != null, "chest not loaded");
		BlockFilterComponent comp = StorageModComponents.BLOCK_FILTER.getNullable(loaded);
		BlockFilter g = comp == null ? null : comp.getFilter(false);
		h.assertTrue(g != null, "old block filter not read");
		h.assertTrue(g.skip() && g.isKeepLast(), "skip or keep last lost");
		h.assertTrue(g.filter.getItem(0).is(Content.itemFilter.get()), "filter item lost");
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldSavedDataLoads(GameTestHelper h) {
		LegacyMigrationChecks.checkSavedData(h);
		h.succeed();
	}
}
