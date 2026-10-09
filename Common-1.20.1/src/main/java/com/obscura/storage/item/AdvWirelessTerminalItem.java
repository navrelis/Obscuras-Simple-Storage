package com.obscura.storage.item;

import java.util.List;

import net.minecraft.ChatFormatting;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Config;
import com.obscura.storage.StorageModClient;
import com.obscura.storage.StorageTags;
import com.obscura.storage.tile.StorageTerminalBlockEntity;

public class AdvWirelessTerminalItem extends Item implements WirelessTerminal {
	/** Boolean NBT key: Restock is on. Absent when off. */
	public static final String RESTOCK_TAG = "Restock";

	public AdvWirelessTerminalItem() {
		super(new Properties().stacksTo(1).fireResistant());
	}

	@Override
	public void appendHoverText(ItemStack stack, Level worldIn, List<Component> tooltip, TooltipFlag flagIn) {
		StorageModClient.tooltip("adv_wireless_terminal", tooltip, Config.get().advWirelessRange);
		if (Config.get().wirelessTermBeaconLvl != -1) {
			StorageModClient.tooltip("adv_wireless_terminal.beacon", false, tooltip);
			StorageModClient.tooltip("adv_wireless_terminal.beacon1", false, tooltip, Config.get().wirelessTermBeaconLvl);
			if (Config.get().wirelessTermBeaconLvlCrossDim != -1) {
				StorageModClient.tooltip("adv_wireless_terminal.beacon2", false, tooltip, Config.get().wirelessTermBeaconLvlCrossDim);
			}
		}
		if (Config.get().buildersReachEnabled)
			StorageModClient.tooltip("wireless_terminal.pick_block", false, tooltip);
		if (Config.get().restockEnabled)
			StorageModClient.tooltip("adv_wireless_terminal.restock_info", false, tooltip);
		if(stack.hasTag() && stack.getTag().contains("BindX")) {
			int x = stack.getTag().getInt("BindX");
			int y = stack.getTag().getInt("BindY");
			int z = stack.getTag().getInt("BindZ");
			String dim = stack.getTag().getString("BindDim");
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
		if(!BoundTerminal.isBound(stack))return InteractionResult.PASS;
		if(worldIn.isClientSide)return InteractionResult.CONSUME;
		BoundTerminal t = BoundTerminal.resolve(playerIn, stack);
		switch (t.status()) {
		case OK:
			return t.open(playerIn, handIn);
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
		// An unbound terminal has nothing to open, and must not shadow a bound one when the key looks for the first that can
		return BoundTerminal.isBound(t);
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
		return stack.hasTag() && stack.getTag().getBoolean(RESTOCK_TAG);
	}

	/** Off is stored as "no tag", so untouched terminals keep their plain NBT. */
	public static void setRestock(ItemStack stack, boolean on) {
		if (on) {
			stack.getOrCreateTag().putBoolean(RESTOCK_TAG, true);
		} else if (stack.hasTag()) {
			stack.getTag().remove(RESTOCK_TAG);
			if (stack.getTag().isEmpty())stack.setTag(null);
		}
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
