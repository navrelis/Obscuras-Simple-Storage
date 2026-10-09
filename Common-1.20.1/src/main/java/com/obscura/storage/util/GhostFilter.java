package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.item.IItemFilter;

/**
 * The 3x3 ghost filter of the Ejector, Disposal and Exporter. An entry is a reference stack of count 1,
 * never a real item. A stack matches when ANY entry matches it:
 * <ul>
 * <li>a plain item matches by item only (durability, enchantments, names and all other NBT ignored),
 * or with {@link #isExact() Exact} on only the same item with the same NBT (an empty tag is the same
 * as none);</li>
 * <li>an entry whose item is an {@link IItemFilter} (Item, Tag, Condition, Polymorphic Filter) acts as
 * that filter, Exact does not change it.</li>
 * </ul>
 * An empty filter matches nothing. Server thread only.
 */
public class GhostFilter {
	public static final int SLOTS = 9;
	private static final String SLOT_TAG = "Slot";

	/** What {@link #test} reads, rebuilt by {@link #updateState()} after an edit. */
	private static record Compiled(Set<Item> items, List<ItemStack> exact, List<ItemPredicate> filters, boolean stable) {
		static final Compiled NOTHING = new Compiled(Set.of(), List.of(), List.of(), true);
	}

	private final NonNullList<ItemStack> stacks = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
	private final Function<ItemStack, ItemPredicate> filterFactory;
	private boolean exact;
	private Compiled compiled = Compiled.NOTHING;
	/** Set by every edit, so updateState() only recompiles after one. */
	private boolean dirty;
	private long stateVersion = 1;

	/**
	 * @param filterFactory makes the predicate of an entry whose item is an {@link IItemFilter}; called
	 *        from {@link #updateState()} only. Null results are ignored (the entry matches nothing).
	 */
	public GhostFilter(Function<ItemStack, ItemPredicate> filterFactory) {
		this.filterFactory = filterFactory;
	}

	public ItemStack getStack(int slot) {
		return slot >= 0 && slot < SLOTS ? stacks.get(slot) : ItemStack.EMPTY;
	}

	/** Stores a copy of {@code stack} with count 1 (or clears the entry). */
	public void setStack(int slot, ItemStack stack) {
		if (slot < 0 || slot >= SLOTS)return;
		stacks.set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
		markDirty();
	}

	public boolean isExact() {
		return exact;
	}

	public void setExact(boolean exact) {
		if (this.exact == exact)return;
		this.exact = exact;
		markDirty();
	}

	/** No entry at all: nothing matches. */
	public boolean isEmpty() {
		for (ItemStack s : stacks) {
			if (!s.isEmpty())return false;
		}
		return true;
	}

	/** Whether the entry in {@code slot} is a plain item (matched by item, or exactly), not a filter item. */
	public boolean isPlainEntry(int slot) {
		ItemStack s = getStack(slot);
		return !s.isEmpty() && !(s.getItem() instanceof IItemFilter);
	}

	/** The compiled state must be rebuilt, e.g. the block was rotated and a filter item reads the block in front. */
	public void markDirty() {
		dirty = true;
	}

	private void compile() {
		Set<Item> items = new HashSet<>();
		List<ItemStack> exactStacks = new ArrayList<>();
		List<ItemPredicate> filters = new ArrayList<>();
		boolean stable = true;
		for (ItemStack s : stacks) {
			if (s.isEmpty())continue;
			if (s.getItem() instanceof IItemFilter) {
				ItemPredicate p = filterFactory != null ? filterFactory.apply(s) : null;
				if (p == null)continue;
				filters.add(p);
				// A Polymorphic Filter follows the inventory it looks at
				if (p instanceof PolyFilter)stable = false;
			} else if (exact) {
				exactStacks.add(s);
			} else {
				items.add(s.getItem());
			}
		}
		compiled = new Compiled(Set.copyOf(items), List.copyOf(exactStacks), List.copyOf(filters), stable);
		stateVersion++;
	}

	/** Before each batch of {@link #test} calls: recompiles after an edit. */
	public void updateState() {
		if (dirty) {
			dirty = false;
			compile();
		}
	}

	/** Changes whenever {@link #updateState()} rebuilt what {@link #test} reads. */
	public long stateVersion() {
		return stateVersion;
	}

	/**
	 * Whether the same stack always gets the same answer until the next edit. False with an entry
	 * whose answers follow something else (the Polymorphic Filter): results must not be remembered then.
	 */
	public boolean isStable() {
		return compiled.stable();
	}

	public boolean test(ItemStack stack) {
		if (stack.isEmpty())return false;
		if (testPlain(stack.getItem(), stack.getTag()))return true;
		List<ItemPredicate> filters = compiled.filters();
		for (int i = 0; i < filters.size(); i++) {
			if (filters.get(i).testStack(stack))return true;
		}
		return false;
	}

	/**
	 * The plain entries only, for callers that hold an item and its NBT instead of a stack (item
	 * variants, the item totals of a network) and ask the {@link #filterEntries() filter items}
	 * themselves. An empty tag is the same as none.
	 */
	public boolean testPlain(Item item, @Nullable CompoundTag tag) {
		Compiled c = compiled;
		if (c.items().contains(item))return true;
		List<ItemStack> ex = c.exact();
		for (int i = 0; i < ex.size(); i++) {
			ItemStack e = ex.get(i);
			if (e.getItem() == item && Objects.equals(orNull(e.getTag()), orNull(tag)))return true;
		}
		return false;
	}

	private static CompoundTag orNull(CompoundTag tag) {
		return tag == null || tag.isEmpty() ? null : tag;
	}

	/** The predicates of the entries that are filter items, do not modify. */
	public List<ItemPredicate> filterEntries() {
		return compiled.filters();
	}

	/** Writes the entries as a list of stack compounds with a byte {@code Slot}, like a vanilla container. */
	public ListTag save() {
		ListTag list = new ListTag();
		for (int i = 0; i < SLOTS; i++) {
			ItemStack s = stacks.get(i);
			if (s.isEmpty())continue;
			CompoundTag e = new CompoundTag();
			e.putByte(SLOT_TAG, (byte) i);
			list.add(s.save(e));
		}
		return list;
	}

	/** Reads what {@link #save} wrote. */
	public void load(ListTag list) {
		for (int i = 0; i < SLOTS; i++)stacks.set(i, ItemStack.EMPTY);
		for (int i = 0; i < list.size(); i++) {
			if (list.getElementType() != Tag.TAG_COMPOUND)break;
			CompoundTag e = list.getCompound(i);
			int slot = e.getByte(SLOT_TAG) & 255;
			if (slot >= SLOTS)continue;
			ItemStack s = ItemStack.of(e);
			if (!s.isEmpty())stacks.set(slot, s.copyWithCount(1));
		}
		markDirty();
	}
}
