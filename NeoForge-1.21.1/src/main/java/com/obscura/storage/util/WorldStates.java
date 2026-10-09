package com.obscura.storage.util;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

import com.obscura.storage.inventory.IChangeTrackerAccess;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.InventoryCableNetwork;
import com.obscura.storage.inventory.InventoryChangeTracker;

public class WorldStates {
	// Weak keys compared with equals(): two wrappers of one container share a tracker. Synchronized,
	// networks register themselves from wherever they are created.
	public static Map<IItemHandler, IInventoryChangeTracker> trackers = Collections.synchronizedMap(new WeakHashMap<>());
	public static Map<Level, InventoryCableNetwork> cableNetworks = new ConcurrentHashMap<>();

	/** Called when the server stops: nothing of a closed world may survive into the next one. */
	public static void clearWorldStates() {
		trackers.clear();
		cableNetworks.clear();
	}

	public static IInventoryChangeTracker getTracker(IItemHandler h) {
		if (h instanceof IChangeTrackerAccess a)return a.tracker();
		return trackers.computeIfAbsent(h, InventoryChangeTracker::new);
	}
}
