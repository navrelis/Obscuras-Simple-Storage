package com.obscura.storage.inventory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.obscura.storage.inventory.IInventoryAccess.IChangeNotifier;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.IInventoryAccess.IMultiThreadedTracker;
import com.obscura.storage.inventory.InventoryImage.FabricStack;
import com.obscura.storage.inventory.InventoryImage.InvState;
import com.obscura.storage.inventory.InventoryImage.SlotMemo;
import com.obscura.storage.inventory.filter.ItemPredicate;

public class InventoryChangeTracker implements IInventoryChangeTracker, IChangeNotifier, IMultiThreadedTracker<InventoryImage, InvState> {
	public static final InventoryChangeTracker NULL = new InventoryChangeTracker(null);
	/** In {@link InvState#memo()}: drop the slot's memo entry. */
	static final SlotMemo NO_MEMO = new SlotMemo(null, Long.MIN_VALUE);
	private Storage<ItemVariant> storage;
	private long lastUpdate, lastVersion, lastChange;
	private boolean initialized;
	/**
	 * The exact Storage instance {@link #lastVersion} was read from. Fabric's
	 * {@code Storage#getVersion()} contract only allows comparing two versions when they came from
	 * the same instance, and for a vanilla double chest {@code ItemStorage.SIDED} hands out a new
	 * wrapper every lookup whose inherited {@code getVersion()} is a global ever-increasing
	 * counter. Comparing across instances is therefore meaningless; when the instance differs we
	 * use {@link #lastDerivedVersion} if the wrapper has one, and rescan the contents otherwise,
	 * which correctly reports "no change" because {@code lastItems} is keyed by the underlying
	 * (stable) slot views.
	 */
	private Storage<ItemVariant> lastVersionStorage;
	/** Version derived from the stable parts of a per-lookup wrapper, see {@link StorageVersion}. */
	private StorageVersion lastDerivedVersion;
	/** {@link #filterStamp()} at the last scan: a filter change must rescan even an unchanged storage. */
	private long lastFilterStamp;
	/** The Storage instance (and its versions) the pending off-thread image was taken from. */
	private volatile Storage<ItemVariant> offThreadStorage;
	private volatile StorageVersion offThreadDerivedVersion;
	private volatile long offThreadFilterStamp;
	private volatile boolean offThreadUseMemo;
	private Map<StorageView<ItemVariant>, StoredItemStack> lastItems = new ConcurrentHashMap<>();
	private final Map<StoredItemStack, Long> itemCounts = new ConcurrentHashMap<>();
	/**
	 * What every non-empty slot held at the last scan (item variant and raw amount), whether or not
	 * the filter accepted it. A rescan that finds the same variant and amount knows the slot is
	 * unchanged without creating an ItemStack or asking the filter again, which makes polling an
	 * idle vanilla inventory allocation-free. Only valid for one {@link #filterStamp()}: cleared
	 * when it changes, and not used at all for filters that cannot tell when they change.
	 */
	private final Map<StorageView<ItemVariant>, SlotMemo> memo = new ConcurrentHashMap<>();
	private long memoStamp;

	public InventoryChangeTracker(Storage<ItemVariant> itemHandler) {
		storage = itemHandler;
	}

	@Override
	public long getChangeTracker(Level level) {
		Storage<ItemVariant> h = storage;
		if (h == null)return 0L;
		long now = level.getGameTime();
		if (lastUpdate != now) {
			long fs = filterStamp();
			long ver = h.getVersion();
			StorageVersion dv = StorageVersion.of(h);
			if (!isUnchanged(h, ver, dv, fs)) {
				boolean useMemo = prepareMemo(fs);
				boolean change = false;
				int before = lastItems.size(), memoBefore = memo.size(), known = 0, memoKnown = 0;
				for (StorageView<ItemVariant> is : h) {
					int r = updateChange(is, useMemo);
					change |= (r & CHANGED) != 0;
					if ((r & KNOWN) != 0)known++;
					if ((r & MEMO_KNOWN) != 0)memoKnown++;
				}
				// Every tracked slot still in the storage was visited above. Any left over belongs to a
				// storage this tracker no longer points at (block replaced, double chest split, see
				// refresh) and would otherwise stay in the terminal forever.
				if (known < before || memoKnown < memoBefore)change |= removeStaleSlots(h);
				if (change)lastChange = System.nanoTime();
			}
			lastUpdate = now;
			recordVersion(h, ver, dv, fs);
		}
		return lastChange;
	}

	/**
	 * Whether {@code h} provably holds what it held at the last scan, without looking at a slot:
	 * the same instance with the same version, or a per-lookup wrapper whose stable parts all
	 * report unchanged versions. Vanilla storages report no usable version, so they never qualify.
	 */
	private boolean isUnchanged(Storage<ItemVariant> h, long ver, StorageVersion dv, long fs) {
		if (!initialized || fs != lastFilterStamp)return false;
		if (h == lastVersionStorage)return ver == lastVersion;
		return dv != null && dv.sameAs(lastDerivedVersion);
	}

	private void recordVersion(Storage<ItemVariant> h, long ver, StorageVersion dv, long fs) {
		lastVersion = ver;
		lastVersionStorage = h;
		lastDerivedVersion = dv;
		lastFilterStamp = fs;
		initialized = true;
	}

	/** Makes {@link #memo} valid for filter stamp {@code fs}; returns whether it may be used. */
	private boolean prepareMemo(long fs) {
		if (fs == ItemPredicate.UNVERSIONED) {
			if (!memo.isEmpty())memo.clear();
			return false;
		}
		if (fs != memoStamp) {
			memo.clear();
			memoStamp = fs;
		}
		return true;
	}

	/**
	 * Changes whenever {@link #checkFilter} or {@link #getCount} may answer differently for the same
	 * slot contents. {@link ItemPredicate#UNVERSIONED} when that cannot be known.
	 */
	protected long filterStamp() {
		return 0L;
	}

	protected boolean checkFilter(StorageView<ItemVariant> stack) {
		return checkFilter(new StoredItemStack(stack.getResource().toStack(), stack.getAmount(), stack.getResource().hashCode()));
	}

	protected boolean checkFilter(StoredItemStack stack) {
		return true;
	}

	protected int getCount(StorageView<ItemVariant> is) {
		return (int) is.getAmount();
	}

	protected int getCount(FabricStack is) {
		return (int) is.count();
	}

	/**
	 * Result flags of updateChange/updateChangeM: the slot changed / the slot was already tracked /
	 * the slot had a memo entry.
	 */
	private static final int CHANGED = 1, KNOWN = 2, MEMO_KNOWN = 4;

	private int updateChange(StorageView<ItemVariant> iv, boolean useMemo) {
		StorageView<ItemVariant> uv = iv.getUnderlyingView();
		StoredItemStack li = lastItems.get(uv);
		int known = li != null ? KNOWN : 0;
		boolean blank = iv.isResourceBlank();
		if (useMemo) {
			SlotMemo m = memo.get(uv);
			if (m != null)known |= MEMO_KNOWN;
			if (blank) {
				if (m != null)memo.remove(uv);
			} else {
				ItemVariant v = iv.getResource();
				long amount = iv.getAmount();
				// Same item and amount as last time: neither the filter's answer nor the tracked
				// entry can have changed (the filter stamp is unchanged, see prepareMemo).
				if (m != null && m.amount() == amount && m.variant().equals(v))return known;
				memo.put(uv, new SlotMemo(v, amount));
			}
		}
		if (!blank && checkFilter(iv)) {
			int cnt = getCount(iv);
			ItemStack cachedStack = iv.getResource().toStack();
			if (li == null || !ItemStack.isSameItemSameComponents(li.getStack(), cachedStack)) {
				StoredItemStack newEntry = new StoredItemStack(cachedStack, cnt);
				lastItems.put(uv, newEntry);
				if (li != null) {
					adjustCount(li, -li.getQuantity());
				}
				adjustCount(newEntry, (long) cnt);
				return CHANGED | known;
			} else if(li.getQuantity() != cnt) {
				long diff = cnt - li.getQuantity();
				li.setCount(cnt);
				adjustCount(li, diff);
				return CHANGED | known;
			}
		} else if (li != null) {
			lastItems.remove(uv);
			adjustCount(li, -li.getQuantity());
			return CHANGED | known;
		}
		return known;
	}

	/** Drops every tracked slot (and memo entry) that is not part of {@code h}. */
	private boolean removeStaleSlots(Storage<ItemVariant> h) {
		Set<StorageView<ItemVariant>> live = new HashSet<>();
		for (StorageView<ItemVariant> is : h)live.add(is.getUnderlyingView());
		memo.keySet().retainAll(live);
		boolean change = false;
		for (var itr = lastItems.entrySet().iterator(); itr.hasNext();) {
			var e = itr.next();
			if (!live.contains(e.getKey())) {
				itr.remove();
				adjustCount(e.getValue(), -e.getValue().getQuantity());
				change = true;
			}
		}
		return change;
	}

	protected StorageView<ItemVariant> getSlotHandler(StorageView<ItemVariant> def) {
		return def;
	}

	protected Storage<ItemVariant> getSlotHandler(Storage<ItemVariant> def) {
		return def;
	}

	@Override
	public Stream<StoredItemStack> streamWrappedStacks(boolean parallel) {
		Storage<ItemVariant> h = storage;
		if (h == null)return Stream.empty();
		return lastItems.values().stream();
	}

	@Override
	public long countItems(StoredItemStack filter) {
		Storage<ItemVariant> h = storage;
		if (h == null)return 0;
		Long c = itemCounts.get(filter);
		return c != null ? c : 0L;
	}

	@Override
	public Stream<StoredItemStack> streamWrappedStacks(Set<?> exclude) {
		Storage<ItemVariant> h = storage;
		if (h == null)return Stream.empty();
		if (exclude.isEmpty())return lastItems.values().stream();
		return lastItems.entrySet().stream().filter(e -> !exclude.contains(e.getKey())).map(Map.Entry::getValue);
	}

	@Override
	public long countItems(StoredItemStack filter, Set<?> exclude) {
		long c = countItems(filter);
		if (c == 0)return 0;
		for (Object v : exclude) {
			StoredItemStack s = lastItems.get(v);
			if (s != null && s.equalItem(filter))c -= s.getQuantity();
		}
		return c;
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
	public boolean owns(InventorySlot slot) {
		return slot.getNotifier() == this;
	}

	@Override
	public InventorySlot findSlot(ItemPredicate filter, boolean findEmpty) {
		return findSlot(filter, findEmpty, Collections.emptySet());
	}

	private InventorySlot findSlot(ItemPredicate filter, boolean findEmpty, Set<StorageView<ItemVariant>> seen) {
		Storage<ItemVariant> h = storage;
		if (h == null)return null;
		for (var slot : h) {
			if (slot.isResourceBlank()) {
				if (findEmpty) {
					if (seen.contains(slot.getUnderlyingView()))continue;
					return new InventorySlot(getSlotHandler(h), getSlotHandler(slot), this, seen);
				}
				continue;
			}
			if (getCount(slot) < 1)continue;
			if (seen.contains(slot.getUnderlyingView()))continue;
			ItemVariant iv = slot.getResource();
			if(filter.test(new StoredItemStack(iv.toStack(), slot.getAmount(), slot.hashCode()))) {
				try (Transaction tr = Transaction.openOuter()) {
					if (slot.extract(iv, 1, tr) == 1)
						return new InventorySlot(getSlotHandler(h), getSlotHandler(slot), this, seen);
				}
			}
			continue;
		}
		return null;
	}

	@Override
	public InventorySlot findSlotAfter(InventorySlot slotIn, ItemPredicate filter, boolean findEmpty, boolean loop) {
		if (slotIn == null)return findSlot(filter, findEmpty);
		var sl = findSlot(filter, findEmpty, slotIn.nextSlot());
		if (sl == null && loop) {
			return findSlot(filter, findEmpty);
		}
		return sl;
	}

	@Override
	public InventorySlot findSlotDest(StoredItemStack forStack) {
		return findSlotDest(forStack, Collections.emptySet());
	}

	private InventorySlot findSlotDest(StoredItemStack forStack, Set<StorageView<ItemVariant>> seen) {
		Storage<ItemVariant> h = storage;
		if (h == null)return null;
		if (!checkFilter(forStack))return null;
		ItemVariant iv = ItemVariant.of(forStack.getStack());
		try (Transaction tr = Transaction.openOuter()) {
			long c = h.insert(iv, forStack.getQuantity(), tr);
			if (c > 0)
				return new InventorySlot(getSlotHandler(h), null, this, seen);
		}
		return null;
	}

	@Override
	public InventorySlot findSlotDestAfter(InventorySlot slotIn, StoredItemStack forStack, boolean loop) {
		if (slotIn == null)return findSlotDest(forStack);
		if (slotIn.getView() == null) {
			if (loop)return findSlotDest(forStack);
			else return null;
		}
		var sl = findSlotDest(forStack, slotIn.nextSlot());
		if (sl == null && loop) {
			return findSlotDest(forStack);
		}
		return sl;
	}

	@Override
	public void onSlotChanged(InventorySlot slot) {
		StorageView<ItemVariant> view = slot.getView();
		if (view == null)return;
		// The view handed out may present the slot differently from a plain scan (keep-last), so
		// let the next scan look at this slot from scratch instead of trusting the memo.
		memo.remove(view.getUnderlyingView());
		if ((updateChange(view, false) & CHANGED) != 0) {
			lastChange = System.nanoTime();
		}
	}

	@Override
	public InventoryImage prepForOffThread(Level level) {
		if (lastUpdate == level.getGameTime())return null;
		Storage<ItemVariant> h = storage;
		if (h == null)return null;
		long fs = filterStamp();
		long ver = h.getVersion();
		StorageVersion dv = StorageVersion.of(h);
		if (isUnchanged(h, ver, dv, fs)) {
			// Nothing to look at. Mark this tick as done so the getChangeTracker call that follows
			// returns right away.
			lastUpdate = level.getGameTime();
			recordVersion(h, ver, dv, fs);
			return null;
		}
		offThreadUseMemo = prepareMemo(fs);
		offThreadStorage = h;
		offThreadDerivedVersion = dv;
		offThreadFilterStamp = fs;
		InventoryImage im = new InventoryImage(ver);
		for (StorageView<ItemVariant> is : h)im.addStack(is);
		return im;
	}

	@Override
	public InvState processOffThread(InventoryImage array) {
		boolean useMemo = offThreadUseMemo;
		boolean change = false;
		var st = array.getStacks();
		Map<StorageView<ItemVariant>, StoredItemStack> mod = new HashMap<>();
		Map<StorageView<ItemVariant>, SlotMemo> memoMod = useMemo ? new HashMap<>() : Collections.emptyMap();
		int known = 0, memoKnown = 0;
		for (int i = 0; i < st.size(); i++) {
			FabricStack s = st.get(i);
			int r = updateChangeM(s, mod, memoMod, useMemo);
			change |= (r & CHANGED) != 0;
			if ((r & KNOWN) != 0)known++;
			if ((r & MEMO_KNOWN) != 0)memoKnown++;
		}
		// Same stale-slot sweep as getChangeTracker. lastItems and memo are only written on the
		// server thread in finishOffThreadProcess, which cannot run concurrently with this.
		if (known < lastItems.size() || (useMemo && memoKnown < memo.size())) {
			Set<StorageView<ItemVariant>> live = new HashSet<>();
			for (int i = 0; i < st.size(); i++)live.add(st.get(i).uv());
			for (StorageView<ItemVariant> uv : lastItems.keySet()) {
				if (!live.contains(uv)) {
					mod.put(uv, TOMBSTONE);
					change = true;
				}
			}
			if (useMemo) {
				for (StorageView<ItemVariant> uv : memo.keySet()) {
					if (!live.contains(uv))memoMod.put(uv, NO_MEMO);
				}
			}
		}
		if (change) return new InvState(System.nanoTime(), array.getVersion(), mod, memoMod);
		// Nothing changed, but the storage WAS read at array.getVersion(). Reporting the previous
		// version here would make every later poll see a "new" version and rescan (A15).
		return new InvState(lastChange, array.getVersion(), Collections.emptyMap(), memoMod);
	}

	private static final StoredItemStack TOMBSTONE = new StoredItemStack(ItemStack.EMPTY, 0);

	private int updateChangeM(FabricStack st, Map<StorageView<ItemVariant>, StoredItemStack> mod, Map<StorageView<ItemVariant>, SlotMemo> memoMod, boolean useMemo) {
		StorageView<ItemVariant> uv = st.uv();
		StoredItemStack li = lastItems.get(uv);
		int known = li != null ? KNOWN : 0;
		boolean blank = st.item().isBlank();
		if (useMemo) {
			SlotMemo m = memo.get(uv);
			if (m != null)known |= MEMO_KNOWN;
			if (blank) {
				if (m != null)memoMod.put(uv, NO_MEMO);
			} else {
				// Same fast path as updateChange: no ItemStack for a slot that did not change.
				if (m != null && m.amount() == st.count() && m.variant().equals(st.item()))return known;
				memoMod.put(uv, new SlotMemo(st.item(), st.count()));
			}
		}
		if (!blank) {
			ItemStack is = st.item().toStack();
			if (checkFilter(new StoredItemStack(is, st.count(), st.item().hashCode()))) {
				int cnt = getCount(st);
				if (li == null || !ItemStack.isSameItemSameComponents(li.getStack(), is)) {
					mod.put(uv, new StoredItemStack(is, cnt));
					return CHANGED | known;
				} else if(li.getQuantity() != cnt) {
					// Never mutate li here: it is shared with the server thread, and itemCounts is only
					// kept in step when the new count goes through finishOffThreadProcess.
					mod.put(uv, new StoredItemStack(li.getStack(), cnt));
					return CHANGED | known;
				}
				return known;
			}
		}
		if (li != null) {
			mod.put(uv, TOMBSTONE);
			return CHANGED | known;
		}
		return known;
	}

	@Override
	public long finishOffThreadProcess(Level level, InvState ct) {
		if (ct == null)return lastChange;
		lastChange = ct.change();
		lastUpdate = level.getGameTime();
		recordVersion(offThreadStorage, ct.version(), offThreadDerivedVersion, offThreadFilterStamp);
		for (var entry : ct.mod().entrySet()) {
			StoredItemStack oldVal = lastItems.get(entry.getKey());
			if (oldVal != null) {
				adjustCount(oldVal, -oldVal.getQuantity());
			}
			if (entry.getValue() == TOMBSTONE) {
				lastItems.remove(entry.getKey());
			} else {
				lastItems.put(entry.getKey(), entry.getValue());
				adjustCount(entry.getValue(), entry.getValue().getQuantity());
			}
		}
		for (var entry : ct.memo().entrySet()) {
			if (entry.getValue() == NO_MEMO)memo.remove(entry.getKey());
			else memo.put(entry.getKey(), entry.getValue());
		}
		return lastChange;
	}

	/**
	 * Re-points this tracker at the current Storage wrapper. {@code lastItems} is deliberately NOT
	 * cleared: it is keyed by {@link StorageView#getUnderlyingView()}, and those slot views are the
	 * cached per-Container {@code InventorySlotWrapper}s, which survive wrapper re-creation. Keeping
	 * the map is what lets the rescan correctly report "nothing changed".
	 */
	public void refresh(Storage<ItemVariant> h) {
		// Deliberately does NOT clear `initialized`: getChangeTracker already falls back to a
		// content rescan whenever `h != lastVersionStorage`, and clearing it here would additionally
		// defeat prepForOffThread's early-out, which requires `initialized`.
		storage = h;
	}
}
