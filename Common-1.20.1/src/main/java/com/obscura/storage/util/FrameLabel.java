package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.item.IItemFilter;

/**
 * What the glow item frames on one inventory hold (Frame Labels): the union of the framed item
 * types, NBT ignored, plus the filters of any filter items (Item, Polymorphic, Tag or Condition
 * Filter) put into a frame. Immutable; a changed frame makes a new label.
 *
 * <p>A label only decides what the network may INSERT into the inventory, and that the inventory is
 * filled first ({@link #PRIORITY}). Extraction and what the network sees are unchanged.
 */
public final class FrameLabel {
	/** Labelled inventories are filled before every unlabelled one. */
	public static final Priority PRIORITY = Priority.HIGHEST;

	private final Set<Item> items;
	private final List<ItemStack> filterStacks;
	private final List<ItemPredicate> filters;

	/**
	 * @param items framed item types
	 * @param filterStacks framed filter items, see {@link #isFilterItem}
	 * @param filterFactory makes the filter of one filter item (null: none)
	 */
	public FrameLabel(Set<Item> items, List<ItemStack> filterStacks, Function<ItemStack, ItemPredicate> filterFactory) {
		this.items = Set.copyOf(items);
		List<ItemStack> st = new ArrayList<>(filterStacks.size());
		List<ItemPredicate> fl = new ArrayList<>(filterStacks.size());
		for (ItemStack s : filterStacks) {
			st.add(s.copy());
			ItemPredicate f = filterFactory.apply(s);
			if (f != null)fl.add(f);
		}
		this.filterStacks = List.copyOf(st);
		this.filters = List.copyOf(fl);
	}

	/** Whether a framed item works as a filter instead of as its item type. */
	public static boolean isFilterItem(ItemStack stack) {
		return stack.getItem() instanceof IItemFilter;
	}

	/**
	 * Whether this label was made from exactly these frame contents, so the one already in use can
	 * be kept (and with it the network's member list, which would otherwise report a change).
	 */
	public boolean sameContent(Set<Item> items, List<ItemStack> filterStacks) {
		if (!this.items.equals(items) || this.filterStacks.size() != filterStacks.size())return false;
		return containsAll(this.filterStacks, filterStacks) && containsAll(filterStacks, this.filterStacks);
	}

	private static boolean containsAll(Collection<ItemStack> in, Collection<ItemStack> what) {
		outer:
		for (ItemStack w : what) {
			for (ItemStack s : in) {
				if (ItemStack.matches(s, w))continue outer;
			}
			return false;
		}
		return true;
	}

	public Set<Item> items() {
		return items;
	}

	public List<ItemStack> filterStacks() {
		return filterStacks;
	}

	/** Whether the label has filter items, which need the full stack to decide. */
	public boolean hasFilters() {
		return !filters.isEmpty();
	}

	public boolean isEmpty() {
		return items.isEmpty() && filters.isEmpty();
	}

	/** Whether the network may insert {@code stack} into the labelled inventory. */
	public boolean test(ItemStack stack) {
		if (stack.isEmpty())return false;
		if (items.contains(stack.getItem()))return true;
		for (int i = 0; i < filters.size(); i++) {
			if (filters.get(i).testStack(stack))return true;
		}
		return false;
	}

	@Override
	public String toString() {
		return "FrameLabel" + items + (filterStacks.isEmpty() ? "" : " + " + filterStacks);
	}

	/**
	 * A network member carrying a frame label (the loaders' {@code LabelledStorage}). The Network
	 * Doctor and the identity rules look through it to {@link #getLabelledStorage()}.
	 */
	public interface Labelled {
		/** The loader storage behind the label. */
		Object getLabelledStorage();

		FrameLabel getLabel();
	}
}
