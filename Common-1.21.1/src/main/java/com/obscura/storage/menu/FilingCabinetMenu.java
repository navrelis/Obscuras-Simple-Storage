package com.obscura.storage.menu;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.mojang.datafixers.util.Pair;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.util.FilingCabinetContainer;
import com.obscura.storage.util.LimitedContainer;

public class FilingCabinetMenu extends AbstractContainerMenu {
	public static final ResourceLocation LOCKED_SLOT = ResourceLocation.tryBuild(StorageMod.modid, "item/locked_slot");

	private final Container containerParent;
	private final LimitedContainer container;
	private final int containerRows;

	public FilingCabinetMenu(int wid, Inventory pinv) {
		this(wid, pinv, new SimpleContainer(512));
	}

	public FilingCabinetMenu(int wid, Inventory pinv, Container inv) {
		super(Content.filingCabinetMenu.get(), wid);

		this.containerParent = inv;
		this.container = new LimitedContainer(inv, 9 * 5);
		this.containerRows = 5;
		int i = (this.containerRows - 4) * 18;

		for (int j = 0; j < this.containerRows; ++j) {
			for (int k = 0; k < 9; ++k) {
				this.addSlot(new Slot(this.container, k + j * 9, 8 + k * 18, 18 + j * 18) {

					@Override
					public int getMaxStackSize() {
						return 1;
					}

					@Override
					public int getMaxStackSize(ItemStack p_40238_) {
						return 1;
					}

					@Override
					public boolean mayPlace(ItemStack stack) {
						if (!isValid())return false;
						if (!FilingCabinetContainer.canStore(stack))return false;
						for (int i = 0;i<containerParent.getContainerSize();i++) {
							ItemStack is = containerParent.getItem(i);
							if (!is.isEmpty())
								return is.getItem() == stack.getItem();
						}
						return true;
					}

					@Override
					public boolean mayPickup(Player p_40228_) {
						if (!isValid())return false;
						return true;
					}

					@Override
					public ItemStack getItem() {
						if (!isValid())return ItemStack.EMPTY;
						return super.getItem();
					}

					@Override
					public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
						if (!isValid())
							return Pair.of(InventoryMenu.BLOCK_ATLAS, LOCKED_SLOT);
						else
							return null;
					}

					private boolean isValid() {
						return this.container.getContainerSize() > getContainerSlot();
					}
				});
			}
		}

		for (int l = 0; l < 3; ++l) {
			for (int j1 = 0; j1 < 9; ++j1) {
				this.addSlot(new Slot(pinv, j1 + l * 9 + 9, 8 + j1 * 18, 103 + l * 18 + i));
			}
		}

		for (int i1 = 0; i1 < 9; ++i1) {
			this.addSlot(new Slot(pinv, i1, 8 + i1 * 18, 161 + i));
		}
	}

	@Override
	public ItemStack quickMoveStack(Player p_39253_, int p_39254_) {
		ItemStack itemstack = ItemStack.EMPTY;
		Slot slot = this.slots.get(p_39254_);
		if (slot != null && slot.hasItem()) {
			ItemStack itemstack1 = slot.getItem();
			itemstack = itemstack1.copy();
			if (p_39254_ < this.containerRows * 9) {
				if (!this.moveItemStackTo(itemstack1, this.containerRows * 9, this.slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else if (!this.moveItemStackTo(itemstack1, 0, this.containerRows * 9, false)) {
				return ItemStack.EMPTY;
			}

			if (itemstack1.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
		}

		return itemstack;
	}

	@Override
	public boolean stillValid(Player p_38874_) {
		return container.stillValid(p_38874_);
	}

	public int getRowCount() {
		return this.containerRows;
	}

	@Override
	public boolean clickMenuButton(Player p_38875_, int row) {
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
