package com.obscura.storage.inventory;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import com.obscura.storage.block.entity.IInventoryConnector;

public interface IInventoryLink {
	IInventoryConnector getConnector();
	boolean isAccessibleFrom(ServerLevel world, BlockPos blockPos, int level);
	UUID getChannel();
}
