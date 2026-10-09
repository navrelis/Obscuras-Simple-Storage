package com.obscura.storage.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Config;
import com.obscura.storage.StorageTags;

public class InventoryConnectorConfigUtil {
	public static boolean canConnect(BlockState blockState) {
		if (blockState.is(StorageTags.INV_CONNECTOR_SKIP))return false;
		var block = blockState.getBlock();
		if (Config.get().getBlockedBlocks().contains(block))
			return false;
		if (!Config.get().getBlockedMods().isEmpty()) {
			ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
			var modid = blockId.getNamespace();
			if (Config.get().getBlockedMods().contains(modid))
				return false;
		}
		return true;
	}
}
