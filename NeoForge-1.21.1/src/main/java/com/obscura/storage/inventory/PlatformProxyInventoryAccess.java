package com.obscura.storage.inventory;

import java.util.Set;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Exposes another access as a handler. The recursion guard is always cleared in a finally block: an
 * exception from the target (a broken modded inventory) must not leave the proxy reporting itself
 * empty forever.
 */
public class PlatformProxyInventoryAccess implements PlatformInventoryAccess, IItemHandler {
	private boolean calling;
	private IInventoryAccess access;

	public PlatformProxyInventoryAccess(IInventoryAccess access) {
		this.access = access;
	}

	private IItemHandler getP() {
		return access.getPlatformHandler();
	}

	@Deprecated
	@Override
	public IItemHandler get() {
		return this;
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		if(calling)return false;
		calling = true;
		try {
			return getP().isItemValid(slot, stack);
		} finally {
			calling = false;
		}
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		if(calling)return stack;
		calling = true;
		try {
			return getP().insertItem(slot, stack, simulate);
		} finally {
			calling = false;
		}
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		if(calling)return ItemStack.EMPTY;
		calling = true;
		try {
			return getP().getStackInSlot(slot);
		} finally {
			calling = false;
		}
	}

	@Override
	public int getSlots() {
		if(calling)return 0;
		calling = true;
		try {
			return getP().getSlots();
		} finally {
			calling = false;
		}
	}

	@Override
	public int getSlotLimit(int slot) {
		if(calling)return 0;
		calling = true;
		try {
			return getP().getSlotLimit(slot);
		} finally {
			calling = false;
		}
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		if(calling)return ItemStack.EMPTY;
		calling = true;
		try {
			return getP().extractItem(slot, amount, simulate);
		} finally {
			calling = false;
		}
	}

	@Override
	public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
		if (dejaVu.add(access))
			return access.getRootHandler(dejaVu);
		else
			return this;
	}

	@Override
	public IInventoryChangeTracker tracker() {
		if(calling)return InventoryChangeTracker.NULL;
		calling = true;
		try {
			return access.tracker();
		} finally {
			calling = false;
		}
	}
}
