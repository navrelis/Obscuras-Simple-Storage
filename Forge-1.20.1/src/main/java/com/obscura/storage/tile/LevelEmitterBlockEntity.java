package com.obscura.storage.tile;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

import net.minecraftforge.items.IItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.block.LevelEmitterBlock;
import com.obscura.storage.doctor.INetworkMember;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.gui.LevelEmitterMenu;
import com.obscura.storage.util.ItemHandlers;
import com.obscura.storage.util.ItemKey;
import com.obscura.storage.util.MergedStorage;
import com.obscura.storage.util.StorageSnapshot;
import com.obscura.storage.util.RedstoneSignals;
import com.obscura.storage.util.TickerUtil.TickableServer;

public class LevelEmitterBlockEntity extends BlockEntity implements TickableServer, MenuProvider, INetworkMember {
	private ItemStack filter = ItemStack.EMPTY;
	private int count = 1;
	private IItemHandler top;
	private boolean lessThan;
	private boolean analog;
	private boolean tracked;
	/** Variant of {@link #filter}, null until needed and after the filter changed. */
	private ItemKey filterVariant;
	/** Whether the target has been looked up at least once since the block entity loaded. */
	private boolean scanned;
	/** Oldest network snapshot an emitter accepts, in ticks. */
	private static final int SNAPSHOT_MAX_AGE = 4;
	private final Stack<BlockPos> toCheck = new Stack<>();
	private final Set<BlockPos> checkedBlocks = new HashSet<>();

	public LevelEmitterBlockEntity(BlockPos pos, BlockState state) {
		super(Content.levelEmitterTile.get(), pos, state);
	}

	@Override
	public void updateServer() {
		long time = level.getGameTime();
		if(!tracked) {
			tracked = true;
			NetworkDoctor.track(this);
		}
		int hash = worldPosition.hashCode() & 0x7FFFFFFF;
		if(time % 20 == hash % 20) {
			BlockState state = level.getBlockState(worldPosition);
			Direction facing = state.getValue(LevelEmitterBlock.FACING);
			toCheck.clear();
			checkedBlocks.clear();
			checkedBlocks.add(worldPosition);
			BlockPos up = worldPosition.relative(facing.getOpposite());
			state = level.getBlockState(up);
			scanned = true;
			if(state.getBlock() instanceof IInventoryCable) {
				top = null;
				toCheck.add(up);
				while(!toCheck.isEmpty()) {
					BlockPos cp = toCheck.pop();
					if(!checkedBlocks.contains(cp)) {
						checkedBlocks.add(cp);
						if(level.isLoaded(cp)) {
							state = level.getBlockState(cp);
							if(state.getBlock() == Content.connector.get()) {
								BlockEntity te = level.getBlockEntity(cp);
								if(te instanceof InventoryConnectorBlockEntity) {
									top = ((InventoryConnectorBlockEntity) te).getInventory();
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
				toCheck.clear();
				checkedBlocks.clear();
			} else {
				top = ItemHandlers.find(level, up, facing);
			}
		}
		// Staggered by position like the rescan, so emitters don't all read their network on the same tick
		if(time % 10 == hash % 10 && scanned) {
			BlockState state = level.getBlockState(worldPosition);
			int signal = LevelEmitterBlock.signalOf(state);
			int currSignal = 0;
			// The target is gone: the emitter must not keep signalling for a stock it can't see any more
			if(top != null && !filter.isEmpty()) {
				// Network Doctor: the check is booked to the network it reads
				NetworkStats stats = NetworkDoctor.statsOf(top);
				long t0 = NetworkDoctor.begin(stats);
				long counter;
				try {
					counter = countMatchingItems(top, getFilterVariant(), count, time);
				} finally {
					NetworkDoctor.end(stats, time, t0);
				}

				if(analog) {
					currSignal = RedstoneSignals.emitterSignal(counter, count, lessThan);
				} else if(lessThan ? counter < count : counter > count) {
					currSignal = RedstoneSignals.MAX;
				}
			}
			if(currSignal != signal) {
				level.setBlock(worldPosition, state.setValue(LevelEmitterBlock.POWER, currSignal).setValue(LevelEmitterBlock.POWERED, currSignal > 0), 3);

				Direction direction = state.getValue(LevelEmitterBlock.FACING);
				BlockPos blockPos = worldPosition.relative(direction);

				level.neighborChanged(blockPos, state.getBlock(), worldPosition);
				level.updateNeighborsAtExceptFromFacing(blockPos, state.getBlock(), direction.getOpposite());
			}
		}
	}

	private ItemKey getFilterVariant() {
		if(filterVariant == null)filterVariant = ItemKey.of(filter);
		return filterVariant;
	}

	/**
	 * Amount of {@code variant} in {@code storage}. A whole inventory connector network is answered from its
	 * shared snapshot (read at most every few ticks for all readers); anything else is read directly, comparing
	 * variants without building item stacks.
	 */
	private static long countMatchingItems(IItemHandler storage, ItemKey variant, int threshold, long time) {
		if (storage == null || variant.isBlank()) return 0L;
		if (storage instanceof MergedStorage merged) {
			StorageSnapshot snapshot = merged.getSnapshot();
			snapshot.refresh(merged, time, SNAPSHOT_MAX_AGE);
			return snapshot.getCount(variant);
		}
		return ItemHandlers.count(storage, variant, (long) threshold + 1L);
	}

	@Override
	public void saveAdditional(CompoundTag compound) {
		compound.put("Filter", getFilter().save(new CompoundTag()));
		compound.putInt("Count", count);
		compound.putBoolean("lessThan", lessThan);
		compound.putBoolean("analog", analog);
	}

	@Override
	public void load(CompoundTag nbtIn) {
		super.load(nbtIn);
		filter = ItemStack.of(nbtIn.getCompound("Filter"));
		filterVariant = null;
		count = nbtIn.getInt("Count");
		lessThan = nbtIn.getBoolean("lessThan");
		analog = nbtIn.getBoolean("analog");
	}

	public void setFilter(ItemStack filter) {
		this.filter = filter;
		this.filterVariant = null;
		setChanged();
	}

	public ItemStack getFilter() {
		return filter;
	}

	public void setCount(int count) {
		this.count = Math.max(0, count);
		setChanged();
	}

	public int getCount() {
		return count;
	}

	public void setLessThan(boolean lessThan) {
		this.lessThan = lessThan;
		setChanged();
	}

	public boolean isLessThan() {
		return lessThan;
	}

	/** Analog mode: the signal strength follows the stored amount, instead of 15 while the condition holds. */
	public void setAnalog(boolean analog) {
		this.analog = analog;
		setChanged();
	}

	public boolean isAnalog() {
		return analog;
	}

	@Override
	public boolean isOnNetwork(NetworkStats network) {
		return network != null && NetworkDoctor.statsOf(top) == network;
	}

	@Override
	public AbstractContainerMenu createMenu(int p_createMenu_1_, Inventory p_createMenu_2_, Player p_createMenu_3_) {
		return new LevelEmitterMenu(p_createMenu_1_, p_createMenu_2_, this);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("menu.obscuras_storage.level_emitter");
	}

	public boolean stillValid(Player p_59619_) {
		if (this.level.getBlockEntity(this.worldPosition) != this) {
			return false;
		} else {
			return !(p_59619_.distanceToSqr(this.worldPosition.getX() + 0.5D, this.worldPosition.getY() + 0.5D, this.worldPosition.getZ() + 0.5D) > 64.0D);
		}
	}
}
