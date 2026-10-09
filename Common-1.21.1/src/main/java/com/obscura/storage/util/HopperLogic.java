package com.obscura.storage.util;

import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** World independent decisions of the Basic Inventory Hopper modes, kept separate so they can be unit tested. */
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

	/**
	 * Moves up to {@code amount} items, a single item per step. Both the source and the destination are
	 * looked up again for every item so that inventories which only accept an item into specific slots
	 * (the vanilla Crafter keeps its slots balanced) get one item per call. Stops early without carry-over
	 * when the source runs out, the target is full or a transfer fails.
	 *
	 * @param findSource gets the previous source (null on the first item) and returns the source to take the next item from, or null
	 * @param findDest returns a destination accepting an item from the source, or null
	 * @param moveOne moves a single item, returns false when nothing was moved
	 * @return the number of items moved
	 */
	public static <S, D> int moveOneAtATime(int amount, UnaryOperator<S> findSource, Function<S, D> findDest, BiPredicate<S, D> moveOne) {
		int moved = 0;
		S src = null;
		while (moved < amount) {
			src = findSource.apply(src);
			if (src == null)break;
			D dst = findDest.apply(src);
			if (dst == null)break;
			if (!moveOne.test(src, dst))break;
			moved++;
		}
		return moved;
	}
}
