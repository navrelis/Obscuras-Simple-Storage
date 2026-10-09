package com.obscura.storage.block;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.phys.BlockHitResult;

import com.obscura.storage.StorageModClient;
import com.obscura.storage.tile.AbstractFilterBlockEntity;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.util.FilterBlockLogic;
import com.obscura.storage.util.TickerUtil;

/**
 * Shared block of the Ejector, Disposal and Exporter: a leaf of the storage network with a 3x3 ghost
 * filter. {@link #FACING} is the FRONT (where the items go); the BACK is the source side, the only
 * side a cable connects to. The block exposes no inventory of its own, so a connector never adopts it.
 */
public abstract class AbstractFilterBlock extends BaseEntityBlock implements IInventoryCable {
	public static final DirectionProperty FACING = BlockStateProperties.FACING;
	private final String tooltipKey;

	/** @param tooltipKey the registry path of the block, which is also its tooltip key */
	protected AbstractFilterBlock(String tooltipKey) {
		super(Block.Properties.of().mapColor(MapColor.WOOD).sound(SoundType.WOOD).strength(3));
		this.tooltipKey = tooltipKey;
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
	}

	@Override
	public void appendHoverText(ItemStack stack, BlockGetter worldIn, List<Component> tooltip,
			TooltipFlag flagIn) {
		StorageModClient.tooltip(tooltipKey, tooltip);
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

	/** See {@link FilterBlockLogic#frontFor}. Sneaking does not change the orientation. */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		Direction toClicked = context.getClickedFace().getOpposite();
		BlockPos clicked = pos.relative(toClicked);
		boolean network = isNetworkBlock(level, clicked);
		Set<Direction> networkNeighbours = EnumSet.noneOf(Direction.class);
		if (!network) {
			for (Direction d : Direction.values()) {
				if (isNetworkBlock(level, pos.relative(d)))networkNeighbours.add(d);
			}
		}
		Direction front = FilterBlockLogic.frontFor(toClicked, network, level.getBlockEntity(clicked) != null, networkNeighbours, context.getNearestLookingDirection().getOpposite());
		return defaultBlockState().setValue(FACING, front);
	}

	private static boolean isNetworkBlock(Level level, BlockPos pos) {
		if (level.getBlockState(pos).getBlock() instanceof IInventoryCable)return true;
		return level.getBlockEntity(pos) instanceof InventoryConnectorBlockEntity;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public RenderShape getRenderShape(BlockState p_149645_1_) {
		return RenderShape.MODEL;
	}

	/** A leaf like the Basic Inventory Hopper: the network is looked up from the block behind, not traced through here. */
	@Override
	public List<BlockPos> next(Level world, BlockState state, BlockPos pos) {
		return Collections.emptyList();
	}

	/** Only the back: a cable draws an arm to it and to no other side. */
	@Override
	public boolean canConnectFrom(BlockState state, Direction dir) {
		return state.getValue(FACING).getOpposite() == dir;
	}

	@Override
	public InteractionResult use(BlockState state, Level world, BlockPos pos,
			Player player, InteractionHand hand, BlockHitResult rtr) {
		if (world.isClientSide) {
			return InteractionResult.SUCCESS;
		}

		BlockEntity blockEntity_1 = world.getBlockEntity(pos);
		if (blockEntity_1 instanceof AbstractFilterBlockEntity be) {
			player.openMenu(be);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean isMoving) {
		if (!oldState.is(state.getBlock())) {
			updateRedstone(world, pos);
		}
	}

	/** Re-reads the redstone signal and lets the block entity see it (edge detection). */
	public void updateRedstone(Level world, BlockPos pos) {
		if (world.isClientSide)return;
		if (world.getBlockEntity(pos) instanceof AbstractFilterBlockEntity be) {
			be.onRedstoneUpdate(world.hasNeighborSignal(pos));
		}
	}

	@Override
	public void neighborChanged(BlockState state, Level world, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
		if (world.isClientSide)return;
		updateRedstone(world, pos);
		// The block behind or in front may be another one now
		if (world.getBlockEntity(pos) instanceof AbstractFilterBlockEntity be)be.onNeighborChanged(fromPos);
	}
}
