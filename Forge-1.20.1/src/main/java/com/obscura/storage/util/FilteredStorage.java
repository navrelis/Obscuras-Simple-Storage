package com.obscura.storage.util;

import net.minecraft.world.item.ItemStack;

import net.minecraftforge.items.IItemHandler;

/**
 * A block filter's view of an inventory: only items passing the filter are shown, inserted and
 * extracted; with keep-last one item of every stack stays in its slot.
 */
public class FilteredStorage implements IItemHandler, IProxy, BlockFilter.Prioritised {
	private final IItemHandler backing;
	private final ItemPredicate filter;
	private final boolean keepLastInSlot;
	private Priority priority = Priority.NORMAL;

	public FilteredStorage(IItemHandler backing, ItemPredicate filter, boolean keepLastInSlot) {
		this.backing = backing;
		this.filter = filter;
		this.keepLastInSlot = keepLastInSlot;
	}

	/** The filter this view applies (the Tidy button reads its rules). */
	public ItemPredicate getFilter() {
		return filter;
	}

	public boolean isKeepLast() {
		return keepLastInSlot;
	}

	/** The priority of the block filter that made this view, see {@link BlockFilter.Prioritised}. */
	public Priority getFilterPriority() {
		return priority;
	}

	@Override
	public void setFilterPriority(Priority priority) {
		this.priority = priority;
	}

	public IItemHandler getBacking() {
		return backing;
	}

	/** Amount of {@code inSlot} this view shows: 0 when filtered out, one less with keep-last. */
	public int visibleCount(ItemStack inSlot) {
		if(inSlot.isEmpty() || !filter.test(inSlot))return 0;
		return keepLastInSlot ? inSlot.getCount() - 1 : inSlot.getCount();
	}

	@Override
	public IItemHandler get() {
		return IProxy.resolve(backing);
	}

	@Override
	public int getSlots() {
		return backing.getSlots();
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		ItemStack inSlot = backing.getStackInSlot(slot);
		int c = visibleCount(inSlot);
		if(c <= 0)return ItemStack.EMPTY;
		if(c == inSlot.getCount())return inSlot;
		return inSlot.copyWithCount(c);
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		if(stack.isEmpty() || !filter.test(stack))return stack;
		return backing.insertItem(slot, stack, simulate);
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		ItemStack inSlot = backing.getStackInSlot(slot);
		int c = visibleCount(inSlot);
		if(c <= 0)return ItemStack.EMPTY;
		return backing.extractItem(slot, Math.min(amount, c), simulate);
	}

	@Override
	public int getSlotLimit(int slot) {
		return backing.getSlotLimit(slot);
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		return filter.test(stack) && backing.isItemValid(slot, stack);
	}
}
