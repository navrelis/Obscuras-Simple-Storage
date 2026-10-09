package com.obscura.storage.util;

import net.minecraft.world.level.block.Block;

import com.obscura.storage.Config;
import com.obscura.storage.StorageTags;

public class InventoryConnectorConfigUtil {

	public static boolean canConnect(Block block) {
		if (block.defaultBlockState().is(StorageTags.INV_CONNECTOR_SKIP))
			return false;
		if (Config.get().getBlockedBlocks().contains(block))
			return false;
		if (!Config.get().getBlockedMods().isEmpty()) {
			var modid = block.builtInRegistryHolder().key().location().getNamespace();
			if (Config.get().getBlockedMods().contains(modid))
				return false;
		}
		return true;
	}
}
