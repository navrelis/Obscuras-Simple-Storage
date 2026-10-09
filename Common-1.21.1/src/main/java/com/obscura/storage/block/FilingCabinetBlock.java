package com.obscura.storage.block;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

import com.mojang.serialization.MapCodec;

import com.obscura.storage.block.entity.FilingCabinetBlockEntity;
import com.obscura.storage.client.ClientUtil;
import com.obscura.storage.util.ComparatorOutput;

public class FilingCabinetBlock extends BaseEntityBlock {
	public static final MapCodec<FilingCabinetBlock> CODEC = ChestBlock.simpleCodec(properties -> new FilingCabinetBlock());
	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

	public FilingCabinetBlock() {
		super(Block.Properties.of().mapColor(MapColor.STONE).sound(SoundType.STONE).strength(3));
	}

	@Override
	public void appendHoverText(ItemStack itemStack, TooltipContext tooltipContext, List<Component> tooltip,
			TooltipFlag tooltipFlag) {
		ClientUtil.tooltip("filing_cabinet", tooltip);
	}

	@Override
	public RenderShape getRenderShape(BlockState p_149645_1_) {
		return RenderShape.MODEL;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState rotate(BlockState state, Rotation rot) {
		return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
	}

	@Override
	public BlockState mirror(BlockState state, Mirror mirrorIn) {
		return state.rotate(mirrorIn.getRotation(state.getValue(FACING)));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player,
			BlockHitResult hit) {
		if (world.isClientSide) {
			return InteractionResult.SUCCESS;
		}

		BlockEntity blockEntity_1 = world.getBlockEntity(pos);
		if (blockEntity_1 instanceof FilingCabinetBlockEntity be) {
			player.openMenu(be);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos p_153215_, BlockState p_153216_) {
		return new FilingCabinetBlockEntity(p_153215_, p_153216_);
	}

@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
		return ComparatorOutput.at(level, pos);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	public void onRemove(BlockState state, Level world, BlockPos pos, BlockState state2, boolean flag) {
		if (!state.is(state2.getBlock())) {
			BlockEntity blockentity = world.getBlockEntity(pos);
			if (blockentity instanceof FilingCabinetBlockEntity te) {
				dropContentsBatched(world, pos, te.getInv());
				world.updateNeighbourForOutputSignal(pos, this);
			}

			super.onRemove(state, world, pos, state2, flag);
		}
	}

	/**
	 * Drops container contents grouped by item type, reducing the number of
	 * spawned ItemEntities compared to Containers.dropContents() which creates
	 * one entity per non-empty slot.
	 */
	private static void dropContentsBatched(Level world, BlockPos pos, Container inv) {
		List<ItemStack> templates = new ArrayList<>();
		List<Long> counts = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty()) {
				boolean found = false;
				for (int j = 0; j < templates.size(); j++) {
					if (ItemStack.isSameItemSameComponents(templates.get(j), stack)) {
						counts.set(j, counts.get(j) + stack.getCount());
						found = true;
						break;
					}
				}
				if (!found) {
					templates.add(stack.copyWithCount(1));
					counts.add((long) stack.getCount());
				}
			}
		}
		double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
		for (int i = 0; i < templates.size(); i++) {
			long total = counts.get(i);
			int maxStack = templates.get(i).getMaxStackSize();
			while (total > 0) {
				int dropCount = (int) Math.min(total, maxStack);
				ItemStack drop = templates.get(i).copyWithCount(dropCount);
				Containers.dropItemStack(world, x, y, z, drop);
				total -= dropCount;
			}
		}
		inv.clearContent();
	}
}
