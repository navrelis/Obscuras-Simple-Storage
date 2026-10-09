package com.obscura.storage.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
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
import com.obscura.storage.components.TagFilterComponent;
import com.obscura.storage.inventory.filter.ItemFilter;
import com.obscura.storage.inventory.filter.TagFilter;
import com.obscura.storage.menu.TagItemFilterMenu;
import com.obscura.storage.migration.LegacyItemComponents;
import com.obscura.storage.util.BlockFaceReference;

public class TagFilterItem extends Item implements IItemFilter {

	/** Settings saved by the 1.20.1 builds (custom data) move to their components, see LegacyItemComponents. */
	@Override
	public void verifyComponentsAfterLoad(ItemStack stack) {
		super.verifyComponentsAfterLoad(stack);
		LegacyItemComponents.onLoad(stack);
	}

	public TagFilterItem() {
		super(new Properties().stacksTo(1));
	}

	@Override
	public void appendHoverText(ItemStack itemStack, TooltipContext tooltipContext, List<Component> tooltip,
			TooltipFlag tooltipFlag) {
		ClientUtil.tooltip("tag_item_filter", tooltip);
		if(Screen.hasControlDown()) {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.item_filter.contents"));
			TagFilterComponent c = itemStack.get(Content.tagFilterComponent.get());
			boolean allow = false;
			List<Component> elems = new ArrayList<>();
			if (c != null) {
				for (TagKey<Item> s : c.tags()) {
					elems.add(Component.translatable("tooltip.obscuras_storage.item_filter.prefix", s.location().toString()));
				}
				allow = c.allowList();
			}
			if (elems.isEmpty()) {
				tooltip.add(Component.translatable("tooltip.obscuras_storage.item_filter.no_items"));
			} else {
				tooltip.addAll(elems);
			}
			tooltip.add(Component.translatable(allow ? "tooltip.obscuras_storage.allowList" : "tooltip.obscuras_storage.denyList"));
		} else {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.hold_control_for_details", Minecraft.ON_OSX ? "CMD" : "CTRL").withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
		}
	}

	@Override
	public ItemFilter createFilter(BlockFaceReference face, ItemStack stack) {
		return new TagFilter(stack);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
		ItemStack is = player.getItemInHand(hand);
		openGui(is, player, () -> player.getItemInHand(hand).getItem() == this, null);
		return InteractionResultHolder.sidedSuccess(is, world.isClientSide);
	}

	@Override
	public void openGui(ItemStack is, Player player, BooleanSupplier isValid, Runnable refresh) {
		player.openMenu(new SimpleMenuProvider((id, pi, pl) -> new TagItemFilterMenu(id, pi, new TagFilter(is), isValid, refresh), is.getHoverName()));
	}
}
