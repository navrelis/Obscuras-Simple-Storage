package com.obscura.storage.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import com.obscura.storage.Content;
import com.obscura.storage.StorageModComponents;
import com.obscura.storage.block.InventoryCableBlock;
import com.obscura.storage.block.entity.InventoryCableConnectorBlockEntity;
import com.obscura.storage.inventory.BlockFilterComponent;

/**
 * A world saved by the mod's Minecraft 1.20.1 builds, upgraded to 1.21.1: every block entity, the saved
 * data and the mod's items keep their contents and settings. See Legacy1201Checks for the data.
 */
public class Upgrade1201GameTests implements FabricGameTest {

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void dockBoxLoads(GameTestHelper h) {
		Legacy1201Checks.shulkerDock(h, new BlockPos(2, 2, 2));
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void filingCabinetLoads(GameTestHelper h) {
		Legacy1201Checks.filingCabinet(h, new BlockPos(2, 2, 2));
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void hopperAndEmitterFiltersLoad(GameTestHelper h) {
		Legacy1201Checks.hopper(h, new BlockPos(2, 2, 2));
		Legacy1201Checks.levelEmitter(h, new BlockPos(4, 2, 4));
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void craftingTerminalLoads(GameTestHelper h) {
		Legacy1201Checks.craftingTerminal(h, new BlockPos(2, 2, 2));
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void paintedAndRenamedBlockEntitiesLoad(GameTestHelper h) {
		Legacy1201Checks.paintedAndRenamed(h, new BlockPos(2, 2, 2), new BlockPos(4, 2, 2), new BlockPos(6, 2, 2));
		Legacy1201Checks.filteredConnectorIds(h);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 40)
	public void paintedVariantsLoad(GameTestHelper h) {
		BlockPos cable = Legacy1201Checks.paintedVariants(h, new BlockPos(3, 2, 3));
		h.succeedWhen(() -> {
			var st = h.getBlockState(cable);
			h.assertTrue(st.getValue(InventoryCableBlock.WEST) && st.getValue(InventoryCableBlock.EAST), "connections not worked out: " + st);
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 60)
	public void filteredConnectorDropsItsFilters(GameTestHelper h) {
		BlockPos rel = new BlockPos(3, 2, 3);
		InventoryCableConnectorBlockEntity c = Legacy1201Checks.filteredConnectorLoads(h, rel);
		h.succeedWhen(() -> {
			h.assertTrue(c.getLegacyFilterItems().isEmpty(), "filter items not handed out yet");
			boolean dropped = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(rel)).inflate(2))
					.stream().anyMatch(e -> e.getItem().is(Content.itemFilter.get()));
			h.assertTrue(dropped, "filter item not dropped");
			h.assertFalse(c.saveWithFullMetadata(h.getLevel().registryAccess()).contains("filter"), "dropped items still saved");
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void savedDataLoads(GameTestHelper h) {
		Legacy1201Checks.remoteConnections(h);
		Legacy1201Checks.blockFilterFile(h, new BlockPos(2, 2, 2), BlockFilterComponent.LEGACY_TARGET, be -> {
			BlockFilterComponent comp = StorageModComponents.BLOCK_FILTER.getNullable(be);
			return comp == null ? null : comp.getFilter(false);
		});
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void itemSettingsLoad(GameTestHelper h) {
		Legacy1201Checks.items(h);
		h.succeed();
	}
}
