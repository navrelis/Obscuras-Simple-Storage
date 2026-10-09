package com.obscura.storage.block;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import com.obscura.storage.Content;
import com.obscura.storage.StorageModClient;
import com.obscura.storage.util.NetworkChanges;

public class TrimBlock extends Block implements ITrim, IPaintable {

	public TrimBlock() {
		super(Block.Properties.of().mapColor(MapColor.WOOD).sound(SoundType.WOOD).strength(3));
	}

	@Override
	public void appendHoverText(ItemStack stack, BlockGetter worldIn, List<Component> tooltip,
			TooltipFlag flagIn) {
		tooltip.add(Component.translatable("tooltip.obscuras_storage.paintable"));
		StorageModClient.tooltip("trim", tooltip);
	}

	@Override
	public boolean paint(Level world, BlockPos pos, BlockState to) {
		world.setBlockAndUpdate(pos, Content.paintedTrim.get().defaultBlockState());
		return Content.paintedTrim.get().paint(world, pos, to);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
		if(!level.isClientSide)NetworkChanges.bump();
		super.neighborChanged(state, level, pos, block, fromPos, isMoving);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
		if(!level.isClientSide)NetworkChanges.bump();
		super.onPlace(state, level, pos, oldState, isMoving);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		if(!level.isClientSide)NetworkChanges.bump();
		super.onRemove(state, level, pos, newState, isMoving);
	}
}
