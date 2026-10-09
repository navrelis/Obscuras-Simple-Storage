package com.obscura.storage.util;

import java.util.List;

import net.minecraft.world.item.ItemStack;

import com.obscura.storage.item.IItemFilter;

public class ItemFilterHandler implements ItemPredicate {
	private List<ItemStack> filter;
	private ItemPredicate[] predicates;
	private BlockFace face;

	public ItemFilterHandler(List<ItemStack> filter, BlockFace face) {
		this.filter = filter;
		this.face = face;
		this.predicates = new ItemPredicate[0];
	}

	@Override
	public boolean test(ItemStack resource) {
		if(predicates.length != filter.size()) {
			predicates = new ItemPredicate[filter.size()];
		}
		for (int i = 0; i < filter.size(); i++) {
			ItemStack is = filter.get(i);
			if(is.isEmpty())continue;
			if(is.getItem() instanceof IItemFilter f) {
				if (predicates[i] == null || !predicates[i].configMatch(is)) {
					predicates[i] = f.createFilter(face, is);
				}
				if(predicates[i].test(resource))return true;
				else continue;
			} else if(predicates[i] != null) {
				predicates[i] = null;
			}
			if(ItemStack.isSameItemSameTags(resource, is))return true;
		}
		return false;
	}
}
