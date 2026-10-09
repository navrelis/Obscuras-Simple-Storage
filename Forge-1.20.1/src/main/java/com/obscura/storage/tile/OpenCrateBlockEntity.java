package com.obscura.storage.tile;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import com.obscura.storage.Content;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.util.RedstoneSignals;
import com.obscura.storage.util.TickerUtil.TickableServer;

/**
 * Exposes the item entities in front of it as slots, plus one empty slot that drops what is
 * inserted as a new item entity (one entity per insert, at most one max-size stack).
 */
public class OpenCrateBlockEntity extends BlockEntity implements TickableServer, ComparatorOutput.Source {
	private List<ItemEntity> items = new ArrayList<>();
	private final ComparatorOutput comparator = new ComparatorOutput();
	private final IntSupplier signal = this::getComparatorSignal;
	private final Handler handler = new Handler();
	private LazyOptional<IItemHandler> capability;

	public OpenCrateBlockEntity(BlockPos pos, BlockState state) {
		super(Content.openCrateTile.get(), pos, state);
	}

	/** The dropped items in front of the crate like a chest: every item entity is a slot, out of 27. */
	@Override
	public int getComparatorSignal() {
		return RedstoneSignals.entityFill(items);
	}

	@Override
	public void updateServer() {
		if(level.getGameTime() % 5 == 0){
			BlockState state = level.getBlockState(worldPosition);
			Direction f = state.getValue(BlockStateProperties.FACING);
			BlockPos p = worldPosition.relative(f);
			items = level.getEntitiesOfClass(ItemEntity.class, new AABB(p));
			comparator.tick(level, worldPosition, signal);
		}
	}

	private class Handler implements IItemHandler {

		@Override
		public int getSlots() {
			return items.size() + 1;
		}

		@Override
		public @NotNull ItemStack getStackInSlot(int slot) {
			if(slot >= 0 && slot < items.size()) {
				ItemEntity e = items.get(slot);
				if(e.isAlive())return e.getItem();
			}
			return ItemStack.EMPTY;
		}

		@Override
		public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
			if(slot != items.size() || stack.isEmpty() || level == null)return stack;
			// One item entity per insert: clamp to the max stack size and report what was really accepted
			int amount = Math.min(stack.getCount(), stack.getMaxStackSize());
			if(!simulate) {
				BlockState state = level.getBlockState(worldPosition);
				Direction f = Direction.UP;
				if(state.getBlock() == Content.openCrate.get())f = state.getValue(BlockStateProperties.FACING);
				BlockPos p = worldPosition.relative(f);
				ItemEntity entityitem = new ItemEntity(level, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, stack.copyWithCount(amount));
				entityitem.setDefaultPickUpDelay();
				entityitem.setDeltaMovement(Vec3.ZERO);
				level.addFreshEntity(entityitem);
				List<ItemEntity> l = new ArrayList<>(items);
				l.add(entityitem);
				items = l;
			}
			return amount == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - amount);
		}

		@Override
		public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
			if(amount < 1 || slot < 0 || slot >= items.size())return ItemStack.EMPTY;
			ItemEntity ent = items.get(slot);
			if(!ent.isAlive())return ItemStack.EMPTY;
			ItemStack in = ent.getItem();
			if(in.isEmpty())return ItemStack.EMPTY;
			int n = Math.min(amount, in.getCount());
			if(simulate)return in.copyWithCount(n);
			ItemStack out = in.split(n);
			if(in.isEmpty())ent.discard();
			else ent.setItem(in);
			return out;
		}

		@Override
		public int getSlotLimit(int slot) {
			return 64;
		}

		@Override
		public boolean isItemValid(int slot, @NotNull ItemStack stack) {
			return slot == items.size();
		}
	}

	@Override
	public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
		if (!this.remove && cap == ForgeCapabilities.ITEM_HANDLER) {
			if (capability == null)capability = LazyOptional.of(() -> handler);
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
