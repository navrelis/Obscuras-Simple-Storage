package com.obscura.storage.item;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import com.obscura.storage.StorageModClient;
import com.obscura.storage.StorageTags;
import com.obscura.storage.platform.Platform;

public class WirelessTerminalItem extends Item implements WirelessTerminal {

	public WirelessTerminalItem() {
		super(new Properties().stacksTo(1));
	}

	@Override
	public void appendHoverText(ItemStack stack, Level worldIn, List<Component> tooltip, TooltipFlag flagIn) {
		StorageModClient.tooltip("wireless_terminal", tooltip, Config.get().wirelessRange);
		if (Config.get().buildersReachEnabled)
			StorageModClient.tooltip("wireless_terminal.pick_block", false, tooltip);
		if(stack.hasTag() && stack.getTag().contains("BindX")) {
			int x = stack.getTag().getInt("BindX");
			int y = stack.getTag().getInt("BindY");
			int z = stack.getTag().getInt("BindZ");
			String dim = stack.getTag().getString("BindDim");
			tooltip.add(Component.translatable("tooltip.obscuras_storage.wireless_terminal.bound", x, y, z, dim));
		}
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level worldIn, Player playerIn, InteractionHand handIn) {
		ItemStack held = playerIn.getItemInHand(handIn);
		if(held.hasTag() && held.getTag().contains("BindX")) {
			InteractionResult bound = activateBoundTerminal(worldIn, held, playerIn, handIn);
			if(bound != InteractionResult.PASS) {
				return new InteractionResultHolder<>(bound, playerIn.getItemInHand(handIn));
			}
		}
		BlockHitResult lookingAt = (BlockHitResult) playerIn.pick(Config.get().wirelessRange, 0f, true);
		BlockState state = worldIn.getBlockState(lookingAt.getBlockPos());
		if(state.is(StorageTags.REMOTE_ACTIVATE)) {
			InteractionResult r = Platform.checkUse(worldIn, lookingAt, playerIn, handIn);
			if(r == null)r = state.use(worldIn, playerIn, handIn, lookingAt);
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
					if(!stack.hasTag())stack.setTag(new CompoundTag());
					stack.getTag().putInt("BindX", pos.getX());
					stack.getTag().putInt("BindY", pos.getY());
					stack.getTag().putInt("BindZ", pos.getZ());
					stack.getTag().putString("BindDim", c.getLevel().dimension().location().toString());
					if(c.getPlayer() != null)
						c.getPlayer().displayClientMessage(Component.translatable("chat.obscuras_storage.terminal_bound"), true);
					return InteractionResult.SUCCESS;
				}
			} else
				return InteractionResult.CONSUME;
		}
		return InteractionResult.PASS;
	}

	private static InteractionResult activateBoundTerminal(Level worldIn, ItemStack stack, Player playerIn, InteractionHand handIn) {
		if(worldIn.isClientSide)return InteractionResult.CONSUME;
		BoundTerminal t = BoundTerminal.resolve(playerIn, stack);
		switch (t.status()) {
		case OK:
			return t.open(playerIn, handIn);
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
		if(t.hasTag() && t.getTag().contains("BindX")) {
			activateBoundTerminal(sender.level(), t, sender, InteractionHand.MAIN_HAND);
		}
	}

	@Override
	public boolean canOpen(ItemStack t) {
		return BoundTerminal.isBound(t);
	}

	@Override
	public boolean isInRange(Player player, ItemStack stack, Level termLevel, BlockPos pos) {
		if (player.level().dimension() != termLevel.dimension())return false;
		double distSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		int range = Config.get().wirelessRange;
		return distSq <= (double) range * range;
	}
}
