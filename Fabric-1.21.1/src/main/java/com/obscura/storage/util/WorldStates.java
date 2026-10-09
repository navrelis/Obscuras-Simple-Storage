package com.obscura.storage.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.minecraft.world.level.Level;

import com.google.common.collect.MapMaker;

import com.obscura.storage.inventory.IChangeTrackerAccess;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.InventoryCableNetwork;
import com.obscura.storage.inventory.InventoryChangeTracker;

public class WorldStates {
	public static Map<Object, IInventoryChangeTracker> trackers = new MapMaker().weakKeys().weakValues().makeMap();
	public static Map<Level, InventoryCableNetwork> cableNetworks = new ConcurrentHashMap<>();

	public static void clearWorldStates() {
		trackers.clear();
		cableNetworks.clear();
	}

	/**
	 * Identity of the physical inventory behind a Storage wrapper.
	 *
	 * <p>Fabric hands out a fresh wrapper object on every lookup for several common block types, so
	 * the Storage object itself is useless as a map key - it would create a new tracker with an
	 * empty {@code lastItems} every tick and report "everything changed" forever:
	 * <ul>
	 * <li>a vanilla double chest yields a new {@code CombinedSlottedStorage} whose parts come from
	 * the per-Container cached {@code InventoryStorage.of(...)};</li>
	 * <li>any {@code WorldlyContainer} queried with a direction (furnace, hopper, dropper,
	 * dispenser, brewing stand) yields a new {@code SidedInventoryStorageImpl}, a CombinedStorage
	 * SUBCLASS whose parts are freshly allocated {@code SidedInventorySlotWrapper}s too.</li>
	 * </ul>
	 *
	 * <p>So unwrapping to {@code parts.get(0)} is not enough on its own - that object churns as
	 * well for the sided case. What IS stable in both cases is the part's
	 * {@link StorageView#getUnderlyingView()}, which Fabric documents as the canonical identity of
	 * a storage view ("two storage views with the same underlying view" are the same storage). For
	 * a double chest that resolves to the same underlying view from either half, which additionally
	 * lets the two halves deduplicate against each other.
	 *
	 * <p>Note {@code BlockInventoryAccess} no longer depends on this - it owns its tracker. This
	 * remains for other callers and for {@link #trackers}.
	 */
	public static Object identityOf(Storage<ItemVariant> h) {
		Object o = h;
		// instanceof, not an exact class test: SidedInventoryStorageImpl is a package-private
		// CombinedStorage subclass, and missing it was what left sided inventories churning.
		// Bounded loop: nesting is one level in practice, the bound only guards against a
		// pathological third-party storage that wraps itself.
		for (int i = 0; i < 8 && o instanceof CombinedStorage<?, ?> cs; i++) {
			var parts = cs.parts;
			if (parts == null || parts.isEmpty())break;
			o = parts.get(0);
		}
		// The slot views themselves may be per-lookup wrappers (sided inventories); their underlying
		// view is the stable per-Container object.
		if (o instanceof StorageView<?> sv)o = sv.getUnderlyingView();
		return o;
	}

	public static IInventoryChangeTracker getTracker(Storage<ItemVariant> h) {
		if (h instanceof IChangeTrackerAccess a)return a.tracker();
		Object key = identityOf(h);
		var ct = trackers.computeIfAbsent(key, k -> new InventoryChangeTracker(h));
		if (ct instanceof InventoryChangeTracker c)
			c.refresh(h);
		return ct;
	}
}
