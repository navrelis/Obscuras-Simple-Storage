package com.obscura.storage.util;

import java.util.Set;

import net.minecraft.core.Direction;

/** World independent decisions of the Ejector, Disposal and Exporter, kept separate so they can be unit tested. */
public class FilterBlockLogic {
	public static final int MIN_COOLDOWN = 1;
	public static final int MAX_COOLDOWN = 200;
	/** Most items one operation handles, whatever the item's stack size. */
	public static final int MAX_BATCH = 64;
	/**
	 * Oldest item totals the idle check of a block that waits for the cooldown accepts, in ticks, like
	 * the Level Emitter's and the Basic Inventory Hopper's: idle blocks on one network then share one
	 * count of it every few ticks instead of counting it once each. A change is never missed, the
	 * block only sees it up to 3 ticks later, with the first check after the totals were read again.
	 */
	public static final int IDLE_MAX_AGE = 4;
	/**
	 * Stocked slots one operation still looks at after the first slot that gave it items. A block that
	 * cannot fill its batch (a stack is asked for, the network holds less) would otherwise walk to the
	 * end of the network on every operation. What lies further on is left to the next operation.
	 */
	public static final int MAX_SLOT_VISITS = 256;

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
	 * How old the item totals may be that an idle block compares with what it remembered, in ticks.
	 * A block that works without waiting for the cooldown (the Ejector's Pulse mode) reads them afresh:
	 * a pulse that follows an item within a few ticks must still give its batch.
	 */
	public static int idleMaxAge(boolean waitsForCooldown) {
		return waitsForCooldown ? IDLE_MAX_AGE : 1;
	}

	/**
	 * Whether an operation still looks at the {@code visited}-th stocked slot after the first one that
	 * gave it items (1 = the slot right after it), see {@link #MAX_SLOT_VISITS}.
	 */
	public static boolean gathersFrom(int visited) {
		return visited <= MAX_SLOT_VISITS;
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

	/**
	 * The placement rule of the three blocks, the first case that applies decides:
	 * <ol>
	 * <li>placed against a network block (cable, connector, interface...): the BACK faces it, so it
	 * plugs in;</li>
	 * <li>placed against any other block with a block entity (chest, barrel, Shulker Dock, a machine):
	 * the FRONT points at it;</li>
	 * <li>a network block next to the new block: the BACK faces it. With several, the one opposite the
	 * clicked block wins, then the first in the order down, up, north, south, west, east;</li>
	 * <li>otherwise like a vanilla dropper: the front faces the player.</li>
	 * </ol>
	 *
	 * @param toClicked direction from the new block to the block it was placed against
	 * @param clickedNetworkBlock that block is a network block
	 * @param clickedHasBlockEntity that block has a block entity
	 * @param networkNeighbours directions from the new block in which a network block sits
	 * @param dropperFront the front a dropper placed by the same click would get
	 * @return the front (FACING) of the new block
	 */
	public static Direction frontFor(Direction toClicked, boolean clickedNetworkBlock, boolean clickedHasBlockEntity, Set<Direction> networkNeighbours, Direction dropperFront) {
		if (clickedNetworkBlock)return toClicked.getOpposite();
		if (clickedHasBlockEntity)return toClicked;
		if (networkNeighbours.contains(toClicked.getOpposite()))return toClicked;
		// Direction.values(): down, up, north, south, west, east
		for (Direction d : Direction.values()) {
			if (networkNeighbours.contains(d))return d.getOpposite();
		}
		return dropperFront;
	}

	/** {@code a + b} for non-negative counts, stopping at {@link Long#MAX_VALUE} instead of overflowing. */
	public static long saturatedAdd(long a, long b) {
		long r = a + b;
		return r < 0 ? Long.MAX_VALUE : r;
	}
}
