package com.obscura.storage.inventory;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.EmptyItemHandler;

import com.obscura.storage.block.entity.BlockFilterAttachment;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.util.IValidInfo;

public interface PlatformInventoryAccess extends IInventoryAccess {
	public static final PlatformInventoryAccess EMPTY = new PlatformInventoryAccess() {

		@Override
		public IInventoryChangeTracker tracker() {
			return InventoryChangeTracker.NULL;
		}

		@Deprecated
		@Override
		public IItemHandler get() {
			return EmptyItemHandler.INSTANCE;
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
		private boolean valid;
		private BlockCapabilityCache<IItemHandler, Direction> itemCache;
		private IInventoryChangeTracker.Delegate tracker = new IInventoryChangeTracker.Delegate();
		/**
		 * The one tracker this access owns, for handlers that do not carry their own. Owned per access
		 * (per block face) rather than looked up by handler identity, like the Fabric twin: the
		 * accumulated slot state belongs to the face, and a re-created capability (a chest that became
		 * a double chest, a replaced block) is diffed against it instead of starting from nothing.
		 */
		private InventoryChangeTracker ownTracker;

		// Slot counts are asked for several times per tick (usage info, terminals): cache per game tick.
		private int cachedSlotCount = -1;
		private int cachedFreeSlotCount = -1;
		private long slotCountCheckTime = Long.MIN_VALUE;

		public void onLoad(Level level, BlockPos worldPosition, Direction side, IValidInfo isValid) {
			valid = true;
			itemCache = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, (ServerLevel) level, worldPosition, side, () -> valid && isValid.isObjectValid(), this::onInvalid);
		}

		protected void onInvalid() {
		}

		@Override
		public IInventoryChangeTracker tracker() {
			IItemHandler itemHandler = get();
			if (itemHandler == null) {
				tracker.setDelegate(InventoryChangeTracker.NULL);
			} else if (itemHandler instanceof IChangeTrackerAccess a) {
				// Handlers that carry their own tracker keep using it.
				tracker.setDelegate(a.tracker());
			} else {
				if (ownTracker == null)ownTracker = new InventoryChangeTracker(itemHandler);
				ownTracker.refresh(itemHandler, face());
				tracker.setDelegate(ownTracker);
			}
			return tracker;
		}

		private IItemHandler faceHandler;
		private PlatformMultiInventoryAccess.Face face;

		/**
		 * The container and face behind this access's handler when it is a sided wrapper (a furnace
		 * seen from one side), else null. Looked up once per handler: a capability is re-created when
		 * the block changes, and the handler object changes with it.
		 */
		PlatformMultiInventoryAccess.Face face() {
			IItemHandler h = get();
			if (h == null)return null;
			if (h != faceHandler) {
				face = PlatformMultiInventoryAccess.faceOf(h, getLevel(), getPos(), getSide());
				faceHandler = h;
			}
			return face;
		}

		@Deprecated
		@Override
		public IItemHandler get() {
			var c = itemCache;
			return c == null || !valid ? null : c.getCapability();
		}

		public static boolean hasInventoryAt(Level level, BlockPos pos, BlockState state, Direction direction) {
			return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, null, direction) != null;
		}

		@Override
		public void markInvalid() {
			valid = false;
			// Drop the accumulated slot state with the access: this face is no longer part of the
			// network, and a later reuse must not diff against a removed block's contents.
			ownTracker = null;
		}

		public boolean exists() {
			return get() != null;
		}

		/** The block this access reads from, or null when it is not bound to one. */
		public BlockPos getPos() {
			var c = itemCache;
			return c != null ? c.pos() : null;
		}

		/** The level of {@link #getPos()}, or null when this access is not bound to a block. */
		public Level getLevel() {
			var c = itemCache;
			return c != null ? c.level() : null;
		}

		/** The side of the block this access reads from, or null when it is not bound to one. */
		public Direction getSide() {
			var c = itemCache;
			return c != null ? c.context() : null;
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
			var c = itemCache;
			long time = c != null ? c.level().getGameTime() : -1;
			if (slotCountCheckTime != time) {
				IItemHandler h = get();
				if (h == null) {
					cachedSlotCount = 0;
					cachedFreeSlotCount = 0;
				} else {
					int cnt = h.getSlots(), em = 0;
					for (int i = 0; i < cnt; i++) {
						if (h.getStackInSlot(i).isEmpty())em++;
					}
					cachedSlotCount = cnt;
					cachedFreeSlotCount = em;
				}
				slotCountCheckTime = time;
			}
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

		@Override
		public String toString() {
			var c = itemCache;
			return "BlockInventoryAccess at " + (c != null ? c.pos() : "<unbound>");
		}
	}

	@Deprecated
	@Override
	IItemHandler get();

	@Override
	public default int getFreeSlotCount() {
		IItemHandler itemHandler = get();
		if (itemHandler == null)return 0;
		int empty = 0;
		for(int i = 0;i<itemHandler.getSlots();i++) {
			if(itemHandler.getStackInSlot(i).isEmpty())empty++;
		}
		return empty;
	}

	@Override
	public default int getSlotCount() {
		IItemHandler itemHandler = get();
		if (itemHandler == null)return 0;
		return itemHandler.getSlots();
	}

	public static BlockFilter getBlockFilterAt(Level level, BlockPos p, boolean make) {
		BlockEntity be = level.getBlockEntity(p);
		if (be == null || (!make && !be.hasData(Platform.BLOCK_FILTER)))return null;
		return be.getData(Platform.BLOCK_FILTER).getFilter();
	}

	public static void removeBlockFilterAt(Level level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be == null || !be.hasData(Platform.BLOCK_FILTER))return;
		BlockFilterAttachment f = be.getData(Platform.BLOCK_FILTER);
		f.getFilter().dropContents(level, pos);
		be.removeData(Platform.BLOCK_FILTER);
		be.setChanged();
		BlockFilter.bumpConfigEpoch();
	}
}
