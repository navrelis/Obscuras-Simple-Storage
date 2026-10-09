package com.obscura.storage.util;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.world.item.ItemStack;

import net.minecraftforge.items.IItemHandler;

import com.obscura.storage.Content;

public class PolyFilter implements ItemPredicate {
	private BlockFace face;
	private Set<StoredItemStack> filter;
	private long lastCheck;

	public PolyFilter(BlockFace face) {
		this.face = face;
		this.filter = new HashSet<>();
	}

	private void updateFilter() {
		long time = face.level().getGameTime();
		if(time - lastCheck >= 10) {
			lastCheck = time;
			filter.clear();
			IItemHandler st = ItemHandlers.find(face.level(), face.pos(), face.from());
			if(st != null) {
				ItemHandlers.forEach(st, (s, c) -> {
					if(!s.isEmpty())filter.add(new StoredItemStack(s));
					return true;
				});
			}
		}
	}

	@Override
	public boolean test(ItemStack stack) {
		updateFilter();
		for (StoredItemStack f : filter) {
			if(ItemStack.isSameItemSameTags(stack, f.getStack()))return true;
		}
		return false;
	}

	@Override
	public boolean configMatch(ItemStack stack) {
		return stack.getItem() == Content.polyItemFliter.get();
	}
}
