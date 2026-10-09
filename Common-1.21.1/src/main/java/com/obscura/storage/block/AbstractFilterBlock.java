package com.obscura.storage.block;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
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

import com.obscura.storage.block.entity.AbstractFilterBlockEntity;
import com.obscura.storage.block.entity.IInventoryConnector;
import com.obscura.storage.client.ClientUtil;
import com.obscura.storage.inventory.InventoryCableNetwork;
import com.obscura.storage.util.BlockFace;
import com.obscura.storage.util.TickerUtil;

/**
 * Shared block of the Ejector, Disposal and Exporter: a leaf of the storage network with a 3x3 ghost
 * filter. {@link #FACING} is the FRONT (where the items go); the BACK is the source side, the only
 * side a cable connects to. The block exposes no inventory of its own, so a connector never adopts it.
 */
public abstract class AbstractFilterBlock extends BaseEntityBlock implements IInventoryCable, NeoForgeBlock {
	public static final DirectionProperty FACING = BlockStateProperties.FACING;
	private final String tooltipKey;

	/** @param tooltipKey the registry path of the block, which is also its tooltip key */
	protected AbstractFilterBlock(String tooltipKey) {
		super(Block.Properties.of().mapColor(MapColor.WOOD).sound(SoundType.WOOD).strength(3));
		this.tooltipKey = tooltipKey;
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
	}

	@Override
	public void appendHoverText(ItemStack itemStack, TooltipContext tooltipContext, List<Component> tooltip,
			TooltipFlag tooltipFlag) {
		ClientUtil.tooltip(tooltipKey, tooltip);
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

	/** See {@link #frontFor}. Sneaking does not change the orientation. */
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
		Direction front = frontFor(toClicked, network, level.getBlockEntity(clicked) != null, networkNeighbours, context.getNearestLookingDirection().getOpposite());
		return defaultBlockState().setValue(FACING, front);
	}

	/**
	 * The placement rule as a pure function, the first case that applies decides:
	 * <ol>
	 * <li>placed against a network block (cable, connector, interface, terminal...): the BACK faces it,
	 * so it plugs in;</li>
	 * <li>placed against any other block with a block entity (chest, barrel, Shulker Dock, a machine):
	 * the FRONT points at it;</li>
	 * <li>a network block next to the new block: the BACK faces it. With several, the one opposite the
	 * clicked block wins, then the first in the order down, up, north, south, west, east;</li>
	 * <li>otherwise like a vanilla dropper: the front faces the player.</li>
	 * </ol>
	 *
	 * @param toClicked direction from the new block to the block it was placed against
	 * @param clickedNetworkBlock that block is a network block
	 * @param clickedHasBlockEntity that block has a block entity
	 * @param networkNeighbours directions from the new block in which a network block sits
	 * @param dropperFront the front a dropper placed by the same click would get
	 * @return the front (FACING) of the new block
	 */
	public static Direction frontFor(Direction toClicked, boolean clickedNetworkBlock, boolean clickedHasBlockEntity, Set<Direction> networkNeighbours, Direction dropperFront) {
		if (clickedNetworkBlock)return toClicked.getOpposite();
		if (clickedHasBlockEntity)return toClicked;
		if (networkNeighbours.contains(toClicked.getOpposite()))return toClicked;
		// Direction.values(): down, up, north, south, west, east
		for (Direction d : Direction.values()) {
			if (networkNeighbours.contains(d))return d.getOpposite();
		}
		return dropperFront;
	}

	private static boolean isNetworkBlock(Level level, BlockPos pos) {
		if (level.getBlockState(pos).getBlock() instanceof IInventoryCable)return true;
		return level.getBlockEntity(pos) instanceof IInventoryConnector;
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
	public List<BlockFace> nextScan(Level world, BlockState state, BlockPos pos) {
		return Collections.emptyList();
	}

	/** Only the back: a cable draws an arm to it and to no other side. */
	@Override
	public boolean canConnectFrom(BlockState state, Direction dir) {
		return state.getValue(FACING).getOpposite() == dir;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player,
			BlockHitResult hit) {
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
	public void destroy(LevelAccessor p_49860_, BlockPos p_49861_, BlockState p_49862_) {
		if (p_49860_ instanceof ServerLevel l)
			InventoryCableNetwork.getNetwork(l).markNodeInvalid(p_49861_);
	}

	@Override
	public void neighborChanged(BlockState p_60509_, Level p_60510_, BlockPos p_60511_, Block p_60512_,
			BlockPos p_60513_, boolean p_60514_) {
		super.neighborChanged(p_60509_, p_60510_, p_60511_, p_60512_, p_60513_, p_60514_);
		if (!p_60510_.isClientSide) {
			updateRedstone(p_60510_, p_60511_);
			// Only invalidates when the neighbour now looks different to the cable graph
			InventoryCableNetwork.getNetwork(p_60510_).onNeighborChanged(p_60511_, p_60512_, p_60513_);
		}
	}

	@Override
	public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
		if (level instanceof ServerLevel l) {
			InventoryCableNetwork.getNetwork(l).onNeighborChanged(pos, false, neighbor);
		}
	}
}
