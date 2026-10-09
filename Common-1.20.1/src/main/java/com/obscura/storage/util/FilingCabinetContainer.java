package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class FilingCabinetContainer implements Container {
	private static final String ITEMS_KEY = "Items2";
	private static final String SLOT_KEY = "Slot";
	private final SimpleContainer inv;
	private final Runnable onChange;
	private final Predicate<Player> isValidFor;
	private Item item;

	public FilingCabinetContainer(int size, Runnable onChange, Predicate<Player> isValidFor) {
		this.inv = new SimpleContainer(size);
		this.onChange = onChange;
		this.isValidFor = isValidFor;
	}

	@Override
	public int getContainerSize() {
		return inv.getContainerSize();
	}

	@Override
	public boolean isEmpty() {
		return inv.isEmpty();
	}

	@Override
	public ItemStack getItem(int slot) {
		return inv.getItem(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		ItemStack out = inv.removeItem(slot, amount);
		if (inv.isEmpty()) item = null;
		return out;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		ItemStack out = inv.removeItemNoUpdate(slot);
		if (inv.isEmpty()) item = null;
		return out;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		inv.setItem(slot, stack);
		if (!stack.isEmpty()) {
			item = stack.getItem();
		} else if (inv.isEmpty()) {
			item = null;
		}
	}

	@Override
	public boolean stillValid(Player player) {
		return isValidFor.test(player);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		if (!canStore(stack)) return false;
		if (item != null) return stack.getItem() == item;
		return true;
	}

	/**
	 * Only unstackable items that may be put into containers are accepted (no shulker boxes and the like).
	 * Bundles are rejected too, like on 1.21.1: 1.20.1's BundleItem does not override
	 * {@code canFitInsideContainerItems}, so it is recognised by its class.
	 */
	public static boolean canStore(ItemStack stack) {
		return stack.getMaxStackSize() == 1 && stack.getItem().canFitInsideContainerItems() &&
				!(stack.getItem() instanceof BundleItem);
	}

	@Override
	public int getMaxStackSize() {
		return 1;
	}

	@Override
	public void setChanged() {
		if (onChange != null) onChange.run();
	}

	@Override
	public void clearContent() {
		inv.clearContent();
		item = null;
	}

	/**
	 * Saved as a list of compounds under {@code Items2}. Each entry is an item stack tag with an additional int
	 * {@code Slot}. Vanilla's {@link ContainerHelper#saveAllItems} stores the slot as a byte, which wraps around for
	 * containers bigger than 256 slots (this one has 512).
	 */
	public void saveToTag(CompoundTag tag) {
		ListTag list = new ListTag();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty()) {
				CompoundTag st = new CompoundTag();
				stack.save(st);
				st.putInt(SLOT_KEY, i);
				list.add(st);
			}
		}
		tag.put(ITEMS_KEY, list);
	}

	public void loadFromTag(CompoundTag tag) {
		int size = inv.getContainerSize();
		NonNullList<ItemStack> list = NonNullList.withSize(size, ItemStack.EMPTY);
		if (tag.contains(ITEMS_KEY, Tag.TAG_LIST)) {
			ListTag stacks = tag.getList(ITEMS_KEY, Tag.TAG_COMPOUND);
			List<ItemStack> overflow = new ArrayList<>();
			for (int i = 0; i < stacks.size(); i++) {
				CompoundTag st = stacks.getCompound(i);
				ItemStack stack = ItemStack.of(st);
				if (stack.isEmpty()) continue;
				int slot = st.getInt(SLOT_KEY);
				if (slot >= 0 && slot < size && list.get(slot).isEmpty()) {
					list.set(slot, stack);
				} else {
					overflow.add(stack);
				}
			}
			// never lose items: anything that did not fit its slot goes into the free slots
			int free = 0;
			for (ItemStack stack : overflow) {
				while (free < size && !list.get(free).isEmpty()) free++;
				if (free >= size) break;
				list.set(free, stack);
			}
		} else {
			// legacy format (vanilla ContainerHelper, slot stored as a byte)
			ContainerHelper.loadAllItems(tag, list);
		}
		inv.clearContent();
		for (int i = 0; i < list.size(); i++) {
			inv.setItem(i, list.get(i));
		}
		item = null;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty()) {
				item = stack.getItem();
				break;
			}
		}
	}
}
