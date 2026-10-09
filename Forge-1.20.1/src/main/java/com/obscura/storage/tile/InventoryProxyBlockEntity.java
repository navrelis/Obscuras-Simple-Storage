package com.obscura.storage.tile;

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

import com.obscura.storage.Content;
import com.obscura.storage.block.InventoryProxyBlock;
import com.obscura.storage.util.IProxy;
import com.obscura.storage.util.ItemHandlers;
import com.obscura.storage.util.TickerUtil.TickableServer;

public class InventoryProxyBlockEntity extends PaintedBlockEntity implements TickableServer, IItemHandler, IProxy, ItemHandlers.Delegating {
	private IItemHandler pointedAtSt;
	private LazyOptional<IItemHandler> capability;

	public InventoryProxyBlockEntity(BlockPos pos, BlockState state) {
		super(Content.invProxyTile.get(), pos, state);
	}

	@Override
	public void updateServer() {
		if(level.getGameTime() % 20 == 18) {
			BlockState state = level.getBlockState(worldPosition);
			Direction facing = state.getValue(InventoryProxyBlock.FACING);
			BlockEntity te = level.getBlockEntity(worldPosition.relative(facing));
			if(te != null && !(te instanceof InventoryProxyBlockEntity)) {
				pointedAtSt = ItemHandlers.ofBlockEntity(te, facing.getOpposite());
			} else {
				pointedAtSt = null;
			}
		}
	}

	@Override
	public IItemHandler get() {
		return pointedAtSt;
	}

	@Override
	public @Nullable IItemHandler getDelegate() {
		return pointedAtSt;
	}

	/** Block this proxy currently points at, or null when it points at nothing (Item Locator). */
	public BlockPos getPointedAtPos() {
		if(pointedAtSt == null || level == null)return null;
		BlockState state = level.getBlockState(worldPosition);
		if(!state.hasProperty(InventoryProxyBlock.FACING))return null;
		return worldPosition.relative(state.getValue(InventoryProxyBlock.FACING));
	}

	@Override
	public int getSlots() {
		return pointedAtSt == null ? 0 : pointedAtSt.getSlots();
	}

	@Override
	public @NotNull ItemStack getStackInSlot(int slot) {
		return pointedAtSt == null ? ItemStack.EMPTY : pointedAtSt.getStackInSlot(slot);
	}

	@Override
	public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
		return pointedAtSt == null ? stack : pointedAtSt.insertItem(slot, stack, simulate);
	}

	@Override
	public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
		return pointedAtSt == null ? ItemStack.EMPTY : pointedAtSt.extractItem(slot, amount, simulate);
	}

	@Override
	public int getSlotLimit(int slot) {
		return pointedAtSt == null ? 0 : pointedAtSt.getSlotLimit(slot);
	}

	@Override
	public boolean isItemValid(int slot, @NotNull ItemStack stack) {
		return pointedAtSt != null && pointedAtSt.isItemValid(slot, stack);
	}

	@Override
	public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
		if (!this.remove && cap == ForgeCapabilities.ITEM_HANDLER) {
			BlockState state = getBlockState();
			// Not towards the block it points at
			if (side != null && state.hasProperty(InventoryProxyBlock.FACING) && side == state.getValue(InventoryProxyBlock.FACING))
				return LazyOptional.empty();
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
