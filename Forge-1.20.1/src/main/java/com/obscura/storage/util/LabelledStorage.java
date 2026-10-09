package com.obscura.storage.util;

import net.minecraft.world.item.ItemStack;

import net.minecraftforge.items.IItemHandler;

/**
 * An inventory labelled by glow item frames (see {@link FrameLabels}): the network may only insert
 * the labelled items, and fills it before unlabelled inventories (the connector sorts it with
 * {@link FrameLabel#PRIORITY}). Unlike a block filter it leaves extraction, keep-last and what the
 * network lists untouched, so a hand-placed item stays visible and extractable.
 *
 * <p>Not a {@link ItemHandlers.Delegating}: that would let inserts bypass the label. The bulk
 * helpers of {@link ItemHandlers} read and extract straight from {@link #getBacking()} and insert
 * through the label.
 */
public class LabelledStorage implements IItemHandler, IProxy, FrameLabel.Labelled {
	private final IItemHandler backing;
	private final FrameLabel label;

	public LabelledStorage(IItemHandler backing, FrameLabel label) {
		this.backing = backing;
		this.label = label;
	}

	public IItemHandler getBacking() {
		return backing;
	}

	/** Whether the network may insert {@code stack} here. */
	public boolean accepts(ItemStack stack) {
		return label.test(stack);
	}

	/** The handler behind the label, as a network member it stands for the same block. */
	@Override
	public IItemHandler get() {
		return backing;
	}

	@Override
	public Object getLabelledStorage() {
		return backing;
	}

	@Override
	public FrameLabel getLabel() {
		return label;
	}

	@Override
	public int getSlots() {
		return backing.getSlots();
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		return backing.getStackInSlot(slot);
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		if (stack.isEmpty() || !label.test(stack))return stack;
		return backing.insertItem(slot, stack, simulate);
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		return backing.extractItem(slot, amount, simulate);
	}

	@Override
	public int getSlotLimit(int slot) {
		return backing.getSlotLimit(slot);
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		return label.test(stack) && backing.isItemValid(slot, stack);
	}

	@Override
	public String toString() {
		return "Labelled: {" + backing + " by " + label + "}";
	}
}
