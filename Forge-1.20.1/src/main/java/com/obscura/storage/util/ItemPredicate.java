package com.obscura.storage.util;

import java.util.function.Predicate;

import net.minecraft.world.item.ItemStack;

/** Item filter test. The Forge twin tests item stacks (the Fabric one tests item variants); the count is ignored. */
public interface ItemPredicate extends Predicate<ItemStack> {
	@Override
	boolean test(ItemStack stack);

	default boolean configMatch(ItemStack stack) {
		return true;
	}

	/** {@link #test} for an item stack (same on Forge; Frame Labels test framed filter items in loader neutral code). */
	default boolean testStack(ItemStack stack) {
		return test(stack);
	}

	/** Matches the item and NBT of {@code ref} (a plain item in a block filter slot). Used by the shared BlockFilter. */
	static ItemPredicate sameItemSameTags(ItemStack ref) {
		return stack -> ItemStack.isSameItemSameTags(stack, ref);
	}
}
