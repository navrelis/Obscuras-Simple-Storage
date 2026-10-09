package com.obscura.storage.item;

import java.util.function.BooleanSupplier;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.util.BlockFace;
import com.obscura.storage.util.ItemPredicate;

public interface IItemFilter {
	ItemPredicate createFilter(BlockFace from, ItemStack stack);

	default void openGui(ItemStack stack, Player player, BooleanSupplier isValid, Runnable refresh) {
	}
}
