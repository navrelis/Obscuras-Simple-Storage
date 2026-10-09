package com.obscura.storage.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.serialization.MapCodec;

import com.obscura.storage.block.entity.ExporterBlockEntity;

/** Exporter: moves matching items from the source into the inventory in front, a redstone signal stops it. */
public class ExporterBlock extends AbstractFilterBlock {
	public static final MapCodec<ExporterBlock> CODEC = ChestBlock.simpleCodec(properties -> new ExporterBlock());

	public ExporterBlock() {
		super("exporter");
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new ExporterBlockEntity(pos, state);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}
}
