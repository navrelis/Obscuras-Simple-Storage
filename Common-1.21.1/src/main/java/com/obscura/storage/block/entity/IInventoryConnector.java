package com.obscura.storage.block.entity;

import java.util.Collection;

import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.inventory.IInventoryAccess;

public interface IInventoryConnector {
	IInventoryAccess getMergedHandler();
	Collection<IInventoryAccess> getConnectedInventories();
	Collection<IInventoryConnector> getConnectedConnectors();
	boolean hasConnectedInventories();

	/** The Network Doctor timing window this connector's work is booked to, null when none. */
	default NetworkStats doctorStats() {
		return null;
	}
}
