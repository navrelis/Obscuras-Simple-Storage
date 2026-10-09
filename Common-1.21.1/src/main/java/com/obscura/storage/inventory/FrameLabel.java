package com.obscura.storage.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.filter.ItemFilter;
import com.obscura.storage.inventory.filter.ItemPredicate;
import com.obscura.storage.util.Priority;

/**
 * What the glow item frames on one inventory hold (Frame Labels): the union of the framed item
 * types, components ignored, plus the filters of any filter items (Item, Polymorphic, Tag or
 * Condition Filter) put into a frame. Immutable; a changed frame makes a new label.
 *
 * <p>A label only decides what the network may INSERT into the inventory, and that the inventory is
 * filled first ({@link #PRIORITY}). Extraction and what the network sees are unchanged.
 */
public final class FrameLabel implements ItemPredicate {
	/** Labelled inventories are filled before every unlabelled one. */
	public static final Priority PRIORITY = Priority.HIGHEST;

	private final Set<Item> items;
	private final List<ItemStack> filterStacks;
	private final List<ItemFilter> filters;
	private final long version = ItemPredicate.nextStateVersion();

	/**
	 * @param items framed item types
	 * @param filterStacks framed filter items, see {@link #isFilterItem}
	 * @param filterFactory makes the filter of one filter item
	 */
	public FrameLabel(Set<Item> items, List<ItemStack> filterStacks, Function<ItemStack, ItemFilter> filterFactory) {
		this.items = Set.copyOf(items);
		List<ItemStack> st = new ArrayList<>(filterStacks.size());
		List<ItemFilter> fl = new ArrayList<>(filterStacks.size());
		for (ItemStack s : filterStacks) {
			st.add(s.copy());
			ItemFilter f = filterFactory.apply(s);
			if (f != null)fl.add(f);
		}
		this.filterStacks = List.copyOf(st);
		this.filters = List.copyOf(fl);
	}

	/** Whether a framed item works as a filter instead of as its item type. */
	public static boolean isFilterItem(ItemStack stack) {
		return stack.getItem() instanceof com.obscura.storage.item.IItemFilter;
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

	public boolean isEmpty() {
		return items.isEmpty() && filters.isEmpty();
	}

	@Override
	public boolean test(StoredItemStack stack) {
		if (items.contains(stack.getStack().getItem()))return true;
		for (int i = 0; i < filters.size(); i++) {
			if (filters.get(i).test(stack))return true;
		}
		return false;
	}

	@Override
	public boolean test(ItemStack stack) {
		if (stack.isEmpty())return false;
		if (items.contains(stack.getItem()))return true;
		for (int i = 0; i < filters.size(); i++) {
			if (filters.get(i).test(stack))return true;
		}
		return false;
	}

	@Override
	public void updateState() {
		for (int i = 0; i < filters.size(); i++)filters.get(i).updateState();
	}

	/** Own version plus the filters' (see {@code MultiFilter}): moves when a framed filter changes its answers. */
	@Override
	public long stateVersion() {
		long v = version;
		for (int i = 0; i < filters.size(); i++) {
			long p = filters.get(i).stateVersion();
			if (p == UNVERSIONED)return UNVERSIONED;
			v += p;
		}
		return v;
	}

	@Override
	public String toString() {
		return "FrameLabel" + items + (filterStacks.isEmpty() ? "" : " + " + filterStacks);
	}

	/**
	 * An inventory access carrying a frame label. The member identity rules of the network (double
	 * chest halves, sided faces) look through it to {@link #getLabelledInventory()}.
	 */
	public interface Labelled {
		IInventoryAccess getLabelledInventory();

		FrameLabel getLabel();
	}

	/**
	 * The change tracker of a labelled inventory: the wrapped inventory's own, except that it offers
	 * no destination for items outside the label. Hoppers and {@code pushStack} look for destinations
	 * through here; everything they list and extract is untouched.
	 */
	public static final class DestTracker extends IInventoryChangeTracker.Delegate {
		private final FrameLabel label;

		public DestTracker(FrameLabel label) {
			this.label = label;
		}

		@Override
		public InventorySlot findSlotDest(StoredItemStack forStack) {
			return label.test(forStack) ? super.findSlotDest(forStack) : null;
		}

		@Override
		public InventorySlot findSlotDestAfter(InventorySlot slot, StoredItemStack forStack, boolean loop) {
			return label.test(forStack) ? super.findSlotDestAfter(slot, forStack, loop) : null;
		}
	}
}
