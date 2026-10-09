package com.obscura.storage.tile;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.tile.InventoryConnectorBlockEntity.LinkedInv;
import com.obscura.storage.util.BlockFilter;
import com.obscura.storage.util.BlockFilterData;
import com.obscura.storage.util.FilteredStorage;
import com.obscura.storage.util.FrameLabel;
import com.obscura.storage.util.FrameLabels;
import com.obscura.storage.util.LabelledStorage;
import com.obscura.storage.util.IInventoryLink;
import com.obscura.storage.util.IProxy;
import com.obscura.storage.util.ItemHandlers;
import com.obscura.storage.util.NetworkChanges;
import com.obscura.storage.util.Priority;
import com.obscura.storage.util.TickerUtil.TickableServer;

public class AbstractInventoryCableConnectorBlockEntity extends PaintedBlockEntity implements TickableServer, IItemHandler, IProxy, ItemHandlers.Delegating {
	/** Ticks after which the cables are searched again even when nothing reported a change. */
	private static final int HEARTBEAT = 20;
	protected InventoryConnectorBlockEntity master;
	protected IItemHandler pointedAt, masterW;
	/** {@link #pointedAt} behind the block filters of this connector and of its target, null when skipped. */
	private IItemHandler filteredPointedAt;
	protected LinkedInv linv;
	private final Stack<BlockPos> toCheck = new Stack<>();
	private final Set<BlockPos> checkedBlocks = new HashSet<>();
	private long lastScan, lastGeneration;
	private boolean scanned;
	private LazyOptional<IItemHandler> capability;

	public AbstractInventoryCableConnectorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void updateServer() {
		long time = level.getGameTime();
		if((time + (worldPosition.hashCode() & 0x7FFFFFFF)) % 5 == 0) {
			// Search the cables again when something reported a change (cable, trim, neighbour, filter), otherwise only as a heartbeat
			long generation = NetworkChanges.get();
			boolean changed = generation != lastGeneration;
			if(scanned && !changed && time >= lastScan && time - lastScan < HEARTBEAT)return;
			// Network Doctor: booked to the network found by the previous search (the first one is not timed)
			NetworkStats stats = master != null ? master.getDoctorStats() : null;
			long t0 = NetworkDoctor.begin(stats);
			try {
				search(time, generation, changed);
			} finally {
				NetworkDoctor.end(stats, time, t0);
			}
		}
	}

	/** The cable search of {@link #updateServer}. */
	private void search(long time, long generation, boolean changed) {
		scanned = true;
		lastScan = time;
		lastGeneration = generation;
		BlockState state = level.getBlockState(worldPosition);
		Direction facing = state.getValue(InventoryCableConnectorBlock.FACING);
		toCheck.clear();
		checkedBlocks.clear();
		checkedBlocks.add(worldPosition);
		toCheck.addAll(((IInventoryCable)state.getBlock()).next(level, state, worldPosition));
		InventoryConnectorBlockEntity found = null;
		while(!toCheck.isEmpty()) {
			BlockPos cp = toCheck.pop();
			if(!checkedBlocks.contains(cp)) {
				checkedBlocks.add(cp);
				if(level.isLoaded(cp)) {
					state = level.getBlockState(cp);
					if(state.getBlock() == Content.connector.get()) {
						BlockEntity te = level.getBlockEntity(cp);
						if(te instanceof InventoryConnectorBlockEntity) {
							found = (InventoryConnectorBlockEntity) te;
						}
						break;
					}
					if(state.getBlock() instanceof IInventoryCable) {
						toCheck.addAll(((IInventoryCable)state.getBlock()).next(level, state, cp));
					}
				}
				if(checkedBlocks.size() > StorageMod.CONFIG.invConnectorMaxCables)break;
			}
		}
		BlockPos target = worldPosition.relative(facing);
		if(found != master || linv == null || !target.equals(linv.pos)) {
			// Connected to another network or pointing elsewhere: link again. Otherwise the link stays and is only kept alive.
			if(master != null)master.unLink(linv);
			linv = new LinkedInv();
			linv.pos = target;
			initLinv();
			if(found != null) {
				linv.time = time;
				linv.handler = this::getPointedAtHandler;
				found.addLinked(linv);
			}
		} else if(found != null) {
			linv.time = time;
		}
		master = found;
		masterW = found != null ? applyFilter(found.getInventory()) : null;
		pointedAt = getPointedAt(target, facing);
		updateBlockFilter(target);
		IInventoryLink link = getLink();
		if(linv.link != link) {
			linv.link = link;
			if(found != null)found.requestRescan();
		}
		// What this connector points at may have changed with the block change that got us here, so have the network read it again
		if(changed && found != null)found.requestRescan();
	}

	protected void initLinv() {
	}

	/** The Inventory Connector whose network this connector works for, null when no connector is reachable over the cables. */
	public InventoryConnectorBlockEntity getMaster() {
		return master;
	}

	/** The block this connector faces (reads from). */
	public BlockPos getTargetPos() {
		BlockState state = getBlockState();
		return state.hasProperty(InventoryCableConnectorBlock.FACING) ? worldPosition.relative(state.getValue(InventoryCableConnectorBlock.FACING)) : worldPosition;
	}

	/** The Inventory Link end this connector is, null when it is a normal connector. */
	protected IInventoryLink getLink() {
		return null;
	}

	protected IItemHandler getPointedAt(BlockPos pos, Direction facing) {
		return ItemHandlers.find(level, pos, facing.getOpposite());
	}

	/**
	 * Applies the Inventory Configurator's block filters to what this connector points at, like the inventory
	 * connector does for an inventory it touches: the filter on this connector (the Configurator replaced the
	 * Filtered Inventory Cable Connector) and the filter on the target block itself.
	 */
	private void updateBlockFilter(BlockPos target) {
		IItemHandler s = pointedAt;
		Priority pr = Priority.NORMAL;
		if(s == null && level.getBlockState(target).isAir()) {
			// Like the inventory connector scan: a block filter whose block vanished (explosion, piston) is cleaned up
			BlockFilterData.get(level).onBlockRemoved(level, target);
		} else if(s != null) {
			BlockFilterData data = BlockFilterData.get(level);
			BlockFilter own = data.findContaining(level, worldPosition);
			BlockFilter tgt = data.findContaining(level, target);
			// The target's filter first, then this connector's: an item has to pass both.
			// One filter covering both blocks applies once.
			if(tgt != null && tgt != own) {
				if(tgt.skip())s = null;
				else {
					s = tgt.wrap(level, s, FilteredStorage::new);
					pr = tgt.getPriority();
				}
			}
			if(tgt != null) {
				// A Configurator filter on the target wins over frame labels on it
				FrameLabels.markOverridden(level, target, null, true);
			} else {
				// Without one, glow frames on the target label it, below this connector's own filter
				s = FrameLabels.apply(level, target, null, true, s, LabelledStorage::new);
				if(s instanceof FrameLabel.Labelled)pr = FrameLabel.PRIORITY;
			}
			if(s != null && own != null) {
				if(own.skip())s = null;
				else {
					s = own.wrap(level, s, FilteredStorage::new);
					pr = own.getPriority();
				}
			}
		}
		filteredPointedAt = s;
		if(linv != null)linv.priority = pr;
	}

	private IItemHandler getPointedAtHandler() {
		return filteredPointedAt == null ? null : applyFilter(filteredPointedAt);
	}

	protected IItemHandler applyFilter(IItemHandler st) {
		return st;
	}

	@Override
	public IItemHandler get() {
		return masterW;
	}

	@Override
	public @Nullable IItemHandler getDelegate() {
		return masterW;
	}

	@Override
	public int getSlots() {
		return masterW == null ? 0 : masterW.getSlots();
	}

	@Override
	public @NotNull ItemStack getStackInSlot(int slot) {
		return masterW == null ? ItemStack.EMPTY : masterW.getStackInSlot(slot);
	}

	@Override
	public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
		return masterW == null ? stack : masterW.insertItem(slot, stack, simulate);
	}

	@Override
	public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
		return masterW == null ? ItemStack.EMPTY : masterW.extractItem(slot, amount, simulate);
	}

	@Override
	public int getSlotLimit(int slot) {
		return masterW == null ? 0 : masterW.getSlotLimit(slot);
	}

	@Override
	public boolean isItemValid(int slot, @NotNull ItemStack stack) {
		return masterW != null && masterW.isItemValid(slot, stack);
	}

	@Override
	public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
		if (!this.remove && cap == ForgeCapabilities.ITEM_HANDLER) {
			if (capability == null)capability = LazyOptional.of(() -> this);
			return capability.cast();
		}
		return super.getCapability(cap, side);
	}

	@Override
	public void invalidateCaps() {
		super.invalidateCaps();
		if (capability != null) {
			capability.invalidate();
			capability = null;
		}
	}
}
