package com.obscura.storage.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Content;
import com.obscura.storage.menu.FilingCabinetMenu;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.util.FilingCabinetContainer;

public class FilingCabinetBlockEntity extends BlockEntity implements MenuProvider, Nameable, ComparatorOutput.Source {
	private FilingCabinetContainer inv = new FilingCabinetContainer(512, this::setChanged, this::canInteractWith);
	private Component name;
	/** Cached comparator signal, -1 after a change until it is read again. */
	private int comparator = -1;

	public FilingCabinetBlockEntity(BlockPos p_155229_, BlockState p_155230_) {
		super(Content.filingCabinetBE.get(), p_155229_, p_155230_);
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		tag.put("inventory", inv.createTag(provider));
		if (this.name != null) {
			tag.putString("CustomName", Component.Serializer.toJson(this.name, provider));
		}
		// Not a PlatformBlockEntity: let the platform keep loader specific data (block filter) portable
		Platform.writeCrossLoaderData(this, tag, provider);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.loadAdditional(tag, provider);
		if (tag.contains("inventory", Tag.TAG_COMPOUND)) {
			// Saved by the 1.20.1 builds: a compound with 1.20.1 item stacks, see fromLegacyTag
			inv.fromLegacyTag(tag.getCompound("inventory"), provider);
		} else {
			inv.fromTag(tag.getList("inventory", Tag.TAG_COMPOUND), provider);
		}
		if (tag.contains("CustomName", 8)) {
			this.name = parseCustomNameSafe(tag.getString("CustomName"), provider);
		}
		Platform.readCrossLoaderData(this, tag, provider);
	}

	@Override
	public AbstractContainerMenu createMenu(int p_39954_, Inventory p_39955_, Player p_39956_) {
		return new FilingCabinetMenu(p_39954_, p_39955_, inv);
	}

	public Component getDefaultName() {
		return Component.translatable("menu.obscuras_storage.filing_cabinet");
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
		if (comparator < 0)comparator = AbstractContainerMenu.getRedstoneSignalFromContainer(inv);
		return comparator;
	}

	@Override
	public void setChanged() {
		comparator = -1;
		super.setChanged();
	}

	private boolean canInteractWith(Player player) {
		if(level.getBlockEntity(worldPosition) != this)return false;
		return player.distanceToSqr(this.worldPosition.getX() + 0.5D, this.worldPosition.getY() + 0.5D, this.worldPosition.getZ() + 0.5D) < 64;
	}

	@Override
	public Component getName() {
		return this.name != null ? this.name : this.getDefaultName();
	}

	@Override
	public Component getDisplayName() {
		return this.getName();
	}

	@Override
	public Component getCustomName() {
		return this.name;
	}
}
