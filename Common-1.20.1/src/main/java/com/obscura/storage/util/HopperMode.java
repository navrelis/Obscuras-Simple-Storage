package com.obscura.storage.util;

import java.util.Locale;

/** Transfer modes of the Basic Inventory Hopper. The ordinal is saved to NBT, only append new values. */
public enum HopperMode {
	/** Moves matching items one by one until the target is full, redstone disables it. */
	NORMAL,
	/** Idles, every redstone rising edge moves exactly {@code amount} items. */
	PULSE,
	/** Like {@link #NORMAL} but only while the target holds fewer than {@code amount} matching items. */
	KEEP_STOCK;

	private static final HopperMode[] VALUES = values();

	public HopperMode next() {
		return VALUES[(ordinal() + 1) % VALUES.length];
	}

	/** Unknown or missing ids (old worlds) load as {@link #NORMAL}. */
	public static HopperMode byId(int id) {
		return id >= 0 && id < VALUES.length ? VALUES[id] : NORMAL;
	}

	/** Pulse mode is triggered by redstone, so redstone must not disable it. */
	public boolean isDisabledByRedstone() {
		return this != PULSE;
	}

	/** Pulse and Keep Stock count or move a specific item, without a filter they do nothing. */
	public boolean needsFilter() {
		return this != NORMAL;
	}

	public String getTranslationKey() {
		return "tooltip.obscuras_storage.hopper_mode." + name().toLowerCase(Locale.ROOT);
	}
}
