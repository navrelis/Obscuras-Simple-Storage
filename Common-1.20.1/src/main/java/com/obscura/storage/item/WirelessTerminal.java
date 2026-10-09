package com.obscura.storage.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public interface WirelessTerminal {
	int getRange(Player pl, ItemStack stack);
	void open(Player sender, ItemStack t);
	boolean canOpen(ItemStack t);

	/**
	 * Server: whether {@code player} reaches the (loaded) terminal at {@code pos} in {@code termLevel}
	 * with this item. Used through {@link BoundTerminal#resolve}.
	 */
	boolean isInRange(Player player, ItemStack stack, Level termLevel, BlockPos pos);
}
