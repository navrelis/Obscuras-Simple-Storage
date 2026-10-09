package com.obscura.storage.block;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
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
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

import com.obscura.storage.StorageModClient;
import com.obscura.storage.tile.ShulkerDockBlockEntity;
import com.obscura.storage.util.ComparatorOutput;

/**
 * Shulker Dock: holds one shulker box and offers its 27 slots to the network, hoppers and
 * comparators. The box goes in and out by hand only:
 * <ul>
 * <li>dockable item in hand: docks it (swapping with the docked box, which goes to that hand);</li>
 * <li>empty main hand on a docked box: takes the box out into that hand;</li>
 * <li>anything else: no block interaction, the item is used as usual (e.g. a block is placed).</li>
 * </ul>
 * Sneaking with an item in either hand skips all of this (vanilla), so a held box is placed as a block.
 *
 * <p>1.20.1 has one {@code use} per hand (main hand first, the off hand only when the main hand did
 * nothing), which gives the same behaviour as the 1.21.1 {@code useItemOn}/{@code useWithoutItem} pair.
 */
public class ShulkerDockBlock extends BaseEntityBlock {
	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final EnumProperty<Box> BOX = EnumProperty.create("box", Box.class);

	public ShulkerDockBlock() {
		super(Block.Properties.of().mapColor(MapColor.WOOD).instrument(NoteBlockInstrument.BASS).sound(SoundType.WOOD).strength(2.5F));
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(BOX, Box.NONE));
	}

	/** What the dock's window shows: no box, an undyed (or modded) box, or one of the 16 colours. */
	public enum Box implements StringRepresentable {
		NONE("none", null), SHULKER("shulker", null),
		WHITE(DyeColor.WHITE), ORANGE(DyeColor.ORANGE), MAGENTA(DyeColor.MAGENTA), LIGHT_BLUE(DyeColor.LIGHT_BLUE),
		YELLOW(DyeColor.YELLOW), LIME(DyeColor.LIME), PINK(DyeColor.PINK), GRAY(DyeColor.GRAY),
		LIGHT_GRAY(DyeColor.LIGHT_GRAY), CYAN(DyeColor.CYAN), PURPLE(DyeColor.PURPLE), BLUE(DyeColor.BLUE),
		BROWN(DyeColor.BROWN), GREEN(DyeColor.GREEN), RED(DyeColor.RED), BLACK(DyeColor.BLACK);

		private final String name;
		private final DyeColor color;

		Box(DyeColor color) {
			this(color.getSerializedName(), color);
		}

		Box(String name, DyeColor color) {
			this.name = name;
			this.color = color;
		}

		@Override
		public String getSerializedName() {
			return name;
		}

		public DyeColor color() {
			return color;
		}

		/** The window for a docked {@code stack}: its colour for a vanilla box, the undyed look otherwise. */
		public static Box of(ItemStack stack) {
			if (stack.isEmpty())return NONE;
			if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock sb && sb.getColor() != null) {
				for (Box b : values()) {
					if (b.color == sb.getColor())return b;
				}
			}
			return SHULKER;
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable BlockGetter level, List<Component> tooltip, TooltipFlag flag) {
		StorageModClient.tooltip("shulker_dock", tooltip);
	}

	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, BOX);
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
	public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		ItemStack stack = player.getItemInHand(hand);
		if (stack.isEmpty()) {
			// Empty hand: only the main hand takes the box out; an empty off hand does nothing.
			if (hand != InteractionHand.MAIN_HAND)return InteractionResult.PASS;
			if (level.isClientSide)return state.getValue(BOX) != Box.NONE ? InteractionResult.SUCCESS : InteractionResult.PASS;
			if (level.getBlockEntity(pos) instanceof ShulkerDockBlockEntity be && be.hasBox()) {
				be.takeOutToHand(player);
				return InteractionResult.CONSUME;
			}
			// Empty hand on an empty dock: nothing, the off hand may still act.
			return InteractionResult.PASS;
		}
		// Anything that is not a box: no block interaction, the item does its own thing.
		if (!ShulkerDockBlockEntity.isDockable(stack))return InteractionResult.PASS;
		if (level.isClientSide)return InteractionResult.SUCCESS;
		if (!(level.getBlockEntity(pos) instanceof ShulkerDockBlockEntity be))return InteractionResult.PASS;
		be.insertFromHand(player, hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new ShulkerDockBlockEntity(pos, state);
	}

	@Override
	public boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
		return ComparatorOutput.at(level, pos);
	}

	/**
	 * Broken, exploded or replaced: the box drops as one item with its contents (never 27 stacks).
	 * The dock itself drops through its loot table. A change of this block's own state (box shown,
	 * rotation) is not a removal.
	 */
	@Override
	public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.is(newState.getBlock())) {
			if (level.getBlockEntity(pos) instanceof ShulkerDockBlockEntity be) {
				be.dropBox();
				level.updateNeighbourForOutputSignal(pos, this);
			}
			super.onRemove(state, level, pos, newState, moved);
		}
	}
}
