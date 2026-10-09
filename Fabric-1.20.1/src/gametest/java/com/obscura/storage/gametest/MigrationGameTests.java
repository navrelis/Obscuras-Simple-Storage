package com.obscura.storage.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import com.obscura.storage.Content;

/**
 * Worlds and saved data written before v5.0.0 (mod id {@code toms_storage}) load in this version:
 * registry aliases (MappedRegistryMixin, also for the holder lookups), block states, block entities, item
 * stacks with old ids in their NBT, the block filter and Inventory Link saved data, and the removed
 * Filtered Connector, which still converts itself.
 */
public class MigrationGameTests implements FabricGameTest {

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldRegistryIdsResolve(GameTestHelper h) {
		LegacyMigrationChecks.checkVanillaRegistries(h, true, true);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldBlockStatesLoad(GameTestHelper h) {
		LegacyMigrationChecks.checkBlockStates(h, true);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldItemStacksLoad(GameTestHelper h) {
		LegacyMigrationChecks.checkItemStacks(h);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldBlockEntitiesLoad(GameTestHelper h) {
		LegacyMigrationChecks.checkBlockEntities(h, new BlockPos(2, 2, 2), new BlockPos(5, 2, 5));
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldBlockFiltersLoad(GameTestHelper h) {
		BlockPos chest = new BlockPos(2, 2, 2);
		BlockPos cabinet = new BlockPos(5, 2, 5);
		h.setBlock(chest, Blocks.CHEST);
		h.setBlock(cabinet, Content.filingCabinet.get());
		LegacyMigrationChecks.checkBlockFilterData(h, chest, cabinet);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldSavedDataLoads(GameTestHelper h) {
		LegacyMigrationChecks.checkSavedData(h);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void oldFilteredConnectorConverts(GameTestHelper h) {
		BlockPos pos = new BlockPos(3, 2, 3);
		LegacyMigrationChecks.checkFilteredConnectorLoads(h, pos);
		// The converter block replaces itself with a normal Inventory Cable Connector on its first tick
		h.setBlock(pos, Content.invCableConnectorFiltered.get());
		h.succeedWhen(() -> h.assertBlockPresent(Content.invCableConnector.get(), pos));
	}
}
