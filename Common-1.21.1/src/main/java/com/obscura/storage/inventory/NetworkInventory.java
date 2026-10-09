package com.obscura.storage.inventory;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.obscura.storage.block.entity.IInventoryConnector;
import com.obscura.storage.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.obscura.storage.util.IValidInfo;

public class NetworkInventory {
	private BlockInventoryAccess block = new BlockInventoryAccess();
	private WeakReference<IInventoryConnector> networkAccess;
	private long lastUpdate;

	public void onLoad(Level level, BlockPos worldPosition, Direction side, IValidInfo v) {
		block.onLoad(level, worldPosition, side, v);
	}

	public void scanNetwork(Level level, BlockPos worldPosition) {
		networkAccess = null;
		Collection<BlockPos> netBlocks = InventoryCableNetwork.getNetwork(level).getNetworkNodes(worldPosition);

		IInventoryConnector best = null;
		int bestCount = -1;
		for (BlockPos p : netBlocks) {
			if (!level.isLoaded(p))continue;

			BlockEntity be = level.getBlockEntity(p);
			if (be instanceof IInventoryConnector te && te.hasConnectedInventories()) {
				int count = te.getConnectedInventories().size();
				if (count > bestCount) {
					best = te;
					bestCount = count;
				}
			}
		}
		if (best != null) {
			networkAccess = new WeakReference<>(best);
		}
	}

	public IInventoryAccess getAccess(Level level, BlockPos worldPosition) {
		if (block.exists()) {
			@SuppressWarnings("deprecation")
			Object storage = block.get();
			if (storage instanceof PlatformItemHandler h) {
				Set<IProxy> dejaVu = Collections.newSetFromMap(new IdentityHashMap<>());
				return h.getRootHandler(dejaVu);
			}
			return block;
		}
		if (level.getGameTime() - lastUpdate > 2) {
			scanNetwork(level, worldPosition);
			lastUpdate = level.getGameTime();
		}
		if (networkAccess != null) {
			IInventoryConnector net = networkAccess.get();
			if (net != null && net.hasConnectedInventories())return net.getMergedHandler();
		}
		return block;
	}

	public boolean isNetwork() {
		return !block.exists() && networkAccess != null;
	}

	public boolean isValid() {
		return block.exists() || networkAccess != null;
	}
}
