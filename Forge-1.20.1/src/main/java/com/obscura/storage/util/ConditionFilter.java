package com.obscura.storage.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;

/**
 * Matches items by traits (damaged, enchanted, food, ...) instead of by item list.
 * <p>
 * test() only reads the (immutable) state, the stack and the immutable fuel and smeltable sets
 * published by {@link ConditionSnapshots}. Those sets are built on the server thread in the
 * constructor, which runs where the filter is created.
 */
public class ConditionFilter implements ItemPredicate {
	private static final int FUEL_BIT = FilterCondition.FUEL.bit();
	private static final int SMELTABLE_BIT = FilterCondition.SMELTABLE.bit();

	private final ItemStack stack;
	private final ConditionFilterState conditions;
	private final int active;

	public ConditionFilter(BlockFace face, ItemStack stack) {
		this.stack = stack;
		this.conditions = ConditionFilterState.read(stack);
		this.active = conditions.activeMask();
		if ((active & FUEL_BIT) != 0)ConditionSnapshots.requireFuel();
		if ((active & SMELTABLE_BIT) != 0)ConditionSnapshots.requireSmeltable(face != null ? face.level() : null);
	}

	@Override
	public boolean configMatch(ItemStack stack) {
		return stack == this.stack;
	}

	@Override
	public boolean test(ItemStack stack) {
		if (conditions.isEmpty())return false;
		return conditions.matches(traitsOf(stack, active));
	}

	/**
	 * Computes the trait bits of a stack, but only for the conditions in {@code wanted}. Only reads
	 * the stack and the published immutable sets, so it is safe on any thread.
	 */
	public static int traitsOf(ItemStack stack, int wanted) {
		int t = 0;
		if ((wanted & FilterCondition.DAMAGED.bit()) != 0 && stack.isDamaged())t |= FilterCondition.DAMAGED.bit();
		if ((wanted & FilterCondition.ENCHANTED.bit()) != 0 && isEnchanted(stack))t |= FilterCondition.ENCHANTED.bit();
		if ((wanted & FilterCondition.RENAMED.bit()) != 0 && stack.hasCustomHoverName())t |= FilterCondition.RENAMED.bit();
		if ((wanted & FilterCondition.STACKABLE.bit()) != 0 && stack.getMaxStackSize() > 1)t |= FilterCondition.STACKABLE.bit();
		if ((wanted & FilterCondition.FOOD.bit()) != 0 && stack.getItem().isEdible())t |= FilterCondition.FOOD.bit();
		if ((wanted & FUEL_BIT) != 0 && ConditionSnapshots.fuel().contains(stack.getItem()))t |= FUEL_BIT;
		if ((wanted & SMELTABLE_BIT) != 0 && ConditionSnapshots.smeltable().contains(stack.getItem()))t |= SMELTABLE_BIT;
		if ((wanted & FilterCondition.BLOCK.bit()) != 0 && stack.getItem() instanceof BlockItem)t |= FilterCondition.BLOCK.bit();
		if ((wanted & FilterCondition.HAS_CONTENTS.bit()) != 0 && hasContents(stack))t |= FilterCondition.HAS_CONTENTS.bit();
		return t;
	}

	private static boolean isEnchanted(ItemStack stack) {
		return stack.isEnchanted() || !EnchantedBookItem.getEnchantments(stack).isEmpty();
	}

	/** Shulker boxes and other block entity containers (BlockEntityTag Items) and bundles (Items). */
	private static boolean hasContents(ItemStack stack) {
		CompoundTag be = stack.getTagElement("BlockEntityTag");
		if (be != null && !be.getList("Items", 10).isEmpty())return true;
		if (stack.getItem() instanceof BundleItem) {
			CompoundTag tag = stack.getTag();
			return tag != null && !tag.getList("Items", 10).isEmpty();
		}
		return false;
	}
}
