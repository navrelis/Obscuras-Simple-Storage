package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import com.obscura.storage.StorageMod;
import com.obscura.storage.doctor.NetworkStats;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;

public class MergedStorage implements Storage<ItemVariant> {
	private Set<Storage<ItemVariant>> dupCheck = new HashSet<>();
	public List<Storage<ItemVariant>> parts = new ArrayList<>();
	/** Block position each part came from, when the builder knew it (Item Locator). Keyed by identity, cleared with the parts. */
	private final Map<Storage<ItemVariant>, BlockPos> positions = new IdentityHashMap<>();
	private boolean iterating;
	private static boolean warnedRecursion;
	private StorageSnapshot snapshot;
	/** Network Doctor timing window of the network this storage is the inventory of, null when it is not attributed to any. */
	private NetworkStats doctorStats;
	/** Parts {@link #add} rejected as duplicates since the last {@link #clear}, for the Network Doctor. */
	private int duplicatesSkipped;
	private long fillVersion = Long.MIN_VALUE;
	private int fillSlots = -1, fillSignal;
	/** Oldest network snapshot a comparator accepts, in ticks. */
	private static final int FILL_MAX_AGE = 4;

	public MergedStorage() {
	}

	public Collection<Storage<ItemVariant>> getStorages() {
		return parts;
	}

	public void add(Storage<ItemVariant> storage) {
		add(storage, null);
	}

	/**
	 * Adds a part and remembers the block it came from. When {@code storage} is itself a merged
	 * storage, its parts are added with the positions it knows.
	 */
	public void add(Storage<ItemVariant> storage, @Nullable BlockPos pos) {
		if(storage == this)return;
		if(storage instanceof MergedStorage str) {
			for (Storage<ItemVariant> part : new ArrayList<>(str.parts)) {
				add(part, str.positions.get(part));
			}
			return;
		}
		if(dupCheck.add(storage)) {
			this.parts.add(storage);
			if(pos != null)positions.put(storage, pos.immutable());
		} else {
			duplicatesSkipped++;
		}
	}

	/** {@link #getPosition} for loader neutral code (the Tidy button): {@code part} is a member of {@link #getStorages()}. */
	@Nullable
	public BlockPos positionOf(Object part) {
		return positions.get(part);
	}

	/** Read-only: the block {@code part} was added for, or null when unknown. */
	@Nullable
	public BlockPos getPosition(Storage<ItemVariant> part) {
		return positions.get(part);
	}

	/**
	 * Item totals of everything merged here, shared by every reader of this network (terminals, level
	 * emitters). Readers refresh it with the age they can accept, see {@link StorageSnapshot#refresh}.
	 */
	public StorageSnapshot getSnapshot() {
		if(snapshot == null)snapshot = new StorageSnapshot();
		return snapshot;
	}

	/** This network's Network Doctor timing window, null when its work is not attributed to any. */
	public NetworkStats getDoctorStats() {
		return doctorStats;
	}

	public void setDoctorStats(NetworkStats doctorStats) {
		this.doctorStats = doctorStats;
	}

	/** Parts offered to {@link #add} that were already there since the last {@link #clear}. */
	public int getDuplicatesSkipped() {
		return duplicatesSkipped;
	}

	/** Members labelled by glow item frames (Frame Labels), also behind a cable connector's own filter. For the Network Doctor. */
	public int countLabelled() {
		int n = 0;
		for (Storage<ItemVariant> part : parts) {
			Storage<ItemVariant> a = part;
			for (int i = 0; i < 8 && a != null; i++) {
				if (a instanceof FrameLabel.Labelled) {
					n++;
					break;
				}
				a = a instanceof FilteredStorage f ? f.get() : null;
			}
		}
		return n;
	}

	/**
	 * How full the whole network is as a comparator signal (see {@link RedstoneSignals#fill}): the
	 * stored items, each as a fraction of its max stack size, out of the slot count. The value is cached
	 * and only recomputed when the shared snapshot changed (which it re-reads at most every few ticks for
	 * all readers), so asking every tick costs next to nothing.
	 */
	public int getFillSignal(Level level) {
		StorageSnapshot s = getSnapshot();
		s.refresh(this, level.getGameTime(), FILL_MAX_AGE);
		if(s.getVersion() != fillVersion || s.getSlotCount() != fillSlots) {
			fillVersion = s.getVersion();
			fillSlots = s.getSlotCount();
			float sum = 0;
			boolean any = false;
			for (Object2LongMap.Entry<ItemVariant> e : Object2LongMaps.fastIterable(s.counts())) {
				long n = e.getLongValue();
				if(n <= 0)continue;
				any = true;
				sum += RedstoneSignals.slotFill(n, e.getKey().getItem().getMaxStackSize());
			}
			fillSignal = RedstoneSignals.fill(sum, any, s.getSlotCount());
		}
		return fillSignal;
	}

	public void clear() {
		parts.clear();
		dupCheck.clear();
		positions.clear();
		duplicatesSkipped = 0;
	}

	@Override
	public boolean supportsInsertion() {
		for (Storage<ItemVariant> part : parts) {
			if (part.supportsInsertion()) {
				return true;
			}
		}

		return false;
	}

	@Override
	public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		StoragePreconditions.notNegative(maxAmount);
		long amount = 0;

		try (Transaction iterationTransaction = Transaction.openNested(transaction)) {
			for (Storage<ItemVariant> part : parts) {
				try (Transaction transferTransaction = iterationTransaction.openNested()) {
					amount += part.insert(resource, maxAmount - amount, transferTransaction);
					transferTransaction.commit();
				}
				if (amount == maxAmount) break;
			}

			iterationTransaction.commit();
		}

		return amount;
	}

	@Override
	public boolean supportsExtraction() {
		for (Storage<ItemVariant> part : parts) {
			if (part.supportsExtraction()) {
				return true;
			}
		}

		return false;
	}

	@Override
	public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		StoragePreconditions.notNegative(maxAmount);
		long amount = 0;

		try (Transaction iterationTransaction = Transaction.openNested(transaction)) {
			for (Storage<ItemVariant> part : parts) {
				try (Transaction transferTransaction = iterationTransaction.openNested()) {
					amount += part.extract(resource, maxAmount - amount, transferTransaction);
					transferTransaction.commit();
				}
				if (amount == maxAmount) break;
			}
			iterationTransaction.commit();
		}

		return amount;
	}

	@Override
	public Iterator<StorageView<ItemVariant>> iterator() {
		if(iterating) {
			if(!warnedRecursion) {
				warnedRecursion = true;
				StorageMod.LOGGER.warn("Recursive inventory connector access detected (a network is connected to itself); it is skipped. Further occurrences are not logged.");
			}
			return Collections.emptyIterator();
		}
		return new CombinedIterator();
	}

	/**
	 * The combined iterator for multiple storages.
	 */
	private class CombinedIterator implements Iterator<StorageView<ItemVariant>> {
		final Iterator<Storage<ItemVariant>> partIterator = parts.iterator();
		// Always holds the next StorageView<T>, except during next() while the iterator is being advanced.
		Iterator<? extends StorageView<ItemVariant>> currentPartIterator = null;

		CombinedIterator() {
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
			iterating = true;
			while (partIterator.hasNext()) {
				this.currentPartIterator = partIterator.next().iterator();

				if (this.currentPartIterator.hasNext()) {
					break;
				}
			}
			iterating = false;
		}
	}
}
