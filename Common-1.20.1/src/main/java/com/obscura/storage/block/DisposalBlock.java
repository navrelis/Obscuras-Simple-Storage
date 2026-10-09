package com.obscura.storage.block;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.tile.DisposalBlockEntity;

/** Disposal: destroys matching items from the source for good and counts them. Starts paused, a redstone signal stops it. */
public class DisposalBlock extends AbstractFilterBlock {

	public DisposalBlock() {
		super("disposal");
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new DisposalBlockEntity(pos, state);
	}

	@Override
	public void appendHoverText(ItemStack stack, BlockGetter worldIn, List<Component> tooltip,
			TooltipFlag flagIn) {
		// Always visible, not only with Shift held
		tooltip.add(Component.translatable("tooltip.obscuras_storage.disposal.warning").withStyle(ChatFormatting.RED));
		super.appendHoverText(stack, worldIn, tooltip, flagIn);
	}
}
