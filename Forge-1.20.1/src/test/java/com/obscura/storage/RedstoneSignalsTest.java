package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.util.RedstoneSignals;

/** The Level Emitter's analog formulas and the comparator fill formula. */
@DisplayName("Redstone signals")
public class RedstoneSignalsTest {

	private static int gt(long amount, long threshold) {
		return RedstoneSignals.emitterSignal(amount, threshold, false);
	}

	private static int lt(long amount, long threshold) {
		return RedstoneSignals.emitterSignal(amount, threshold, true);
	}

	@Test
	@DisplayName("More than: 0 when empty, at least 1 with anything stored, 15 from the threshold")
	void analogMoreThanEdges() {
		assertEquals(0, gt(0, 640));
		assertEquals(1, gt(1, 640));
		assertEquals(14, gt(639, 640));
		assertEquals(15, gt(640, 640));
		assertEquals(15, gt(641, 640));
		assertEquals(15, gt(Long.MAX_VALUE, 640));
		// 1 + floor(14 * 320 / 640) = 8
		assertEquals(8, gt(320, 640));
	}

	@Test
	@DisplayName("More than: tiny and zero thresholds")
	void analogMoreThanSmallThresholds() {
		assertEquals(0, gt(0, 1));
		assertEquals(15, gt(1, 1));
		assertEquals(15, gt(5, 1));
		assertEquals(0, gt(0, 0));
		assertEquals(15, gt(1, 0));
		assertEquals(8, gt(1, 2));
		assertEquals(15, gt(2, 2));
	}

	@Test
	@DisplayName("Less than: 15 when empty, 0 from the threshold, 1 just below it")
	void analogLessThanEdges() {
		assertEquals(15, lt(0, 640));
		assertEquals(15, lt(1, 640));
		assertEquals(1, lt(639, 640));
		assertEquals(0, lt(640, 640));
		assertEquals(0, lt(641, 640));
		assertEquals(0, lt(Long.MAX_VALUE, 640));
		// 15 - floor(15 * 320 / 640) = 8
		assertEquals(8, lt(320, 640));
	}

	@Test
	@DisplayName("Less than: tiny and zero thresholds")
	void analogLessThanSmallThresholds() {
		assertEquals(15, lt(0, 1));
		assertEquals(0, lt(1, 1));
		assertEquals(0, lt(0, 0));
		assertEquals(0, lt(1, 0));
		assertEquals(15, lt(0, 2));
		assertEquals(8, lt(1, 2));
		assertEquals(0, lt(2, 2));
	}

	@Test
	@DisplayName("Both modes stay within 0..15 and only move in their direction")
	void analogMonotonic() {
		for (long t : new long[] {1, 2, 3, 14, 15, 16, 64, 640, 1000, 4096}) {
			int prevGt = 0;
			int prevLt = 15;
			for (long a = 0; a <= t + 2; a++) {
				int g = gt(a, t);
				int l = lt(a, t);
				assertTrue(g >= prevGt && g >= 0 && g <= 15, "more than, threshold " + t + " amount " + a + " = " + g);
				assertTrue(l <= prevLt && l >= 0 && l <= 15, "less than, threshold " + t + " amount " + a + " = " + l);
				if (a > 0)assertTrue(g >= 1, "more than reads 1 once something is stored");
				if (a < t)assertTrue(l >= 1, "less than reads at least 1 below the threshold");
				prevGt = g;
				prevLt = l;
			}
		}
	}

	@Test
	@DisplayName("Fill: 0 when empty, 1 + floor(14 * fullness) otherwise, capped at 15")
	void fillEdges() {
		assertEquals(0, RedstoneSignals.fill(0, false, 27));
		assertEquals(0, RedstoneSignals.fill(5, true, 0));
		assertEquals(1, RedstoneSignals.fill(RedstoneSignals.slotFill(1, 64), true, 27));
		assertEquals(15, RedstoneSignals.fill(27, true, 27));
		assertEquals(15, RedstoneSignals.fill(40, true, 27));
		assertEquals(8, RedstoneSignals.fill(13.5F, true, 27));
		assertEquals(1, RedstoneSignals.fill(RedstoneSignals.slotFill(1, 0), true, 100000));
	}

}
