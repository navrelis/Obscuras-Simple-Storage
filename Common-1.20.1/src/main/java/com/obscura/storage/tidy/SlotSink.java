package com.obscura.storage.tidy;

/** Receives the slots of an inventory read by {@code com.obscura.storage.tidy.TidyIO#snapshot}. */
@FunctionalInterface
public interface SlotSink {
	/**
	 * @param slot slot index, in the order of the platform's slot list
	 * @param key the loader's item key (item and components) of the slot's contents, null when empty
	 * @param count items in the slot
	 * @param limit how many items the slot can hold (before the item's own max stack size)
	 */
	void slot(int slot, Object key, int count, int limit);
}
