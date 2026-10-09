package com.obscura.storage.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.serialization.MapCodec;

import com.obscura.storage.block.entity.EjectorBlockEntity;

/** Ejector: drops matching items from the source into the world in front, like a dropper, while powered by redstone. */
public class EjectorBlock extends AbstractFilterBlock {
	public static final MapCodec<EjectorBlock> CODEC = ChestBlock.simpleCodec(properties -> new EjectorBlock());

	public EjectorBlock() {
		super("ejector");
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new EjectorBlockEntity(pos, state);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}
}
