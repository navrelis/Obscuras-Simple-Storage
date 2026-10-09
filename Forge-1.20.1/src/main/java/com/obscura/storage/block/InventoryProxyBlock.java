package com.obscura.storage.block;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.obscura.storage.StorageMod;
import com.obscura.storage.StorageModClient;
import com.obscura.storage.tile.InventoryProxyBlockEntity;
import com.obscura.storage.tile.PaintedBlockEntity;
import com.obscura.storage.util.TickerUtil;

public class InventoryProxyBlock extends BaseEntityBlock implements IPaintable, IConfiguratorHighlight {
	public static final DirectionProperty FACING = BlockStateProperties.FACING;

	public InventoryProxyBlock() {
		super(Block.Properties.of().mapColor(MapColor.WOOD).sound(SoundType.WOOD).strength(3));
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.DOWN));
	}

	@Override
	public void appendHoverText(ItemStack stack, BlockGetter worldIn, List<Component> tooltip,
			TooltipFlag flagIn) {
		tooltip.add(Component.translatable("tooltip.obscuras_storage.paintable"));
		StorageModClient.tooltip("inventory_proxy", tooltip);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new InventoryProxyBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state,
			BlockEntityType<T> type) {
		return TickerUtil.createTicker(world, false, true);
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
		return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public RenderShape getRenderShape(BlockState p_149645_1_) {
		return RenderShape.MODEL;
	}

	@Override
	public boolean paint(Level world, BlockPos pos, BlockState to) {
		BlockState old = world.getBlockState(pos);
		world.setBlock(pos, StorageMod.invProxyPainted.get().defaultBlockState().setValue(FACING, old.getValue(FACING)), 2);
		BlockEntity te = world.getBlockEntity(pos);
		if(te != null && te instanceof PaintedBlockEntity)
			return ((PaintedBlockEntity)te).setPaintedBlockState(to);
		return false;
	}

	@Override
	public int getAnalogOutputSignal(BlockState state, Level world, BlockPos pos) {
		BlockEntity te = world.getBlockEntity(pos);
		return AbstractContainerMenu.getRedstoneSignalFromBlockEntity(te);
	}

	@Override
	public int getHighlightColor() {
		return 0x880088;
	}

	@Override
	public VoxelShape getHighlightShape(BlockState state, BlockGetter level, BlockPos pos) {
		switch (state.getValue(FACING)) {
		case DOWN:
			return Shapes.or(box(4, 2, 4, 12, 12, 12), box(2, 0, 2, 14, 2, 14));
		case EAST:
			return Shapes.or(box(4, 4, 4, 14, 12, 12), box(14, 2, 2, 16, 14, 14));
		case NORTH:
			return Shapes.or(box(4, 4, 2, 12, 12, 12), box(2, 2, 0, 14, 14, 2));
		case SOUTH:
			return Shapes.or(box(4, 4, 4, 12, 12, 14), box(2, 2, 14, 14, 14, 16));
		case UP:
			return Shapes.or(box(4, 4, 4, 12, 14, 12), box(2, 14, 2, 14, 16, 14));
		case WEST:
			return Shapes.or(box(2, 4, 4, 12, 12, 12), box(0, 2, 2, 2, 14, 14));
		default:
			break;
		}
		return Shapes.block();
	}
}
