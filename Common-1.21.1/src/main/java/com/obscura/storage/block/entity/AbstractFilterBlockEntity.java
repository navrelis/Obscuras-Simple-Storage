package com.obscura.storage.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Config;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.AbstractFilterBlock;
import com.obscura.storage.doctor.INetworkMember;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.InventorySlot;
import com.obscura.storage.inventory.NetworkInventory;
import com.obscura.storage.inventory.filter.GhostFilter;
import com.obscura.storage.inventory.filter.ItemPredicate;
import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.platform.PlatformBlockEntity;
import com.obscura.storage.util.BlockFaceReference;
import com.obscura.storage.util.FilterAmount;
import com.obscura.storage.util.FilterBlockLogic;
import com.obscura.storage.util.FilterBlockStatus;
import com.obscura.storage.util.HopperLogic;
import com.obscura.storage.util.TickerUtil.TickableServer;

/**
 * Shared block entity of the Ejector, Disposal and Exporter: the source behind the block, the ghost
 * filter, the redstone state and the throttle.
 *
 * <p>Tick cost: between two attempts the block only counts down ({@code filterBlockCooldown}). An
 * attempt that found nothing to do remembers the change tracker values of the source (and target)
 * and the filter state; while those are unchanged the following attempts skip the slot search, so an
 * idle block costs one tracker read per cooldown. There is never more than one operation per cooldown;
 * the one exception is the Ejector's Pulse mode, where every redstone pulse gives its batch on the next
 * tick ({@link #waitsForCooldown()}), still skipping the search while nothing changed.
 * A search covers at most {@link #MAX_SLOT_VISITS} matching slots per attempt and continues where it
 * stopped on the next one.
 */
public abstract class AbstractFilterBlockEntity extends PlatformBlockEntity implements TickableServer, MenuProvider, INetworkMember {
	public static final String FILTER_TAG = "Filter", EXACT_TAG = "Exact", POWERED_TAG = "Powered";
	/**
	 * Source slots one attempt looks at. A search that runs into it without moving anything continues
	 * after the last slot on the next attempt (slots that match but cannot be extracted from, like the
	 * fuel slot of a furnace seen from the side, must not hide the inventories behind them).
	 */
	protected static final int MAX_SLOT_VISITS = 256;
	/** Ticks a computed {@link #getStatus()} is reused for. */
	private static final int STATUS_TICKS = 5;

	protected final NetworkInventory sourceCache = new NetworkInventory();
	protected final GhostFilter filter = new GhostFilter(this::createEntryFilter);
	protected BlockPos sourcePos, frontPos;
	private Direction facing;
	/** Last seen redstone signal, saved so that a chunk reload doesn't count as a rising edge. */
	private boolean wasPowered;
	private int cooldown;
	/** A whole search pass found nothing to do, at the pass values below. */
	private boolean idle;
	/** Tracker values of the source and target and the filter state the running search pass started at. */
	private long passSource, passTarget, passFilter;
	/** Game time at which the block went idle, for {@link #idleRecheckTicks()}. */
	private long idleSince;
	/** Where the next attempt continues its search pass, see {@link #MAX_SLOT_VISITS}. */
	private InventorySlot resume;
	private FilterBlockStatus status = FilterBlockStatus.RUNNING;
	private long statusTime = Long.MIN_VALUE;

	protected AbstractFilterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void saveAdditional(CompoundTag compound, HolderLookup.Provider provider) {
		super.saveAdditional(compound, provider);
		compound.put(FILTER_TAG, filter.save(provider));
		compound.putBoolean(EXACT_TAG, filter.isExact());
		compound.putBoolean(POWERED_TAG, wasPowered);
	}

	@Override
	public void loadAdditional(CompoundTag nbtIn, HolderLookup.Provider provider) {
		super.loadAdditional(nbtIn, provider);
		filter.load(nbtIn.getList(FILTER_TAG, Tag.TAG_COMPOUND), provider);
		filter.setExact(nbtIn.getBoolean(EXACT_TAG));
		wasPowered = nbtIn.getBoolean(POWERED_TAG);
		resetSearch();
	}

	@Override
	public void onLoad() {
		super.onLoad();
		if (updatePositions())NetworkDoctor.track(this);
	}

	@Override
	public boolean isOnNetwork(NetworkStats network) {
		if (network == null || !updatePositions())return false;
		return NetworkDoctor.statsOf(sourceCache.getAccess(level, sourcePos)) == network;
	}

	/**
	 * Makes {@link #sourcePos}/{@link #frontPos} match the current FACING. The deferred onLoad can run
	 * after the first tick, and the block can be rotated in place without replacing this block entity,
	 * so this is re-checked every tick.
	 *
	 * @return false while the positions are not available (client side or no level yet)
	 */
	protected boolean updatePositions() {
		if (level == null || level.isClientSide)return false;
		BlockState state = getBlockState();
		if (!state.hasProperty(AbstractFilterBlock.FACING))return false;
		Direction facing = state.getValue(AbstractFilterBlock.FACING);
		if (facing != this.facing || sourcePos == null || frontPos == null) {
			this.facing = facing;
			frontPos = worldPosition.relative(facing);
			sourcePos = worldPosition.relative(facing.getOpposite());
			sourceCache.onLoad(level, sourcePos, facing, this);
			onPositionsChanged();
		}
		return true;
	}

	/** Called after the source/front positions were (re)computed, drop state tied to the old ones. */
	protected void onPositionsChanged() {
		// A filter item's predicate references the touching face, rebuild it for the new facing.
		filter.markDirty();
		resetSearch();
	}

	/** The front, null until {@link #updatePositions()} ran. */
	protected Direction getFacing() {
		return facing;
	}

	/** The predicate of a ghost entry that is a filter item, like the Basic Inventory Hopper builds its own. */
	private ItemPredicate createEntryFilter(ItemStack stack) {
		if (level == null || facing == null || !(stack.getItem() instanceof IItemFilter f))return null;
		return f.createFilter(BlockFaceReference.touching(level, worldPosition, facing), stack);
	}

	/** Forgets what the last attempts found: something that decides what an attempt does was changed. */
	protected void resetSearch() {
		idle = false;
		resume = null;
		statusTime = Long.MIN_VALUE;
	}

	/** The last search pass found nothing to do and nothing changed since: the block only counts down. */
	public boolean isIdle() {
		return idle;
	}

	public GhostFilter getFilter() {
		return filter;
	}

	/** Puts a reference copy (count 1) of {@code stack} into ghost slot {@code slot}, or clears it. */
	public void setFilterStack(int slot, ItemStack stack) {
		filter.setStack(slot, stack);
		resetSearch();
		setChanged();
	}

	public void setExact(boolean exact) {
		filter.setExact(exact);
		resetSearch();
		setChanged();
	}

	/** Called on the server whenever the block re-reads its redstone signal (neighbor change, placement). */
	public void onRedstoneUpdate(boolean powered) {
		boolean rising = HopperLogic.isRisingEdge(wasPowered, powered);
		if (wasPowered != powered) {
			wasPowered = powered;
			statusTime = Long.MIN_VALUE;
			setChanged();
			onPowerChanged(rising);
		}
	}

	/** The redstone signal changed. {@code rising}: it turned on. */
	protected void onPowerChanged(boolean rising) {
	}

	public boolean isPowered() {
		return wasPowered;
	}

	// Read live from the config, nothing is cached so config changes apply without reloading the block.
	private static int cooldownTicks() {
		return FilterBlockLogic.clampCooldown(Config.get().filterBlockCooldown);
	}

	@Override
	public void updateServer() {
		if (!updatePositions())return;
		boolean throttled = waitsForCooldown();
		if (cooldown > 0) {
			cooldown--;
			if (throttled)return;
		}
		// An empty filter matches nothing: no attempt at all, whatever else is set
		if (!claimRun() || filter.isEmpty())return;
		if (throttled)cooldown = FilterBlockLogic.cooldownAfterOperation(cooldownTicks());
		IInventoryAccess source = sourceCache.getAccess(level, sourcePos);
		if (!sourceCache.isValid() || !prepare())return;
		NetworkStats stats = doctorStatsOf(source);
		long t0 = NetworkDoctor.begin(stats);
		try {
			attempt(source);
		} finally {
			NetworkDoctor.end(stats, level.getGameTime(), t0);
		}
	}

	/** One attempt, timed for the Network Doctor. */
	private void attempt(IInventoryAccess source) {
		IInventoryChangeTracker tt = source.tracker();
		// Always before a slot search: this refreshes the cached slot contents the search reads
		long s = tt.getChangeTracker(level);
		long t = targetChange();
		filter.updateState();
		long f = filter.stateVersion();
		long now = level.getGameTime();
		if (idle) {
			int recheck = idleRecheckTicks();
			boolean due = recheck > 0 && (now - idleSince >= recheck || now < idleSince);
			if (!due && s == passSource && t == passTarget && f == passFilter && f != ItemPredicate.UNVERSIONED)return;
			idle = false;
		}
		// A pass that is continued keeps its place when only the contents changed; the slots it already
		// passed are looked at by the next pass (see below). A changed filter starts over.
		if (f != passFilter)resume = null;
		if (resume == null) {
			passSource = s;
			passTarget = t;
			passFilter = f;
		}
		boolean worked = operate(source, tt);
		// Idle only after a pass that saw every slot at the values it started with
		idle = !worked && resume == null && s == passSource && t == passTarget && f == passFilter;
		if (idle)idleSince = now;
	}

	/**
	 * After this many ticks an idle block searches again although nothing it watches changed; 0 = never.
	 * For the one case the change trackers cannot see: a target that refused items and may accept them
	 * now without its contents having changed (an empty box put into a Shulker Dock).
	 */
	protected int idleRecheckTicks() {
		return 0;
	}

	/**
	 * Whether attempts are at least a cooldown apart. False only where something else already limits
	 * them: the Ejector's Pulse mode works once per redstone rising edge.
	 */
	protected boolean waitsForCooldown() {
		return true;
	}

	/**
	 * Whether an attempt may start now (redstone, pause...). Only asked when the cooldown has run out;
	 * consumes whatever triggers the attempt (the Ejector's pending pulse). Must be cheap: it is the
	 * only thing a block that may not run does per tick.
	 */
	protected abstract boolean claimRun();

	/** Resolves what the attempt needs besides the source; false when it cannot run now (front not loaded, no target). */
	protected boolean prepare() {
		return true;
	}

	/** Change tracker value of the target resolved by {@link #prepare()}, 0 when the block has none. */
	protected long targetChange() {
		return 0L;
	}

	/** The network an attempt is booked to: the source's, else the target's. */
	protected NetworkStats doctorStatsOf(IInventoryAccess source) {
		return NetworkDoctor.statsOf(source);
	}

	/**
	 * One operation. The trackers were refreshed and the filter state is up to date.
	 *
	 * @return whether anything was taken out of the source
	 */
	protected abstract boolean operate(IInventoryAccess source, IInventoryChangeTracker tt);

	/** The slot the search continues after, see {@link #MAX_SLOT_VISITS}; null to start from the beginning. */
	protected InventorySlot takeResume() {
		InventorySlot slot = resume;
		resume = null;
		return slot;
	}

	protected void setResume(InventorySlot slot) {
		resume = slot;
	}

	/**
	 * Takes one batch out of the source: matching items of ONE type (same item and components), a
	 * single item or up to one stack, gathered from as many slots as it takes.
	 *
	 * <p>Every slot is read again right before it is extracted from (a tracker's cached contents can
	 * be a scan behind), and whatever comes out other than expected goes straight back, so nothing
	 * that does not match the filter leaves the source.
	 *
	 * @return the batch as one stack, EMPTY when nothing matching can be taken
	 */
	protected ItemStack extractBatch(IInventoryAccess source, IInventoryChangeTracker tt, FilterAmount amount) {
		InventorySlot slot = takeResume();
		ItemStack batch = ItemStack.EMPTY;
		int max = 1;
		for (int visits = 0; ; visits++) {
			if (visits >= MAX_SLOT_VISITS) {
				if (batch.isEmpty())setResume(slot);
				break;
			}
			slot = tt.findSlotAfter(slot, filter, false, false);
			if (slot == null)break;
			ItemStack cur = slot.getStack();
			if (cur.isEmpty() || !filter.test(cur))continue;
			if (batch.isEmpty())max = FilterBlockLogic.batchSize(amount, cur.getMaxStackSize());
			else if (!ItemStack.isSameItemSameComponents(batch, cur))continue;
			// A copy: a slot may hand out its live stack, which changes when it is extracted from
			ItemStack ref = cur.copyWithCount(1);
			ItemStack ex = slot.extract(max - batch.getCount());
			if (ex.isEmpty())continue;
			if (!ItemStack.isSameItemSameComponents(ex, ref)) {
				giveBack(source, ex);
				continue;
			}
			if (batch.isEmpty())batch = ex;
			else batch.grow(ex.getCount());
			if (batch.getCount() >= max)break;
		}
		return batch;
	}

	/** Returns {@code stack} to the source. Whatever does not fit is dropped at the block, never lost. */
	protected void giveBack(IInventoryAccess source, ItemStack stack) {
		ItemStack rest = source.pushStack(stack);
		if (!rest.isEmpty())dropAtBlock(rest);
	}

	/** Last resort for items that are in neither inventory any more: drop them here and say so. */
	protected void dropAtBlock(ItemStack stack) {
		if (stack.isEmpty() || level == null)return;
		StorageMod.LOGGER.warn("{} at {}: could not put {} back, dropping it", getMenuKey(), worldPosition, stack);
		Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, stack);
	}

	/** What the block is doing right now, for its GUI and Jade. Server side; cached for a few ticks. */
	public FilterBlockStatus getStatus() {
		if (level == null || !updatePositions())return FilterBlockStatus.RUNNING;
		long now = level.getGameTime();
		if (statusTime == Long.MIN_VALUE || now - statusTime >= STATUS_TICKS || now < statusTime) {
			statusTime = now;
			status = computeStatus();
		}
		return status;
	}

	private FilterBlockStatus computeStatus() {
		FilterBlockStatus blocked = blockedStatus();
		if (blocked != null)return blocked;
		sourceCache.getAccess(level, sourcePos);
		if (!sourceCache.isValid())return FilterBlockStatus.NO_SOURCE;
		if (!hasTarget())return FilterBlockStatus.NO_TARGET;
		if (filter.isEmpty())return FilterBlockStatus.NO_FILTER;
		return FilterBlockStatus.RUNNING;
	}

	/** The status while redstone or a pause keeps the block from working, null when nothing does. */
	protected abstract FilterBlockStatus blockedStatus();

	/** Whether the block has what it outputs into. Only the Exporter needs an inventory there. */
	protected boolean hasTarget() {
		return true;
	}

	/** The block's registry path, also the key of its menu title. */
	protected abstract String getMenuKey();

	@Override
	public Component getDisplayName() {
		return Component.translatable("menu.obscuras_storage." + getMenuKey());
	}

	public boolean stillValid(Player p_59619_) {
		if (this.level.getBlockEntity(this.worldPosition) != this) {
			return false;
		} else {
			return !(p_59619_.distanceToSqr(this.worldPosition.getX() + 0.5D, this.worldPosition.getY() + 0.5D, this.worldPosition.getZ() + 0.5D) > 64.0D);
		}
	}
}
