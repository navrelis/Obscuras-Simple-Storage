package com.obscura.storage.platform;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

import com.obscura.storage.StorageMod;
import com.obscura.storage.tile.ShulkerDockBlockEntity;

/**
 * Forge: the Shulker Dock's item handler capability. The block entity is shared with Fabric
 * (Common), so it cannot override Forge's {@code getCapability}; the capability is attached instead
 * (Fabric: {@code ItemStorage.SIDED}). An {@link InvWrapper} reads the container's size live: 27
 * slots while a box is docked, 0 otherwise.
 */
public final class ShulkerDockCapability implements ICapabilityProvider {
	private static final ResourceLocation ID = StorageMod.id("shulker_dock_items");
	private final ShulkerDockBlockEntity be;
	private LazyOptional<IItemHandler> cap = LazyOptional.empty();

	private ShulkerDockCapability(ShulkerDockBlockEntity be) {
		this.be = be;
	}

	/** AttachCapabilitiesEvent for block entities. The block entity is not fully constructed yet: nothing is read here. */
	public static void attach(AttachCapabilitiesEvent<BlockEntity> event) {
		if (!(event.getObject() instanceof ShulkerDockBlockEntity be))return;
		ShulkerDockCapability p = new ShulkerDockCapability(be);
		event.addCapability(ID, p);
		event.addListener(p::invalidate);
	}

	@Override
	public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> c, @Nullable Direction side) {
		if (c != ForgeCapabilities.ITEM_HANDLER || be.isRemoved())return LazyOptional.empty();
		// A fresh one after an invalidation (the block entity can be revived, e.g. by a chunk reload of the same object)
		if (!cap.isPresent())cap = LazyOptional.of(() -> new InvWrapper(be.getInv()));
		return cap.cast();
	}

	private void invalidate() {
		cap.invalidate();
		cap = LazyOptional.empty();
	}
}
