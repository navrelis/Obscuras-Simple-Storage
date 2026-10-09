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
	public ItemStack getItem(int pSlot) {
		return delegate.getItem(startOffset + pSlot);
	}

	@Override
	public ItemStack removeItem(int pSlot, int pAmount) {
		return delegate.removeItem(startOffset + pSlot, pAmount);
	}

	@Override
	public ItemStack removeItemNoUpdate(int pSlot) {
		return delegate.removeItemNoUpdate(startOffset + pSlot);
	}

	@Override
	public void setItem(int pSlot, ItemStack pStack) {
		delegate.setItem(startOffset + pSlot, pStack);
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
	public boolean stillValid(Player pPlayer) {
		return delegate.stillValid(pPlayer);
	}

	@Override
	public void startOpen(Player pPlayer) {
		delegate.startOpen(pPlayer);
	}

	@Override
	public void stopOpen(Player pPlayer) {
		delegate.stopOpen(pPlayer);
	}

	@Override
	public boolean canPlaceItem(int pIndex, ItemStack pStack) {
		return delegate.canPlaceItem(startOffset + pIndex, pStack);
	}

	@Override
	public int countItem(Item pItem) {
		int count = 0;
		int size = getContainerSize();
		for (int i = 0; i < size; i++) {
			ItemStack stack = delegate.getItem(startOffset + i);
			if (stack.getItem() == pItem) {
				count += stack.getCount();
			}
		}
		return count;
	}

	@Override
	public boolean hasAnyOf(Set<Item> pSet) {
		int size = getContainerSize();
		for (int i = 0; i < size; i++) {
			ItemStack stack = delegate.getItem(startOffset + i);
			if (!stack.isEmpty() && pSet.contains(stack.getItem())) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean hasAnyMatching(Predicate<ItemStack> p_216875_) {
		int size = getContainerSize();
		for (int i = 0; i < size; i++) {
			if (p_216875_.test(delegate.getItem(startOffset + i))) {
				return true;
			}
		}
		return false;
	}

	public void setSizeLimit(int sizeLimit) {
		this.sizeLimit = sizeLimit;
	}

	public void setStartOffset(int startOffset) {
		this.startOffset = Math.max(0, Math.min(startOffset, delegate.getContainerSize()));
	}
}
