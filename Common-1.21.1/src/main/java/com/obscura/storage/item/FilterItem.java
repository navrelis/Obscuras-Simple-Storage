package com.obscura.storage.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import com.obscura.storage.Content;
import com.obscura.storage.client.ClientUtil;
import com.obscura.storage.components.SimpleItemFilterComponent;
import com.obscura.storage.inventory.filter.ItemFilter;
import com.obscura.storage.inventory.filter.SimpleItemFilter;
import com.obscura.storage.menu.ItemFilterMenu;
import com.obscura.storage.migration.LegacyItemComponents;
import com.obscura.storage.util.BlockFaceReference;

public class FilterItem extends Item implements IItemFilter {

	/** Settings saved by the 1.20.1 builds (custom data) move to their components, see LegacyItemComponents. */
	@Override
	public void verifyComponentsAfterLoad(ItemStack stack) {
		super.verifyComponentsAfterLoad(stack);
		LegacyItemComponents.onLoad(stack);
	}

	public FilterItem() {
		super(new Properties().stacksTo(1));
	}

	@Override
	public void appendHoverText(ItemStack itemStack, TooltipContext tooltipContext, List<Component> tooltip,
			TooltipFlag tooltipFlag) {
		ClientUtil.tooltip("item_filter", tooltip);
		if(Screen.hasControlDown()) {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.item_filter.contents"));
			SimpleItemFilterComponent c = itemStack.get(Content.simpleItemFilterComponent.get());
			boolean allow = false;
			List<Component> elems = new ArrayList<>();
			if (c != null) {
				for (ItemStack s : c.stacks()) {
					if (!s.isEmpty()) {
						elems.add(Component.translatable("tooltip.obscuras_storage.item_filter.prefix", s.getHoverName()));
					}
				}
				allow = c.allowList();
			}
			if (elems.isEmpty()) {
				tooltip.add(Component.translatable("tooltip.obscuras_storage.item_filter.no_items"));
			} else {
				tooltip.addAll(elems);
			}
			tooltip.add(Component.translatable(allow ? "tooltip.obscuras_storage.allowList" : "tooltip.obscuras_storage.denyList"));
			if (allow && elems.isEmpty()) {
				tooltip.add(Component.translatable("tooltip.obscuras_storage.empty_whitelist_warning").withStyle(ChatFormatting.RED));
			}
		} else {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.hold_control_for_details", Minecraft.ON_OSX ? "CMD" : "CTRL").withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
		}
	}

	@Override
	public ItemFilter createFilter(BlockFaceReference face, ItemStack stack) {
		// An Item Filter of the 1.20.1 builds read before registries were known still has its items in custom data
		if (face != null && face.level() != null)LegacyItemComponents.upgrade(stack, face.level().registryAccess());
		return new SimpleItemFilter(stack);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
		ItemStack is = player.getItemInHand(hand);
		openGui(is, player, () -> player.getItemInHand(hand).getItem() == this, null);
		return InteractionResultHolder.sidedSuccess(is, world.isClientSide);
	}

	@Override
	public void openGui(ItemStack is, Player player, BooleanSupplier isValid, Runnable refresh) {
		LegacyItemComponents.upgrade(is, player.level().registryAccess());
		player.openMenu(new SimpleMenuProvider((id, pi, pl) -> new ItemFilterMenu(id, pi, new SimpleItemFilter(is), isValid, refresh), is.getHoverName()));
	}
}
