package com.obscura.storage.tile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

import com.obscura.storage.Content;
import com.obscura.storage.gui.FilingCabinetMenu;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.util.FilingCabinetContainer;

public class FilingCabinetBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity implements MenuProvider, Nameable, ComparatorOutput.Source {
	private final FilingCabinetContainer inv = new FilingCabinetContainer(512, this::setChanged, this::canInteractWith);
	private LazyOptional<IItemHandler> capability;
	private Component name;
	/** Cached comparator signal, -1 after a change until it is read again. */
	private int comparator = -1;

	public FilingCabinetBlockEntity(BlockPos pos, BlockState state) {
		super(Content.filingCabinetTile.get(), pos, state);
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory playerInv, Player player) {
		return new FilingCabinetMenu(id, playerInv, inv);
	}

	@Override
	public Component getDisplayName() {
		return getName();
	}

	@Override
	public Component getName() {
		return name != null ? name : Component.translatable("menu.obscuras_storage.filing_cabinet");
	}

	@Override
	public Component getCustomName() {
		return name;
	}

	public Container getInv() {
		return inv;
	}

	/**
	 * The vanilla container formula over the 512 slots (one item each). Computed when a comparator
	 * reads it after a change, not on every change: vanilla's {@code setChanged} already updates the
	 * neighbours, this only makes sure they read the new value once.
	 */
	@Override
	public int getComparatorSignal() {
		if (comparator < 0) comparator = AbstractContainerMenu.getRedstoneSignalFromContainer(inv);
		return comparator;
	}

	@Override
	public void setChanged() {
		comparator = -1;
		super.setChanged();
	}

	private boolean canInteractWith(Player player) {
		if (level == null || level.getBlockEntity(worldPosition) != this) return false;
		return player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) < 64.0D;
	}

	@Override
	public void load(CompoundTag tag) {
		super.load(tag);
		CompoundTag invTag = tag.getCompound("inventory");
		inv.loadFromTag(invTag);
		if (tag.contains("CustomName", 8)) {
			name = Component.Serializer.fromJson(tag.getString("CustomName"));
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag) {
		super.saveAdditional(tag);
		CompoundTag invTag = new CompoundTag();
		inv.saveToTag(invTag);
		tag.put("inventory", invTag);
		if (name != null) {
			tag.putString("CustomName", Component.Serializer.toJson(name));
		}
	}

	@Override
	public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
		if (!this.remove && cap == ForgeCapabilities.ITEM_HANDLER) {
			// canPlaceItem of the container rejects shulker boxes and bundles, InvWrapper honours it
			if (capability == null)capability = LazyOptional.of(() -> new InvWrapper(inv));
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
