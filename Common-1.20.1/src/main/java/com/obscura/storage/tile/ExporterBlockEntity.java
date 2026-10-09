package com.obscura.storage.tile;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import com.obscura.storage.Content;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.gui.ExporterMenu;
import com.obscura.storage.util.FilterAmount;
import com.obscura.storage.util.FilterBlockAccess;
import com.obscura.storage.util.FilterBlockLogic;
import com.obscura.storage.util.FilterBlockStatus;
import com.obscura.storage.util.TargetExclusion;

/**
 * Exporter: moves matching items from the source into the inventory in front, slot to slot, one item
 * or up to one stack of one item type per operation. A redstone signal stops it.
 *
 * <p>The target may itself be connected to the source network. Slots that lie in the target are never
 * taken from ({@link TargetExclusion}), items that are already there count as done, so the block
 * settles instead of moving the same items forever.
 */
public class ExporterBlockEntity extends AbstractFilterBlockEntity {
	public static final String AMOUNT_TAG = "Amount";
	/** A target that had no room is asked again this often, see {@link #idleRecheckTicks()}. */
	private static final int FULL_TARGET_RECHECK_TICKS = 100;

	private final FilterBlockAccess target = new FilterBlockAccess(this::dropAtBlock);
	private FilterAmount amount = FilterAmount.STACK;
	/** The last search found matching items the target had no room for. */
	private boolean targetRefused;

	public ExporterBlockEntity(BlockPos pos, BlockState state) {
		super(Content.exporterTile.get(), pos, state);
	}

	@Override
	public void saveAdditional(CompoundTag compound) {
		super.saveAdditional(compound);
		compound.putByte(AMOUNT_TAG, (byte) amount.ordinal());
	}

	@Override
	public void load(CompoundTag nbtIn) {
		super.load(nbtIn);
		// A missing key (never written by this block) reads as the default, a whole stack
		amount = nbtIn.contains(AMOUNT_TAG) ? FilterAmount.byId(nbtIn.getByte(AMOUNT_TAG), FilterAmount.STACK) : FilterAmount.STACK;
	}

	public FilterAmount getAmount() {
		return amount;
	}

	public void setAmount(FilterAmount amount) {
		this.amount = amount;
		resetSearch();
		setChanged();
	}

	@Override
	protected void onPositionsChanged() {
		target.bind(level, worldPosition, frontPos, getFacing().getOpposite());
		super.onPositionsChanged();
	}

	@Override
	public void onNeighborChanged(BlockPos pos) {
		super.onNeighborChanged(pos);
		if (pos.equals(frontPos))target.invalidate();
	}

	@Override
	public boolean isOnNetwork(NetworkStats network) {
		if (super.isOnNetwork(network))return true;
		return network != null && updatePositions() && hasTarget() && target.stats() == network;
	}

	@Override
	protected boolean claimRun() {
		return FilterBlockLogic.exporterMayRun(isPowered());
	}

	@Override
	protected boolean prepare() {
		return hasTarget();
	}

	@Override
	protected boolean targetChanged(int maxAge) {
		return target.changed(maxAge);
	}

	@Override
	protected void markTarget() {
		target.markSeen();
	}

	@Override
	protected int idleRecheckTicks() {
		return targetRefused ? FULL_TARGET_RECHECK_TICKS : 0;
	}

	@Override
	protected NetworkStats doctorStats() {
		NetworkStats s = source.stats();
		return s != null ? s : target.stats();
	}

	@Override
	protected boolean operate() {
		int moved = source.moveTo(target, filter, amount);
		targetRefused = source.wasRefused();
		return moved > 0;
	}

	/**
	 * Which source slots lie in the target right now: both sides are looked up again. For tests and
	 * diagnostics, null while a side is missing.
	 */
	public TargetExclusion exclusion() {
		if (!updatePositions() || !source.resolve() || !hasTarget())return null;
		return source.exclusion(target);
	}

	/** The stocked source slots {inside the target, outside it}, see {@link #exclusion()}; null while a side is missing. */
	public int[] countSourceSlots() {
		TargetExclusion ex = exclusion();
		return ex == null ? null : source.countSlots(ex);
	}

	/**
	 * The blocks the inventory at {@code pos} consists of: the block itself and, for a double chest,
	 * its other half (found like the Inventory Connector finds it) while that is loaded.
	 *
	 * @param pos a loaded position
	 */
	public static List<BlockPos> blocksOf(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos opos = pos.relative(ChestBlock.getConnectedDirection(state));
			// Never load a chunk to find out: a half that is not loaded is not looked at
			if (!level.isLoaded(opos))return List.of(pos.immutable());
			BlockState ostate = level.getBlockState(opos);
			if (state.getBlock() == ostate.getBlock()) {
				ChestType otype = ostate.getValue(ChestBlock.TYPE);
				if (otype != ChestType.SINGLE && state.getValue(ChestBlock.TYPE) != otype && state.getValue(ChestBlock.FACING) == ostate.getValue(ChestBlock.FACING)) {
					return List.of(pos.immutable(), opos);
				}
			}
		}
		return List.of(pos.immutable());
	}

	@Override
	protected FilterBlockStatus blockedStatus() {
		return isPowered() ? FilterBlockStatus.STOPPED_BY_REDSTONE : null;
	}

	@Override
	protected boolean hasTarget() {
		return level.isLoaded(frontPos) && target.resolve();
	}

	@Override
	protected String getMenuKey() {
		return "exporter";
	}

	@Override
	public AbstractContainerMenu createMenu(int p_createMenu_1_, Inventory p_createMenu_2_, Player p_createMenu_3_) {
		return new ExporterMenu(p_createMenu_1_, p_createMenu_2_, this);
	}
}
