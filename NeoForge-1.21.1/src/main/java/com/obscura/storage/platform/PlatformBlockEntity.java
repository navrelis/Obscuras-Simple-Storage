package com.obscura.storage.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.util.IValidInfo;

public class PlatformBlockEntity extends BlockEntity implements IValidInfo {

	public PlatformBlockEntity(BlockEntityType<?> p_155228_, BlockPos p_155229_, BlockState p_155230_) {
		super(p_155228_, p_155229_, p_155230_);
	}

	@Override
	public boolean isObjectValid() {
		return !isRemoved();
	}

	protected void markCapsInvalid() {
		level.invalidateCapabilities(worldPosition);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.loadAdditional(tag, provider);
		FabricDataCompat.readFabricBlockFilter(this, tag, provider);
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		FabricDataCompat.writeFabricBlockFilter(this, tag, provider);
	}
}
