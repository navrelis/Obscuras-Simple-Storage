package com.obscura.storage.util;

import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class LimitedContainer implements Container {
	private final Container delegate;
	private int sizeLimit;
	private int startOffset;

	public LimitedContainer(Container delegate, int sizeLimit) {
		this.delegate = delegate;
		this.sizeLimit = sizeLimit;
	}

	@Override
	public void clearContent() {
		int size = getContainerSize();
		for (int i = 0; i < size; i++) {
			delegate.setItem(startOffset + i, ItemStack.EMPTY);
		}
	}

	@Override
	public int getContainerSize() {
		return Math.min(sizeLimit, delegate.getContainerSize() - startOffset);
	}

	@Override
	public boolean isEmpty() {
		int size = getContainerSize();
		for (int i = 0; i < size; i++) {
			if (!delegate.getItem(startOffset + i).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getItem(int slot) {
		return delegate.getItem(startOffset + slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		return delegate.removeItem(startOffset + slot, amount);
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return delegate.removeItemNoUpdate(startOffset + slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		delegate.setItem(startOffset + slot, stack);
	}

	@Override
	public int getMaxStackSize() {
		return delegate.getMaxStackSize();
	}

	@Override
	public void setChanged() {
		delegate.setChanged();
	}

	@Override
	public boolean stillValid(Player player) {
		return delegate.stillValid(player);
	}

	@Override
	public void startOpen(Player player) {
		delegate.startOpen(player);
	}

	@Override
	public void stopOpen(Player player) {
		delegate.stopOpen(player);
	}

	@Override
	public boolean canPlaceItem(int index, ItemStack stack) {
		return delegate.canPlaceItem(startOffset + index, stack);
	}

	@Override
	public int countItem(Item item) {
		int count = 0;
		int size = getContainerSize();
		for (int i = 0; i < size; i++) {
			ItemStack stack = delegate.getItem(startOffset + i);
			if (stack.getItem() == item) {
				count += stack.getCount();
			}
		}
		return count;
	}

	@Override
	public boolean hasAnyOf(Set<Item> set) {
		int size = getContainerSize();
		for (int i = 0; i < size; i++) {
			ItemStack stack = delegate.getItem(startOffset + i);
			if (!stack.isEmpty() && set.contains(stack.getItem())) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean hasAnyMatching(Predicate<ItemStack> test) {
		int size = getContainerSize();
		for (int i = 0; i < size; i++) {
			if (test.test(delegate.getItem(startOffset + i))) {
				return true;
			}
		}
		return false;
	}

	public void setStartOffset(int startOffset) {
		this.startOffset = Math.max(0, Math.min(startOffset, delegate.getContainerSize()));
	}
}
