package com.obscura.storage.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.tile.ExporterBlockEntity;

/** Exporter: moves matching items from the source into the inventory in front, a redstone signal stops it. */
public class ExporterBlock extends AbstractFilterBlock {

	public ExporterBlock() {
		super("exporter");
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new ExporterBlockEntity(pos, state);
	}
}
