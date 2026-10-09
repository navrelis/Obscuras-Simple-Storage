package com.obscura.storage.doctor;

import java.util.List;

/**
 * What the Network Doctor knows about one network, as plain values: shared by the chat output and
 * the export (which is unit tested without a world).
 *
 * @param dimension dimension id, e.g. "minecraft:overworld"
 * @param x connector position
 * @param inventories inventories in the network (after duplicates were removed and the cap applied)
 * @param slots slots of those inventories
 * @param slotsFree empty slots among them
 * @param windowSeconds how much time the timing values cover (up to 30 s)
 * @param details the full analysis, null for the quick summary used by {@code doctor top}
 */
public record NetworkReport(String dimension, int x, int y, int z, int inventories, int slots, int slotsFree,
		double avgMs, double peakMs, double rescansPerMin, double rebuildsPerMin, double windowSeconds,
		int playersWatching, double packetsPerSec, double bytesPerSec, Details details) {

	public int slotsUsed() {
		return Math.max(0, slots - slotsFree);
	}

	/**
	 * @param dupSameStorage inventories dropped because another member already covers the same storage
	 *        (both halves of a double chest, two faces of one block)
	 * @param dupReachedTwice inventories dropped because the same block was reached a second time
	 *        (through another connector, a proxy or a filter)
	 * @param dupNetwork members leading into a storage network (a proxy or interface next to a network block)
	 * @param cap the inventory cap in effect, 0 = off
	 * @param overCap inventories left out because of the cap
	 * @param skipped blocks next to the network that are not part of it, with the reason
	 * @param hints reason codes of the hint lines, see {@link Hints}
	 * @param labelled members labelled by glow item frames (Frame Labels)
	 * @param filterBlocks Ejectors, Disposals and Exporters working on the network, counted together
	 */
	public record Details(int dupSameStorage, int dupReachedTwice, int dupNetwork, int uniqueItems,
			int cables, int cableConnectors, int interfaces, int proxies, int links, int linkedConnectors,
			int terminals, int craftingTerminals, int hoppers, int emitters, int cap, int overCap,
			boolean cablesTruncated, List<Skipped> skipped, List<String> hints, int labelled, int filterBlocks) {

		public int duplicates() {
			return dupSameStorage + dupReachedTwice + dupNetwork;
		}
	}

	/** A block next to the network that is not part of it. {@code reason} is one of the {@link Reasons} codes. */
	public record Skipped(String dimension, int x, int y, int z, String reason) {}

	/** Why a block is skipped. The codes are part of the export format and name the lang keys. */
	public static final class Reasons {
		/** In the block tag #obscuras_storage:inventory_connector_skip. */
		public static final String SKIP_TAG = "skip_tag";
		/** Listed in the blockedBlocks config. */
		public static final String CONFIG_BLOCK = "config_block";
		/** Its mod is listed in the blockedMods config. */
		public static final String CONFIG_MOD = "config_mod";
		/** Excluded with the Inventory Configurator. */
		public static final String EXCLUDED = "excluded";
		/** Has a block entity but no item inventory on the touching side. */
		public static final String NOT_INVENTORY = "not_inventory";
		/** Left out because the network reached doctorMaxInventories. */
		public static final String OVER_CAP = "over_cap";
		/** In a chunk that is not loaded. */
		public static final String UNLOADED = "unloaded";
		/** An inventory just outside the Inventory Connector's scan range. */
		public static final String OUT_OF_RANGE = "out_of_range";

		private Reasons() {}
	}

	/** Hint codes of {@code doctor at}, each names a lang key. */
	public static final class Hints {
		public static final String RESCANS = "rescans";
		public static final String REBUILDS = "rebuilds";
		public static final String OVER_CAP = "over_cap";
		public static final String UNLOADED = "unloaded";
		public static final String SYNC = "sync";
		public static final String BLACKLISTED = "blacklisted";
		public static final String CABLES_TRUNCATED = "cables_truncated";
		public static final String SLOW = "slow";

		/** Rescans per minute above which the rescan hint shows. */
		public static final double RESCANS_PER_MIN = 10;
		public static final double REBUILDS_PER_MIN = 6;
		public static final double PACKETS_PER_SEC = 40;
		/** Average ms per tick at or above which a network counts as slow for the hint (red in the output). */
		public static final double SLOW_MS = 2;

		private Hints() {}
	}
}
