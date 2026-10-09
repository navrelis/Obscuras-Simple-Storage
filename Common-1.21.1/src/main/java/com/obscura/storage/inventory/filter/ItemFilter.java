package com.obscura.storage.inventory.filter;

import net.minecraft.world.item.ItemStack;

import com.obscura.storage.inventory.StoredItemStack;

public interface ItemFilter extends ItemPredicate {

	default boolean configMatch(ItemStack stack) {
		return true;
	}

	public static final ItemFilter TRUE = new ItemFilter() {

		@Override
		public boolean test(StoredItemStack stack) {
			return true;
		}

		@Override
		public long stateVersion() {
			// Never changes its answer
			return 0L;
		}
	};
}
