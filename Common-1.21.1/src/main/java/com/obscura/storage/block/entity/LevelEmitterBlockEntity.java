package com.obscura.storage.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import com.obscura.storage.Content;
import com.obscura.storage.block.LevelEmitterBlock;
import com.obscura.storage.doctor.INetworkMember;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.NetworkInventory;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.menu.LevelEmitterMenu;
import com.obscura.storage.migration.LegacyData;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.platform.PlatformBlockEntity;
import com.obscura.storage.util.RedstoneSignals;
import com.obscura.storage.util.TickerUtil.TickableServer;

public class LevelEmitterBlockEntity extends PlatformBlockEntity implements TickableServer, MenuProvider, INetworkMember {
	private StoredItemStack filter;
	private int count = 1;
	private NetworkInventory topCache = new NetworkInventory();
	private boolean lessThan;
	private boolean analog;
	private Direction cachedFacing;
	private long changeTracker = Long.MIN_VALUE;
	private long lastStateChangeTick = Long.MIN_VALUE;
	private static final int HYSTERESIS_TICKS = 4;

	public LevelEmitterBlockEntity(BlockPos pos, BlockState state) {
		super(Content.levelEmitterBE.get(), pos, state);
	}

	@Override
	public void updateServer() {
		if (level.getGameTime() % 10 != (worldPosition.hashCode() & 0x7FFFFFFF) % 10)return;
		BlockState state = level.getBlockState(worldPosition);
		int signal = LevelEmitterBlock.signalOf(state);
		int currSignal = 0;
		if(filter != null) {
			Direction facing = state.getValue(LevelEmitterBlock.FACING);
			BlockPos targetPos = worldPosition.relative(facing.getOpposite());
			if (facing != cachedFacing) {
				// The block was rotated in place (same block entity), point the cache at the new face.
				cachedFacing = facing;
				topCache.onLoad(level, targetPos, facing, this);
			}
			IInventoryAccess access = topCache.getAccess(level, targetPos);
			NetworkStats stats = NetworkDoctor.statsOf(access);
			long t0 = NetworkDoctor.begin(stats);
			long counter;
			try {
				IInventoryChangeTracker tr = access.tracker();
				changeTracker = tr.getChangeTracker(level);
				counter = tr.streamWrappedStacks(false)
						.filter(getFilter()::equalItem)
						.mapToLong(StoredItemStack::getQuantity)
						.sum();
			} finally {
				NetworkDoctor.end(stats, level.getGameTime(), t0);
			}
			if(analog) {
				currSignal = RedstoneSignals.emitterSignal(counter, count, lessThan);
			} else if(lessThan ? counter < count : counter > count) {
				currSignal = RedstoneSignals.MAX;
			}
		}
		if(currSignal != signal) {
			if (lastStateChangeTick == Long.MIN_VALUE || level.getGameTime() - lastStateChangeTick >= HYSTERESIS_TICKS) {
				lastStateChangeTick = level.getGameTime();
				level.setBlock(worldPosition, state.setValue(LevelEmitterBlock.POWER, currSignal).setValue(LevelEmitterBlock.POWERED, currSignal > 0), 3);

				Direction direction = state.getValue(LevelEmitterBlock.FACING);
				BlockPos blockpos = worldPosition.relative(direction);
				if (Platform.notifyBlocks(level, worldPosition, direction)) {
					level.neighborChanged(blockpos, state.getBlock(), worldPosition);
					level.updateNeighborsAtExceptFromFacing(blockpos, state.getBlock(), direction.getOpposite());
				}
			}
		}
	}

	@Override
	public void saveAdditional(CompoundTag compound, HolderLookup.Provider provider) {
		super.saveAdditional(compound, provider);
		StoredItemStack f = getFilter();
		if (f != null) {
			ItemStack is = f.getStack();
			if (!is.isEmpty())
				compound.put("Filter", is.save(provider, new CompoundTag()));
		}
		compound.putInt("Count", count);
		compound.putBoolean("lessThan", lessThan);
		compound.putBoolean("analog", analog);
	}

	@Override
	public void loadAdditional(CompoundTag nbtIn, HolderLookup.Provider provider) {
		super.loadAdditional(nbtIn, provider);
		// LegacyData: an emitter saved by the 1.20.1 builds holds a 1.20.1 item stack
		setFilter(LegacyData.parseItemStack(provider, nbtIn.getCompound("Filter")));
		count = nbtIn.getInt("Count");
		lessThan = nbtIn.getBoolean("lessThan");
		analog = nbtIn.getBoolean("analog");
	}

	public void setFilter(ItemStack filter) {
		this.filter = filter.isEmpty() ? null : new StoredItemStack(filter);
		changeTracker = Long.MIN_VALUE;
		setChanged();
	}

	public StoredItemStack getFilter() {
		return filter;
	}

	public void setCount(int count) {
		this.count = Math.max(0, count);
		changeTracker = Long.MIN_VALUE;
		setChanged();
	}

	public int getCount() {
		return count;
	}

	public void setLessThan(boolean lessThan) {
		this.lessThan = lessThan;
		changeTracker = Long.MIN_VALUE;
		setChanged();
	}

	public boolean isLessThan() {
		return lessThan;
	}

	/** Analog mode: the signal strength follows the stored amount, instead of 15 while the condition holds. */
	public void setAnalog(boolean analog) {
		this.analog = analog;
		changeTracker = Long.MIN_VALUE;
		setChanged();
	}

	public boolean isAnalog() {
		return analog;
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

	@Override
	public void onLoad() {
		super.onLoad();
		if (!level.isClientSide) {
			BlockState state = level.getBlockState(worldPosition);
			Direction facing = state.getValue(BlockStateProperties.FACING);
			cachedFacing = facing;
			topCache.onLoad(level, worldPosition.relative(facing.getOpposite()), facing, this);
			NetworkDoctor.track(this);
		}
	}

	@Override
	public boolean isOnNetwork(NetworkStats network) {
		if (network == null || level == null || cachedFacing == null)return false;
		return NetworkDoctor.statsOf(topCache.getAccess(level, worldPosition.relative(cachedFacing.getOpposite()))) == network;
	}
}
