package com.obscura.storage.util;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;

public class TickerUtil {

	public static <T extends BlockEntity> BlockEntityTicker<T> createTicker(Level world, boolean client, boolean server) {
		return world.isClientSide ? (client ? (a, b, c, tile) -> ((TickableClient)tile).updateClient() : null) : (server ? (a, b, c, tile) -> ((TickableServer)tile).updateServer() : null);
	}

	public static interface TickableClient {
		public void updateClient();
	}

	/**
	 * Same as the Fabric twin. On NeoForge every block entity already gets {@code onLoad()} (called at
	 * the start of the first level tick after it was added), so nothing has to dispatch this.
	 */
	public static interface OnLoadListener {
		public void onLoad();
	}

	public static interface TickableServer {
		public void updateServer();
	}
}
