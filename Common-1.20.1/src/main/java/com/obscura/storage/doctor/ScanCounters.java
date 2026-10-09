package com.obscura.storage.doctor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * What the last scan of an Inventory Connector left out while it merged its inventories, for the
 * Network Doctor: plain counters filled as a side effect of the scan (no extra work), reset by each scan.
 */
public final class ScanCounters {
	/** Inventories left out because another member already covers the same storage (the other half of a double chest). */
	public int sameStorage;
	/** Inventories left out because the very same storage was offered a second time. */
	public int reachedTwice;
	/** Members that lead back into a storage network (a cable connector facing a connector or interface), left out. */
	public int network;
	/** Inventories left out because the network reached doctorMaxInventories. */
	public int overCap;
	/** Positions of the over cap inventories, at most {@link DoctorScan#MAX_SKIPPED}. */
	public final List<BlockPos> overCapPositions = new ArrayList<>();

	public void reset() {
		sameStorage = 0;
		reachedTwice = 0;
		network = 0;
		overCap = 0;
		overCapPositions.clear();
	}

	public void overCap(BlockPos pos) {
		overCap++;
		if (pos != null && overCapPositions.size() < DoctorScan.MAX_SKIPPED)overCapPositions.add(pos.immutable());
	}

	public int duplicates() {
		return sameStorage + reachedTwice + network;
	}
}
