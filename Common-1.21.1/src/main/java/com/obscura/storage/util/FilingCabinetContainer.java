package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.StorageMod;
import com.obscura.storage.migration.LegacyData;

public class FilingCabinetContainer implements Container {
	private static final Logger LOGGER = LogManager.getLogger(StorageMod.modid);
	private SimpleContainer inv;
	private Item item;
	private Runnable onChange;
	private Predicate<Player> isValidFor;

	public FilingCabinetContainer(int size, Runnable onChange, Predicate<Player> isValidFor) {
		this.inv = new SimpleContainer(size);
		this.onChange = onChange;
		this.isValidFor = isValidFor;
	}

	@Override
	public void clearContent() {
		inv.clearContent();
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
	public ItemStack getItem(int p_18941_) {
		return inv.getItem(p_18941_);
	}

	@Override
	public ItemStack removeItem(int p_18942_, int p_18943_) {
		ItemStack is = inv.removeItem(p_18942_, p_18943_);
		if (inv.isEmpty())item = null;
		return is;
	}

	@Override
	public ItemStack removeItemNoUpdate(int p_18951_) {
		ItemStack is = inv.removeItemNoUpdate(p_18951_);
		if (inv.isEmpty())item = null;
		return is;
	}

	@Override
	public void setItem(int p_18944_, ItemStack stack) {
		inv.setItem(p_18944_, stack);
		if (!stack.isEmpty())
			item = stack.getItem();
		else if (inv.isEmpty())item = null;
	}

	@Override
	public boolean stillValid(Player p_18946_) {
		return isValidFor.test(p_18946_);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		if (!canStore(stack))return false;
		if (item != null)return stack.getItem() == item;
		return true;
	}

	/**
	 * Whether a stack may be stored at all: unstackable, and not a container item itself, which would
	 * allow nesting inventories. canFitInsideContainerItems covers shulker boxes, but vanilla 1.21.1
	 * still returns true for bundles, so those are recognised by their contents component.
	 */
	public static boolean canStore(ItemStack stack) {
		return stack.getMaxStackSize() == 1 && stack.getItem().canFitInsideContainerItems() &&
				!stack.has(DataComponents.BUNDLE_CONTENTS);
	}

	@Override
	public int getMaxStackSize() {
		return 1;
	}

	@Override
	public void setChanged() {
		if(onChange != null)
			onChange.run();
	}

	public ListTag createTag(HolderLookup.Provider reg) {
		return inv.createTag(reg);
	}

	public void fromTag(ListTag list, HolderLookup.Provider reg) {
		inv.fromTag(list, reg);
		item = null;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack is = inv.getItem(i);
			if (!is.isEmpty()) {
				item = is.getItem();
				break;
			}
		}
	}

	/**
	 * The inventory as the 1.20.1 builds saved it: a compound (not a list) with the stacks under
	 * {@code Items2}, each a 1.20.1 item stack with an int {@code Slot}; older 1.20.1 versions used the
	 * vanilla {@code Items} list (byte {@code Slot}). The stacks are upgraded through the data fixers
	 * ({@link LegacyData}). Like the 1.20.1 reader, a stack whose slot is taken or out of range goes into
	 * the next free slot, so no item is lost.
	 */
	public void fromLegacyTag(CompoundTag tag, HolderLookup.Provider reg) {
		int size = inv.getContainerSize();
		NonNullList<ItemStack> list = NonNullList.withSize(size, ItemStack.EMPTY);
		List<ItemStack> overflow = new ArrayList<>();
		boolean intSlots = tag.contains("Items2", Tag.TAG_LIST);
		ListTag stacks = tag.getList(intSlots ? "Items2" : "Items", Tag.TAG_COMPOUND);
		for (int i = 0; i < stacks.size(); i++) {
			CompoundTag st = stacks.getCompound(i);
			ItemStack stack = LegacyData.parseItemStack(reg, st);
			if (stack.isEmpty())continue;
			int slot = intSlots ? st.getInt("Slot") : st.getByte("Slot") & 255;
			if (slot >= 0 && slot < size && list.get(slot).isEmpty())list.set(slot, stack);
			else overflow.add(stack);
		}
		int free = 0;
		for (ItemStack stack : overflow) {
			while (free < size && !list.get(free).isEmpty())free++;
			if (free >= size) {
				LOGGER.error("Filing Cabinet: no free slot for {}", stack);
				continue;
			}
			list.set(free, stack);
		}
		inv.clearContent();
		item = null;
		for (int i = 0; i < size; i++) {
			ItemStack is = list.get(i);
			inv.setItem(i, is);
			if (item == null && !is.isEmpty())item = is.getItem();
		}
	}

	public NonNullList<ItemStack> getItems() {
		return inv.getItems();
	}
}
