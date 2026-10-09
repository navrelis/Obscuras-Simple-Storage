package com.obscura.storage.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.block.AbstractInventoryHopperBlock;
import com.obscura.storage.doctor.INetworkMember;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.inventory.NetworkInventory;
import com.obscura.storage.platform.PlatformBlockEntity;
import com.obscura.storage.util.IValidInfo;
import com.obscura.storage.util.TickerUtil.TickableServer;

public abstract class AbstractInventoryHopperBlockEntity extends PlatformBlockEntity implements TickableServer, IValidInfo, INetworkMember {
	protected NetworkInventory topCache = new NetworkInventory();
	protected NetworkInventory bottomCache = new NetworkInventory();
	protected BlockPos topPos, bottomPos;
	private Direction facing;

	public AbstractInventoryHopperBlockEntity(BlockEntityType<?> tileEntityTypeIn, BlockPos pos, BlockState state) {
		super(tileEntityTypeIn, pos, state);
	}

	@Override
	public void onLoad() {
		super.onLoad();
		if (updatePositions())NetworkDoctor.track(this);
	}

	@Override
	public boolean isOnNetwork(NetworkStats network) {
		if (network == null || !updatePositions())return false;
		return NetworkDoctor.statsOf(topCache.getAccess(level, topPos)) == network || NetworkDoctor.statsOf(bottomCache.getAccess(level, bottomPos)) == network;
	}

	/**
	 * Makes {@link #topPos}/{@link #bottomPos} match the current FACING. The deferred onLoad can run
	 * after the first tick, and the block can be rotated in place without replacing this block entity,
	 * so this is re-checked every tick.
	 *
	 * @return false while the positions are not available (client side or no level yet)
	 */
	protected boolean updatePositions() {
		if (level == null || level.isClientSide)return false;
		BlockState state = getBlockState();
		if (!state.hasProperty(AbstractInventoryHopperBlock.FACING))return false;
		Direction facing = state.getValue(AbstractInventoryHopperBlock.FACING);
		if (facing != this.facing || topPos == null || bottomPos == null) {
			this.facing = facing;
			bottomPos = worldPosition.relative(facing);
			topPos = worldPosition.relative(facing.getOpposite());
			bottomCache.onLoad(level, bottomPos, facing.getOpposite(), this);
			topCache.onLoad(level, topPos, facing, this);
			onPositionsChanged();
		}
		return true;
	}

	/** Called after the top/bottom positions were (re)computed, drop state tied to the old ones. */
	protected void onPositionsChanged() {
	}

	/**
	 * Called on the server whenever the block re-reads its redstone signal (neighbor change, placement).
	 *
	 * @return the value for the ENABLED block state property
	 */
	public boolean onRedstoneUpdate(boolean powered) {
		return !powered;
	}

	public boolean isEnabled() {
		return this.getBlockState().getValue(AbstractInventoryHopperBlock.ENABLED);
	}
}
