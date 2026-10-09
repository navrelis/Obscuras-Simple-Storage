package com.obscura.storage.util;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** The redstone signal formulas of the Level Emitter's analog mode and of the comparator outputs. */
public final class RedstoneSignals {
	public static final int MAX = 15;

	private RedstoneSignals() {}

	/**
	 * Level Emitter, analog mode, for {@code amount} items in storage and the set {@code threshold}.
	 * <ul>
	 * <li>More than: 0 when nothing is stored, else {@code 1 + floor(14 * min(amount, threshold) / threshold)}:
	 * at least 1 as soon as anything is stored, 15 from {@code amount >= threshold}. A threshold of 0
	 * reads 15 for any amount above 0.</li>
	 * <li>Less than: 0 from {@code amount >= threshold}, else {@code 15 - floor(15 * amount / threshold)}:
	 * 15 when empty, falling to 1 just below the threshold (so it reads at least 1 exactly while the
	 * on/off condition "less than" holds).</li>
	 * </ul>
	 */
	public static int emitterSignal(long amount, long threshold, boolean lessThan) {
		if (amount < 0)amount = 0;
		if (lessThan) {
			if (amount >= threshold)return 0;
			return MAX - (int) (MAX * amount / threshold);
		}
		if (amount == 0)return 0;
		if (threshold <= 0)return MAX;
		return 1 + (int) (14 * Math.min(amount, threshold) / threshold);
	}

	/** {@code count} items of a slot that holds at most {@code max}, as a fraction of one slot. */
	public static float slotFill(long count, int max) {
		return (float)count / Math.max(1, max);
	}

	/**
	 * The vanilla container formula ({@code AbstractContainerMenu.getRedstoneSignalFromContainer}):
	 * 0 when nothing is stored, else {@code 1 + floor(14 * fullness)}, where the fullness is the sum
	 * of {@link #slotFill} over all slots divided by the slot count (at most 1).
	 *
	 * @param fillSum sum of the fractions of all used slots
	 * @param any whether anything is stored
	 * @param slots number of slots the fullness is measured against
	 */
	public static int fill(float fillSum, boolean any, int slots) {
		if (!any || slots <= 0)return 0;
		float f = Math.min(1F, fillSum / slots);
		return (int) Math.floor(f * 14F) + 1;
	}

	/** An Open Crate counts like a chest: its item entities are slots, out of this many. */
	public static final int OPEN_CRATE_SLOTS = 27;

	/** {@link #fill} of dropped items, each entity one slot out of {@link #OPEN_CRATE_SLOTS}. */
	public static int entityFill(Iterable<ItemEntity> items) {
		float sum = 0;
		boolean any = false;
		for (ItemEntity e : items) {
			ItemStack s = e.getItem();
			if (!e.isAlive() || s.isEmpty())continue;
			any = true;
			sum += slotFill(s.getCount(), s.getMaxStackSize());
		}
		return fill(sum, any, OPEN_CRATE_SLOTS);
	}
}
