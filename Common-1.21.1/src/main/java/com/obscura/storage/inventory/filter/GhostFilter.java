package com.obscura.storage.inventory.filter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.migration.LegacyData;

/**
 * The 3x3 ghost filter of the Ejector, Disposal and Exporter. An entry is a reference stack of count 1,
 * never a real item. A stack matches when ANY entry matches it:
 * <ul>
 * <li>a plain item matches by item only (durability, enchantments, names and all other components
 * ignored), or with {@link #isExact() Exact} on only the same item with the same components;</li>
 * <li>an entry whose item is an {@link IItemFilter} (Item, Tag, Condition, Polymorphic Filter) acts as
 * that filter, Exact does not change it.</li>
 * </ul>
 * An empty filter matches nothing.
 */
public class GhostFilter implements ItemPredicate {
	public static final int SLOTS = 9;
	private static final String SLOT_TAG = "Slot";

	/** What {@link #test} reads. Immutable, see {@link #compiled}. */
	private static record Compiled(Set<Item> items, List<ItemStack> exact, List<ItemPredicate> filters) {
		static final Compiled NOTHING = new Compiled(Set.of(), List.of(), List.of());
	}

	private final NonNullList<ItemStack> stacks = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
	private final Function<ItemStack, ItemPredicate> filterFactory;
	private boolean exact;
	/**
	 * Built on the server thread only ({@link #updateState()}) and published as an immutable value,
	 * like SimpleItemFilter's compiled set: test() may run while a tracker works off-thread and must
	 * never build it.
	 */
	private volatile Compiled compiled = Compiled.NOTHING;
	/** Set by every edit, so updateState() only recompiles after one. */
	private volatile boolean dirty;
	private volatile long stateVersion = ItemPredicate.nextStateVersion();

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
		for (ItemStack s : stacks) {
			if (s.isEmpty())continue;
			if (s.getItem() instanceof IItemFilter) {
				ItemPredicate p = filterFactory != null ? filterFactory.apply(s) : null;
				if (p != null)filters.add(p);
			} else if (exact) {
				exactStacks.add(s);
			} else {
				items.add(s.getItem());
			}
		}
		compiled = new Compiled(Set.copyOf(items), List.copyOf(exactStacks), List.copyOf(filters));
		stateVersion = ItemPredicate.nextStateVersion();
	}

	/** Server thread, before each batch of {@link #test} calls: recompiles after an edit and updates the filter items. */
	@Override
	public void updateState() {
		if (dirty) {
			dirty = false;
			compile();
		}
		List<ItemPredicate> filters = compiled.filters();
		for (int i = 0; i < filters.size(); i++)filters.get(i).updateState();
	}

	@Override
	public long stateVersion() {
		long v = stateVersion;
		for (ItemPredicate p : compiled.filters()) {
			long s = p.stateVersion();
			if (s == UNVERSIONED)return UNVERSIONED;
			v += s;
		}
		return v;
	}

	@Override
	public boolean test(StoredItemStack stack) {
		Compiled c = compiled;
		if (testPlain(c, stack.getStack()))return true;
		List<ItemPredicate> filters = c.filters();
		for (int i = 0; i < filters.size(); i++) {
			if (filters.get(i).test(stack))return true;
		}
		return false;
	}

	@Override
	public boolean test(ItemStack stack) {
		Compiled c = compiled;
		if (testPlain(c, stack))return true;
		List<ItemPredicate> filters = c.filters();
		for (int i = 0; i < filters.size(); i++) {
			if (filters.get(i).test(stack))return true;
		}
		return false;
	}

	private static boolean testPlain(Compiled c, ItemStack stack) {
		if (stack.isEmpty())return false;
		if (c.items().contains(stack.getItem()))return true;
		List<ItemStack> exact = c.exact();
		for (int i = 0; i < exact.size(); i++) {
			if (ItemStack.isSameItemSameComponents(stack, exact.get(i)))return true;
		}
		return false;
	}

	/** Writes the entries as a list of stack compounds with a byte {@code Slot}, like a vanilla container. */
	public ListTag save(HolderLookup.Provider provider) {
		ListTag list = new ListTag();
		for (int i = 0; i < SLOTS; i++) {
			ItemStack s = stacks.get(i);
			if (s.isEmpty())continue;
			CompoundTag e = new CompoundTag();
			e.putByte(SLOT_TAG, (byte) i);
			list.add(s.save(provider, e));
		}
		return list;
	}

	/** Reads what {@link #save} wrote. LegacyData: a filter saved by the 1.20.1 builds holds 1.20.1 item stacks. */
	public void load(ListTag list, HolderLookup.Provider provider) {
		for (int i = 0; i < SLOTS; i++)stacks.set(i, ItemStack.EMPTY);
		for (int i = 0; i < list.size(); i++) {
			if (list.getElementType() != Tag.TAG_COMPOUND)break;
			CompoundTag e = list.getCompound(i);
			int slot = e.getByte(SLOT_TAG) & 255;
			if (slot >= SLOTS)continue;
			ItemStack s = LegacyData.parseItemStack(provider, e);
			if (!s.isEmpty())stacks.set(slot, s.copyWithCount(1));
		}
		markDirty();
	}
}
