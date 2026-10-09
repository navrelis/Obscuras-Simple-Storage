package com.obscura.storage.util;

import java.util.Locale;

/** What an Ejector, Disposal or Exporter is doing, for its GUI. Synced by ordinal. */
public enum FilterBlockStatus {
	RUNNING,
	PAUSED,
	STOPPED_BY_REDSTONE,
	NEEDS_REDSTONE,
	NO_SOURCE,
	NO_TARGET,
	NO_FILTER;

	private static final FilterBlockStatus[] VALUES = values();

	public static FilterBlockStatus byId(int id) {
		return id >= 0 && id < VALUES.length ? VALUES[id] : RUNNING;
	}

	public String getTranslationKey() {
		return "tooltip.obscuras_storage.filter_block.status." + name().toLowerCase(Locale.ROOT);
	}
}
