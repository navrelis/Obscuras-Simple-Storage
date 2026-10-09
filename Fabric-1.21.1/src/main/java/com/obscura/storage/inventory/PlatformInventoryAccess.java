package com.obscura.storage.inventory;

import java.util.Set;

import net.fabricmc.fabric.api.lookup.v1.block.BlockApiCache;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.StorageModComponents;
import com.obscura.storage.util.IValidInfo;

public interface PlatformInventoryAccess extends IInventoryAccess {
	public static final PlatformInventoryAccess EMPTY = new PlatformInventoryAccess() {

		@Override
		public IInventoryChangeTracker tracker() {
			return InventoryChangeTracker.NULL;
		}

		@Deprecated
		@Override
		public Storage<ItemVariant> get() {
			return Storage.empty();
		}

		@Override
		public ItemStack pullMatchingStack(ItemStack st, long max) {
			return ItemStack.EMPTY;
		}

		@Override
		public ItemStack pushStack(ItemStack stack) {
			return stack;
		}

		@Override
		public int getFreeSlotCount() {
			return 0;
		}

		@Override
		public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
			return this;
		}
	};

	public static class BlockInventoryAccess implements PlatformInventoryAccess {
		private BlockApiCache<Storage<ItemVariant>, Direction> cache;
		private Direction d;
		private IInventoryChangeTracker.Delegate tracker = new IInventoryChangeTracker.Delegate();
		/**
		 * The one tracker this access owns, for storages that do not carry their own.
		 *
		 * <p>It must NOT be looked up by Storage identity: Fabric hands out a fresh wrapper per
		 * lookup for several common block types - a {@code CombinedSlottedStorage} for vanilla
		 * double chests, and a {@code SidedInventoryStorageImpl} (whose {@code parts} are freshly
		 * allocated too) for every {@code WorldlyContainer} queried with a direction, i.e. furnaces,
		 * hoppers, droppers, dispensers and brewing stands. Keying on any of those objects creates a
		 * new tracker with an empty {@code lastItems} every tick, which then reports "everything
		 * changed" forever. Owning the tracker per access is immune to every such wrapper class,
		 * present and future, because the accumulated state is tied to the block face instead.
		 */
		private InventoryChangeTracker ownTracker;
		private long lastCheck;
		private Storage<ItemVariant> storageCache;

		// OPT-019: Cached slot counts
		private int cachedSlotCount = -1;
		private int cachedFreeSlotCount = -1;
		private long slotCountCheckTime = -1;

		@Deprecated
		@Override
		public Storage<ItemVariant> get() {
			var c = cache;
			if (c == null)return null;
			if (c.getWorld().getGameTime() == lastCheck) {
				return storageCache;
			}
			Storage<ItemVariant> sv = c.find(d);
			lastCheck = c.getWorld().getGameTime();
			storageCache = sv;
			return sv;
		}

		@Override
		public IInventoryChangeTracker tracker() {
			var itemHandler = get();
			if (itemHandler == null) {
				tracker.setDelegate(InventoryChangeTracker.NULL);
			} else if (itemHandler instanceof IChangeTrackerAccess a) {
				// Storages that carry their own tracker keep using it.
				tracker.setDelegate(a.tracker());
			} else {
				if (ownTracker == null)ownTracker = new InventoryChangeTracker(itemHandler);
				else ownTracker.refresh(itemHandler);
				tracker.setDelegate(ownTracker);
			}
			return tracker;
		}

		/**
		 * Makes the next {@link #get()} look the Storage up again instead of reusing the one found
		 * earlier this tick. Tidy calls it before every move: a block broken or replaced since then
		 * (same tick) must not be read from or written into. The lookup itself goes through the
		 * BlockApiCache, which drops a removed block entity's storage.
		 */
		public void invalidateTickCache() {
			lastCheck = Long.MIN_VALUE;
			storageCache = null;
		}

		public void onLoad(Level level, BlockPos p, Direction d, IValidInfo inventoryConnectorBlockEntity) {
			cache = BlockApiCache.create(ItemStorage.SIDED, (ServerLevel) level, p);
			this.d = d;
		}

		public static boolean hasInventoryAt(Level level, BlockPos pos, BlockState state, Direction direction) {
			return ItemStorage.SIDED.find(level, pos, state, null, direction) != null;
		}

		public boolean exists() {
			return get() != null;
		}

		/** The block this access reads from, or null when it is not bound to one. */
		public BlockPos getPos() {
			var c = cache;
			return c != null ? c.getPos() : null;
		}

		/** The level of {@link #getPos()}, or null when this access is not bound to a block. */
		public Level getLevel() {
			var c = cache;
			return c != null ? c.getWorld() : null;
		}

		@Override
		public int getSlotCount() {
			ensureSlotCountsCached();
			return cachedSlotCount;
		}

		@Override
		public int getFreeSlotCount() {
			ensureSlotCountsCached();
			return cachedFreeSlotCount;
		}

		private void ensureSlotCountsCached() {
			var c = cache;
			long time = c != null ? c.getWorld().getGameTime() : -1;
			if (slotCountCheckTime != time) {
				Storage<ItemVariant> h = get();
				if (h == null) {
					cachedSlotCount = 0;
					cachedFreeSlotCount = 0;
				} else {
					int cnt = 0, em = 0;
					for (StorageView<ItemVariant> slot : h) {
						cnt++;
						if (slot.isResourceBlank()) em++;
					}
					cachedSlotCount = cnt;
					cachedFreeSlotCount = em;
				}
				slotCountCheckTime = time;
			}
		}

		@Override
		public void markInvalid() {
			cache = null;
			storageCache = null;
			// Drop the accumulated lastItems with the access: this face is no longer part of the
			// network, and a later reuse must not diff against a removed block's contents.
			ownTracker = null;
		}

		@Override
		public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
			if (get() instanceof IProxy p) {
				if (dejaVu.add(p)) {
					return p.getRootHandler(dejaVu);
				} else {
					return this;
				}
			}
			return this;
		}

		protected void onInvalid() {
		}
	}

	@Deprecated
	@Override
	Storage<ItemVariant> get();

	@Override
	default int getFreeSlotCount() {
		Storage<ItemVariant> h = get();
		if (h == null)return 0;
		int em = 0;
		for (StorageView<ItemVariant> slot : h) {
			if (slot.isResourceBlank())em++;
		}
		return em;
	}

	@Override
	default int getSlotCount() {
		Storage<ItemVariant> h = get();
		if (h == null)return 0;
		int cnt = 0;
		for (StorageView<ItemVariant> slot : h)cnt++;
		return cnt;
	}

	public static BlockFilter getBlockFilterAt(Level level, BlockPos pos, boolean make) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be == null)return null;
		BlockFilterComponent comp = StorageModComponents.BLOCK_FILTER.getNullable(be);
		if (comp == null)return null;
		return comp.getFilter(make);
	}

	public static void removeBlockFilterAt(Level level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be == null)return;
		BlockFilterComponent comp = StorageModComponents.BLOCK_FILTER.getNullable(be);
		if (comp != null) {
			comp.remove(level, pos);
		}
	}
}
