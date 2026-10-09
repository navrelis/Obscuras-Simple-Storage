package com.obscura.storage.inventory;

import java.util.Set;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.FilteringStorage;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.util.Priority;
import com.obscura.storage.util.Priority.IPriority;

/**
 * An inventory labelled by glow item frames (see {@link FrameLabels}): the network may only insert
 * the labelled items, and fills it before unlabelled inventories. Unlike a block filter it leaves
 * extraction, keep-last and what the network lists untouched, so the change tracker is the wrapped
 * inventory's own and a hand-placed item stays visible and extractable.
 *
 * <p>The Transfer API view ({@link #get()}) filters insertion only, for network inserts that go
 * through the merged Storage (other mods' pipes into an Inventory Interface or Connector).
 */
public class PlatformLabelledInventoryAccess extends FilteringStorage<ItemVariant> implements IInventoryAccess, IPriority, FrameLabel.Labelled {
	private final IInventoryAccess acc;
	private final FrameLabel label;
	private final FrameLabel.DestTracker tracker;

	public PlatformLabelledInventoryAccess(IInventoryAccess acc, FrameLabel label) {
		super(acc::getPlatformHandler);
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
	protected boolean canInsert(ItemVariant resource) {
		return label.test(resource.toStack());
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
	public Storage<ItemVariant> get() {
		return this;
	}

	@Override
	public Priority getPriority() {
		return IPriority.get(acc).add(FrameLabel.PRIORITY);
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
