package com.obscura.storage.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Content;
import com.obscura.storage.block.AbstractInventoryHopperBlock;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.InventorySlot;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.inventory.filter.ItemPredicate;
import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.migration.LegacyData;
import com.obscura.storage.Config;
import com.obscura.storage.util.BlockFaceReference;
import com.obscura.storage.util.HopperLogic;
import com.obscura.storage.util.HopperMode;

public class BasicInventoryHopperBlockEntity extends AbstractInventoryHopperBlockEntity {
	private static final ItemPredicate ALWAYS_TRUE = s -> true;
	private ItemStack filter = ItemStack.EMPTY;
	private int cooldown;
	private long topChange, bottomChange;
	public int waiting = 0;
	private ItemPredicate filterPred;
	private InventorySlot topSlot;
	private HopperMode mode = HopperMode.NORMAL;
	private int amount = HopperLogic.MIN_AMOUNT;
	/** Last seen redstone signal, saved so that a chunk reload doesn't count as a rising edge. */
	private boolean wasPowered;
	private boolean pulsePending;
	/** Network Doctor: the network the last transfer attempt worked on (the source side first). */
	private NetworkStats doctorStats;

	public BasicInventoryHopperBlockEntity(BlockPos pos, BlockState state) {
		super(Content.basicInvHopperBE.get(), pos, state);
	}

	@Override
	public void saveAdditional(CompoundTag compound, HolderLookup.Provider provider) {
		super.saveAdditional(compound, provider);
		ItemStack is = getFilter();
		if (!is.isEmpty())
			compound.put("Filter", is.save(provider, new CompoundTag()));
		compound.putByte("Mode", (byte) mode.ordinal());
		compound.putByte("Amount", (byte) amount);
		compound.putBoolean("Powered", wasPowered);
	}

	@Override
	public void loadAdditional(CompoundTag nbtIn, HolderLookup.Provider provider) {
		super.loadAdditional(nbtIn, provider);
		// LegacyData: a hopper saved by the 1.20.1 builds holds a 1.20.1 item stack
		this.filter = LegacyData.parseItemStack(provider, nbtIn.getCompound("Filter"));
		// Missing keys (worlds from before the modes existed) read as 0: Normal mode, amount clamped to 1
		this.mode = HopperMode.byId(nbtIn.getByte("Mode"));
		this.amount = HopperLogic.clampAmount(nbtIn.getByte("Amount"));
		this.wasPowered = nbtIn.getBoolean("Powered");
	}

	@Override
	public boolean onRedstoneUpdate(boolean powered) {
		boolean rising = HopperLogic.isRisingEdge(wasPowered, powered);
		if (wasPowered != powered) {
			wasPowered = powered;
			setChanged();
		}
		if (!mode.isDisabledByRedstone()) {
			// Moved on the next server tick of this block entity (same game tick for most redstone sources)
			if (rising)pulsePending = true;
			return true;
		}
		return !powered;
	}

	public HopperMode getMode() {
		return mode;
	}

	public void setMode(HopperMode mode) {
		this.mode = mode;
		pulsePending = false;
		topSlot = null;
		waiting = 0;
		cooldown = 0;
		if (level != null && !level.isClientSide) {
			// Sync the stored signal first so switching into Pulse mode while powered doesn't fire a pulse
			wasPowered = level.hasNeighborSignal(worldPosition);
			BlockState state = getBlockState();
			if (state.getBlock() instanceof AbstractInventoryHopperBlock b)
				b.updateState(level, worldPosition, state);
		}
		setChanged();
	}

	public HopperMode cycleMode() {
		setMode(mode.next());
		return mode;
	}

	public int getAmount() {
		return amount;
	}

	public void setAmount(int amount) {
		this.amount = HopperLogic.clampAmount(amount);
		waiting = 0;
		setChanged();
	}

	public int cycleAmount() {
		setAmount(HopperLogic.nextAmount(amount));
		return amount;
	}

	public void setFilter(ItemStack filter) {
		this.filter = filter;
		if (this.filter.isEmpty())filterPred = null;
		else if (this.filter.getItem() instanceof IItemFilter i) {
			filterPred = i.createFilter(BlockFaceReference.touching(level, worldPosition, getBlockState().getValue(AbstractInventoryHopperBlock.FACING)), filter);
		} else {
			filterPred = s -> ItemStack.isSameItemSameComponents(s.getStack(), filter);
		}
		// The cached slot was selected by the old filter, it must not be used to move another item.
		topSlot = null;
		waiting = 0;
		setChanged();
	}

	public ItemStack getFilter() {
		return filter;
	}

	// Read live from the config, nothing is cached so config changes apply without reloading the block.
	private static int baseCd() {
		return Math.max(1, Config.get().basicHopperCooldown);
	}

	private static int midCd() {
		return Math.max(4, baseCd() * 4 / 10);
	}

	private static int fastCd() {
		return Math.max(1, baseCd() / 10);
	}

	/** Value to store after a successful transfer so that the next one happens exactly {@code cd} ticks later. */
	public static int cooldownAfterMove(int cd) {
		return Math.max(0, cd - 1);
	}

	@Override
	protected void onPositionsChanged() {
		topSlot = null;
		waiting = 0;
		topChange = 0;
		bottomChange = 0;
		// The filter predicate references the touching face, rebuild it for the new facing.
		filterPred = null;
	}

	@Override
	public void updateServer() {
		if (!updatePositions())return;
		if(!filter.isEmpty() && filterPred == null)setFilter(filter);//update predicate
		if (mode == HopperMode.PULSE) {
			// Idle between pulses, the regular state machine below is not used
			if (pulsePending) {
				pulsePending = false;
				NetworkStats stats = doctorStats;
				long t0 = NetworkDoctor.begin(stats);
				try {
					runPulse();
				} finally {
					NetworkDoctor.end(stats, level.getGameTime(), t0);
				}
			}
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		IInventoryAccess top = topCache.getAccess(level, topPos);
		IInventoryAccess bottom = bottomCache.getAccess(level, bottomPos);
		NetworkStats stats = doctorStats = doctorStatsOf(top, bottom);
		long t0 = NetworkDoctor.begin(stats);
		try {
			transferTick(top, bottom);
		} finally {
			NetworkDoctor.end(stats, level.getGameTime(), t0);
		}
	}

	private static NetworkStats doctorStatsOf(IInventoryAccess top, IInventoryAccess bottom) {
		NetworkStats s = NetworkDoctor.statsOf(top);
		return s != null ? s : NetworkDoctor.statsOf(bottom);
	}

	/** One step of the regular (non pulse) state machine, timed for the Network Doctor. */
	private void transferTick(IInventoryAccess top, IInventoryAccess bottom) {
		boolean topNet = topCache.isNetwork();
		if (!topCache.isValid() || !bottomCache.isValid())return;
		if (!topNet && !bottomCache.isNetwork())return;
		boolean hasFilter = filterPred != null;
		if (topNet && !hasFilter)return;
		if (mode.needsFilter() && !hasFilter)return;
		if (!isEnabled())return;

		IInventoryChangeTracker tt = top.tracker();
		long t = tt.getChangeTracker(level);
		if (topChange != t) {
			topChange = t;
			waiting = 0;
			topSlot = null;
		} else cooldown = midCd();
		if (waiting == 1)return;

		IInventoryChangeTracker bt = bottom.tracker();
		long b = bt.getChangeTracker(level);
		if (bottomChange != b) {
			bottomChange = b;
			waiting = 0;
		} else cooldown = midCd();
		if (waiting == 2)return;

		boolean topWasNull = topSlot == null;
		if(hasFilter)filterPred.updateState();
		if (topSlot == null || waiting == 3)
			topSlot = tt.findSlotAfter(topSlot, hasFilter ? filterPred : ALWAYS_TRUE, false, true);

		if (topSlot == null) {
			if(topWasNull) {
				waiting = 1;
				cooldown = baseCd();
			} else {
				cooldown = midCd();
			}
			return;
		}

		ItemStack is = topSlot.getStack();
		if (is.isEmpty()) {
			waiting = 3;
			cooldown = fastCd();
			return;
		}
		StoredItemStack st = new StoredItemStack(is);

		// Only counted when a move is about to happen, then wait for the target to change
		if (mode == HopperMode.KEEP_STOCK && !HopperLogic.needsStock(countInTarget(bt), amount)) {
			waiting = 2;
			cooldown = baseCd();
			return;
		}

		InventorySlot bottomSlot = bt.findSlotDest(st);
		if (bottomSlot == null) {
			waiting = 3;
			cooldown = baseCd();
			return;
		}

		if (topSlot.transferTo(1, bottomSlot)) {
			cooldown = cooldownAfterMove(baseCd());
		} else {
			waiting = 3;
			cooldown = baseCd();
		}
	}

	/** Matching items in the target, only called by Keep Stock right before a move. */
	private long countInTarget(IInventoryChangeTracker bt) {
		if (filter.getItem() instanceof IItemFilter) {
			ItemPredicate pred = filterPred;
			return bt.streamWrappedStacks(false).filter(s -> pred.test(s)).mapToLong(StoredItemStack::getQuantity).sum();
		}
		return bt.countItems(new StoredItemStack(filter));
	}

	/**
	 * Moves {@link #amount} filtered items right away, one item at a time. The destination is looked up
	 * again for every item: the vanilla Crafter only accepts an item into the slot that keeps its slots
	 * balanced, so 9 items end up as one per slot. Whatever can't be moved is dropped, not carried over.
	 */
	private void runPulse() {
		IInventoryAccess top = topCache.getAccess(level, topPos);
		IInventoryAccess bottom = bottomCache.getAccess(level, bottomPos);
		doctorStats = doctorStatsOf(top, bottom);
		if (!topCache.isValid() || !bottomCache.isValid())return;
		if (!topCache.isNetwork() && !bottomCache.isNetwork())return;
		ItemPredicate pred = filterPred;
		if (pred == null)return;

		IInventoryChangeTracker tt = top.tracker();
		IInventoryChangeTracker bt = bottom.tracker();
		// Refresh the cached contents before searching, like the regular tick does
		tt.getChangeTracker(level);
		bt.getChangeTracker(level);
		pred.updateState();

		HopperLogic.<InventorySlot, InventorySlot>moveOneAtATime(amount, prev -> {
			if (prev != null) {
				ItemStack s = prev.getStack();
				if (!s.isEmpty() && pred.test(s))return prev;
			}
			return tt.findSlotAfter(prev, pred, false, true);
		}, src -> {
			ItemStack s = src.getStack();
			return s.isEmpty() ? null : bt.findSlotDest(new StoredItemStack(s, 1));
		}, (src, dst) -> src.transferTo(1, dst));

		// The regular state machine is not used in Pulse mode, start it fresh when switching back
		topSlot = null;
		waiting = 0;
	}
}