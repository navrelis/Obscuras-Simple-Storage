package com.obscura.storage.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.obscura.storage.StorageMod;

/**
 * Ejector, Disposal and Exporter on Forge: the shared checks of {@link FilterBlockChecks} (conservation
 * for all three blocks, the ghost filter rules, Pulse and the saved redstone signal, the Disposal
 * counters, the Exporter targets and its target on the source network, the idle checks and the search
 * limit, the cable and connector rules, placement, the menus and the data files). A batch of its own: the tests need nothing from the
 * other test classes and leave the batch those run in as it is.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class FilterBlockGameTests {
	private static final String BATCH = "filter_blocks";
	private static final FilterBlockChecks.Platform PLATFORM = h -> FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "filter-test"));

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void ejectorContinuousDropsMatchingItemsWhilePowered(GameTestHelper h) {
		FilterBlockChecks.ejectorContinuousDropsMatchingItemsWhilePowered(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void ejectorExactDecidesAboutDamagedTools(GameTestHelper h) {
		FilterBlockChecks.ejectorExactDecidesAboutDamagedTools(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void ejectorExactMatchesTheSameNbt(GameTestHelper h) {
		FilterBlockChecks.ejectorExactMatchesTheSameNbt(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200, batch = BATCH)
	public void ejectorEmptyFilterDoesNothing(GameTestHelper h) {
		FilterBlockChecks.ejectorEmptyFilterDoesNothing(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = BATCH)
	public void ejectorPulseGivesOneBatchPerRisingEdge(GameTestHelper h) {
		FilterBlockChecks.ejectorPulseGivesOneBatchPerRisingEdge(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void ejectorPulseFollowsClockFasterThanCooldown(GameTestHelper h) {
		FilterBlockChecks.ejectorPulseFollowsClockFasterThanCooldown(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void ejectorSavedSignalPreventsPulseAfterReload(GameTestHelper h) {
		FilterBlockChecks.ejectorSavedSignalPreventsPulseAfterReload(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = BATCH)
	public void ejectorFilterItemsWorkAsEntries(GameTestHelper h) {
		FilterBlockChecks.ejectorFilterItemsWorkAsEntries(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = BATCH)
	public void ejectorWorksFromNetwork(GameTestHelper h) {
		FilterBlockChecks.ejectorWorksFromNetwork(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = BATCH)
	public void disposalStartsPausedThenDestroysMatchingOnly(GameTestHelper h) {
		FilterBlockChecks.disposalStartsPausedThenDestroysMatchingOnly(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void disposalDestroysAtMostOneStackPerCooldown(GameTestHelper h) {
		FilterBlockChecks.disposalDestroysAtMostOneStackPerCooldown(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void disposalCountsAndPauseSurviveReload(GameTestHelper h) {
		FilterBlockChecks.disposalCountsAndPauseSurviveReload(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void disposalRedstoneStopsIt(GameTestHelper h) {
		FilterBlockChecks.disposalRedstoneStopsIt(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void disposalEmptyFilterDestroysNothing(GameTestHelper h) {
		FilterBlockChecks.disposalEmptyFilterDestroysNothing(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void disposalWorksFromNetworkAndResets(GameTestHelper h) {
		FilterBlockChecks.disposalWorksFromNetworkAndResets(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = BATCH)
	public void exporterMovesItemsThenStacksIntoChest(GameTestHelper h) {
		FilterBlockChecks.exporterMovesItemsThenStacksIntoChest(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = BATCH)
	public void exporterFillsDoubleChestAndBarrel(GameTestHelper h) {
		FilterBlockChecks.exporterFillsDoubleChestAndBarrel(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = BATCH)
	public void exporterIntoShulkerDock(GameTestHelper h) {
		FilterBlockChecks.exporterIntoShulkerDock(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = BATCH)
	public void exporterFullTargetLosesNothing(GameTestHelper h) {
		FilterBlockChecks.exporterFullTargetLosesNothing(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void exporterRedstoneStopsIt(GameTestHelper h) {
		FilterBlockChecks.exporterRedstoneStopsIt(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = BATCH)
	public void exporterWorksFromNetworkWithFilterItem(GameTestHelper h) {
		FilterBlockChecks.exporterWorksFromNetworkWithFilterItem(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 600, batch = BATCH)
	public void exporterTargetOnSourceNetworkSettles(GameTestHelper h) {
		FilterBlockChecks.targetOnSourceNetwork(h, false);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 600, batch = BATCH)
	public void exporterDoubleChestTargetOnSourceNetworkSettles(GameTestHelper h) {
		FilterBlockChecks.targetOnSourceNetwork(h, true);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void exporterSourceIsItsOwnTarget(GameTestHelper h) {
		FilterBlockChecks.exporterSourceIsItsOwnTarget(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200, batch = BATCH)
	public void ejectorPulseRightAfterNewItemGivesItsBatch(GameTestHelper h) {
		FilterBlockChecks.ejectorPulseRightAfterNewItemGivesItsBatch(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void idleExporterWakesWithinCooldown(GameTestHelper h) {
		FilterBlockChecks.idleBlockWakesWithinCooldown(h, false);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void idleDisposalWakesWithinCooldown(GameTestHelper h) {
		FilterBlockChecks.idleBlockWakesWithinCooldown(h, true);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void idleExportersOnOneNetworkBothWake(GameTestHelper h) {
		FilterBlockChecks.idleExportersOnOneNetworkBothWake(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void exporterSlotWalkStopsAfterTheVisitLimit(GameTestHelper h) {
		FilterBlockChecks.slotWalkStopsAfterTheVisitLimit(h, false);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void disposalSlotWalkStopsAfterTheVisitLimit(GameTestHelper h) {
		FilterBlockChecks.slotWalkStopsAfterTheVisitLimit(h, true);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = BATCH)
	public void exporterPolymorphicFilterEntryFollowsTheTarget(GameTestHelper h) {
		FilterBlockChecks.exporterPolymorphicFilterEntryFollowsTheTarget(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = BATCH)
	public void cablesConnectToTheBackOnly(GameTestHelper h) {
		FilterBlockChecks.cablesConnectToTheBackOnly(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public void neverAnInventoryOfAConnector(GameTestHelper h) {
		FilterBlockChecks.neverAnInventoryOfAConnector(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = BATCH)
	public void placementFollowsNetworkBlocksInventoriesAndThePlayer(GameTestHelper h) {
		FilterBlockChecks.placementFollowsNetworkBlocksInventoriesAndThePlayer(h, PLATFORM);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = BATCH)
	public void menusValidateClientMessages(GameTestHelper h) {
		FilterBlockChecks.menusValidateClientMessages(h, PLATFORM);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20, batch = BATCH)
	public void blocksRecipesTagsAndLoot(GameTestHelper h) {
		FilterBlockChecks.blocksRecipesTagsAndLoot(h);
	}
}
