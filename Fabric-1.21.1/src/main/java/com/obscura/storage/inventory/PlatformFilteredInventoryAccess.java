package com.obscura.storage.inventory;

import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.BlankVariantView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.FilteringStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.google.common.collect.Iterators;

import com.obscura.storage.inventory.InventoryImage.FabricStack;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.inventory.filter.IFilter;
import com.obscura.storage.inventory.filter.ItemPredicate;
import com.obscura.storage.util.Priority;
import com.obscura.storage.util.Priority.IPriority;

public class PlatformFilteredInventoryAccess extends FilteringStorage<ItemVariant> implements IInventoryAccess, IPriority {
	private BlankVariantView<ItemVariant> nullSlot = new BlankVariantView<>(ItemVariant.blank(), 0);
	private final IInventoryAccess acc;
	private final IFilter filter;
	private final InventoryChangeTracker tracker;

	public PlatformFilteredInventoryAccess(IInventoryAccess acc, IFilter filter) {
		super(acc::getPlatformHandler);
		this.acc = acc;
		this.filter = filter;
		this.tracker = new InventoryChangeTracker(acc.getPlatformHandler()) {

			@Override
			protected boolean checkFilter(StoredItemStack stack) {
				return filter.getItemPred().test(stack);
			}

			@Override
			protected long filterStamp() {
				// Read after updateState(), which both entry points below call first.
				long v = filter.getItemPred().stateVersion();
				if (v == ItemPredicate.UNVERSIONED)return v;
				// Keep-last changes every count, so it is part of the stamp.
				return (v << 1) | (filter.isKeepLast() ? 1 : 0);
			}

			@Override
			protected int getCount(StorageView<ItemVariant> is) {
				return (int) (filter.isKeepLast() ? is.getAmount() - 1 : is.getAmount());
			}

			@Override
			protected int getCount(FabricStack is) {
				return (int) (filter.isKeepLast() ? is.count() - 1 : is.count());
			}

			@Override
			protected StorageView<ItemVariant> getSlotHandler(StorageView<ItemVariant> def) {
				if (def instanceof FilteringStorageView s && s.getOuter() == PlatformFilteredInventoryAccess.this)
					return def;
				return new FilteringStorageView(def);
			}

			@Override
			protected Storage<ItemVariant> getSlotHandler(Storage<ItemVariant> def) {
				return PlatformFilteredInventoryAccess.this;
			}

			@Override
			public InventoryImage prepForOffThread(Level level) {
				refreshHandler();
				filter.getItemPred().updateState();
				return super.prepForOffThread(level);
			}

			@Override
			public long getChangeTracker(Level level) {
				refreshHandler();
				filter.getItemPred().updateState();
				return super.getChangeTracker(level);
			}

			@Override
			public Stream<StoredItemStack> streamWrappedStacks(boolean parallel) {
				refreshHandler();
				return super.streamWrappedStacks(parallel);
			}

			@Override
			public long countItems(StoredItemStack filter) {
				refreshHandler();
				return super.countItems(filter);
			}

			@Override
			public InventorySlot findSlot(ItemPredicate filter, boolean findEmpty) {
				refreshHandler();
				return super.findSlot(filter, findEmpty);
			}

			@Override
			public InventorySlot findSlotAfter(InventorySlot slot, ItemPredicate filter, boolean findEmpty, boolean loop) {
				refreshHandler();
				return super.findSlotAfter(slot, filter, findEmpty, loop);
			}

			@Override
			public InventorySlot findSlotDest(StoredItemStack forStack) {
				refreshHandler();
				return super.findSlotDest(forStack);
			}

			@Override
			public InventorySlot findSlotDestAfter(InventorySlot slot, StoredItemStack forStack, boolean loop) {
				refreshHandler();
				return super.findSlotDestAfter(slot, forStack, loop);
			}
		};
	}

	/**
	 * This access (and with it the tracker) is cached by {@link BlockFilter#wrap} for as long as the
	 * wrapped access object is reused, so the Storage behind it must be looked up again on every use,
	 * like {@code BlockInventoryAccess.tracker()} does. Otherwise a chest placed or replaced later
	 * (or a single chest becoming a double chest) behind a filtered connector is never seen.
	 */
	private void refreshHandler() {
		tracker.refresh(acc.getPlatformHandler());
	}

	@Override
	public IInventoryChangeTracker tracker() {
		refreshHandler();
		return tracker;
	}

	@Override
	public ItemStack pushStack(ItemStack stack) {
		if (!test(stack))return stack;
		return IInventoryAccess.super.pushStack(stack);
	}

	@Override
	public ItemStack pullMatchingStack(ItemStack st, long max) {
		if (!test(st))return ItemStack.EMPTY;
		return IInventoryAccess.super.pullMatchingStack(st, max);
	}

	@Override
	public int getFreeSlotCount() {
		return acc.getFreeSlotCount();
	}

	@Override
	public int getSlotCount() {
		return acc.getSlotCount();
	}

	@Deprecated
	@Override
	public Storage<ItemVariant> get() {
		return this;
	}

	@Override
	public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		if(!canExtract(resource))return 0;
		if(filter.isKeepLast()) {
			Iterator<StorageView<ItemVariant>> itr = super.iterator();
			long amount = 0;
			while (itr.hasNext()) {
				StorageView<ItemVariant> sv = itr.next();
				if(!sv.isResourceBlank() && sv.getResource().equals(resource)) {
					long maxEx = Math.min(maxAmount - amount, sv.getAmount() - 1);
					amount += sv.extract(resource, maxEx, transaction);
					if (amount == maxAmount) break;
				}
			}
			return amount;
		}
		return backingStorage.get().extract(resource, maxAmount, transaction);
	}

	@Override
	protected boolean canInsert(ItemVariant resource) {
		return inFilter(resource, 1);
	}

	@Override
	protected boolean canExtract(ItemVariant resource) {
		return inFilter(resource, 1);
	}

	@Override
	public Iterator<StorageView<ItemVariant>> iterator() {
		return Iterators.transform(super.iterator(), sv -> {
			if(!inFilter(sv.getResource(), sv.getAmount()))return nullSlot;
			return filter.isKeepLast() ? new FilteringStorageView(sv) : sv;
		});
	}

	private boolean inFilter(ItemVariant resource, long cnt) {
		return filter.getItemPred().test(new StoredItemStack(resource.toStack(), cnt, resource.hashCode()));
	}

	@Override
	public Priority getPriority() {
		Priority pr = IPriority.get(acc);
		return pr.add(filter.getPriority());
	}

	private boolean test(ItemStack stack) {
		return filter.getItemPred().test(stack);
	}

	private class FilteringStorageView implements StorageView<ItemVariant> {
		private StorageView<ItemVariant> delegate;

		public FilteringStorageView(StorageView<ItemVariant> delegate) {
			this.delegate = delegate;
		}

		@Override
		public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
			if(filter.isKeepLast()) {
				maxAmount = Math.min(delegate.getAmount() - 1, maxAmount);
				if(maxAmount < 1)return 0L;
			}
			return delegate.extract(resource, maxAmount, transaction);
		}

		@Override
		public boolean isResourceBlank() {
			return delegate.isResourceBlank() || (filter.isKeepLast() && delegate.getAmount() == 1L);
		}

		@Override
		public ItemVariant getResource() {
			return delegate.getResource();
		}

		@Override
		public long getAmount() {
			return filter.isKeepLast() ? delegate.getAmount() - 1L : delegate.getAmount();
		}

		@Override
		public long getCapacity() {
			return filter.isKeepLast() && !delegate.isResourceBlank() ? delegate.getCapacity() - 1L : delegate.getCapacity();
		}

		@Override
		public StorageView<ItemVariant> getUnderlyingView() {
			return delegate.getUnderlyingView();
		}

		public PlatformFilteredInventoryAccess getOuter() {
			return PlatformFilteredInventoryAccess.this;
		}
	}

	@Override
	public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
		return acc.getRootHandler(dejaVu);
	}

	@Override
	public String toString() {
		return "Filtering: {" + acc + "}";
	}

	public IFilter getFilter() {
		return filter;
	}

	public IInventoryAccess getActualInventory() {
		return acc;
	}
}
