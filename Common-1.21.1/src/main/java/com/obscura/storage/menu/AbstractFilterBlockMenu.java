package com.obscura.storage.menu;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.block.entity.AbstractFilterBlockEntity;
import com.obscura.storage.inventory.filter.GhostFilter;
import com.obscura.storage.menu.slot.PhantomSlot;
import com.obscura.storage.util.DataSlots;

/**
 * Shared menu of the Ejector, Disposal and Exporter: the 3x3 ghost filter (phantom slots that write
 * reference stacks into the block entity, never real items) and the Exact toggle. Settings come from
 * the client as a data packet ({@link #receive}) and are validated here.
 */
public abstract class AbstractFilterBlockMenu<T extends AbstractFilterBlockEntity> extends AbstractFilteredMenu {
	/** Client message: byte, the Exact toggle. */
	public static final String MSG_EXACT = "exact";
	private final Container inv;
	/** The block entity, null on the client. */
	protected final T te;
	public boolean exact;

	/** @param slotX left edge of the ghost slot grid; its top edge is always 17 */
	protected AbstractFilterBlockMenu(MenuType<?> type, int wid, Inventory pinv, T te, int slotX) {
		super(type, wid, pinv);
		this.te = te;
		this.inv = te == null ? new SimpleContainer(GhostFilter.SLOTS) : new Container() {

			@Override
			public void clearContent() {
			}

			@Override
			public void setItem(int index, ItemStack stack) {
				te.setFilterStack(index, stack);
			}

			@Override
			public ItemStack removeItemNoUpdate(int index) {
				return ItemStack.EMPTY;
			}

			@Override
			public void setChanged() {
			}

			@Override
			public boolean stillValid(Player player) {
				return te.stillValid(player);
			}

			@Override
			public boolean isEmpty() {
				return te.getFilter().isEmpty();
			}

			@Override
			public ItemStack getItem(int index) {
				return te.getFilter().getStack(index);
			}

			@Override
			public int getContainerSize() {
				return GhostFilter.SLOTS;
			}

			@Override
			public ItemStack removeItem(int index, int count) {
				return ItemStack.EMPTY;
			}
		};
		inv.startOpen(pinv.player);

		for(int i = 0; i < 3; ++i) {
			for(int j = 0; j < 3; ++j) {
				this.addSlot(new PhantomSlot(inv, j + i * 3, slotX + j * 18, 17 + i * 18));
			}
		}

		for(int k = 0; k < 3; ++k) {
			for(int i1 = 0; i1 < 9; ++i1) {
				this.addSlot(new Slot(pinv, i1 + k * 9 + 9, 8 + i1 * 18, 84 + k * 18));
			}
		}

		for(int l = 0; l < 9; ++l) {
			this.addSlot(new Slot(pinv, l, 8 + l * 18, 142));
		}

		addDataSlot(DataSlots.create(v -> exact = v > 0, () -> te != null && te.getFilter().isExact() ? 1 : 0));
	}

	/**
	 * Determines whether supplied player can use this container
	 */
	@Override
	public boolean stillValid(Player playerIn) {
		return te != null ? te.stillValid(playerIn) : true;
	}

	/**
	 * Shift-click on an inventory stack: a reference copy goes into the first free ghost slot, unless the
	 * filter already holds the same entry. Nothing is taken from the player.
	 */
	@Override
	public ItemStack quickMoveStack(Player playerIn, int index) {
		Slot slot = this.slots.get(index);
		if (slot != null && slot.hasItem() && index >= GhostFilter.SLOTS) {
			ItemStack is = slot.getItem().copy();
			is.setCount(1);
			for(int i = 0;i<GhostFilter.SLOTS;i++) {
				Slot sl = this.slots.get(i);
				if(ItemStack.isSameItemSameComponents(sl.getItem(), is))break;
				if(sl.getItem().isEmpty()) {
					sl.set(is);
					break;
				}
			}
		}

		return ItemStack.EMPTY;
	}

	/**
	 * Called when the container is closed.
	 */
	@Override
	public void removed(Player playerIn) {
		super.removed(playerIn);
		this.inv.stopOpen(playerIn);
	}

	@Override
	public final void receive(CompoundTag tag) {
		if(pinv.player.isSpectator() || te == null || !stillValid(pinv.player))return;
		super.receive(tag);
		if(tag.contains(MSG_EXACT, Tag.TAG_BYTE))te.setExact(tag.getBoolean(MSG_EXACT));
		handle(tag);
	}

	/** The block's own settings in a client message. Server side, the sender may use the menu. */
	protected void handle(CompoundTag tag) {
	}

	/**
	 * An enum ordinal sent by the client under {@code key}.
	 *
	 * @return the ordinal, -1 when the key is missing or the value is not one of the {@code count} ordinals
	 */
	protected static int readOrdinal(CompoundTag tag, String key, int count) {
		if(!tag.contains(key, Tag.TAG_ANY_NUMERIC))return -1;
		int v = tag.getInt(key);
		return v >= 0 && v < count ? v : -1;
	}
}
