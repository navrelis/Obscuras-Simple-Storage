package com.obscura.storage.platform;

import java.util.function.Predicate;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import top.theillusivec4.curios.api.CuriosApi;

/** Curios access, only loaded when Curios is installed (the Forge counterpart of the Fabric Trinkets lookup). */
final class CuriosCompat {
	private CuriosCompat() {
	}

	static ItemStack findFirst(Player player, Predicate<ItemStack> is) {
		return CuriosApi.getCuriosInventory(player)
				.resolve()
				.flatMap(inv -> inv.findFirstCurio(is))
				.map(r -> r.stack())
				.orElse(ItemStack.EMPTY);
	}
}
