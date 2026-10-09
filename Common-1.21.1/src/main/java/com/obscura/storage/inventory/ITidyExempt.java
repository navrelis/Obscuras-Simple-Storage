package com.obscura.storage.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/**
 * Marks an inventory whose contents belong together and must not be rearranged by network-wide
 * sorting (the Tidy button): Tidy never moves items into or out of it by default. Ordinary network
 * transfers (terminals, hoppers, connectors) are not affected.
 *
 * <p>Implemented by the Shulker Dock's block entity and by its exposed container, so it can be
 * recognised from the block position of a network member ({@link #isExempt}) as well as from the
 * container itself.
 */
public interface ITidyExempt {

	/** Whether the block entity at {@code pos} is exempt from Tidy. */
	static boolean isExempt(BlockGetter level, BlockPos pos) {
		return level != null && pos != null && level.getBlockEntity(pos) instanceof ITidyExempt;
	}
}
