package com.obscura.storage.block;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

import com.mojang.serialization.MapCodec;

import com.obscura.storage.block.entity.BasicInventoryHopperBlockEntity;
import com.obscura.storage.client.ClientUtil;
import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.item.InventoryConfiguratorItem;
import com.obscura.storage.util.HopperMode;

public class BasicInventoryHopperBlock extends AbstractInventoryHopperBlock {
	public static final MapCodec<BasicInventoryHopperBlock> CODEC = ChestBlock.simpleCodec(properties -> new BasicInventoryHopperBlock());

	public BasicInventoryHopperBlock() {
		super(Block.Properties.of().mapColor(MapColor.WOOD).sound(SoundType.WOOD).strength(3).noOcclusion());
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new BasicInventoryHopperBlockEntity(pos, state);
	}

	@Override
	public void appendHoverText(ItemStack itemStack, TooltipContext tooltipContext, List<Component> tooltip,
			TooltipFlag tooltipFlag) {
		super.appendHoverText(itemStack, tooltipContext, tooltip, tooltipFlag);
		ClientUtil.tooltip("inventory_hopper_modes", false, tooltip);
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack is, BlockState state, Level world,
			BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (is.isEmpty())return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (is.getItem() instanceof InventoryConfiguratorItem) {
			// Normally handled earlier by InventoryConfiguratorItem.onRightClick (UseBlockCallback), this is
			// only a fallback. The configurator must never become the filter.
			if (!world.isClientSide)useConfigurator(world, pos, player);
			return ItemInteractionResult.SUCCESS;
		}
		if(!world.isClientSide) {
			BlockEntity te = world.getBlockEntity(pos);
			if(te instanceof BasicInventoryHopperBlockEntity h) {
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
			}
		}
		return ItemInteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player,
			BlockHitResult hit) {
		if(!world.isClientSide) {
			BlockEntity te = world.getBlockEntity(pos);
			if(te instanceof BasicInventoryHopperBlockEntity h) {
				if(player.isShiftKeyDown()) {
					if(!h.getFilter().isEmpty() && h.getFilter().getItem() instanceof IItemFilter) {
						player.getInventory().add(h.getFilter());
					}
					h.setFilter(ItemStack.EMPTY);
				}
				player.displayClientMessage(statusMessage(h), true);
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
		if (!(world.getBlockEntity(pos) instanceof BasicInventoryHopperBlockEntity h))return false;
		if (player.isSecondaryUseActive())h.cycleAmount();
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
	public void onRemove(BlockState state, Level world, BlockPos pos, BlockState state2, boolean flag) {
		if (!state.is(state2.getBlock())) {
			BlockEntity blockentity = world.getBlockEntity(pos);
			if (blockentity instanceof BasicInventoryHopperBlockEntity te && !te.getFilter().isEmpty() && te.getFilter().getItem() instanceof IItemFilter) {
				Containers.dropItemStack(world, pos.getX(), pos.getY(), pos.getZ(), te.getFilter());
			}

			super.onRemove(state, world, pos, state2, flag);
		}
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}
}
