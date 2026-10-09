package com.obscura.storage.tile;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;
import java.util.function.IntSupplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.block.InventoryConnectorBlock;
import com.obscura.storage.doctor.INetworkMember;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.util.MergedStorage;
import com.obscura.storage.util.IProxy;
import com.obscura.storage.util.ItemHandlers;
import com.obscura.storage.util.TickerUtil.TickableServer;

/** Exposes the network it is connected to (over cables) as an item handler on every side. */
public class InventoryInterfaceBlockEntity extends BlockEntity implements TickableServer, INetworkMember, ComparatorOutput.Source {
	private IItemHandler inventory;
	private final View view = new View();
	private LazyOptional<IItemHandler> capability;

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

	private IItemHandler findConnectorInventory() {
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

	/**
	 * The capability object stays the same while the network behind it changes (Forge caches
	 * capabilities); it shows no slots while no network is found.
	 */
	private class View implements IItemHandler, IProxy, ItemHandlers.Delegating {

		@Override
		public IItemHandler get() {
			return inventory;
		}

		@Override
		public @Nullable IItemHandler getDelegate() {
			return inventory;
		}

		@Override
		public int getSlots() {
			return inventory == null ? 0 : inventory.getSlots();
		}

		@Override
		public @NotNull ItemStack getStackInSlot(int slot) {
			return inventory == null ? ItemStack.EMPTY : inventory.getStackInSlot(slot);
		}

		@Override
		public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
			return inventory == null ? stack : inventory.insertItem(slot, stack, simulate);
		}

		@Override
		public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
			return inventory == null ? ItemStack.EMPTY : inventory.extractItem(slot, amount, simulate);
		}

		@Override
		public int getSlotLimit(int slot) {
			return inventory == null ? 0 : inventory.getSlotLimit(slot);
		}

		@Override
		public boolean isItemValid(int slot, @NotNull ItemStack stack) {
			return inventory != null && inventory.isItemValid(slot, stack);
		}
	}

	@Override
	public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
		if (!this.remove && cap == ForgeCapabilities.ITEM_HANDLER) {
			if (capability == null)capability = LazyOptional.of(() -> view);
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
