package com.obscura.storage.block;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.serialization.MapCodec;

import com.obscura.storage.block.entity.DisposalBlockEntity;

/** Disposal: destroys matching items from the source for good and counts them. Starts paused, a redstone signal stops it. */
public class DisposalBlock extends AbstractFilterBlock {
	public static final MapCodec<DisposalBlock> CODEC = ChestBlock.simpleCodec(properties -> new DisposalBlock());

	public DisposalBlock() {
		super("disposal");
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new DisposalBlockEntity(pos, state);
	}

	@Override
	public void appendHoverText(ItemStack itemStack, TooltipContext tooltipContext, List<Component> tooltip,
			TooltipFlag tooltipFlag) {
		// Always visible, not only with Shift held
		tooltip.add(Component.translatable("tooltip.obscuras_storage.disposal.warning").withStyle(ChatFormatting.RED));
		super.appendHoverText(itemStack, tooltipContext, tooltip, tooltipFlag);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}
}
