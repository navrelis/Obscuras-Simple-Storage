package com.obscura.storage.inventory.filter;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.util.AbstractSimpleItemFilter;

public class SimpleItemFilter extends AbstractSimpleItemFilter implements ItemFilter {
	/**
	 * Built on the server thread only (constructor/updateState) and published as an immutable set:
	 * test() runs concurrently from the off-thread tracker processing and must never build it.
	 */
	private volatile Set<Item> compiledFilter;
	/** Set by the filter container's listener, so updateState() only recompiles after an edit. */
	private volatile boolean dirty;
	private volatile long stateVersion;

	public SimpleItemFilter(ItemStack stack) {
		super(stack);
		compile();
		filter.addListener(c -> dirty = true);
	}

	private void compile() {
		Set<Item> set = new HashSet<>();
		for (int i = 0; i < filter.getContainerSize(); i++) {
			ItemStack is = filter.getItem(i);
			if (!is.isEmpty()) {
				set.add(is.getItem());
			}
		}
		compiledFilter = Set.copyOf(set);
		stateVersion = ItemPredicate.nextStateVersion();
	}

	@Override
	public void updateState() {
		// Called every tick by every filtered inventory: only recompile when the contents changed.
		if (dirty) {
			dirty = false;
			compile();
		}
	}

	@Override
	protected void onConfigChanged() {
		stateVersion = ItemPredicate.nextStateVersion();
	}

	@Override
	public long stateVersion() {
		return stateVersion;
	}

	@Override
	public boolean test(StoredItemStack stack) {
		if (matchNBT)return testNbt(stack) == allowList;
		else return test0(stack) == allowList;
	}

	@Override
	public boolean test(ItemStack stack) {
		if (matchNBT) return testNbtStack(stack) == allowList;
		else return test0Stack(stack) == allowList;
	}

	private boolean test0(StoredItemStack stack) {
		return compiledFilter.contains(stack.getStack().getItem());
	}

	private boolean test0Stack(ItemStack stack) {
		return compiledFilter.contains(stack.getItem());
	}

	private boolean testNbt(StoredItemStack stack) {
		for(int i = 0;i<filter.getContainerSize();i++) {
			ItemStack is = filter.getItem(i);
			if(is.isEmpty())continue;
			if(ItemStack.isSameItemSameComponents(stack.getStack(), is))return true;
		}
		return false;
	}

	private boolean testNbtStack(ItemStack stack) {
		for(int i = 0;i<filter.getContainerSize();i++) {
			ItemStack is = filter.getItem(i);
			if(is.isEmpty())continue;
			if(ItemStack.isSameItemSameComponents(stack, is))return true;
		}
		return false;
	}

	@Override
	public boolean configMatch(ItemStack stack) {
		return stack == this.stack;
	}
}
