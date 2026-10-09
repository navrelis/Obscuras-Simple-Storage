package com.obscura.storage.tile;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.AbstractFilterBlock;
import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.doctor.INetworkMember;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.util.BlockFace;
import com.obscura.storage.util.ConditionSnapshots;
import com.obscura.storage.util.FilterBlockAccess;
import com.obscura.storage.util.FilterBlockLogic;
import com.obscura.storage.util.FilterBlockStatus;
import com.obscura.storage.util.GhostFilter;
import com.obscura.storage.util.HopperLogic;
import com.obscura.storage.util.ItemPredicate;
import com.obscura.storage.util.TickerUtil.TickableServer;

/**
 * Shared block entity of the Ejector, Disposal and Exporter: the source behind the block, the ghost
 * filter, the redstone state and the throttle. The item access itself is the loader's
 * {@link FilterBlockAccess}.
 *
 * <p>Tick cost: between two attempts the block only counts down ({@code filterBlockCooldown}). An
 * attempt that found nothing to do remembers what the source (and target) held and the filter state;
 * while nothing the filter matches changed there, the following attempts skip the slot search, so an
 * idle block costs one look at the item totals per cooldown (for a network the totals it shares with
 * its terminals and level emitters, counted again at most every {@link FilterBlockLogic#IDLE_MAX_AGE}
 * ticks for all of them together). There is never more than one operation per cooldown; the one
 * exception is the Ejector's Pulse mode, where every redstone pulse gives its batch on the next tick
 * ({@link #waitsForCooldown()}), still skipping the search while nothing changed.
 *
 * <p>A filter whose answers can change without an edit ({@link GhostFilter#isStable()} false, a
 * Polymorphic Filter entry) cannot wait like that: the block searches on every attempt and remembers
 * nothing. A datapack reload makes every idle block search once, tags and recipes may match other
 * items afterwards.
 */
public abstract class AbstractFilterBlockEntity extends BlockEntity implements TickableServer, MenuProvider, INetworkMember {
	public static final String FILTER_TAG = "Filter", EXACT_TAG = "Exact", POWERED_TAG = "Powered";
	/** Ticks a computed {@link #getStatus()} is reused for. */
	private static final int STATUS_TICKS = 5;

	protected final FilterBlockAccess source = new FilterBlockAccess(this::dropAtBlock);
	protected final GhostFilter filter = new GhostFilter(this::createEntryFilter);
	protected BlockPos sourcePos, frontPos;
	private Direction facing;
	/** Last seen redstone signal, saved so that a chunk reload doesn't count as a rising edge. */
	private boolean wasPowered;
	private int cooldown;
	private boolean tracked;
	/** The last search found nothing to do, with the filter at {@link #idleFilter}. */
	private boolean idle;
	private long idleFilter;
	/** {@link ConditionSnapshots#reloads()} when the block went idle. */
	private long idleReloads;
	/** Game time at which the block went idle, for {@link #idleRecheckTicks()}. */
	private long idleSince;
	private FilterBlockStatus status = FilterBlockStatus.RUNNING;
	private long statusTime = Long.MIN_VALUE;

	protected AbstractFilterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void saveAdditional(CompoundTag compound) {
		super.saveAdditional(compound);
		compound.put(FILTER_TAG, filter.save());
		compound.putBoolean(EXACT_TAG, filter.isExact());
		compound.putBoolean(POWERED_TAG, wasPowered);
	}

	@Override
	public void load(CompoundTag nbtIn) {
		super.load(nbtIn);
		filter.load(nbtIn.getList(FILTER_TAG, Tag.TAG_COMPOUND));
		filter.setExact(nbtIn.getBoolean(EXACT_TAG));
		wasPowered = nbtIn.getBoolean(POWERED_TAG);
		resetSearch();
	}

	@Override
	public boolean isOnNetwork(NetworkStats network) {
		return network != null && updatePositions() && source.resolve() && source.stats() == network;
	}

	/**
	 * Makes {@link #sourcePos}/{@link #frontPos} match the current FACING. The block can be rotated in
	 * place without replacing this block entity, so this is re-checked every tick.
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
			source.bind(level, worldPosition, sourcePos, facing);
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

	/** The block at {@code pos} next to this one was replaced or updated: what stands there is looked up again. */
	public void onNeighborChanged(BlockPos pos) {
		if (pos.equals(sourcePos))source.invalidate();
	}

	/**
	 * Follows the cables from {@code start} to the Inventory Connector of the network, like the Basic
	 * Inventory Hopper and the Level Emitter do. A connector or interface at {@code start} counts too.
	 *
	 * @param self the block that asks, never walked through
	 * @return the connector, null when the cables lead to none
	 */
	public static InventoryConnectorBlockEntity findConnector(Level level, BlockPos self, BlockPos start) {
		Stack<BlockPos> toCheck = new Stack<>();
		Set<BlockPos> checkedBlocks = new HashSet<>();
		checkedBlocks.add(self);
		toCheck.add(start);
		int max = Config.get().invConnectorMaxCables;
		while (!toCheck.isEmpty()) {
			BlockPos cp = toCheck.pop();
			if (!checkedBlocks.add(cp))continue;
			if (level.isLoaded(cp)) {
				BlockState state = level.getBlockState(cp);
				if (state.getBlock() == Content.connector.get()) {
					return level.getBlockEntity(cp) instanceof InventoryConnectorBlockEntity c ? c : null;
				}
				if (state.getBlock() instanceof IInventoryCable cable) {
					toCheck.addAll(cable.next(level, state, cp));
				}
			}
			if (checkedBlocks.size() > max)break;
		}
		return null;
	}

	/** The predicate of a ghost entry that is a filter item, like the Basic Inventory Hopper builds its own. */
	private ItemPredicate createEntryFilter(ItemStack stack) {
		if (level == null || facing == null || !(stack.getItem() instanceof IItemFilter f))return null;
		return f.createFilter(BlockFace.touching(level, worldPosition, facing), stack);
	}

	/** Forgets what the last attempts found: something that decides what an attempt does was changed. */
	protected void resetSearch() {
		idle = false;
		statusTime = Long.MIN_VALUE;
	}

	/** The last search found nothing to do and nothing changed since: the block only counts down. */
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
		if (!tracked) {
			tracked = true;
			NetworkDoctor.track(this);
		}
		boolean throttled = waitsForCooldown();
		if (cooldown > 0) {
			cooldown--;
			if (throttled)return;
		}
		// An empty filter matches nothing: no attempt at all, whatever else is set
		if (!claimRun() || filter.isEmpty())return;
		if (throttled)cooldown = FilterBlockLogic.cooldownAfterOperation(cooldownTicks());
		if (!source.resolve() || !prepare())return;
		NetworkStats stats = doctorStats();
		long t0 = NetworkDoctor.begin(stats);
		try {
			attempt();
		} finally {
			NetworkDoctor.end(stats, level.getGameTime(), t0);
		}
	}

	/** One attempt, timed for the Network Doctor. */
	private void attempt() {
		filter.updateState();
		long f = filter.stateVersion();
		long reloads = ConditionSnapshots.reloads();
		long now = level.getGameTime();
		// What was remembered only tells something while the same stack always gets the same answer
		boolean stable = filter.isStable();
		if (idle) {
			int recheck = idleRecheckTicks();
			boolean due = recheck > 0 && (now - idleSince >= recheck || now < idleSince);
			if (!due && f == idleFilter && reloads == idleReloads && stable) {
				int maxAge = FilterBlockLogic.idleMaxAge(waitsForCooldown());
				if (!source.changedFor(filter, maxAge) && !targetChanged(maxAge))return;
			}
			idle = false;
		}
		if (operate())return;
		if (stable) {
			// Read again right after the search: exactly what it saw is what the block now waits on
			source.markSeen();
			markTarget();
		}
		idleFilter = f;
		idleReloads = reloads;
		idleSince = now;
		idle = true;
	}

	/**
	 * After this many ticks an idle block searches again although nothing it watches changed; 0 = never.
	 * For the one case the item totals cannot show: a target that refused items and may accept them
	 * now without its contents having changed.
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

	/**
	 * Whether the target resolved by {@link #prepare()} changed since {@link #markTarget()}.
	 *
	 * @param maxAge how old the item totals read for it may be, in ticks
	 */
	protected boolean targetChanged(int maxAge) {
		return false;
	}

	/** Remembers the target as it is now, for {@link #targetChanged}. Only called while the filter is stable. */
	protected void markTarget() {
	}

	/** The network an attempt is booked to: the source's, else the target's. */
	protected NetworkStats doctorStats() {
		return source.stats();
	}

	/**
	 * One operation on the resolved source, with the filter state up to date.
	 *
	 * @return whether anything was taken out of the source
	 */
	protected abstract boolean operate();

	/** Last resort for items that are in neither inventory any more: drop them here and say so. */
	protected void dropAtBlock(ItemStack stack) {
		if (stack.isEmpty() || level == null)return;
		StorageMod.LOGGER.warn("{} at {}: could not put {} back, dropping it", getMenuKey(), worldPosition, stack);
		Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, stack);
	}

	/** What the block is doing right now, for its GUI. Server side; cached for a few ticks. */
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
		if (!source.resolve())return FilterBlockStatus.NO_SOURCE;
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
