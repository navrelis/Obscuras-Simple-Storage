package com.obscura.storage.tile;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;
import java.util.function.IntSupplier;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SidedStorageBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.block.InventoryConnectorBlock;
import com.obscura.storage.doctor.INetworkMember;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.util.MergedStorage;
import com.obscura.storage.util.TickerUtil.TickableServer;

public class InventoryInterfaceBlockEntity extends BlockEntity implements TickableServer, SidedStorageBlockEntity, INetworkMember, ComparatorOutput.Source {
	private Storage<ItemVariant> inventory;

	private boolean tracked;
	private final ComparatorOutput comparator = new ComparatorOutput();
	private final IntSupplier fillSignal = this::getComparatorSignal;

	public InventoryInterfaceBlockEntity(BlockPos pos, BlockState state) {
		super(Content.invInterfaceTile.get(), pos, state);
	}

	/** How full the network behind the interface is, 0 to 15 (0 without a network). */
	@Override
	public int getComparatorSignal() {
		return level != null && inventory instanceof MergedStorage m ? m.getFillSignal(level) : 0;
	}

	@Override
	public boolean isOnNetwork(NetworkStats network) {
		return network != null && NetworkDoctor.statsOf(inventory) == network;
	}

	@Override
	public void updateServer() {
		if (level == null) return;
		long time = level.getGameTime();
		if (!tracked) {
			tracked = true;
			NetworkDoctor.track(this);
		}
		// A comparator next to the interface follows the fill level of the network behind it
		if (time % 5 == (worldPosition.hashCode() & 0x7FFFFFFF) % 5) comparator.tick(level, worldPosition, fillSignal);
		if (time % 20 != Math.abs(worldPosition.hashCode()) % 20) return;
		inventory = findConnectorInventory();
	}

	private Storage<ItemVariant> findConnectorInventory() {
		Stack<BlockPos> toCheck = new Stack<>();
		Set<BlockPos> checked = new HashSet<>();
		toCheck.push(worldPosition);
		checked.add(worldPosition);

		while (!toCheck.isEmpty() && checked.size() <= Config.get().invConnectorMaxCables) {
			BlockPos current = toCheck.pop();
			for (Direction dir : Direction.values()) {
				BlockPos next = current.relative(dir);
				if (checked.contains(next) || !level.isLoaded(next)) continue;
				checked.add(next);
				BlockState state = level.getBlockState(next);
				if (state.getBlock() == Content.connector.get() || state.getBlock() instanceof InventoryConnectorBlock) {
					BlockEntity be = level.getBlockEntity(next);
					if (be instanceof InventoryConnectorBlockEntity connector) {
						return connector.getInventory();
					}
				}
				if (state.getBlock() instanceof IInventoryCable cable) {
					toCheck.addAll(cable.next(level, state, next));
				}
			}
		}
		return null;
	}

	@Override
	public @Nullable Storage<ItemVariant> getItemStorage(Direction side) {
		return inventory;
	}
}
