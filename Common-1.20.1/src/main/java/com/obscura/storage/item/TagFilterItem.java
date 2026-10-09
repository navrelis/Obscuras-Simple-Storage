package com.obscura.storage.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import com.obscura.storage.StorageModClient;
import com.obscura.storage.gui.TagItemFilterMenu;
import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.util.BlockFace;
import com.obscura.storage.util.ItemPredicate;
import com.obscura.storage.util.TagFilter;

public class TagFilterItem extends Item implements IItemFilter {
	/**
	 * Stacks saved before v5.0.0 keep ids of this mod in the old namespace in their NBT (painted block
	 * states, tag names, the stacks inside a filter); they are rewritten when the stack is loaded, see
	 * LegacyIds#migrateStrings.
	 */
	@Override
	public void verifyTagAfterLoad(CompoundTag tag) {
		super.verifyTagAfterLoad(tag);
		LegacyIds.migrateStrings(tag);
	}


	public TagFilterItem() {
		super(new Properties().stacksTo(1));
	}

	@Override
	public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag tooltipFlag) {
		StorageModClient.tooltip("tag_item_filter", tooltip);
		List<Component> entries = new ArrayList<>();
		CompoundTag tag = stack.getTag();
		boolean allow = false;
		if (tag != null) {
			ListTag list = tag.getList("tags", Tag.TAG_STRING);
			for (int i = 0; i < list.size(); i++) {
				entries.add(Component.literal(LegacyIds.toCurrent(list.getString(i))));
			}
			allow = tag.getBoolean("allowlist");
		}
		StorageModClient.filterContentsTooltip(tooltip, entries, allow, false);
	}

	@Override
	public ItemPredicate createFilter(BlockFace face, ItemStack stack) {
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
