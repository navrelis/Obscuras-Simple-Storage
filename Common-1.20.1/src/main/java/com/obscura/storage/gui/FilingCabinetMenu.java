package com.obscura.storage.gui;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.Content;
import com.obscura.storage.util.FilingCabinetContainer;
import com.obscura.storage.util.LimitedContainer;

public class FilingCabinetMenu extends AbstractContainerMenu {
	private final Container containerParent;
	private final LimitedContainer container;
	private final int containerRows;

	public FilingCabinetMenu(int id, Inventory playerInv) {
		this(id, playerInv, new SimpleContainer(512));
	}

	public FilingCabinetMenu(int id, Inventory playerInv, Container inv) {
		super(Content.filingCabinetContainer.get(), id);
		this.containerParent = inv;
		this.container = new LimitedContainer(inv, 9 * 5);
		this.containerRows = 5;
		int yOffset = (this.containerRows - 4) * 18;

		for (int row = 0; row < this.containerRows; ++row) {
			for (int col = 0; col < 9; ++col) {
				this.addSlot(new Slot(this.container, col + row * 9, 8 + col * 18, 18 + row * 18) {
					@Override
					public int getMaxStackSize() {
						return 1;
					}

					@Override
					public int getMaxStackSize(ItemStack stack) {
						return 1;
					}

					@Override
					public boolean mayPlace(ItemStack stack) {
						if (!FilingCabinetContainer.canStore(stack) || !isVisible()) return false;
						for (int i = 0; i < containerParent.getContainerSize(); i++) {
							ItemStack in = containerParent.getItem(i);
							if (!in.isEmpty()) {
								return in.getItem() == stack.getItem();
							}
						}
						return true;
					}

					@Override
					public boolean mayPickup(Player player) {
						return isVisible();
					}

					@Override
					public ItemStack getItem() {
						if (!isVisible()) return ItemStack.EMPTY;
						return super.getItem();
					}

					private boolean isVisible() {
						return this.container.getContainerSize() > getContainerSlot();
					}
				});
			}
		}

		for (int row = 0; row < 3; ++row) {
			for (int col = 0; col < 9; ++col) {
				this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + yOffset));
			}
		}

		for (int col = 0; col < 9; ++col) {
			this.addSlot(new Slot(playerInv, col, 8 + col * 18, 161 + yOffset));
		}
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		ItemStack result = ItemStack.EMPTY;
		Slot slot = this.slots.get(index);
		if (slot != null && slot.hasItem()) {
			ItemStack stack = slot.getItem();
			result = stack.copy();
			if (index < this.containerRows * 9) {
				if (!this.moveItemStackTo(stack, this.containerRows * 9, this.slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else if (!this.moveItemStackTo(stack, 0, this.containerRows * 9, false)) {
				return ItemStack.EMPTY;
			}

			if (stack.isEmpty()) {
				slot.set(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
		}
		return result;
	}

	@Override
	public boolean stillValid(Player player) {
		return container.stillValid(player);
	}

	@Override
	public boolean clickMenuButton(Player player, int row) {
		setRow(row);
		return true;
	}

	public void setRow(int row) {
		int visibleSize = containerRows * 9;
		int maxRow = Math.max(0, (containerParent.getContainerSize() - visibleSize) / 9);
		row = Math.max(0, Math.min(row, maxRow));
		container.setStartOffset(row * 9);
	}

	public int getContainerSize() {
		return containerParent.getContainerSize();
	}
}
