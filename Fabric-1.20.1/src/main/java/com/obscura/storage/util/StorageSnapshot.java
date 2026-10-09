package com.obscura.storage.util;

import java.util.Collection;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

/**
 * Item totals of a storage, keyed by {@link ItemVariant}, with a version counter and a short change log.
 *
 * <p>Fabric's {@code Storage#getVersion()} can't be used to skip the scan: vanilla inventories (chests,
 * barrels, furnaces...) don't override it (the default returns a new value on every call), and they change
 * without a transfer transaction whenever a player or a hopper touches them. So the contents are re-read on
 * a fixed short interval instead, without allocating item stacks, and only real differences bump
 * {@link #getVersion()}. Everything downstream (terminal sync, emitters) then works on the differences
 * alone. Server thread only.
 */
public class StorageSnapshot {
	private static final int LOG_MIN = 1024;
	private Object2LongOpenHashMap<ItemVariant> counts = new Object2LongOpenHashMap<>();
	private Object2LongOpenHashMap<ItemVariant> scratch = new Object2LongOpenHashMap<>();
	private final ReferenceOpenHashSet<Object> seen = new ReferenceOpenHashSet<>();
	private final ObjectArrayList<ItemVariant> logVariants = new ObjectArrayList<>();
	private final LongArrayList logVersions = new LongArrayList();
	private long version;
	/** Versions up to this one may have been dropped from the log. */
	private long logBase;
	private long lastRefresh;
	private boolean refreshed;
	private int slotCount, freeSlotCount;

	/** Slots seen by the last {@link #refresh} (each view counted once). */
	public int getSlotCount() {
		return slotCount;
	}

	/** Empty slots seen by the last {@link #refresh}. */
	public int getFreeSlotCount() {
		return freeSlotCount;
	}

	/** Bumps whenever the contents differ from the previous {@link #refresh}. */
	public long getVersion() {
		return version;
	}

	/** Total amount of {@code variant}, 0 when absent. */
	public long getCount(ItemVariant variant) {
		return counts.getLong(variant);
	}

	public boolean contains(ItemVariant variant) {
		return counts.containsKey(variant);
	}

	/** Live view of the totals, do not modify. */
	public Object2LongMap<ItemVariant> counts() {
		return counts;
	}

	/**
	 * Re-reads {@code storage} unless the last read is younger than {@code maxAge} ticks.
	 *
	 * @return whether the contents changed
	 */
	public boolean refresh(Storage<ItemVariant> storage, long gameTime, int maxAge) {
		if (refreshed && gameTime >= lastRefresh && gameTime - lastRefresh < Math.max(1, maxAge))return false;
		refreshed = true;
		lastRefresh = gameTime;
		scratch.clear();
		seen.clear();
		int slots = 0, free = 0;
		if (storage != null) {
			for (StorageView<ItemVariant> view : storage) {
				// The same view can be reachable twice (both halves of a double chest)
				if (!seen.add(view))continue;
				slots++;
				long amount = view.isResourceBlank() ? 0 : view.getAmount();
				if (amount <= 0) {
					free++;
					continue;
				}
				scratch.addTo(view.getResource(), amount);
			}
			seen.clear();
		}
		slotCount = slots;
		freeSlotCount = free;

		long next = version + 1;
		boolean changed = false;
		for (Object2LongMap.Entry<ItemVariant> e : Object2LongMaps.fastIterable(scratch)) {
			if (counts.getLong(e.getKey()) != e.getLongValue()) {
				changed = true;
				log(next, e.getKey());
			}
		}
		if (counts.size() != scratch.size() || changed) {
			for (ItemVariant v : counts.keySet()) {
				if (!scratch.containsKey(v)) {
					changed = true;
					log(next, v);
				}
			}
		}

		Object2LongOpenHashMap<ItemVariant> tmp = counts;
		counts = scratch;
		scratch = tmp;
		scratch.clear();
		if (changed) {
			version = next;
			trimLog();
		}
		return changed;
	}

	/** Makes the next {@link #refresh} read the storage no matter how recent the last read was. */
	public void invalidate() {
		refreshed = false;
	}

	private void log(long v, ItemVariant variant) {
		logVariants.add(variant);
		logVersions.add(v);
	}

	private void trimLog() {
		int max = Math.max(LOG_MIN, counts.size() * 2);
		int size = logVariants.size();
		if (size > max) {
			int drop = size - max / 2;
			logBase = logVersions.getLong(drop - 1);
			logVariants.removeElements(0, drop);
			logVersions.removeElements(0, drop);
		}
	}

	/** Whether {@link #collectChangedSince} is complete for a viewer that last saw {@code since}. */
	public boolean canDiffSince(long since) {
		return since >= logBase && since <= version;
	}

	/** Adds every variant that changed after version {@code since} (only valid when {@link #canDiffSince}). */
	public void collectChangedSince(long since, Collection<ItemVariant> out) {
		int i = logVersions.size();
		while (i > 0 && logVersions.getLong(i - 1) > since)i--;
		for (; i < logVariants.size(); i++) {
			out.add(logVariants.get(i));
		}
	}
}
