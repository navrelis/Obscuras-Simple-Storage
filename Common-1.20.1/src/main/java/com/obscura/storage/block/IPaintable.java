package com.obscura.storage.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.migration.LegacyIds;

public interface IPaintable {
	boolean paint(Level world, BlockPos pos, BlockState to);

	/**
	 * Reads a painted block state (painted block entities and the BlockEntityTag of painted items). A
	 * state saved before v5.0.0 that names one of this mod's blocks by its old id ({@code toms_storage:trim})
	 * is read as the block's current id: the holder lookup used here does not resolve registry aliases on
	 * Forge. The new id is written the next time the block entity is saved.
	 */
	public static BlockState readBlockState(Level level, CompoundTag tag) {
		HolderGetter<Block> holdergetter = level != null ? level.holderLookup(Registries.BLOCK) : BuiltInRegistries.BLOCK.asLookup();
		return NbtUtils.readBlockState(holdergetter, LegacyIds.currentBlockState(tag));
	}
}
