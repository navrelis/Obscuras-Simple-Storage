package com.obscura.storage.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A block whose real shape is outlined while an Inventory Configurator is held (it may be painted to look like another block). */
public interface IConfiguratorHighlight {
	int getHighlightColor();
	VoxelShape getHighlightShape(BlockState state, BlockGetter level, BlockPos pos);
}
