package com.obscura.storage.item;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.StorageTags;
import com.obscura.storage.client.ClientUtil;
import com.obscura.storage.components.WorldPos;
import com.obscura.storage.migration.LegacyItemComponents;
import com.obscura.storage.platform.Platform;

public class WirelessTerminalItem extends Item implements WirelessTerminal {

	/** Settings saved by the 1.20.1 builds (custom data) move to their components, see LegacyItemComponents. */
	@Override
	public void verifyComponentsAfterLoad(ItemStack stack) {
		super.verifyComponentsAfterLoad(stack);
		LegacyItemComponents.onLoad(stack);
	}

	public WirelessTerminalItem() {
		super(new Properties().stacksTo(1));
	}

	@Override
	public void appendHoverText(ItemStack itemStack, TooltipContext tooltipContext, List<Component> tooltip,
			TooltipFlag tooltipFlag) {
		ClientUtil.tooltip("wireless_terminal", tooltip, Config.get().wirelessRange);
		if (Config.get().buildersReachEnabled)
			ClientUtil.tooltip("wireless_terminal.pick_block", false, tooltip);
		WorldPos pos = itemStack.get(Content.boundPosComponent.get());
		if(pos != null) {
			int x = pos.pos().getX();
			int y = pos.pos().getY();
			int z = pos.pos().getZ();
			String dim = pos.dim().location().toString();
			tooltip.add(Component.translatable("tooltip.obscuras_storage.wireless_terminal.bound", x, y, z, dim));
		}
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level worldIn, Player playerIn, InteractionHand handIn) {
		ItemStack held = playerIn.getItemInHand(handIn);
		WorldPos bound = held.get(Content.boundPosComponent.get());
		if(bound != null) {
			InteractionResult r = activateBoundTerminal(worldIn, held, playerIn);
			if(r != InteractionResult.PASS) {
				return new InteractionResultHolder<>(r, playerIn.getItemInHand(handIn));
			}
		}
		BlockHitResult lookingAt = (BlockHitResult) playerIn.pick(Config.get().wirelessRange, 0f, true);
		BlockState state = worldIn.getBlockState(lookingAt.getBlockPos());
		if(state.is(StorageTags.REMOTE_ACTIVATE)) {
			InteractionResult r = Platform.checkUse(worldIn, lookingAt, playerIn, handIn);
			if(r == null)r = state.useWithoutItem(worldIn, playerIn, lookingAt);
			return new InteractionResultHolder<>(r, playerIn.getItemInHand(handIn));
		} else {
			return super.use(worldIn, playerIn, handIn);
		}
	}

	@Override
	public InteractionResult useOn(UseOnContext c) {
		if(c.isSecondaryUseActive()) {
			if(!c.getLevel().isClientSide) {
				BlockPos pos = c.getClickedPos();
				BlockState state = c.getLevel().getBlockState(pos);
				if(state.is(StorageTags.REMOTE_ACTIVATE)) {
					ItemStack stack = c.getItemInHand();
					stack.applyComponents(DataComponentPatch.builder().set(Content.boundPosComponent.get(), new WorldPos(c.getLevel().dimension(), pos)).build());
					if(c.getPlayer() != null)
						c.getPlayer().displayClientMessage(Component.translatable("chat.obscuras_storage.terminal_bound"), true);
					return InteractionResult.SUCCESS;
				}
			} else
				return InteractionResult.CONSUME;
		}
		return InteractionResult.PASS;
	}

	private static InteractionResult activateBoundTerminal(Level worldIn, ItemStack stack, Player playerIn) {
		if(worldIn.isClientSide)return InteractionResult.CONSUME;
		BoundTerminal t = BoundTerminal.resolve(playerIn, stack);
		switch (t.status()) {
		case OK:
			return t.open(playerIn);
		case NOT_BOUND:
			return InteractionResult.PASS;
		default:
			t.showMessage(playerIn);
			return InteractionResult.FAIL;
		}
	}

	public static boolean isPlayerHolding(Player player) {
		return player.getMainHandItem().getItem() == Content.wirelessTerminal.get() ||
				player.getOffhandItem().getItem() == Content.wirelessTerminal.get();
	}

	@Override
	public int getRange(Player pl, ItemStack stack) {
		return Config.get().wirelessRange;
	}

	@Override
	public void open(Player sender, ItemStack t) {
		WorldPos bound = t.get(Content.boundPosComponent.get());
		if(bound != null) {
			activateBoundTerminal(sender.level(), t, sender);
		}
	}

	@Override
	public boolean canOpen(ItemStack t) {
		return t.get(Content.boundPosComponent.get()) != null;
	}

	@Override
	public boolean isInRange(Player player, ItemStack stack, Level termLevel, BlockPos pos) {
		if (player.level().dimension() != termLevel.dimension())return false;
		double distSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		int range = Config.get().wirelessRange;
		return distSq <= (double) range * range;
	}
}
