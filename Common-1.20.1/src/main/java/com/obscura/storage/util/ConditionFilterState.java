package com.obscura.storage.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * Stored state of the Condition Filter: one bit per {@link FilterCondition}. A condition is
 * Ignored when its bit is in neither mask, Required when it is in {@code required} and Excluded
 * when it is in {@code excluded}. A bit can never be in both masks (Required wins), and bits
 * outside the known conditions are dropped, so bad data can not produce an impossible filter.
 * <p>
 * The masks are stored in the item's NBT as the ints {@code Required} and {@code Excluded}.
 */
public record ConditionFilterState(int required, int excluded) {
	public static final int STATE_IGNORE = 0;
	public static final int STATE_REQUIRED = 1;
	public static final int STATE_EXCLUDED = 2;
	public static final int STATES = 3;

	public static final int ALL_MASK = (1 << FilterCondition.COUNT) - 1;
	public static final ConditionFilterState EMPTY = new ConditionFilterState(0, 0);

	private static final String KEY_REQUIRED = "Required";
	private static final String KEY_EXCLUDED = "Excluded";

	public ConditionFilterState {
		required &= ALL_MASK;
		excluded &= ALL_MASK & ~required;
	}

	/** Reads the state from the stack's NBT; a missing or broken tag is an empty filter. */
	public static ConditionFilterState read(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null)return EMPTY;
		return new ConditionFilterState(tag.getInt(KEY_REQUIRED), tag.getInt(KEY_EXCLUDED));
	}

	/** Writes the state to the stack's NBT. An empty state removes the keys again. */
	public void write(ItemStack stack) {
		if (isEmpty()) {
			CompoundTag tag = stack.getTag();
			if (tag == null)return;
			tag.remove(KEY_REQUIRED);
			tag.remove(KEY_EXCLUDED);
			if (tag.isEmpty())stack.setTag(null);
			return;
		}
		CompoundTag tag = stack.getOrCreateTag();
		tag.putInt(KEY_REQUIRED, required);
		tag.putInt(KEY_EXCLUDED, excluded);
	}

	/** True if no condition is Required or Excluded; such a filter matches nothing. */
	public boolean isEmpty() {
		return (required | excluded) == 0;
	}

	/** Bits of every condition that is not Ignored. */
	public int activeMask() {
		return required | excluded;
	}

	/** @return {@link #STATE_IGNORE}, {@link #STATE_REQUIRED} or {@link #STATE_EXCLUDED}; out of range indexes are Ignore. */
	public int getState(int condition) {
		if (condition < 0 || condition >= FilterCondition.COUNT)return STATE_IGNORE;
		int bit = 1 << condition;
		if ((required & bit) != 0)return STATE_REQUIRED;
		if ((excluded & bit) != 0)return STATE_EXCLUDED;
		return STATE_IGNORE;
	}

	/** @return a copy with the given condition set to the given state; invalid input returns this unchanged. */
	public ConditionFilterState withState(int condition, int state) {
		if (condition < 0 || condition >= FilterCondition.COUNT || state < 0 || state >= STATES)return this;
		int bit = 1 << condition;
		int r = required & ~bit;
		int e = excluded & ~bit;
		if (state == STATE_REQUIRED)r |= bit;
		else if (state == STATE_EXCLUDED)e |= bit;
		return new ConditionFilterState(r, e);
	}

	/**
	 * The decision rule: every Required trait must be present, every Excluded trait must be absent
	 * and at least one condition must be set (an empty filter matches nothing).
	 *
	 * @param traits bit i is set if the item has trait i
	 */
	public boolean matches(int traits) {
		if (isEmpty())return false;
		return (traits & required) == required && (traits & excluded) == 0;
	}

	/** Next state when the user clicks a condition button: Ignore, Required, Excluded, Ignore, ... */
	public static int nextState(int state) {
		return (state + 1) % STATES;
	}

	/** Menu button id sent to the server. */
	public static int encodeButton(int condition, int newState) {
		return condition * STATES + newState;
	}

	/** @return the condition index of a menu button id, or -1 if the id is invalid. */
	public static int buttonCondition(int id) {
		if (id < 0 || id >= FilterCondition.COUNT * STATES)return -1;
		return id / STATES;
	}

	/** @return the requested state of a menu button id, or -1 if the id is invalid. */
	public static int buttonState(int id) {
		if (id < 0 || id >= FilterCondition.COUNT * STATES)return -1;
		return id % STATES;
	}
}
