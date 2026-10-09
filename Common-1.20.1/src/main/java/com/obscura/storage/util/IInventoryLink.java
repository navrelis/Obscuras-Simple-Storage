package com.obscura.storage.util;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import com.obscura.storage.tile.InventoryConnectorBlockEntity;

/**
 * An Inventory Link end. Every end both provides the inventories of its own network to the other ends of its channel
 * and accesses theirs.
 */
public interface IInventoryLink {
	/** Whether the link end at {@code pos} (with beacon level {@code lvl}) in {@code world} can reach this end. */
	boolean isAccessibleFrom(ServerLevel world, BlockPos pos, int lvl);
	UUID getChannel();
	/** The network (Inventory Connector) this end is part of, null when it is not connected to one. */
	@Nullable InventoryConnectorBlockEntity getLinkedNetwork();
}
