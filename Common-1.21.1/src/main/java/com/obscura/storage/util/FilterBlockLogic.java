package com.obscura.storage.util;

/** World independent decisions of the Ejector, Disposal and Exporter, kept separate so they can be unit tested. */
public class FilterBlockLogic {
	public static final int MIN_COOLDOWN = 1;
	public static final int MAX_COOLDOWN = 200;
	/** Most items one operation handles, whatever the item's stack size. */
	public static final int MAX_BATCH = 64;

	public static int clampCooldown(int cooldown) {
		return Math.max(MIN_COOLDOWN, Math.min(MAX_COOLDOWN, cooldown));
	}

	/** Value to store after an attempt so that the next one happens exactly {@code cd} ticks later. */
	public static int cooldownAfterOperation(int cd) {
		return Math.max(0, clampCooldown(cd) - 1);
	}

	/** Items of one type an operation handles: 1, or one stack of at most {@link #MAX_BATCH}. */
	public static int batchSize(FilterAmount amount, int maxStackSize) {
		if (amount != FilterAmount.STACK)return 1;
		return Math.max(1, Math.min(MAX_BATCH, maxStackSize));
	}

	/**
	 * Whether an Ejector may try a batch now. Continuous works while powered; Pulse works off the
	 * pending rising edge, whether or not the signal is still on.
	 */
	public static boolean ejectorMayRun(EjectorMode mode, boolean powered, boolean pulsePending) {
		return mode == EjectorMode.PULSE ? pulsePending : powered;
	}

	/**
	 * Whether the cooldown spaces an Ejector's batches. Not in Pulse mode: every rising edge gives its
	 * batch on the next tick, the redstone signal is what limits the rate there.
	 */
	public static boolean ejectorWaitsForCooldown(EjectorMode mode) {
		return mode != EjectorMode.PULSE;
	}

	/** A Disposal works only when it was started by hand and no redstone signal stops it. */
	public static boolean disposalMayRun(boolean paused, boolean powered) {
		return !paused && !powered;
	}

	/** An Exporter works unless a redstone signal stops it. */
	public static boolean exporterMayRun(boolean powered) {
		return !powered;
	}

	/** {@code a + b} for non-negative counts, stopping at {@link Long#MAX_VALUE} instead of overflowing. */
	public static long saturatedAdd(long a, long b) {
		long r = a + b;
		return r < 0 ? Long.MAX_VALUE : r;
	}
}
