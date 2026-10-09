package com.obscura.storage.util;

import java.util.Comparator;
import java.util.function.Function;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;

public class StoredItemStack {
	private ItemStack stack;
	private long count;
	private static final String ITEM_COUNT_NAME = "c", ITEMSTACK_NAME = "s";
	private int hash;
	// Sort keys, computed on first use. The client sorts and filters thousands of these per update.
	private String displayName, displayNameLower, modId, registryId;

	public StoredItemStack(ItemStack stack, long count) {
		this.stack = stack;
		this.count = count;
	}

	public StoredItemStack(ItemStack stack) {
		this.stack = stack.copy();
		this.stack.setCount(1);
		this.count = stack.getCount();
	}

	public ItemStack getStack() {
		return stack;
	}

	public long getQuantity() {
		return count;
	}

	public ItemStack getActualStack() {
		ItemStack s = stack.copy();
		s.setCount((int) count);
		return s;
	}

	public void writeToNBT(CompoundTag tag) {
		tag.putLong(ITEM_COUNT_NAME, getQuantity());
		tag.put(ITEMSTACK_NAME, stack.save(new CompoundTag()));
		tag.getCompound(ITEMSTACK_NAME).remove("Count");
	}

	public void writeToNBT(CompoundTag tag, long q) {
		tag.putLong(ITEM_COUNT_NAME, q);
		tag.put(ITEMSTACK_NAME, stack.save(new CompoundTag()));
		tag.getCompound(ITEMSTACK_NAME).remove("Count");
	}

	public static StoredItemStack readFromNBT(CompoundTag tag) {
		ItemStack cheat = ItemStack.of(tag);
		tag.getCompound(ITEMSTACK_NAME).putByte("Count", (byte) 1);
		StoredItemStack stack = new StoredItemStack(!cheat.isEmpty() ? cheat : ItemStack.of(tag.getCompound(ITEMSTACK_NAME)), !cheat.isEmpty() ? cheat.getCount() : tag.getLong(ITEM_COUNT_NAME));
		return !stack.stack.isEmpty() ? stack : null;
	}

	public static class ComparatorAmount implements IStoredItemStackComparator {
		public boolean reversed;

		public ComparatorAmount(boolean reversed) {
			this.reversed = reversed;
		}

		@Override
		public int compare(StoredItemStack in1, StoredItemStack in2) {
			int c = in2.getQuantity() > in1.getQuantity() ? 1 : (in1.getQuantity() == in2.getQuantity() ? in1.getDisplayName().compareTo(in2.getDisplayName()) : -1);
			return this.reversed ? -c : c;
		}

		@Override
		public boolean isReversed() {
			return reversed;
		}

		@Override
		public int type() {
			return 0;
		}

		@Override
		public void setReversed(boolean rev) {
			reversed  = rev;
		}
	}

	public static class ComparatorName implements IStoredItemStackComparator {
		public boolean reversed;

		public ComparatorName(boolean reversed) {
			this.reversed = reversed;
		}

		@Override
		public int compare(StoredItemStack in1, StoredItemStack in2) {
			int c = in1.getDisplayName().compareTo(in2.getDisplayName());
			return this.reversed ? -c : c;
		}

		@Override
		public boolean isReversed() {
			return reversed;
		}

		@Override
		public int type() {
			return 1;
		}

		@Override
		public void setReversed(boolean rev) {
			reversed = rev;
		}
	}

	public static class ComparatorModName implements IStoredItemStackComparator {
		public boolean reversed;

		public ComparatorModName(boolean reversed) {
			this.reversed = reversed;
		}

		@Override
		public int compare(StoredItemStack in1, StoredItemStack in2) {
			String m1 = in1.getModId();
			String m2 = in2.getModId();
			int c1 = m1.compareTo(m2);
			int c2 = in1.getDisplayName().compareTo(in2.getDisplayName());
			int c = c1 == 0 ? c2 : c1;
			return this.reversed ? -c : c;
		}

		@Override
		public boolean isReversed() {
			return reversed;
		}

		@Override
		public void setReversed(boolean rev) {
			reversed = rev;
		}

		@Override
		public int type() {
			return 2;
		}
	}

	public static class ComparatorRarity implements IStoredItemStackComparator {
		public boolean reversed;

		public ComparatorRarity(boolean reversed) {
			this.reversed = reversed;
		}

		@Override
		public int compare(StoredItemStack in1, StoredItemStack in2) {
			int c = Integer.compare(in2.getStack().getRarity().ordinal(), in1.getStack().getRarity().ordinal());
			if (c == 0)c = compareByName(in1, in2);
			return this.reversed ? -c : c;
		}

		@Override
		public boolean isReversed() {
			return reversed;
		}

		@Override
		public void setReversed(boolean rev) {
			reversed = rev;
		}

		@Override
		public int type() {
			return 3;
		}
	}

	public static class ComparatorRegistryId implements IStoredItemStackComparator {
		public boolean reversed;

		public ComparatorRegistryId(boolean reversed) {
			this.reversed = reversed;
		}

		@Override
		public int compare(StoredItemStack in1, StoredItemStack in2) {
			String id1 = in1.getRegistryId();
			String id2 = in2.getRegistryId();
			int c = id1.compareTo(id2);
			if (c == 0)c = compareByName(in1, in2);
			return this.reversed ? -c : c;
		}

		@Override
		public boolean isReversed() {
			return reversed;
		}

		@Override
		public void setReversed(boolean rev) {
			reversed = rev;
		}

		@Override
		public int type() {
			return 4;
		}
	}

	public static class ComparatorDurability implements IStoredItemStackComparator {
		public boolean reversed;

		public ComparatorDurability(boolean reversed) {
			this.reversed = reversed;
		}

		@Override
		public int compare(StoredItemStack in1, StoredItemStack in2) {
			boolean d1 = in1.getStack().isDamageableItem();
			boolean d2 = in2.getStack().isDamageableItem();
			int c;
			if (d1 != d2) {
				c = d1 ? -1 : 1;
			} else if (d1) {
				int max1 = in1.getStack().getMaxDamage();
				int max2 = in2.getStack().getMaxDamage();
				double left1 = max1 <= 0 ? 0.0D : (double) (max1 - in1.getStack().getDamageValue()) / (double) max1;
				double left2 = max2 <= 0 ? 0.0D : (double) (max2 - in2.getStack().getDamageValue()) / (double) max2;
				c = Double.compare(left2, left1);
				if (c == 0)c = Integer.compare(max2, max1);
			} else {
				c = 0;
			}
			if (c == 0)c = compareByName(in1, in2);
			return this.reversed ? -c : c;
		}

		@Override
		public boolean isReversed() {
			return reversed;
		}

		@Override
		public void setReversed(boolean rev) {
			reversed = rev;
		}

		@Override
		public int type() {
			return 5;
		}
	}

	public static class ComparatorItemGroup implements IStoredItemStackComparator {
		public boolean reversed;

		public ComparatorItemGroup(boolean reversed) {
			this.reversed = reversed;
		}

		@Override
		public int compare(StoredItemStack in1, StoredItemStack in2) {
			int c = Integer.compare(getItemGroup(in1), getItemGroup(in2));
			if (c == 0)c = compareByName(in1, in2);
			return this.reversed ? -c : c;
		}

		@Override
		public boolean isReversed() {
			return reversed;
		}

		@Override
		public void setReversed(boolean rev) {
			reversed = rev;
		}

		@Override
		public int type() {
			return 6;
		}
	}

	private static int compareByName(StoredItemStack in1, StoredItemStack in2) {
		return in1.getDisplayName().compareTo(in2.getDisplayName());
	}

	private static int getItemGroup(StoredItemStack in) {
		Item item = in.getStack().getItem();
		if (item instanceof BlockItem)return 0;
		if (item instanceof ArmorItem)return 1;
		if (item instanceof TieredItem || item instanceof BowItem || item instanceof CrossbowItem || item instanceof ShieldItem || item instanceof TridentItem)return 2;
		if (in.getStack().isEdible())return 3;
		if (item instanceof BucketItem)return 4;
		if (item instanceof SpawnEggItem)return 5;
		return 6;
	}

	public static interface IStoredItemStackComparator extends Comparator<StoredItemStack> {
		boolean isReversed();
		void setReversed(boolean rev);
		int type();
	}

	public static enum SortingTypes {
		AMOUNT(ComparatorAmount::new),
		NAME(ComparatorName::new),
		BY_MOD(ComparatorModName::new),
		RARITY(ComparatorRarity::new),
		REGISTRY_ID(ComparatorRegistryId::new),
		DURABILITY(ComparatorDurability::new),
		ITEM_GROUP(ComparatorItemGroup::new),
		;
		public static final SortingTypes[] VALUES = values();
		private final Function<Boolean, IStoredItemStackComparator> factory;
		private SortingTypes(Function<Boolean, IStoredItemStackComparator> factory) {
			this.factory = factory;
		}

		public IStoredItemStackComparator create(boolean rev) {
			return factory.apply(rev);
		}
	}

	@Override
	public int hashCode() {
		if(hash == 0) {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((stack == null) ? 0 : stack.getItem().hashCode());
			result = prime * result + ((stack == null || !stack.hasTag()) ? 0 : stack.getTag().hashCode());
			hash = result;
			return result;
		}
		return hash;
	}

	public String getDisplayName() {
		String n = displayName;
		if(n == null)displayName = n = stack.getHoverName().getString();
		return n;
	}

	/** Lower case display name, for searching. */
	public String getDisplayNameLower() {
		String n = displayNameLower;
		if(n == null)displayNameLower = n = getDisplayName().toLowerCase();
		return n;
	}

	/** Namespace of the item's registry id. */
	public String getModId() {
		String n = modId;
		if(n == null)modId = n = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
		return n;
	}

	/** The item's registry id as text. */
	public String getRegistryId() {
		String n = registryId;
		if(n == null)registryId = n = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		return n;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (getClass() != obj.getClass()) return false;
		StoredItemStack other = (StoredItemStack) obj;
		//if (count != other.count) return false;
		if (stack == null) {
			if (other.stack != null) return false;
		} else if (!ItemStack.isSameItemSameTags(stack, other.stack)) return false;
		return true;
	}

	public boolean equals(StoredItemStack other) {
		if (this == other) return true;
		if (other == null) return false;
		if (count != other.count) return false;
		if (stack == null) {
			if (other.stack != null) return false;
		} else if (!ItemStack.isSameItemSameTags(stack, other.stack)) return false;
		return true;
	}

	public void grow(long c) {
		count += c;
	}

	public void setCount(long count) {
		this.count = count;
	}

	public int getMaxStackSize() {
		return stack.getMaxStackSize();
	}
}
