package com.obscura.storage.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.tile.EjectorBlockEntity;

/** Ejector: drops matching items from the source into the world in front, like a dropper, while powered by redstone. */
public class EjectorBlock extends AbstractFilterBlock {

	public EjectorBlock() {
		super("ejector");
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new EjectorBlockEntity(pos, state);
	}
}
