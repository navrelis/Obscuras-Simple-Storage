package com.obscura.storage.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

import com.obscura.storage.inventory.MultiInventoryAccess.SlotSetKey;
import com.obscura.storage.util.WorldStates;

public class PlatformMultiInventoryAccess extends MultiInventoryAccess implements Storage<ItemVariant> {
	private final ThreadLocal<Boolean> iterating = ThreadLocal.withInitial(() -> Boolean.FALSE);
	private final ThreadLocal<Boolean> calling = ThreadLocal.withInitial(() -> Boolean.FALSE);

	// ---- Platform hooks used by MultiInventoryAccess: inventory identity on the Transfer API ----

	/**
	 * The underlying slots a plain (unfiltered, unproxied) access to a sided block exposes, or null
	 * for any other access. Only sided storages can partly overlap: everything else is either the
	 * same inventory (deduplicated in addDistinct) or a different one. The returned slots are
	 * {@link StorageView}s, compared by identity; {@link #isSlotEmpty} reads them.
	 */
	@SuppressWarnings("deprecation")
	static List<Object> sidedSlots(IInventoryAccess a) {
		if (!(a instanceof PlatformInventoryAccess.BlockInventoryAccess))return null;
		if (a.getRootHandler(Collections.newSetFromMap(new IdentityHashMap<>())) != a)return null;
		if (!(a.get() instanceof CombinedStorage<?, ?> cs) || !isSided(cs))return null;
		List<Object> views = new ArrayList<>(cs.parts.size());
		for (Object p : cs.parts) {
			if (p instanceof StorageView<?> v)views.add(v.getUnderlyingView());
		}
		return views;
	}

	/** Whether a slot returned by {@link #sidedSlots} is empty. */
	static boolean isSlotEmpty(Object slot) {
		return ((StorageView<?>) slot).isResourceBlank();
	}

	/**
	 * Stable identity of the physical inventory an access points at, or null when it has none (or
	 * when the access wraps/filters its target, in which case two accesses over the same inventory
	 * are legitimately different and must both be kept).
	 */
	@SuppressWarnings("deprecation")
	static Object storageIdentity(IInventoryAccess a) {
		if (!(a instanceof PlatformInventoryAccess.BlockInventoryAccess))return null;
		Object h = a.get();
		if (!(h instanceof Storage))return null;
		@SuppressWarnings("unchecked")
		Storage<ItemVariant> s = (Storage<ItemVariant>) h;
		return dedupKey(s);
	}

	/**
	 * Key under which two plain accesses count as the same inventory.
	 *
	 * <p>Normally the stable identity of the storage's first slot ({@link WorldStates#identityOf}),
	 * which lets both halves of a double chest collapse into one. That is wrong for a sided
	 * inventory, where the face decides which slots are visible: the bottom and the side face of a
	 * brewing stand both start with slot 0 but expose different slots, and keying on the first slot
	 * silently dropped one of them (A11). Sided storages are keyed on the whole set of slots they
	 * expose instead, so only faces showing exactly the same slots collapse. Faces that share only
	 * some slots both stay (each keeps its own insert/extract rules), and the shared slots are
	 * counted once, see MultiInventoryAccess#overlaps.
	 */
	static Object dedupKey(Storage<ItemVariant> s) {
		if (s instanceof CombinedStorage<?, ?> cs && isSided(cs)) {
			// Interned: the dedup set compares keys by identity.
			return SlotSetKey.INTERNER.intern(new SlotSetKey(cs.parts, PlatformMultiInventoryAccess::underlyingSlot));
		}
		return WorldStates.identityOf(s);
	}

	private static Object underlyingSlot(Object part) {
		return part instanceof StorageView<?> v ? v.getUnderlyingView() : part;
	}

	/** A sided wrapper's parts are per-lookup slot wrappers around the container's own slots. */
	static boolean isSided(CombinedStorage<?, ?> cs) {
		List<?> parts = cs.parts;
		return parts != null && !parts.isEmpty() && parts.get(0) instanceof StorageView<?> v && v.getUnderlyingView() != v;
	}

	@Deprecated
	@Override
	public Storage<ItemVariant> get() {
		return this;
	}

	@Override
	public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		StoragePreconditions.notNegative(maxAmount);
		if(calling.get())return 0L;
		calling.set(true);
		long amount = 0;

		try (Transaction iterationTransaction = Transaction.openNested(transaction)) {
			for (IInventoryAccess part : connected) {
				Storage<ItemVariant> h = part.getPlatformHandler();
				if (h == null)continue;
				try (Transaction transferTransaction = iterationTransaction.openNested()) {
					amount += h.insert(resource, maxAmount - amount, transferTransaction);
					transferTransaction.commit();
				}
				if (amount == maxAmount) break;
			}

			iterationTransaction.commit();
		} finally {
			calling.set(false);
		}

		return amount;
	}

	@Override
	public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		StoragePreconditions.notNegative(maxAmount);
		if(calling.get())return 0L;
		calling.set(true);
		long amount = 0;

		try (Transaction iterationTransaction = Transaction.openNested(transaction)) {
			for (IInventoryAccess part : connected) {
				Storage<ItemVariant> h = part.getPlatformHandler();
				if (h == null)continue;
				try (Transaction transferTransaction = iterationTransaction.openNested()) {
					amount += h.extract(resource, maxAmount - amount, transferTransaction);
					transferTransaction.commit();
				}
				if (amount == maxAmount) break;
			}
			iterationTransaction.commit();
		} finally {
			calling.set(false);
		}

		return amount;
	}

	@Override
	public Iterator<StorageView<ItemVariant>> iterator() {
		if(iterating.get()) {
			com.obscura.storage.StorageMod.LOGGER.warn("Recursive storage access detected");
			return Collections.emptyIterator();
		}
		return new MultiIterator();
	}

	/**
	 * The combined iterator for multiple storages.
	 */
	private class MultiIterator implements Iterator<StorageView<ItemVariant>> {
		final Iterator<IInventoryAccess> partIterator = connected.iterator();
		// Always holds the next StorageView<T>, except during next() while the iterator is being advanced.
		Iterator<? extends StorageView<ItemVariant>> currentPartIterator = null;

		MultiIterator() {
			advanceCurrentPartIterator();
		}

		@Override
		public boolean hasNext() {
			return currentPartIterator != null && currentPartIterator.hasNext();
		}

		@Override
		public StorageView<ItemVariant> next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}

			StorageView<ItemVariant> returned = currentPartIterator.next();

			// Advance the current part iterator
			if (!currentPartIterator.hasNext()) {
				advanceCurrentPartIterator();
			}

			return returned;
		}

		private void advanceCurrentPartIterator() {
			iterating.set(true);
			while (partIterator.hasNext()) {
				Storage<ItemVariant> h = partIterator.next().getPlatformHandler();
				if (h == null)h = Storage.empty();
				this.currentPartIterator = h.iterator();

				if (this.currentPartIterator.hasNext()) {
					break;
				}
			}
			iterating.set(false);
		}
	}
}
