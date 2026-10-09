package com.obscura.storage.util;

import java.util.Collection;

import net.minecraft.world.item.ItemStack;

import net.minecraftforge.items.IItemHandler;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

/**
 * Item totals of a storage, keyed by {@link ItemKey}, with a version counter and a short change log
 * (Forge twin of the Fabric StorageSnapshot).
 *
 * <p>Item handlers have no change notification, so the contents are re-read on a fixed short
 * interval, without copying stacks, and only real differences bump {@link #getVersion()}. Everything
 * downstream (terminal sync, emitters) then works on the differences alone. Server thread only.
 */
public class StorageSnapshot {
	private static final int LOG_MIN = 1024;
	private Object2LongOpenHashMap<ItemKey> counts = new Object2LongOpenHashMap<>();
	private Object2LongOpenHashMap<ItemKey> scratch = new Object2LongOpenHashMap<>();
	/** Owning keys of the item types seen, looked up with non-copying probe keys. */
	private final Object2ObjectOpenHashMap<ItemKey, ItemKey> interned = new Object2ObjectOpenHashMap<>();
	private final ReferenceOpenHashSet<ItemStack> seen = new ReferenceOpenHashSet<>();
	private final ObjectArrayList<ItemKey> logVariants = new ObjectArrayList<>();
	private final LongArrayList logVersions = new LongArrayList();
	private long version;
	/** Versions up to this one may have been dropped from the log. */
	private long logBase;
	private long lastRefresh;
	private boolean refreshed;
	private int slotCount, freeSlotCount;

	/** Slots seen by the last {@link #refresh}. */
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

	/** Total amount of {@code key}, 0 when absent. */
	public long getCount(ItemKey key) {
		return counts.getLong(key);
	}

	public boolean contains(ItemKey key) {
		return counts.containsKey(key);
	}

	/** Live view of the totals, do not modify. */
	public Object2LongMap<ItemKey> counts() {
		return counts;
	}

	private ItemKey intern(ItemStack stack) {
		ItemKey probe = ItemKey.probe(stack);
		if(probe.getTag() == null)return probe;
		ItemKey k = interned.get(probe);
		if(k == null) {
			k = probe.own();
			interned.put(k, k);
		}
		return k;
	}

	/**
	 * Re-reads {@code storage} unless the last read is younger than {@code maxAge} ticks.
	 *
	 * @return whether the contents changed
	 */
	public boolean refresh(IItemHandler storage, long gameTime, int maxAge) {
		if (refreshed && gameTime >= lastRefresh && gameTime - lastRefresh < Math.max(1, maxAge))return false;
		refreshed = true;
		lastRefresh = gameTime;
		scratch.clear();
		seen.clear();
		int[] slots = new int[2];
		if (storage != null) {
			if (storage instanceof MergedStorage m)m.refresh();
			ItemHandlers.forEach(storage, (stack, amount) -> {
				if (!stack.isEmpty() && !seen.add(stack))return true;// The same slot reachable twice (e.g. through two wrappers)
				slots[0]++;
				if (amount <= 0) {
					slots[1]++;
					return true;
				}
				scratch.addTo(intern(stack), amount);
				return true;
			});
			seen.clear();
		}
		slotCount = slots[0];
		freeSlotCount = slots[1];

		long next = version + 1;
		boolean changed = false;
		for (Object2LongMap.Entry<ItemKey> e : Object2LongMaps.fastIterable(scratch)) {
			if (counts.getLong(e.getKey()) != e.getLongValue()) {
				changed = true;
				log(next, e.getKey());
			}
		}
		if (counts.size() != scratch.size() || changed) {
			for (ItemKey v : counts.keySet()) {
				if (!scratch.containsKey(v)) {
					changed = true;
					log(next, v);
				}
			}
		}

		Object2LongOpenHashMap<ItemKey> tmp = counts;
		counts = scratch;
		scratch = tmp;
		scratch.clear();
		if (interned.size() > counts.size() * 2 + 256) {
			interned.clear();
			for (ItemKey k : counts.keySet()) {
				if (k.getTag() != null)interned.put(k, k);
			}
		}
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

	private void log(long v, ItemKey key) {
		logVariants.add(key);
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

	/** Adds every key that changed after version {@code since} (only valid when {@link #canDiffSince}). */
	public void collectChangedSince(long since, Collection<ItemKey> out) {
		int i = logVersions.size();
		while (i > 0 && logVersions.getLong(i - 1) > since)i--;
		for (; i < logVariants.size(); i++) {
			out.add(logVariants.get(i));
		}
	}
}
