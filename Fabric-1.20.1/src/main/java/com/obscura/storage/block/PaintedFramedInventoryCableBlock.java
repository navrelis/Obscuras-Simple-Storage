package com.obscura.storage.block;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.StorageModClient;
import com.obscura.storage.tile.PaintedBlockEntity;

public class PaintedFramedInventoryCableBlock extends BaseEntityBlock implements IInventoryCable, IPaintable, IConfiguratorHighlight {

	public PaintedFramedInventoryCableBlock() {
		super(Block.Properties.of().mapColor(MapColor.WOOD).sound(SoundType.WOOD).strength(2).noOcclusion());
	}

	@Override
	@Environment(EnvType.CLIENT)
	public void appendHoverText(ItemStack stack, BlockGetter worldIn, List<Component> tooltip,
			TooltipFlag flagIn) {
		tooltip.add(Component.translatable("tooltip.obscuras_storage.paintable"));
		StorageModClient.tooltip("inventory_cable", tooltip);
	}

	@Override
	public List<BlockPos> next(Level world, BlockState state, BlockPos pos) {
		List<BlockPos> next = new ArrayList<>();
		for (Direction d : Direction.values()) {
			next.add(pos.relative(d));
		}
		return next;
	}

	@Override
	public boolean paint(Level world, BlockPos pos, BlockState to) {
		world.setBlock(pos, StorageMod.invCablePainted.get().defaultBlockState(), 2);
		BlockEntity te = world.getBlockEntity(pos);
		if(te != null && te instanceof PaintedBlockEntity)
			return ((PaintedBlockEntity)te).setPaintedBlockState(to);
		return false;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new PaintedBlockEntity(pos, state);
	}

	@Override
	public RenderShape getRenderShape(BlockState p_149645_1_) {
		return RenderShape.MODEL;
	}

	@Override
	public boolean propagatesSkylightDown(BlockState state, BlockGetter world, BlockPos pos) {
		return false;
	}

	@Override
	public ItemStack getCloneItemStack(BlockGetter world, BlockPos pos, BlockState state) {
		return new ItemStack(Content.invCableFramed.get());
	}

	@Override
	public int getHighlightColor() {
		return 0xFF0000;
	}

	@Override
	public VoxelShape getHighlightShape(BlockState state, BlockGetter level, BlockPos pos) {
		BlockState cable = Content.invCable.get().defaultBlockState();
		for (Direction d : Direction.values()) {
			BooleanProperty p = PipeBlock.PROPERTY_BY_DIRECTION.get(d);
			// The painted block has no connection properties, show the connections the cable would have
			cable = cable.setValue(p, IInventoryCable.canConnect(level.getBlockState(pos.relative(d)), d));
		}
		return cable.getShape(level, pos);
	}
}
