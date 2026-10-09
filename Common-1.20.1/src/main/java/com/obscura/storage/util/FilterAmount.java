package com.obscura.storage.util;

/** How much an Ejector or Exporter handles per operation. The ordinal is saved to NBT, only append new values. */
public enum FilterAmount {
	/** A single item. */
	ITEM,
	/** Up to one stack of one item, see {@link FilterBlockLogic#batchSize}. */
	STACK;

	public static final FilterAmount[] VALUES = values();

	/** Unknown or missing ids load as {@code def}. */
	public static FilterAmount byId(int id, FilterAmount def) {
		return id >= 0 && id < VALUES.length ? VALUES[id] : def;
	}
}
