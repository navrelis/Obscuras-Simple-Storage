package com.obscura.storage.tile;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;


import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.block.BasicInventoryHopperBlock;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.util.BlockFace;
import com.obscura.storage.util.HopperLogic;
import com.obscura.storage.util.HopperMode;
import com.obscura.storage.util.ItemHandlers;
import com.obscura.storage.util.ItemKey;
import com.obscura.storage.util.ItemPredicate;
import com.obscura.storage.util.MergedStorage;
import com.obscura.storage.util.StorageSnapshot;

public class BasicInventoryHopperBlockEntity extends AbstractInventoryHopperBlockEntity {
	private ItemStack filter = ItemStack.EMPTY;
	private ItemPredicate filterPred;
	private int cooldown;
	private HopperMode mode = HopperMode.NORMAL;
	private int amount = HopperLogic.MIN_AMOUNT;
	/** Last seen redstone signal, saved so that a chunk reload doesn't count as a rising edge. */
	private boolean wasPowered;
	private boolean pulsePending;
	/** Ticks the shared network totals used by Keep Stock may be old, like the level emitter's. */
	private static final int COUNT_MAX_AGE = 4;

	public BasicInventoryHopperBlockEntity(BlockPos pos, BlockState state) {
		super(Content.invHopperBasicTile.get(), pos, state);
	}

	@Override
	protected void update() {
		if(!filter.isEmpty() && filterPred == null)setFilter(filter);//update predicate
		boolean hasFilter = filterPred != null;
		if(mode == HopperMode.PULSE) {
			// Idles between pulses, the regular move logic below is not used
			if(pulsePending) {
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
		if(topNet && !hasFilter)return;
		if(mode.needsFilter() && !hasFilter)return;
		if(cooldown > 0) {
			cooldown--;
			return;
		}
		if(!this.getBlockState().getValue(BasicInventoryHopperBlock.ENABLED))return;

		// The Network Doctor books the attempt to the network it works on
		NetworkStats stats = doctorStats;
		long t0 = NetworkDoctor.begin(stats);
		try {
			moveOne(hasFilter);
		} finally {
			NetworkDoctor.end(stats, level.getGameTime(), t0);
		}
	}

	/** One transfer attempt of the regular (non pulse) modes. */
	private void moveOne(boolean hasFilter) {
		// Only counted when a move is about to happen
		if(mode == HopperMode.KEEP_STOCK && !HopperLogic.needsStock(countInTarget(), amount)) {
			cooldown = 5;
			return;
		}

		if(ItemHandlers.move(top, bottom, hasFilter ? filterPred : s -> true, 1, this::dropOverflow) == 1) {
			// the next move happens exactly basicHopperCooldown ticks later
			cooldown = Math.max(0, Config.get().basicHopperCooldown - 1);
			// The next count must see this item, not the shared totals from before it
			if(mode == HopperMode.KEEP_STOCK && bottom instanceof MergedStorage m)m.getSnapshot().invalidate();
		} else {
			cooldown = 5;
		}
	}

	/** An item the target refused after the simulation said yes and that could not be put back. */
	private void dropOverflow(ItemStack stack) {
		Containers.dropItemStack(level, worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5, stack);
	}

	/** Matching items in the target, only called by Keep Stock right before a move. */
	private long countInTarget() {
		if(bottom instanceof MergedStorage m) {
			// A network: read the totals it shares with its other readers instead of walking every slot
			StorageSnapshot snapshot = m.getSnapshot();
			snapshot.refresh(m, level.getGameTime(), COUNT_MAX_AGE);
			long total = 0;
			for(Object2LongMap.Entry<ItemKey> e : Object2LongMaps.fastIterable(snapshot.counts())) {
				if(filterPred.test(e.getKey().toStack()))total += e.getLongValue();
			}
			return total;
		}
		long[] count = new long[1];
		ItemHandlers.forEach(bottom, (stack, c) -> {
			if(c > 0 && filterPred.test(stack))count[0] += c;
			return true;
		});
		return count[0];
	}

	/**
	 * Moves {@link #amount} filtered items right away, one item per transfer in its own transaction so the
	 * destination is looked up again for every item: the vanilla Crafter only accepts an item into the slot
	 * that keeps its slots balanced, so 9 items end up as one per slot. Whatever can't be moved is not carried over.
	 */
	private void runPulse() {
		if(filterPred == null)return;
		for(int i = 0; i < amount; i++) {
			if(ItemHandlers.move(top, bottom, filterPred, 1, this::dropOverflow) != 1)break;
		}
	}

	@Override
	protected boolean needsLookup() {
		return pulsePending;
	}

	@Override
	protected void onIdle() {
		// A pulse that cannot run now is dropped, it must not fire later when a cable or an inventory gets attached
		pulsePending = false;
	}

	@Override
	public boolean onRedstoneUpdate(boolean powered) {
		boolean rising = HopperLogic.isRisingEdge(wasPowered, powered);
		if(wasPowered != powered) {
			wasPowered = powered;
			setChanged();
		}
		if(!mode.isDisabledByRedstone()) {
			// Moved on the next server tick of this block entity
			if(rising)pulsePending = true;
			return true;
		}
		return !powered;
	}

	@Override
	public void saveAdditional(CompoundTag compound) {
		super.saveAdditional(compound);
		compound.put("Filter", getFilter().save(new CompoundTag()));
		compound.putByte("Mode", (byte) mode.ordinal());
		compound.putByte("Amount", (byte) amount);
		compound.putBoolean("Powered", wasPowered);
	}

	@Override
	public void load(CompoundTag nbtIn) {
		super.load(nbtIn);
		this.filter = ItemStack.of(nbtIn.getCompound("Filter"));
		// Missing keys (worlds from before the modes existed) read as 0: Normal mode, amount clamped to 1
		this.mode = HopperMode.byId(nbtIn.getByte("Mode"));
		this.amount = HopperLogic.clampAmount(nbtIn.getByte("Amount"));
		this.wasPowered = nbtIn.getBoolean("Powered");
	}

	public HopperMode getMode() {
		return mode;
	}

	public void setMode(HopperMode mode) {
		this.mode = mode;
		pulsePending = false;
		cooldown = 0;
		if(level != null && !level.isClientSide) {
			// Sync the stored signal first so switching into Pulse mode while powered doesn't fire a pulse
			wasPowered = level.hasNeighborSignal(worldPosition);
			BlockState state = getBlockState();
			if(state.getBlock() instanceof BasicInventoryHopperBlock b)
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
		setChanged();
	}

	public int cycleAmount() {
		setAmount(HopperLogic.nextAmount(amount));
		return amount;
	}

	public void setFilter(ItemStack filter) {
		this.filter = filter;
		if(this.filter.isEmpty())filterPred = null;
		else if(this.filter.getItem() instanceof IItemFilter i) {
			filterPred = i.createFilter(BlockFace.touching(level, worldPosition, getBlockState().getValue(InventoryCableConnectorBlock.FACING)), filter);
		} else {
			filterPred = ItemPredicate.sameItemSameTags(filter.copy());
		}
		setChanged();
	}

	public ItemStack getFilter() {
		return filter;
	}
}
