package com.obscura.storage.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.minecraftforge.items.IItemHandler;

/** Read-only single slot that shows the "network loop" warning item in terminals. */
public class InfoHandler implements IItemHandler {
	private static final ItemStack STACK = new ItemStack(Items.BARRIER, Integer.MAX_VALUE);
	static {
		STACK.setTag(new CompoundTag());
		CompoundTag d = new CompoundTag();
		STACK.getTag().put("display", d);
		d.putString("Name", "{\"translate\":\"tooltip.obscuras_storage.loop\",\"color\":\"red\",\"italic\":false}");
	}

	public static final IItemHandler INSTANCE = new InfoHandler();

	private InfoHandler() {
	}

	@Override
	public int getSlots() {
		return 1;
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		return slot == 0 ? STACK : ItemStack.EMPTY;
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		return stack;
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		return ItemStack.EMPTY;
	}

	@Override
	public int getSlotLimit(int slot) {
		return 0;
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		return false;
	}
}
