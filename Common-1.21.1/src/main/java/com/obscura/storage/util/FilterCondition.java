package com.obscura.storage.util;

/** The traits the Condition Filter can test. The ordinal is the bit position in the stored masks. */
public enum FilterCondition {
	DAMAGED("damaged"),
	ENCHANTED("enchanted"),
	RENAMED("renamed"),
	STACKABLE("stackable"),
	FOOD("food"),
	FUEL("fuel"),
	SMELTABLE("smeltable"),
	BLOCK("block"),
	HAS_CONTENTS("has_contents");

	public static final int COUNT = values().length;
	private static final FilterCondition[] VALUES = values();

	private final String id;

	FilterCondition(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public int bit() {
		return 1 << ordinal();
	}

	public String nameKey() {
		return "tooltip.obscuras_storage.condition." + id;
	}

	public String descKey() {
		return "tooltip.obscuras_storage.condition." + id + ".desc";
	}

	public static FilterCondition byIndex(int i) {
		return VALUES[i];
	}
}
