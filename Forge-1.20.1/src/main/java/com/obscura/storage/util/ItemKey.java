package com.obscura.storage.util;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Forge counterpart of Fabric's {@code ItemVariant}: an item plus its NBT, without a count.
 * Immutable (the tag is a private copy), so it can be used as a map key. Keys of items without
 * NBT are cached per item, so looking those up does not allocate.
 */
public final class ItemKey {
	private static final Map<Item, ItemKey> PLAIN = new ConcurrentHashMap<>();
	public static final ItemKey BLANK = new ItemKey(Items.AIR, null);

	private final Item item;
	@Nullable
	private final CompoundTag tag;
	private final int hash;

	private ItemKey(Item item, @Nullable CompoundTag tag) {
		this.item = item;
		this.tag = tag;
		this.hash = 31 * System.identityHashCode(item) + (tag != null ? tag.hashCode() : 0);
	}

	/** Key of {@code stack}; the tag is copied. */
	public static ItemKey of(ItemStack stack) {
		if (stack.isEmpty())return BLANK;
		CompoundTag t = stack.getTag();
		if (t == null || t.isEmpty())return of(stack.getItem());
		return new ItemKey(stack.getItem(), t.copy());
	}

	public static ItemKey of(Item item) {
		if (item == Items.AIR)return BLANK;
		return PLAIN.computeIfAbsent(item, i -> new ItemKey(i, null));
	}

	/**
	 * A key that shares the stack's live tag. Only for lookups that end before the stack can
	 * change; never store it.
	 */
	static ItemKey probe(ItemStack stack) {
		if (stack.isEmpty())return BLANK;
		CompoundTag t = stack.getTag();
		if (t == null || t.isEmpty())return of(stack.getItem());
		return new ItemKey(stack.getItem(), t);
	}

	/** An owning copy of a probe key. */
	ItemKey own() {
		if (tag == null)return this;
		return new ItemKey(item, tag.copy());
	}

	public boolean isBlank() {
		return item == Items.AIR;
	}

	public Item getItem() {
		return item;
	}

	@Nullable
	public CompoundTag getTag() {
		return tag;
	}

	public boolean isOf(Item item) {
		return this.item == item;
	}

	/** Whether the tag equals {@code other} (null and empty are the same). */
	public boolean nbtMatches(@Nullable CompoundTag other) {
		if (other != null && other.isEmpty())other = null;
		return Objects.equals(tag, other);
	}

	/** Same item and same tag as {@code stack}. */
	public boolean matches(ItemStack stack) {
		if (stack.isEmpty())return isBlank();
		return stack.getItem() == item && nbtMatches(stack.getTag());
	}

	public ItemStack toStack() {
		return toStack(1);
	}

	public ItemStack toStack(int count) {
		if (isBlank())return ItemStack.EMPTY;
		ItemStack s = new ItemStack(item, count);
		if (tag != null)s.setTag(tag.copy());
		return s;
	}

	@Override
	public int hashCode() {
		return hash;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)return true;
		if (!(obj instanceof ItemKey o))return false;
		return hash == o.hash && item == o.item && Objects.equals(tag, o.tag);
	}

	@Override
	public String toString() {
		return "ItemKey[" + item + (tag != null ? tag : "") + "]";
	}
}
