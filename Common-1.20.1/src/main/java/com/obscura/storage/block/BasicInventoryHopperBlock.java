package com.obscura.storage.block;

import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.obscura.storage.StorageModClient;
import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.item.InventoryConfiguratorItem;
import com.obscura.storage.tile.AbstractInventoryHopperBlockEntity;
import com.obscura.storage.tile.BasicInventoryHopperBlockEntity;
import com.obscura.storage.util.HopperMode;
import com.obscura.storage.util.TickerUtil;

public class BasicInventoryHopperBlock extends BaseEntityBlock implements IInventoryCable {
	public static final DirectionProperty FACING = BlockStateProperties.FACING;
	public static final BooleanProperty ENABLED = BlockStateProperties.ENABLED;

	public BasicInventoryHopperBlock() {
		super(Block.Properties.of().mapColor(MapColor.WOOD).sound(SoundType.WOOD).strength(3).noOcclusion());
		registerDefaultState(defaultBlockState()
				.setValue(FACING, Direction.DOWN).setValue(ENABLED, Boolean.valueOf(true)));
	}

	@Override
	public void appendHoverText(ItemStack stack, BlockGetter worldIn, List<Component> tooltip,
			TooltipFlag flagIn) {
		StorageModClient.tooltip("inventory_hopper", tooltip);
		StorageModClient.tooltip("inventory_hopper_modes", false, tooltip);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new BasicInventoryHopperBlockEntity(pos, state);
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
		builder.add(FACING, ENABLED);
	}

	@Override
	public RenderShape getRenderShape(BlockState p_149645_1_) {
		return RenderShape.MODEL;
	}

	@Override
	public List<BlockPos> next(Level world, BlockState state, BlockPos pos) {
		return Collections.emptyList();
	}

	@Override
	public boolean canConnectFrom(BlockState state, Direction dir) {
		return state.getValue(FACING).getAxis() == dir.getAxis();
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter worldIn, BlockPos pos, CollisionContext context) {
		switch (state.getValue(FACING)) {
		case DOWN:
			return Shapes.or(box(5, 0, 5, 11, 6, 11), box(3, 6, 3, 13, 16, 13));
		case EAST:
			return Shapes.or(box(10, 5, 5, 16, 11, 11), box(0, 3, 3, 10, 13, 13));
		case NORTH:
			return Shapes.or(box(5, 5, 0, 11, 11, 6), box(3, 3, 6, 13, 13, 16));
		case SOUTH:
			return Shapes.or(box(5, 5, 10, 11, 11, 16), box(3, 3, 0, 13, 13, 10));
		case UP:
			return Shapes.or(box(5, 10, 5, 11, 16, 11), box(3, 0, 3, 13, 10, 13));
		case WEST:
			return Shapes.or(box(0, 5, 5, 6, 11, 11), box(6, 3, 3, 16, 13, 13));
		default:
			break;
		}
		return Shapes.block();
	}

	@Override
	public InteractionResult use(BlockState st, Level world, BlockPos pos,
			Player player, InteractionHand hand, BlockHitResult trace) {
		if(!world.isClientSide) {
			ItemStack is = player.getItemInHand(hand);
			BlockEntity te = world.getBlockEntity(pos);
			if(te instanceof BasicInventoryHopperBlockEntity h) {
				if(!is.isEmpty()) {
					if(is.getItem() instanceof InventoryConfiguratorItem) {
						// Normally handled earlier by the use block callback, this is only a fallback.
						// The configurator must never become the filter.
						useConfigurator(world, pos, player);
						return InteractionResult.SUCCESS;
					}
					if(!h.getFilter().isEmpty() && h.getFilter().getItem() instanceof IItemFilter) {
						popResource(world, pos, h.getFilter());
					}
					h.setFilter(is.copy());
					if(is.getItem() instanceof IItemFilter) {
						player.setItemInHand(hand, ItemStack.EMPTY);
					} else {
						// A plain item filter uses the held stack size as the amount, filter items keep the configured one
						h.setAmount(is.getCount());
					}
					player.displayClientMessage(statusMessage(h), true);
				} else {
					if(player.isShiftKeyDown()) {
						if(!h.getFilter().isEmpty() && h.getFilter().getItem() instanceof IItemFilter) {
							if (!player.getInventory().add(h.getFilter())) {
								player.drop(h.getFilter(), false);
							}
						}
						h.setFilter(ItemStack.EMPTY);
					}
					player.displayClientMessage(statusMessage(h), true);
				}
			}
		}
		return InteractionResult.SUCCESS;
	}

	/**
	 * Right click with the Inventory Configurator: cycles the mode, sneak right click cycles the amount.
	 * Server side only.
	 *
	 * @return false if there is no hopper block entity at pos
	 */
	public static boolean useConfigurator(Level world, BlockPos pos, Player player) {
		if(!(world.getBlockEntity(pos) instanceof BasicInventoryHopperBlockEntity h))return false;
		if(player.isSecondaryUseActive())h.cycleAmount();
		else h.cycleMode();
		HopperMode mode = h.getMode();
		String key = mode.needsFilter() && h.getFilter().isEmpty() ? "tooltip.obscuras_storage.hopper_mode_amount_no_filter" : "tooltip.obscuras_storage.hopper_mode_amount";
		player.displayClientMessage(Component.translatable(key, Component.translatable(mode.getTranslationKey()), h.getAmount()), true);
		return true;
	}

	/** Filter, mode and amount in one action bar line. */
	public static Component statusMessage(BasicInventoryHopperBlockEntity h) {
		ItemStack s = h.getFilter();
		Component txt = s.isEmpty() ? Component.translatable("tooltip.obscuras_storage.empty") : s.getHoverName();
		return Component.translatable("tooltip.obscuras_storage.hopper_status", txt, Component.translatable(h.getMode().getTranslationKey()), h.getAmount());
	}

	@Override
	public void onPlace(BlockState p_54110_, Level p_54111_, BlockPos p_54112_, BlockState p_54113_, boolean p_54114_) {
		if (!p_54113_.is(p_54110_.getBlock())) {
			this.updateState(p_54111_, p_54112_, p_54110_);
		}
	}

	@Override
	public void neighborChanged(BlockState state, Level worldIn, BlockPos pos, Block blockIn, BlockPos fromPos, boolean isMoving) {
		this.updateState(worldIn, pos, state);
	}

	/** Re-reads the redstone signal, lets the block entity see it (edge detection) and updates ENABLED. */
	public void updateState(Level worldIn, BlockPos pos, BlockState state) {
		if (worldIn.isClientSide)return;
		boolean powered = worldIn.hasNeighborSignal(pos);
		boolean flag = !powered;
		if (worldIn.getBlockEntity(pos) instanceof AbstractInventoryHopperBlockEntity be) {
			flag = be.onRedstoneUpdate(powered);
		}
		if (flag != state.getValue(ENABLED)) {
			worldIn.setBlock(pos, state.setValue(ENABLED, Boolean.valueOf(flag)), 4);
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	public void onRemove(BlockState state, Level world, BlockPos pos, BlockState state2, boolean flag) {
		if (!state.is(state2.getBlock())) {
			BlockEntity blockentity = world.getBlockEntity(pos);
			if (blockentity instanceof BasicInventoryHopperBlockEntity te && !te.getFilter().isEmpty() && te.getFilter().getItem() instanceof IItemFilter) {
				Containers.dropItemStack(world, pos.getX(), pos.getY(), pos.getZ(), te.getFilter());
			}

			super.onRemove(state, world, pos, state2, flag);
		}
	}
}
