package com.obscura.storage.tile;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

import net.minecraftforge.items.IItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.doctor.INetworkMember;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.util.ItemHandlers;
import com.obscura.storage.util.TickerUtil.TickableServer;

public abstract class AbstractInventoryHopperBlockEntity extends BlockEntity implements TickableServer, INetworkMember {
	protected boolean topNet, bottomNet;
	/** Network Doctor: the network the last lookup found on either side, null when neither is one. */
	protected NetworkStats doctorStats;
	private boolean tracked;
	protected IItemHandler top;
	protected IItemHandler bottom;
	private final Stack<BlockPos> toCheck = new Stack<>();
	private final Set<BlockPos> checkedBlocks = new HashSet<>();
	public AbstractInventoryHopperBlockEntity(BlockEntityType<?> tileEntityTypeIn, BlockPos pos, BlockState state) {
		super(tileEntityTypeIn, pos, state);
	}

	@Override
	public void updateServer() {
		if(!level.isClientSide && (level.getGameTime() % 20 == (worldPosition.hashCode() & 0x7FFFFFFF) % 20 || needsLookup())) {
			BlockState state = level.getBlockState(worldPosition);
			Direction facing = state.getValue(InventoryCableConnectorBlock.FACING);
			BlockPos up = worldPosition.relative(facing.getOpposite());
			BlockPos down = worldPosition.relative(facing);
			state = level.getBlockState(up);
			if(state.getBlock() instanceof IInventoryCable) {
				topNet = true;
				top = findNetwork(up);
			} else {
				topNet = false;
				top = ItemHandlers.find(level, up, facing);
			}
			state = level.getBlockState(down);
			if(state.getBlock() instanceof IInventoryCable) {
				bottomNet = true;
				bottom = findNetwork(down);
			} else {
				bottomNet = false;
				bottom = ItemHandlers.find(level, down, facing.getOpposite());
			}
			// Network Doctor: the network this hopper works on, found together with the sides
			NetworkStats s = NetworkDoctor.statsOf(top);
			doctorStats = s != null ? s : NetworkDoctor.statsOf(bottom);
		}
		if(!tracked && !level.isClientSide) {
			tracked = true;
			NetworkDoctor.track(this);
		}
		if(level.isClientSide)return;
		if((topNet || bottomNet) && top != null && bottom != null) {
			update();
		} else {
			onIdle();
		}
	}

	@Override
	public boolean isOnNetwork(NetworkStats network) {
		return network != null && (NetworkDoctor.statsOf(top) == network || NetworkDoctor.statsOf(bottom) == network);
	}

	/** Whether something waiting for the sides (a pulse) needs them looked up now instead of at the next scheduled lookup. */
	protected boolean needsLookup() {
		return false;
	}

	/** Called every tick the hopper cannot move: a side is missing, or neither side is a network. */
	protected void onIdle() {
	}

	/**
	 * Follows the cables from {@code start} to the inventory connector of the network.
	 * Every search has its own stack and visited set: sharing them made the second search of a hopper
	 * (top and bottom on the same network) skip cables the first one had walked and go on with its leftovers.
	 */
	private IItemHandler findNetwork(BlockPos start) {
		toCheck.clear();
		checkedBlocks.clear();
		checkedBlocks.add(worldPosition);
		toCheck.add(start);
		IItemHandler found = null;
		while(!toCheck.isEmpty()) {
			BlockPos cp = toCheck.pop();
			if(!checkedBlocks.contains(cp)) {
				checkedBlocks.add(cp);
				if(level.isLoaded(cp)) {
					BlockState state = level.getBlockState(cp);
					if(state.getBlock() == Content.connector.get()) {
						BlockEntity te = level.getBlockEntity(cp);
						if(te instanceof InventoryConnectorBlockEntity) {
							found = ((InventoryConnectorBlockEntity) te).getInventory();
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
		return found;
	}

	protected abstract void update();

	/**
	 * Called on the server whenever the block re-reads its redstone signal (neighbor change, placement).
	 *
	 * @return the value for the ENABLED block state property
	 */
	public boolean onRedstoneUpdate(boolean powered) {
		return !powered;
	}
}
