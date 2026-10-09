package com.obscura.storage.item;

import java.util.List;

import net.minecraft.ChatFormatting;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.StorageTags;
import com.obscura.storage.block.entity.StorageTerminalBlockEntity;
import com.obscura.storage.client.ClientUtil;
import com.obscura.storage.components.WorldPos;
import com.obscura.storage.migration.LegacyItemComponents;

public class AdvWirelessTerminalItem extends Item implements WirelessTerminal {

	/** Settings saved by the 1.20.1 builds (custom data) move to their components, see LegacyItemComponents. */
	@Override
	public void verifyComponentsAfterLoad(ItemStack stack) {
		super.verifyComponentsAfterLoad(stack);
		LegacyItemComponents.onLoad(stack);
	}

	public AdvWirelessTerminalItem() {
		super(new Properties().stacksTo(1).fireResistant());
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext tooltipContext, List<Component> tooltip,
			TooltipFlag tooltipFlag) {
		ClientUtil.tooltip("adv_wireless_terminal", tooltip, Config.get().advWirelessRange);
		if (Config.get().wirelessTermBeaconLvl != -1) {
			ClientUtil.tooltip("adv_wireless_terminal.beacon", false, tooltip);
			ClientUtil.tooltip("adv_wireless_terminal.beacon1", false, tooltip, Config.get().wirelessTermBeaconLvl);
			if (Config.get().wirelessTermBeaconLvlCrossDim != -1) {
				ClientUtil.tooltip("adv_wireless_terminal.beacon2", false, tooltip, Config.get().wirelessTermBeaconLvlCrossDim);
			}
		}
		if (Config.get().buildersReachEnabled)
			ClientUtil.tooltip("wireless_terminal.pick_block", false, tooltip);
		if (Config.get().restockEnabled)
			ClientUtil.tooltip("adv_wireless_terminal.restock_info", false, tooltip);
		WorldPos pos = stack.get(Content.boundPosComponent.get());
		if(pos != null) {
			int x = pos.pos().getX();
			int y = pos.pos().getY();
			int z = pos.pos().getZ();
			String dim = pos.dim().location().toString();
			tooltip.add(Component.translatable("tooltip.obscuras_storage.adv_wireless_terminal.bound", x, y, z, dim));
		}
		tooltip.add(Component.translatable("tooltip.obscuras_storage.adv_wireless_terminal.keybind", Component.translatable("tooltip.obscuras_storage.adv_wireless_terminal.keybind.outline", Component.keybind("key.obscuras_storage.open_terminal")).withStyle(ChatFormatting.GREEN)));
		if (Config.get().restockEnabled) {
			boolean on = isRestockOn(stack);
			tooltip.add(Component.translatable("tooltip.obscuras_storage.adv_wireless_terminal.restock",
					Component.translatable(on ? "tooltip.obscuras_storage.restock.on" : "tooltip.obscuras_storage.restock.off").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
			tooltip.add(Component.translatable("tooltip.obscuras_storage.adv_wireless_terminal.restock_keybind", Component.translatable("tooltip.obscuras_storage.adv_wireless_terminal.keybind.outline", Component.keybind("key.obscuras_storage.toggle_restock")).withStyle(ChatFormatting.GREEN)));
		}
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level worldIn, Player playerIn, InteractionHand handIn) {
		return new InteractionResultHolder<>(activateTerminal(worldIn, playerIn.getItemInHand(handIn), playerIn, handIn), playerIn.getItemInHand(handIn));
	}

	public static InteractionResult activateTerminal(Level worldIn, ItemStack stack, Player playerIn, InteractionHand handIn) {
		if(stack.get(Content.boundPosComponent.get()) == null)return InteractionResult.PASS;
		if(worldIn.isClientSide)return InteractionResult.CONSUME;
		BoundTerminal t = BoundTerminal.resolve(playerIn, stack);
		switch (t.status()) {
		case OK:
			return t.open(playerIn);
		case NO_LEVEL:
			return InteractionResult.FAIL;
		case OUT_OF_RANGE:
			t.showMessage(playerIn);
			return InteractionResult.FAIL;
		default:
			// Not loaded / invalid block: message, then pass like before
			t.showMessage(playerIn);
			return InteractionResult.PASS;
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

	@Override
	public int getRange(Player pl, ItemStack stack) {
		return Config.get().advWirelessRange;
	}

	@Override
	public void open(Player sender, ItemStack t) {
		activateTerminal(sender.level(), t, sender, InteractionHand.MAIN_HAND);
	}

	@Override
	public boolean canOpen(ItemStack t) {
		return true;
	}

	@Override
	public boolean isInRange(Player player, ItemStack stack, Level termLevel, BlockPos pos) {
		if (player.level().dimension() != termLevel.dimension()) {
			return crossDimAllowed(getBeaconLevelAt(termLevel, pos));
		}
		double distSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		int range = Config.get().advWirelessRange;
		if (distSq <= (double) range * range)return true;
		int beaconLvl = getBeaconLevelAt(termLevel, pos);
		return Config.get().wirelessTermBeaconLvl != -1 && beaconLvl >= Config.get().wirelessTermBeaconLvl;
	}

	public static boolean isRestockOn(ItemStack stack) {
		return Boolean.TRUE.equals(stack.get(Content.restockComponent.get()));
	}

	/** Off is stored as "no component", so untouched terminals keep their plain components. */
	public static void setRestock(ItemStack stack, boolean on) {
		if (on)stack.set(Content.restockComponent.get(), true);
		else stack.remove(Content.restockComponent.get());
	}

	/**
	 * Same rule as {@link StorageTerminalBlockEntity#canInteractWith}: the terminal's beacon has to reach the
	 * same-dimension level before the cross-dimension level applies.
	 */
	private static boolean crossDimAllowed(int beaconLvl) {
		Config cfg = Config.get();
		return cfg.wirelessTermBeaconLvl != -1 && beaconLvl >= cfg.wirelessTermBeaconLvl
				&& cfg.wirelessTermBeaconLvlCrossDim != -1 && beaconLvl >= cfg.wirelessTermBeaconLvlCrossDim;
	}

	private static int getBeaconLevelAt(Level level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof StorageTerminalBlockEntity ste) {
			return ste.getBeaconLevel();
		}
		return -1;
	}
}
