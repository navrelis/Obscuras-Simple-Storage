package com.obscura.storage.util;

import java.util.function.Predicate;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.world.item.ItemStack;

public interface ItemPredicate extends Predicate<ItemVariant> {
	@Override
	boolean test(ItemVariant stack);

	default boolean configMatch(ItemStack stack) {
		return true;
	}

	/** {@link #test} for an item stack (Frame Labels test framed filter items in loader neutral code). */
	default boolean testStack(ItemStack stack) {
		return !stack.isEmpty() && test(ItemVariant.of(stack));
	}

	/** Matches the item and NBT of {@code ref} (a plain item in a block filter slot). Used by the shared BlockFilter. */
	static ItemPredicate sameItemSameTags(ItemStack ref) {
		return resource -> resource.matches(ref);
	}
}
