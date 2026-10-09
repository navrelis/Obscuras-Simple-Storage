package com.obscura.storage.block;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

import com.mojang.serialization.MapCodec;

import com.obscura.storage.block.entity.InventoryConnectorBlockEntity;
import com.obscura.storage.block.entity.InventoryConnectorBlockEntity.UsageInfo;
import com.obscura.storage.client.ClientUtil;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.inventory.InventoryCableNetwork;
import com.obscura.storage.util.BlockFace;
import com.obscura.storage.util.TickerUtil;

public class InventoryConnectorBlock extends BaseEntityBlock implements IInventoryCable, NeoForgeBlock {
	public static final MapCodec<InventoryConnectorBlock> CODEC = ChestBlock.simpleCodec(properties -> new InventoryConnectorBlock());

	public InventoryConnectorBlock() {
		super(Block.Properties.of().mapColor(MapColor.WOOD).sound(SoundType.WOOD).strength(3));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new InventoryConnectorBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state,
			BlockEntityType<T> type) {
		return TickerUtil.createTicker(world, false, true);
	}

	@Override
	public RenderShape getRenderShape(BlockState p_149645_1_) {
		return RenderShape.MODEL;
	}

	@Override
	public void appendHoverText(ItemStack itemStack, TooltipContext tooltipContext, List<Component> tooltip,
			TooltipFlag tooltipFlag) {
		ClientUtil.tooltip("inventory_connector", tooltip);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level worldIn, BlockPos pos, Player player,
			BlockHitResult hit) {
		if(!worldIn.isClientSide) {
			BlockEntity tile = worldIn.getBlockEntity(pos);
			if(tile instanceof InventoryConnectorBlockEntity) {
				InventoryConnectorBlockEntity te = (InventoryConnectorBlockEntity) tile;
				UsageInfo usage = te.getUsage();
				player.displayClientMessage(Component.translatable("chat.obscuras_storage.inventory_connector.free_slots", usage.free(), usage.all(), usage.blocks()), true);
			}
		}
		return InteractionResult.SUCCESS;
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
	public void destroy(LevelAccessor p_49860_, BlockPos p_49861_, BlockState p_49862_) {
		if (p_49860_ instanceof ServerLevel l)
			InventoryCableNetwork.getNetwork(l).markNodeInvalid(p_49861_);
	}

	@Override
	public void neighborChanged(BlockState p_60509_, Level p_60510_, BlockPos p_60511_, Block p_60512_,
			BlockPos p_60513_, boolean p_60514_) {
		super.neighborChanged(p_60509_, p_60510_, p_60511_, p_60512_, p_60513_, p_60514_);
		if (!p_60510_.isClientSide) {
			// Only invalidates when the neighbour now looks different to the cable graph
			InventoryCableNetwork.getNetwork(p_60510_).onNeighborChanged(p_60511_, p_60512_, p_60513_);
			if (p_60510_.getBlockEntity(p_60511_) instanceof InventoryConnectorBlockEntity be)be.requestRescan();
		}
	}

	@Override
	public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
		if (level instanceof ServerLevel l) {
			InventoryCableNetwork.getNetwork(l).onNeighborChanged(pos, false, neighbor);
		}
	}

	@Override
	public List<BlockFace> nextScan(Level world, BlockState state, BlockPos pos) {
		List<BlockFace> l = IInventoryCable.super.nextScan(world, state, pos);
		if (world.getBlockEntity(pos) instanceof InventoryConnectorBlockEntity be) {
			l.addAll(be.getInterfaces());
		}
		return l;
	}
}
