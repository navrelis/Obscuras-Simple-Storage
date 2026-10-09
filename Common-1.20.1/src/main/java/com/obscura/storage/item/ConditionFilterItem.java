package com.obscura.storage.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import com.obscura.storage.StorageModClient;
import com.obscura.storage.gui.ConditionFilterMenu;
import com.obscura.storage.util.BlockFace;
import com.obscura.storage.util.ConditionFilter;
import com.obscura.storage.util.ConditionFilterState;
import com.obscura.storage.util.FilterCondition;
import com.obscura.storage.util.ItemPredicate;

public class ConditionFilterItem extends Item implements IItemFilter {

	public ConditionFilterItem() {
		super(new Properties().stacksTo(1));
	}

	@Override
	public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag tooltipFlag) {
		StorageModClient.tooltip("condition_filter", tooltip);
		ConditionFilterState c = ConditionFilterState.read(stack);
		if (c.isEmpty()) {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.condition_filter.none").withStyle(ChatFormatting.RED));
			return;
		}
		Component req = names(c, ConditionFilterState.STATE_REQUIRED);
		Component exc = names(c, ConditionFilterState.STATE_EXCLUDED);
		if (req != null && exc != null) {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.condition_filter.requires_excludes", req, exc).withStyle(ChatFormatting.GRAY));
		} else if (req != null) {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.condition_filter.requires", req).withStyle(ChatFormatting.GRAY));
		} else {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.condition_filter.excludes", exc).withStyle(ChatFormatting.GRAY));
		}
	}

	/** Comma separated names of the conditions in the given state, or null if there are none. */
	private static Component names(ConditionFilterState c, int state) {
		List<Component> list = new ArrayList<>();
		for (int i = 0; i < FilterCondition.COUNT; i++) {
			if (c.getState(i) == state)list.add(Component.translatable(FilterCondition.byIndex(i).nameKey()));
		}
		if (list.isEmpty())return null;
		MutableComponent out = Component.empty();
		for (int i = 0; i < list.size(); i++) {
			if (i > 0)out.append(", ");
			out.append(list.get(i));
		}
		return out;
	}

	@Override
	public ItemPredicate createFilter(BlockFace face, ItemStack stack) {
		return new ConditionFilter(face, stack);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
		ItemStack is = player.getItemInHand(hand);
		openGui(is, player, () -> player.getItemInHand(hand).getItem() == this, null);
		return InteractionResultHolder.sidedSuccess(is, world.isClientSide);
	}

	@Override
	public void openGui(ItemStack is, Player player, BooleanSupplier isValid, Runnable refresh) {
		player.openMenu(new SimpleMenuProvider((id, pi, pl) -> new ConditionFilterMenu(id, pi, is, isValid, refresh), is.getHoverName()));
	}
}
