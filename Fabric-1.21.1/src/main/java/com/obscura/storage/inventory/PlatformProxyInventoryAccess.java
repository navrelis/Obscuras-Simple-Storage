package com.obscura.storage.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

public class PlatformProxyInventoryAccess implements PlatformInventoryAccess, Storage<ItemVariant> {
	private boolean calling;
	private IInventoryAccess access;

	public PlatformProxyInventoryAccess(IInventoryAccess access) {
		this.access = access;
	}

	private Storage<ItemVariant> getP() {
		return access.getPlatformHandler();
	}

	@Deprecated
	@Override
	public Storage<ItemVariant> get() {
		return this;
	}

	@Override
	public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		StoragePreconditions.notNegative(maxAmount);
		if(calling)return 0L;
		calling = true;
		try {
			return getP().insert(resource, maxAmount, transaction);
		} finally {
			calling = false;
		}
	}

	@Override
	public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		StoragePreconditions.notNegative(maxAmount);
		if(calling)return 0L;
		calling = true;
		try {
			return getP().extract(resource, maxAmount, transaction);
		} finally {
			calling = false;
		}
	}

	@Override
	public Iterator<StorageView<ItemVariant>> iterator() {
		if(calling)return Collections.emptyIterator();
		calling = true;
		try {
			List<StorageView<ItemVariant>> snapshot = new ArrayList<>();
			Iterator<StorageView<ItemVariant>> it = getP().iterator();
			while (it.hasNext()) snapshot.add(it.next());
			return snapshot.iterator();
		} finally {
			calling = false;
		}
	}

	@Override
	public void forEach(Consumer<? super StorageView<ItemVariant>> action) {
		if(calling)return;
		calling = true;
		try {
			getP().forEach(action);
		} finally {
			calling = false;
		}
	}

	@Override
	public Spliterator<StorageView<ItemVariant>> spliterator() {
		if(calling)return Spliterators.emptySpliterator();
		calling = true;
		try {
			List<StorageView<ItemVariant>> snapshot = new ArrayList<>();
			getP().spliterator().forEachRemaining(snapshot::add);
			return snapshot.spliterator();
		} finally {
			calling = false;
		}
	}

	@Override
	public boolean supportsInsertion() {
		if(calling)return false;
		calling = true;
		try {
			return getP().supportsInsertion();
		} finally {
			calling = false;
		}
	}

	@Override
	public boolean supportsExtraction() {
		if(calling)return false;
		calling = true;
		try {
			return getP().supportsExtraction();
		} finally {
			calling = false;
		}
	}

	@Override
	public Iterator<StorageView<ItemVariant>> nonEmptyIterator() {
		if(calling)return Collections.emptyIterator();
		calling = true;
		try {
			List<StorageView<ItemVariant>> snapshot = new ArrayList<>();
			Iterator<StorageView<ItemVariant>> it = getP().nonEmptyIterator();
			while (it.hasNext()) snapshot.add(it.next());
			return snapshot.iterator();
		} finally {
			calling = false;
		}
	}

	@Override
	public Iterable<StorageView<ItemVariant>> nonEmptyViews() {
		if(calling)return Collections.emptyList();
		calling = true;
		try {
			List<StorageView<ItemVariant>> snapshot = new ArrayList<>();
			for (StorageView<ItemVariant> view : getP().nonEmptyViews()) {
				snapshot.add(view);
			}
			return snapshot;
		} finally {
			calling = false;
		}
	}

	@Override
	public long getVersion() {
		if(calling)return 0L;
		calling = true;
		try {
			return getP().getVersion();
		} finally {
			calling = false;
		}
	}

	@Override
	public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
		if (dejaVu.add(access))
			return access.getRootHandler(dejaVu);
		else
			return this;
	}

	@Override
	public IInventoryChangeTracker tracker() {
		if(calling)return InventoryChangeTracker.NULL;
		calling = true;
		try {
			return access.tracker();
		} finally {
			calling = false;
		}
	}
}
