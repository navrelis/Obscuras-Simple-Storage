package com.obscura.storage.inventory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

import com.obscura.storage.inventory.IInventoryAccess.IChangeNotifier;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.IInventoryAccess.IMultiThreadedTracker;
import com.obscura.storage.inventory.filter.ItemPredicate;

/**
 * Remembers what each slot of an {@link IItemHandler} held at the last scan and reports when that
 * changes. NeoForge twin of the Fabric tracker; the handler is index based, so the per-slot state is
 * an array indexed like the handler.
 *
 * <p>Compared to the original NeoForge tracker (same intent as the Fabric twin):
 * <ul>
 * <li>{@link #countItems} reads a per-item total kept in step with every slot change
 * ({@link #adjustCount}) instead of walking every slot;</li>
 * <li>the off-thread pass never writes shared state: it returns replacement entries that
 * {@link #finishOffThreadProcess} applies on the server thread;</li>
 * <li>a handler that shrinks drops (and reports) the entries of the vanished slots, and a new handler
 * behind the same access is diffed against the old contents ({@link #refresh});</li>
 * <li>{@link #owns} and continuing a search after a slot work for filtered handlers too.</li>
 * </ul>
 */
public class InventoryChangeTracker implements IInventoryChangeTracker, IMultiThreadedTracker<InventoryChangeTracker.Snapshot, InventoryChangeTracker.Diff>, IChangeNotifier {
	public static final InventoryChangeTracker NULL = new InventoryChangeTracker(null);
	private static final StoredItemStack TOMBSTONE = new StoredItemStack(ItemStack.EMPTY, 0);

	// Weak: WorldStates.trackers maps handler -> tracker with weak keys, a strong reference back would
	// keep every handler (and tracker) alive forever.
	private volatile WeakReference<IItemHandler> itemHandler;
	private long lastUpdate, lastChange;
	/** What each slot held at the last scan (null = empty or filtered out). Written on the server thread only. */
	private StoredItemStack[] lastItems = new StoredItemStack[0];
	/** Total per item (count ignored in the key) over {@link #lastItems}. */
	private final Map<StoredItemStack, Long> itemCounts = new ConcurrentHashMap<>();
	/**
	 * The container face the handler shows, when it is a sided wrapper whose container is known: slot
	 * {@code i} of the handler is container slot {@code face.slots()[i]}. Lets a network leave out the
	 * slots two faces of one block share (see {@link #excludedSlots}). Null for everything else.
	 */
	private volatile PlatformMultiInventoryAccess.Face face;

	public InventoryChangeTracker(IItemHandler itemHandler) {
		this.itemHandler = new WeakReference<>(itemHandler);
	}

	private IItemHandler handler() {
		return itemHandler.get();
	}

	/**
	 * Points this tracker at the handler currently behind its access (a capability can be re-created,
	 * e.g. when a chest becomes a double chest). The remembered contents are kept: the next scan diffs
	 * the new handler against them slot by slot and reports exactly what differs.
	 */
	public void refresh(IItemHandler h) {
		refresh(h, null);
	}

	/** {@link #refresh(IItemHandler)}, also telling which container face {@code h} is (see {@link #face}). */
	void refresh(IItemHandler h, PlatformMultiInventoryAccess.Face face) {
		if (handler() != h)itemHandler = new WeakReference<>(h);
		this.face = face;
	}

	@Override
	public long getChangeTracker(Level level) {
		IItemHandler h = handler();
		if (h == null)return 0L;
		if (lastUpdate != level.getGameTime()) {
			int slots = h.getSlots();
			boolean change = resize(slots);
			for (int i = 0;i<slots;i++) {
				change |= updateChange(i, h.getStackInSlot(i));
			}
			if (change)lastChange = System.nanoTime();
			lastUpdate = level.getGameTime();
		}
		return lastChange;
	}

	/** Makes {@link #lastItems} {@code slots} long; returns whether tracked entries were dropped. */
	private boolean resize(int slots) {
		if (lastItems.length == slots)return false;
		boolean change = false;
		for (int i = slots; i < lastItems.length; i++) {
			StoredItemStack li = lastItems[i];
			if (li != null) {
				adjustCount(li, -li.getQuantity());
				change = true;
			}
		}
		lastItems = Arrays.copyOf(lastItems, slots);
		return change;
	}

	protected boolean checkFilter(ItemStack stack) {
		return true;
	}

	protected boolean checkFilter(StoredItemStack stack) {
		return true;
	}

	protected int getCount(ItemStack is) {
		return is.getCount();
	}

	protected IItemHandler getSlotHandler(IItemHandler def) {
		return def;
	}

	/** Server thread only. */
	private boolean updateChange(int i, ItemStack is) {
		StoredItemStack li = lastItems[i];
		if (!is.isEmpty() && checkFilter(is)) {
			int cnt = getCount(is);
			if (li == null || !ItemStack.isSameItemSameComponents(li.getStack(), is)) {
				StoredItemStack n = new StoredItemStack(is);
				n.setCount(cnt);
				lastItems[i] = n;
				if (li != null)adjustCount(li, -li.getQuantity());
				adjustCount(n, cnt);
				return true;
			} else if(li.getQuantity() != cnt) {
				adjustCount(li, cnt - li.getQuantity());
				li.setCount(cnt);
				return true;
			}
		} else if (li != null) {
			lastItems[i] = null;
			adjustCount(li, -li.getQuantity());
			return true;
		}
		return false;
	}

	/** Adds {@code delta} to the tracked total of {@code stack}'s item and drops the key once it is back at 0. */
	private void adjustCount(StoredItemStack stack, long delta) {
		if (delta == 0)return;
		itemCounts.compute(stack, (k, v) -> {
			long n = (v != null ? v : 0L) + delta;
			return n != 0 ? n : null;
		});
	}

	@Override
	public Stream<StoredItemStack> streamWrappedStacks(boolean parallel) {
		IItemHandler h = handler();
		if (h == null)return Stream.empty();
		return Arrays.stream(lastItems).filter(Objects::nonNull);
	}

	@Override
	public long countItems(StoredItemStack filter) {
		IItemHandler h = handler();
		if (h == null)return 0;
		Long c = itemCounts.get(filter);
		return c != null ? c : 0L;
	}

	/**
	 * Which of this tracker's slots are in {@code exclude}, or null when none is. The slots of a sided
	 * handler stand for container slots ({@link PlatformMultiInventoryAccess.SlotRef}); every other
	 * handler has no shared slots and ignores {@code exclude}.
	 */
	private boolean[] excludedSlots(Set<?> exclude) {
		PlatformMultiInventoryAccess.Face f = face;
		if (f == null || exclude.isEmpty())return null;
		int[] slots = f.slots();
		boolean[] skip = null;
		for (int i = 0; i < slots.length; i++) {
			if (exclude.contains(PlatformMultiInventoryAccess.slotRef(f.container(), slots[i]))) {
				if (skip == null)skip = new boolean[slots.length];
				skip[i] = true;
			}
		}
		return skip;
	}

	@Override
	public Stream<StoredItemStack> streamWrappedStacks(Set<?> exclude) {
		IItemHandler h = handler();
		if (h == null)return Stream.empty();
		boolean[] skip = excludedSlots(exclude);
		StoredItemStack[] items = lastItems;
		if (skip == null)return Arrays.stream(items).filter(Objects::nonNull);
		return IntStream.range(0, items.length).filter(i -> i >= skip.length || !skip[i]).mapToObj(i -> items[i]).filter(Objects::nonNull);
	}

	@Override
	public long countItems(StoredItemStack filter, Set<?> exclude) {
		long c = countItems(filter);
		if (c == 0)return 0;
		IItemHandler h = handler();
		if (h == null)return 0;
		boolean[] skip = excludedSlots(exclude);
		if (skip == null)return c;
		StoredItemStack[] items = lastItems;
		for (int i = 0; i < skip.length && i < items.length; i++) {
			StoredItemStack s = items[i];
			if (skip[i] && s != null && s.equalItem(filter))c -= s.getQuantity();
		}
		return c;
	}

	@Override
	public boolean owns(InventorySlot slot) {
		return slot != null && slot.getNotifier() == this;
	}

	@Override
	public InventorySlot findSlot(ItemPredicate filter, boolean findEmpty) {
		return findSlotFrom(0, filter, findEmpty);
	}

	private InventorySlot findSlotFrom(int start, ItemPredicate filter, boolean findEmpty) {
		IItemHandler h = handler();
		if (h == null)return null;
		StoredItemStack[] items = lastItems;
		for (int i = start; i < items.length; i++) {
			StoredItemStack is = items[i];
			if (is == null) {
				if (findEmpty)return new InventorySlot(getSlotHandler(h), this, i);
				continue;
			}
			if (is.getQuantity() < 1)continue;// keep-last: nothing to take here
			if (filter.test(is))return new InventorySlot(getSlotHandler(h), this, i);
		}
		return null;
	}

	/**
	 * Continues after {@code slot} when this tracker handed it out; a slot of another member of a
	 * network means "search this inventory from its beginning" (see MultiInventoryAccess). Loops back to
	 * the first match only when asked to.
	 */
	@Override
	public InventorySlot findSlotAfter(InventorySlot slot, ItemPredicate filter, boolean findEmpty, boolean loop) {
		if (slot == null)return findSlot(filter, findEmpty);
		int start = owns(slot) ? slot.getId() + 1 : 0;
		InventorySlot s = findSlotFrom(start, filter, findEmpty);
		if (s == null && loop && start > 0)return findSlot(filter, findEmpty);
		return s;
	}

	@Override
	public InventorySlot findSlotDest(StoredItemStack forStack) {
		return findSlotDestFrom(0, forStack);
	}

	private InventorySlot findSlotDestFrom(int start, StoredItemStack forStack) {
		IItemHandler h = handler();
		if (h == null)return null;
		if (!checkFilter(forStack))return null;
		StoredItemStack[] items = lastItems;
		int slots = Math.min(items.length, h.getSlots());
		for (int i = start; i < slots; i++) {
			StoredItemStack is = items[i];
			if (is == null && !h.isItemValid(i, forStack.getStack())) {
				continue;
			}
			if (is == null || is.equalItem(forStack)) {
				int rem = h.insertItem(i, forStack.getActualStack(), true).getCount();
				if (rem < forStack.getQuantity()) {
					return new InventorySlot(getSlotHandler(h), this, i);
				}
			}
		}
		return null;
	}

	@Override
	public InventorySlot findSlotDestAfter(InventorySlot slot, StoredItemStack forStack, boolean loop) {
		if (slot == null)return findSlotDest(forStack);
		int start = owns(slot) ? slot.getId() + 1 : 0;
		InventorySlot s = findSlotDestFrom(start, forStack);
		if (s == null && loop && start > 0)return findSlotDest(forStack);
		return s;
	}

	@Override
	public void onSlotChanged(InventorySlot slot) {
		// Read the raw handler: the slot's own handler may be a filtered view that already applied
		// keep-last, and updateChange applies it (getCount) again.
		IItemHandler h = handler();
		if (h == null || !owns(slot))return;
		int id = slot.getId();
		if (id < lastItems.length && id < h.getSlots() && updateChange(id, h.getStackInSlot(id))) {
			lastChange = System.nanoTime();
		}
	}

	// ---- Off-thread processing: prep (server thread) -> process (worker) -> finish (server thread) ----

	/** The slot contents read on the server thread. */
	public static record Snapshot(ItemStack[] items, StoredItemStack[] last) {}

	/** Replacement entries by slot index ({@link #TOMBSTONE} = now empty), computed off-thread. */
	public static record Diff(int[] slots, StoredItemStack[] values) {}

	@Override
	public Snapshot prepForOffThread(Level level) {
		if (lastUpdate == level.getGameTime())return null;
		IItemHandler h = handler();
		if (h == null)return null;
		int slots = h.getSlots();
		if (resize(slots))lastChange = System.nanoTime();
		ItemStack[] items = new ItemStack[slots];
		for (int i = 0;i<slots;i++) {
			items[i] = h.getStackInSlot(i);
		}
		return new Snapshot(items, lastItems);
	}

	/** Worker thread: reads the snapshot and the (unchanging during this pass) last state, writes nothing shared. */
	@Override
	public Diff processOffThread(Snapshot s) {
		ItemStack[] items = s.items();
		StoredItemStack[] last = s.last();
		List<Integer> slots = null;
		List<StoredItemStack> values = null;
		for (int i = 0;i<items.length && i < last.length;i++) {
			StoredItemStack v = diff(last[i], items[i]);
			if (v != null) {
				if (slots == null) {
					slots = new ArrayList<>();
					values = new ArrayList<>();
				}
				slots.add(i);
				values.add(v);
			}
		}
		if (slots == null)return new Diff(new int[0], new StoredItemStack[0]);
		return new Diff(slots.stream().mapToInt(Integer::intValue).toArray(), values.toArray(StoredItemStack[]::new));
	}

	/** The new entry for a slot, {@link #TOMBSTONE} when it got empty, or null when unchanged. */
	private StoredItemStack diff(StoredItemStack li, ItemStack is) {
		if (!is.isEmpty() && checkFilter(is)) {
			int cnt = getCount(is);
			if (li == null || !ItemStack.isSameItemSameComponents(li.getStack(), is)) {
				StoredItemStack n = new StoredItemStack(is);
				n.setCount(cnt);
				return n;
			} else if(li.getQuantity() != cnt) {
				// Never mutate li here: it is shared with the server thread.
				StoredItemStack n = new StoredItemStack(li);
				n.setCount(cnt);
				return n;
			}
			return null;
		}
		return li != null ? TOMBSTONE : null;
	}

	@Override
	public long finishOffThreadProcess(Level level, Diff ct) {
		if (ct == null)return lastChange;
		int[] slots = ct.slots();
		StoredItemStack[] values = ct.values();
		boolean change = false;
		for (int k = 0; k < slots.length; k++) {
			int i = slots[k];
			if (i >= lastItems.length)continue;
			StoredItemStack old = lastItems[i];
			if (old != null)adjustCount(old, -old.getQuantity());
			if (values[k] == TOMBSTONE) {
				lastItems[i] = null;
			} else {
				lastItems[i] = values[k];
				adjustCount(values[k], values[k].getQuantity());
			}
			change = true;
		}
		if (change)lastChange = System.nanoTime();
		lastUpdate = level.getGameTime();
		return lastChange;
	}
}
