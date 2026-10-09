package com.obscura.storage.inventory;

import java.util.Set;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import com.obscura.storage.util.Priority;
import com.obscura.storage.util.Priority.IPriority;

/**
 * An inventory labelled by glow item frames (see {@link FrameLabels}): the network may only insert
 * the labelled items, and fills it before unlabelled inventories. Unlike a block filter it leaves
 * extraction, keep-last and what the network lists untouched, so the change tracker is the wrapped
 * inventory's own and a hand-placed item stays visible and extractable.
 *
 * <p>The item handler view ({@link #get()}) filters insertion only, for network inserts that go
 * through the merged handler (other mods' pipes into an Inventory Interface or Connector).
 */
public class PlatformLabelledInventoryAccess implements IInventoryAccess, IPriority, IItemHandler, FrameLabel.Labelled {
	private final IInventoryAccess acc;
	private final FrameLabel label;
	private final FrameLabel.DestTracker tracker;

	public PlatformLabelledInventoryAccess(IInventoryAccess acc, FrameLabel label) {
		this.acc = acc;
		this.label = label;
		this.tracker = new FrameLabel.DestTracker(label);
	}

	@Override
	public IInventoryChangeTracker tracker() {
		label.updateState();
		tracker.setDelegate(acc.tracker());
		return tracker;
	}

	@Override
	public ItemStack pushStack(ItemStack stack) {
		label.updateState();
		if (!label.test(stack))return stack;
		return acc.pushStack(stack);
	}

	@Override
	public ItemStack pullMatchingStack(ItemStack st, long max) {
		return acc.pullMatchingStack(st, max);
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
		return IPriority.get(acc).add(FrameLabel.PRIORITY);
	}

	@SuppressWarnings("deprecation")
	private IItemHandler getP() {
		IItemHandler h = acc.getPlatformHandler();
		return h != null ? h : PlatformInventoryAccess.EMPTY.get();
	}

	@Override
	public int getSlots() {
		return getP().getSlots();
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		return getP().getStackInSlot(slot);
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		if (!label.test(stack))return stack;
		return getP().insertItem(slot, stack, simulate);
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		return getP().extractItem(slot, amount, simulate);
	}

	@Override
	public int getSlotLimit(int slot) {
		return getP().getSlotLimit(slot);
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		return label.test(stack) && getP().isItemValid(slot, stack);
	}

	@Override
	public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
		return acc.getRootHandler(dejaVu);
	}

	@Override
	public IInventoryAccess getLabelledInventory() {
		return acc;
	}

	@Override
	public FrameLabel getLabel() {
		return label;
	}

	@Override
	public String toString() {
		return "Labelled: {" + acc + " by " + label + "}";
	}
}
