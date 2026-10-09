package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.block.entity.InventoryCableConnectorBlockEntity;

/**
 * The Inventory Link reach rule. A connector that does not face a beacon has level 0, so links work without
 * a beacon inside the configured range and the stronger beacon of the pair decides about unlimited reach.
 */
@DisplayName("Inventory Link rule")
public class InventoryLinkRuleTest {
	// Config defaults: invLinkBeaconLvl 0, invLinkBeaconLvlSameDim 1, invLinkBeaconLvlCrossDim 3, range 4096
	private static final int LOCAL = 0, SAME_DIM = 1, CROSS_DIM = 3;
	private static final double RANGE = 4096;

	private static boolean link(int a, int b, boolean sameDim, double blocks) {
		return InventoryCableConnectorBlockEntity.canLink(a, b, sameDim, blocks * blocks, LOCAL, SAME_DIM, CROSS_DIM, RANGE);
	}

	@Test
	@DisplayName("Without beacons the range decides in the same dimension")
	void noBeaconsUseRange() {
		assertTrue(link(0, 0, true, 0));
		assertTrue(link(0, 0, true, 4095));
		assertFalse(link(0, 0, true, 4096), "the range itself is exclusive");
		assertFalse(link(0, 0, true, 100000));
	}

	@Test
	@DisplayName("Without beacons another dimension is never reachable")
	void noBeaconsNoCrossDim() {
		assertFalse(link(0, 0, false, 0));
		assertFalse(link(0, 0, false, 10));
	}

	@Test
	@DisplayName("Unlimited in the same dimension needs a beacon at both ends, the stronger one counts")
	void sameDimensionUnlimited() {
		assertTrue(link(1, 1, true, 100000));
		assertTrue(link(1, 4, true, 100000));
		assertTrue(link(4, 1, true, 100000));
		// one end without a beacon is level 0 and does not count as having one
		assertFalse(link(0, 4, true, 100000));
		assertFalse(link(4, 0, true, 100000));
		assertTrue(link(0, 4, true, 4000), "still fine inside the range");
		// the stronger end is below the same dimension level
		assertFalse(link(0, 1, true, 100000));
	}

	@Test
	@DisplayName("Cross dimension needs the cross dimension level on the stronger end and a beacon on both")
	void crossDimension() {
		assertTrue(link(3, 3, false, 0));
		assertTrue(link(3, 4, false, 0));
		assertTrue(link(4, 1, false, 5));
		assertFalse(link(2, 2, false, 0), "below the cross dimension level");
		assertFalse(link(0, 4, false, 0), "a beacon-less end blocks cross dimension links");
		assertFalse(link(4, 0, false, 0));
		assertFalse(link(1, 1, false, 0), "same dimension level is not enough across dimensions");
	}

	@Test
	@DisplayName("Level 0 thresholds mean no beacon is needed at all")
	void zeroThresholds() {
		// same dimension unlimited at level 0: any two connectors reach each other
		assertTrue(InventoryCableConnectorBlockEntity.canLink(0, 0, true, 1e12, LOCAL, 0, CROSS_DIM, RANGE));
		assertFalse(InventoryCableConnectorBlockEntity.canLink(0, 0, false, 0, LOCAL, 0, CROSS_DIM, RANGE));
		// cross dimension at level 0: any two connectors reach each other in any dimension
		assertTrue(InventoryCableConnectorBlockEntity.canLink(0, 0, false, 0, LOCAL, SAME_DIM, 0, RANGE));
	}

	@Test
	@DisplayName("-1 disables the matching part of the rule")
	void disabledParts() {
		// invLinkBeaconLvl -1 turns links off entirely, whatever the rest says
		assertFalse(InventoryCableConnectorBlockEntity.canLink(4, 4, true, 0, -1, SAME_DIM, CROSS_DIM, RANGE));
		assertFalse(InventoryCableConnectorBlockEntity.canLink(0, 0, true, 0, -1, 0, 0, RANGE));
		// no cross dimension links
		assertFalse(InventoryCableConnectorBlockEntity.canLink(4, 4, false, 0, LOCAL, SAME_DIM, -1, RANGE));
		assertTrue(InventoryCableConnectorBlockEntity.canLink(4, 4, true, 1e12, LOCAL, SAME_DIM, -1, RANGE));
		// no unlimited same dimension links, the range still works
		assertFalse(InventoryCableConnectorBlockEntity.canLink(4, 4, true, 1e12, LOCAL, -1, -1, RANGE));
		assertTrue(InventoryCableConnectorBlockEntity.canLink(4, 4, true, 10 * 10, LOCAL, -1, -1, RANGE));
	}

	@Test
	@DisplayName("The rule is symmetric in the two ends")
	void symmetric() {
		for (int a = 0; a <= 4; a++) {
			for (int b = 0; b <= 4; b++) {
				for (boolean sameDim : new boolean[] {true, false}) {
					for (double d : new double[] {0, 50, 4095, 4096, 1e6}) {
						assertEquals(link(a, b, sameDim, d), link(b, a, sameDim, d), a + "/" + b + " sameDim=" + sameDim + " d=" + d);
					}
				}
			}
		}
	}

	@Test
	@DisplayName("A configured range is used as blocks, not as squared blocks")
	void rangeIsInBlocks() {
		assertTrue(InventoryCableConnectorBlockEntity.canLink(0, 0, true, 99.0 * 99.0, LOCAL, SAME_DIM, CROSS_DIM, 100));
		assertFalse(InventoryCableConnectorBlockEntity.canLink(0, 0, true, 100.0 * 100.0, LOCAL, SAME_DIM, CROSS_DIM, 100));
	}
}
