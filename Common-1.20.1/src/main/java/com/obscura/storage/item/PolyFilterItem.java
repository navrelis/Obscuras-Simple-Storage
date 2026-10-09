package com.obscura.storage.item;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.StorageModClient;
import com.obscura.storage.util.BlockFace;
import com.obscura.storage.util.ItemPredicate;
import com.obscura.storage.util.PolyFilter;

public class PolyFilterItem extends Item implements IItemFilter {
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


	public PolyFilterItem() {
		super(new Properties().stacksTo(1));
	}

	@Override
	public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag tooltipFlag) {
		StorageModClient.tooltip("poly_item_filter", tooltip);
	}

	@Override
	public ItemPredicate createFilter(BlockFace face, ItemStack stack) {
		return new PolyFilter(face);
	}

}
