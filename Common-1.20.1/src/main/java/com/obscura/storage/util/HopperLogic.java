package com.obscura.storage.util;

/** World independent decisions of the Basic Inventory Hopper modes. */
public class HopperLogic {
	public static final int MIN_AMOUNT = 1;
	public static final int MAX_AMOUNT = 64;
	/** Amounts the Inventory Configurator cycles through, 9 fills a 3x3 Crafter with one item per slot. */
	private static final int[] AMOUNT_STEPS = {1, 2, 4, 8, 9, 16, 32, 64};

	public static int clampAmount(int amount) {
		return Math.max(MIN_AMOUNT, Math.min(MAX_AMOUNT, amount));
	}

	/** Next configurator step above {@code amount}, wrapping to the first step after the last one. */
	public static int nextAmount(int amount) {
		for (int step : AMOUNT_STEPS) {
			if (step > amount)return step;
		}
		return AMOUNT_STEPS[0];
	}

	public static boolean isRisingEdge(boolean wasPowered, boolean powered) {
		return !wasPowered && powered;
	}

	/** Keep Stock only moves while the target holds fewer matching items than the requested amount. */
	public static boolean needsStock(long countInTarget, int amount) {
		return countInTarget < amount;
	}
}
