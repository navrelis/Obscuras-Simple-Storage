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

import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.StorageModClient;
import com.obscura.storage.gui.ItemFilterMenu;
import com.obscura.storage.util.BlockFace;
import com.obscura.storage.util.ItemPredicate;
import com.obscura.storage.util.SimpleItemFilter;

public class FilterItem extends Item implements IItemFilter {
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


	public FilterItem() {
		super(new Properties().stacksTo(1));
	}

	@Override
	public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag tooltipFlag) {
		StorageModClient.tooltip("item_filter", tooltip);
		List<Component> entries = new ArrayList<>();
		CompoundTag tag = stack.getTag();
		boolean allow = false;
		if (tag != null) {
			ListTag list = tag.getList("Filter", Tag.TAG_COMPOUND);
			for (int i = 0; i < list.size(); i++) {
				ItemStack s = ItemStack.of(list.getCompound(i));
				if (!s.isEmpty())entries.add(s.getHoverName());
			}
			allow = tag.getBoolean("allowlist");
		}
		StorageModClient.filterContentsTooltip(tooltip, entries, allow, true);
	}

	@Override
	public ItemPredicate createFilter(BlockFace face, ItemStack stack) {
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
		player.openMenu(new SimpleMenuProvider((id, pi, pl) -> new ItemFilterMenu(id, pi, new SimpleItemFilter(is), isValid, refresh), is.getHoverName()));
	}
}
