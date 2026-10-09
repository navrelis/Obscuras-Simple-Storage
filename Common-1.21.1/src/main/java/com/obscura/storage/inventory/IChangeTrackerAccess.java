package com.obscura.storage.inventory;

import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;

public interface IChangeTrackerAccess {
	IInventoryChangeTracker tracker();
}
