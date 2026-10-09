package com.obscura.storage.block.entity;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Content;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.InventorySlot;
import com.obscura.storage.inventory.MultiInventoryAccess;
import com.obscura.storage.inventory.NetworkInventory;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.inventory.TargetExclusion;
import com.obscura.storage.menu.ExporterMenu;
import com.obscura.storage.util.FilterAmount;
import com.obscura.storage.util.FilterBlockLogic;
import com.obscura.storage.util.FilterBlockStatus;

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
	/** Destination slots tried per source slot; one stack never needs more than {@link FilterBlockLogic#MAX_BATCH}. */
	private static final int MAX_DEST_SLOTS = FilterBlockLogic.MAX_BATCH;
	/** {@link #moveInto}: the target has no destination for the item. */
	private static final int NO_ROOM = -1;
	/** Weight of the target's slot count in {@link #targetChange()}, far above any tracker value difference that matters. */
	private static final long SLOT_COUNT_STEP = 0x9E3779B97F4A7C15L;
	/** A target that had no room is asked again this often, see {@link #idleRecheckTicks()}. */
	private static final int FULL_TARGET_RECHECK_TICKS = 100;

	private final NetworkInventory targetCache = new NetworkInventory();
	private FilterAmount amount = FilterAmount.STACK;
	/** The target of the running attempt, resolved by {@link #prepare()}. */
	private IInventoryAccess target;
	/** The last search found matching items the target had no room for. */
	private boolean targetRefused;

	public ExporterBlockEntity(BlockPos pos, BlockState state) {
		super(Content.exporterBE.get(), pos, state);
	}

	@Override
	public void saveAdditional(CompoundTag compound, HolderLookup.Provider provider) {
		super.saveAdditional(compound, provider);
		compound.putByte(AMOUNT_TAG, (byte) amount.ordinal());
	}

	@Override
	public void loadAdditional(CompoundTag nbtIn, HolderLookup.Provider provider) {
		super.loadAdditional(nbtIn, provider);
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
		targetCache.onLoad(level, frontPos, getFacing().getOpposite(), this);
		target = null;
		super.onPositionsChanged();
	}

	@Override
	public boolean isOnNetwork(NetworkStats network) {
		if (super.isOnNetwork(network))return true;
		if (network == null || !updatePositions())return false;
		return NetworkDoctor.statsOf(targetCache.getAccess(level, frontPos)) == network;
	}

	@Override
	protected boolean claimRun() {
		return FilterBlockLogic.exporterMayRun(isPowered());
	}

	@Override
	protected boolean prepare() {
		target = null;
		if (!level.isLoaded(frontPos))return false;
		IInventoryAccess t = targetCache.getAccess(level, frontPos);
		if (!targetCache.isValid())return false;
		target = t;
		return true;
	}

	@Override
	protected long targetChange() {
		long t = target.tracker().getChangeTracker(level);
		// A box put into an empty Shulker Dock adds 27 empty slots, which is no change of contents: the
		// slot count of a single inventory is part of the value (only ever compared for inequality)
		return target instanceof MultiInventoryAccess ? t : t + SLOT_COUNT_STEP * target.getSlotCount();
	}

	@Override
	protected int idleRecheckTicks() {
		return targetRefused ? FULL_TARGET_RECHECK_TICKS : 0;
	}

	@Override
	protected NetworkStats doctorStatsOf(IInventoryAccess source) {
		NetworkStats s = NetworkDoctor.statsOf(source);
		return s != null ? s : NetworkDoctor.statsOf(target);
	}

	@Override
	protected boolean operate(IInventoryAccess source, IInventoryChangeTracker tt) {
		IInventoryChangeTracker bt = target.tracker();
		InventorySlot slot = takeResume();
		if (slot == null)targetRefused = false;
		TargetExclusion exclusion = null;
		ItemStack type = ItemStack.EMPTY;
		// Item types the target took nothing of in this attempt, not offered to it again
		List<ItemStack> refused = null;
		int max = 1, moved = 0;
		for (int visits = 0; ; visits++) {
			if (visits >= MAX_SLOT_VISITS) {
				if (moved == 0)setResume(slot);
				break;
			}
			slot = tt.findSlotAfter(slot, filter, false, false);
			if (slot == null)break;
			// Worked out for the first candidate only: most attempts of an idle network find none
			if (exclusion == null) {
				exclusion = TargetExclusion.of(source, target);
				if (exclusion.isAll())return false;
			}
			if (exclusion.excludes(slot))continue;
			ItemStack cur = slot.getStack();
			if (cur.isEmpty() || !filter.test(cur))continue;
			if (!type.isEmpty()) {
				if (!ItemStack.isSameItemSameComponents(type, cur))continue;
			} else if (isRefused(refused, cur)) {
				continue;
			}
			// A copy: a slot may hand out its live stack, which changes when items are moved out of it
			ItemStack ref = cur.copyWithCount(1);
			if (type.isEmpty())max = FilterBlockLogic.batchSize(amount, ref.getMaxStackSize());
			int n = moveInto(slot, ref, Math.min(max - moved, cur.getCount()), bt);
			if (n > 0) {
				type = ref;
				moved += n;
				if (moved >= max)break;
			} else if (n == NO_ROOM) {
				targetRefused = true;
				// The target takes no more of the item this operation moves: done
				if (!type.isEmpty())break;
				if (refused == null)refused = new ArrayList<>();
				refused.add(ref);
			}
		}
		return moved > 0;
	}

	private static boolean isRefused(List<ItemStack> refused, ItemStack stack) {
		if (refused == null)return false;
		for (int i = 0; i < refused.size(); i++) {
			if (ItemStack.isSameItemSameComponents(refused.get(i), stack))return true;
		}
		return false;
	}

	/**
	 * Moves up to {@code want} items of {@code from} (which holds {@code type}) into the target, into as
	 * many destination slots as it takes. Simulate-then-move per slot pair, never extract-then-insert.
	 *
	 * @return the number of items that arrived in the target; {@link #NO_ROOM} when the target has no
	 *         place for this item at all (0: it has, but this source slot gave nothing)
	 */
	private int moveInto(InventorySlot from, ItemStack type, int want, IInventoryChangeTracker bt) {
		int moved = 0;
		InventorySlot dest = null;
		for (int i = 0; i < MAX_DEST_SLOTS && moved < want; i++) {
			StoredItemStack rest = new StoredItemStack(type, want - moved);
			dest = dest == null ? bt.findSlotDest(rest) : bt.findSlotDestAfter(dest, rest, false);
			if (dest == null)return i == 0 ? NO_ROOM : moved;
			moved += from.transferCount(want - moved, dest, this::dropAtBlock);
		}
		return moved;
	}

	@Override
	protected FilterBlockStatus blockedStatus() {
		return isPowered() ? FilterBlockStatus.STOPPED_BY_REDSTONE : null;
	}

	@Override
	protected boolean hasTarget() {
		if (!level.isLoaded(frontPos))return false;
		targetCache.getAccess(level, frontPos);
		return targetCache.isValid();
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
