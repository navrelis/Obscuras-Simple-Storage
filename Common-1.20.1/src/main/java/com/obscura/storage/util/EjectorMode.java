package com.obscura.storage.util;

/** When a powered Ejector drops items. The ordinal is saved to NBT, only append new values. */
public enum EjectorMode {
	/** While powered: one batch every cooldown. */
	CONTINUOUS,
	/** One batch per redstone rising edge. */
	PULSE;

	public static final EjectorMode[] VALUES = values();

	/** Unknown or missing ids load as {@link #CONTINUOUS}. */
	public static EjectorMode byId(int id) {
		return id >= 0 && id < VALUES.length ? VALUES[id] : CONTINUOUS;
	}
}
