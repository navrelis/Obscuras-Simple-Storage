package com.obscura.storage.util;

/**
 * The search options of a terminal and how they are stored in the terminal's settings int (block entity "sort" tag,
 * synced to the menu).
 *
 * <p>Search type bits: 1 auto focus, 2 keep the search, 4 sync with the recipe viewer, 8 smart item search OFF
 * (off is the set bit so terminals saved before the option existed have it on). In the settings int the first three
 * bits sit at 5..7 like before, the smart search bit at 11 (bit 8 is the crafting terminal's pull option, 9 ghost
 * items, 10 tall mode, 12 the third sort type bit).
 */
public final class SearchModes {
	public static final int AUTO = 1;
	public static final int KEEP = 2;
	public static final int SYNC = 4;
	public static final int NO_SMART = 8;
	public static final int ALL = AUTO | KEEP | SYNC | NO_SMART;

	private SearchModes() {
	}

	/** The search type stored in the terminal settings int {@code data}. */
	public static int unpack(int data) {
		return ((data >> 5) & 0b111) | (((data >> 11) & 1) << 3);
	}

	/** The settings int bits for {@code searchType}, to be or-ed with the other settings. */
	public static int pack(int searchType) {
		return ((searchType & 0b111) << 5) | (((searchType >> 3) & 1) << 11);
	}

	public static boolean isSmartSearch(int searchType) {
		return (searchType & NO_SMART) == 0;
	}

	/** Translation key of the search button tooltip for {@code searchType}. */
	public static String tooltipKey(int searchType) {
		StringBuilder sb = new StringBuilder("tooltip.obscuras_storage.search");
		if (searchType < 0)return sb.toString();
		if((searchType & AUTO) != 0)sb.append("_auto");
		if((searchType & KEEP) != 0)sb.append("_keep");
		if((searchType & SYNC) != 0)sb.append("_sync");
		return sb.toString();
	}
}
