package com.obscura.storage.inventory;

import java.util.Set;
import java.util.stream.Stream;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

import com.obscura.storage.inventory.filter.IFilter;
import com.obscura.storage.inventory.filter.ItemPredicate;
import com.obscura.storage.util.Priority;
import com.obscura.storage.util.Priority.IPriority;

public class PlatformFilteredInventoryAccess implements IInventoryAccess, IPriority, IItemHandler {
	private final IInventoryAccess acc;
	private final IFilter filter;
	private final InventoryChangeTracker tracker;

	public PlatformFilteredInventoryAccess(IInventoryAccess acc, IFilter filter) {
		this.acc = acc;
		this.filter = filter;
		this.tracker = new InventoryChangeTracker(acc.getPlatformHandler()) {

			@Override
			protected boolean checkFilter(ItemStack stack) {
				return filter.getItemPred().test(stack);
			}

			@Override
			protected boolean checkFilter(StoredItemStack stack) {
				return filter.getItemPred().test(stack);
			}

			@Override
			protected int getCount(ItemStack is) {
				return filter.isKeepLast() ? is.getCount() - 1 : is.getCount();
			}

			@Override
			protected IItemHandler getSlotHandler(IItemHandler def) {
				return PlatformFilteredInventoryAccess.this;
			}

			@Override
			public Snapshot prepForOffThread(Level level) {
				refreshHandler();
				filter.getItemPred().updateState();
				return super.prepForOffThread(level);
			}

			@Override
			public long getChangeTracker(Level level) {
				refreshHandler();
				filter.getItemPred().updateState();
				return super.getChangeTracker(level);
			}

			@Override
			public Stream<StoredItemStack> streamWrappedStacks(boolean parallel) {
				refreshHandler();
				return super.streamWrappedStacks(parallel);
			}

			@Override
			public long countItems(StoredItemStack filter) {
				refreshHandler();
				return super.countItems(filter);
			}

			@Override
			public InventorySlot findSlot(ItemPredicate filter, boolean findEmpty) {
				refreshHandler();
				return super.findSlot(filter, findEmpty);
			}

			@Override
			public InventorySlot findSlotAfter(InventorySlot slot, ItemPredicate filter, boolean findEmpty, boolean loop) {
				refreshHandler();
				return super.findSlotAfter(slot, filter, findEmpty, loop);
			}

			@Override
			public InventorySlot findSlotDest(StoredItemStack forStack) {
				refreshHandler();
				return super.findSlotDest(forStack);
			}

			@Override
			public InventorySlot findSlotDestAfter(InventorySlot slot, StoredItemStack forStack, boolean loop) {
				refreshHandler();
				return super.findSlotDestAfter(slot, forStack, loop);
			}
		};
	}

	/**
	 * This access (and with it the tracker) is cached by {@link BlockFilter#wrap} for as long as the
	 * wrapped access object is reused, so the handler behind it must be looked up again on every use,
	 * like {@code BlockInventoryAccess.tracker()} does. Otherwise a chest placed or replaced later (or
	 * a single chest becoming a double chest) behind a filtered connector is never seen.
	 */
	private void refreshHandler() {
		tracker.refresh(acc.getPlatformHandler());
	}

	@Override
	public IInventoryChangeTracker tracker() {
		refreshHandler();
		return tracker;
	}

	@Override
	public ItemStack pushStack(ItemStack stack) {
		if (!test(stack))return stack;
		return IInventoryAccess.super.pushStack(stack);
	}

	@Override
	public ItemStack pullMatchingStack(ItemStack st, long max) {
		if (!test(st))return ItemStack.EMPTY;
		return IInventoryAccess.super.pullMatchingStack(st, max);
	}

	@Override
	public int getFreeSlotCount() {
		return acc.getFreeSlotCount();
	}

	@Override
	public int getSlotCount() {
		return acc.getSlotCount();
	}

	@Deprecated
	@Override
	public IItemHandler get() {
		return this;
	}

	@Override
	public Priority getPriority() {
		Priority pr = IPriority.get(acc);
		return pr.add(filter.getPriority());
	}

	@Override
	public int getSlots() {
		return getP().getSlots();
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		ItemStack is = getP().getStackInSlot(slot);
		if (is.isEmpty() || !test(is))return ItemStack.EMPTY;
		if (filter.isKeepLast()) {
			is = is.copy();
			is.shrink(1);
		}
		return is;
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		if (!test(stack))return stack;
		return getP().insertItem(slot, stack, simulate);
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		ItemStack is = getP().getStackInSlot(slot);
		if (is.isEmpty() || !test(is))return ItemStack.EMPTY;
		if (filter.isKeepLast()) {
			amount = Math.min(amount, is.getCount() - 1);
			if (amount < 1)return ItemStack.EMPTY;
		}
		return getP().extractItem(slot, amount, simulate);
	}

	@Override
	public int getSlotLimit(int slot) {
		return getP().getSlotLimit(slot);
	}

	@SuppressWarnings("deprecation")
	private IItemHandler getP() {
		IItemHandler h = acc.getPlatformHandler();
		return h != null ? h : PlatformInventoryAccess.EMPTY.get();
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		return test(stack) && getP().isItemValid(slot, stack);
	}

	private boolean test(ItemStack stack) {
		return filter.getItemPred().test(stack);
	}

	@Override
	public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
		return acc.getRootHandler(dejaVu);
	}

	@Override
	public String toString() {
		return "Filtering: {" + acc + " by " + filter + "}";
	}

	public IFilter getFilter() {
		return filter;
	}

	public IInventoryAccess getActualInventory() {
		return acc;
	}
}
