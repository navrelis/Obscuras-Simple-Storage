package com.obscura.storage.item;

import java.util.function.BooleanSupplier;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.inventory.filter.ItemFilter;
import com.obscura.storage.util.BlockFaceReference;

public interface IItemFilter {
	ItemFilter createFilter(BlockFaceReference from, ItemStack stack);
	void openGui(ItemStack is, Player player, BooleanSupplier isValid, Runnable refresh);
}
