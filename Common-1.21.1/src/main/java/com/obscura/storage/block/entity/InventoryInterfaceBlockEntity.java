package com.obscura.storage.block.entity;

import java.lang.ref.WeakReference;
import java.util.Collection;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Content;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IInventoryAccess.IInventory;
import com.obscura.storage.inventory.IInventoryConnectorReference;
import com.obscura.storage.inventory.InventoryCableNetwork;
import com.obscura.storage.inventory.MultiInventoryAccess;
import com.obscura.storage.inventory.PlatformInventoryAccess;
import com.obscura.storage.platform.PlatformBlockEntity;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.util.TickerUtil.TickableServer;

public class InventoryInterfaceBlockEntity extends PlatformBlockEntity implements TickableServer, IInventory, IInventoryConnectorReference, ComparatorOutput.Source {
	private WeakReference<IInventoryConnector> networkAccess;
	private WeakReference<IInventoryConnector> connectorAccess;
	private boolean hasConnector;
	private final ComparatorOutput comparator = new ComparatorOutput();

	public InventoryInterfaceBlockEntity(BlockPos p_155229_, BlockState p_155230_) {
		super(Content.invInterfaceBE.get(), p_155229_, p_155230_);
	}

	@Override
	public void updateServer() {
		long time = level.getGameTime();
		if(time % 5 == (worldPosition.hashCode() & 0x7FFFFFFF) % 5)comparator.tick(level, worldPosition, this::getComparatorSignal);
		if(time % 20 == (worldPosition.hashCode() & 0x7FFFFFFF) % 20) {
			if (hasConnector) {
				hasConnector = false;
			} else {
				connectorAccess = null;
			}
			if (connectorAccess != null) {
				networkAccess = connectorAccess;
			} else {
				networkAccess = null;
				Collection<BlockPos> netBlocks = InventoryCableNetwork.getNetwork(level).getNetworkNodes(worldPosition);
				for (BlockPos p : netBlocks) {
					if (!level.isLoaded(p))continue;

					BlockEntity be = level.getBlockEntity(p);
					if (be instanceof IInventoryConnector te && te.hasConnectedInventories()) {
						networkAccess = new WeakReference<>(te);
						break;
					}
				}
			}
		}
	}

	@Override
	public IInventoryAccess getInventoryAccess() {
		if (networkAccess != null) {
			IInventoryConnector net = networkAccess.get();
			if (net != null && net.hasConnectedInventories())return net.getMergedHandler();
		}
		return PlatformInventoryAccess.EMPTY;
	}

	/** How full the network behind the interface is, 0 to 15 (0 without a network). */
	@Override
	public int getComparatorSignal() {
		return level != null && getInventoryAccess() instanceof MultiInventoryAccess m ? m.getFillSignal(level) : 0;
	}

	@Override
	public IInventoryConnector getConnectorRef() {
		if (networkAccess != null) {
			IInventoryConnector net = networkAccess.get();
			if (net != null && net.hasConnectedInventories())return net;
		}
		return null;
	}

	public void setConnectorAccess(IInventoryConnector connectorAccess) {
		this.connectorAccess = new WeakReference<>(connectorAccess);
		this.hasConnector = true;
	}
}
