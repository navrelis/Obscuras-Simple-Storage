package com.obscura.storage.inventory.filter;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;

import com.obscura.storage.Content;
import com.obscura.storage.components.ConditionFilterComponent;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.util.BlockFaceReference;
import com.obscura.storage.util.FilterCondition;

/**
 * Matches items by traits (damaged, enchanted, food, ...) instead of by item list.
 * <p>
 * test() runs concurrently from the off-thread tracker processing. It only reads the (immutable)
 * masks, the stack's components and two immutable item sets. The fuel and smeltable sets are built
 * on the server thread (constructor/updateState) and published through volatile fields.
 */
public class ConditionFilter implements ItemFilter {
	private static final int FUEL_BIT = FilterCondition.FUEL.bit();
	private static final int SMELTABLE_BIT = FilterCondition.SMELTABLE.bit();

	private final ItemStack stack;
	private final BlockFaceReference ref;
	private final ConditionFilterComponent conditions;
	private volatile Set<Item> fuelItems = Set.of();
	private volatile Set<Item> smeltableItems = Set.of();
	private volatile long stateVersion = ItemPredicate.nextStateVersion();

	public ConditionFilter(BlockFaceReference ref, ItemStack stack) {
		this.stack = stack;
		this.ref = ref;
		ConditionFilterComponent c = stack.get(Content.conditionFilterComponent.get());
		this.conditions = c != null ? c : ConditionFilterComponent.EMPTY;
		updateState();
	}

	@Override
	public boolean configMatch(ItemStack stack) {
		return stack == this.stack;
	}

	@Override
	public void updateState() {
		int active = conditions.activeMask();
		Set<Item> fuel = fuelItems, smeltable = smeltableItems;
		if ((active & FUEL_BIT) != 0)fuel = FuelSnapshot.get();
		if ((active & SMELTABLE_BIT) != 0 && ref != null)smeltable = SmeltableSnapshot.get(ref.level());
		// The snapshots hand out the same set instance until a reload changes them.
		if (fuel != fuelItems || smeltable != smeltableItems) {
			fuelItems = fuel;
			smeltableItems = smeltable;
			stateVersion = ItemPredicate.nextStateVersion();
		}
	}

	@Override
	public long stateVersion() {
		return stateVersion;
	}

	@Override
	public boolean test(StoredItemStack stack) {
		return test(stack.getStack());
	}

	@Override
	public boolean test(ItemStack stack) {
		if (conditions.isEmpty())return false;
		return conditions.matches(traitsOf(stack, conditions.activeMask(), fuelItems, smeltableItems));
	}

	/**
	 * Computes the trait bits of a stack, but only for the conditions in {@code wanted}.
	 * Only reads the stack's components, so it is safe on any thread.
	 */
	public static int traitsOf(ItemStack stack, int wanted, Set<Item> fuel, Set<Item> smeltable) {
		int t = 0;
		if ((wanted & FilterCondition.DAMAGED.bit()) != 0 && stack.isDamaged())t |= FilterCondition.DAMAGED.bit();
		if ((wanted & FilterCondition.ENCHANTED.bit()) != 0 && isEnchanted(stack))t |= FilterCondition.ENCHANTED.bit();
		if ((wanted & FilterCondition.RENAMED.bit()) != 0 && stack.has(DataComponents.CUSTOM_NAME))t |= FilterCondition.RENAMED.bit();
		if ((wanted & FilterCondition.STACKABLE.bit()) != 0 && stack.getMaxStackSize() > 1)t |= FilterCondition.STACKABLE.bit();
		if ((wanted & FilterCondition.FOOD.bit()) != 0 && stack.has(DataComponents.FOOD))t |= FilterCondition.FOOD.bit();
		if ((wanted & FUEL_BIT) != 0 && fuel.contains(stack.getItem()))t |= FUEL_BIT;
		if ((wanted & SMELTABLE_BIT) != 0 && smeltable.contains(stack.getItem()))t |= SMELTABLE_BIT;
		if ((wanted & FilterCondition.BLOCK.bit()) != 0 && stack.getItem() instanceof BlockItem)t |= FilterCondition.BLOCK.bit();
		if ((wanted & FilterCondition.HAS_CONTENTS.bit()) != 0 && hasContents(stack))t |= FilterCondition.HAS_CONTENTS.bit();
		return t;
	}

	private static boolean isEnchanted(ItemStack stack) {
		return !stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty() ||
				!stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty();
	}

	private static boolean hasContents(ItemStack stack) {
		ItemContainerContents c = stack.get(DataComponents.CONTAINER);
		if (c != null && c.nonEmptyStream().findAny().isPresent())return true;
		BundleContents b = stack.get(DataComponents.BUNDLE_CONTENTS);
		return b != null && !b.isEmpty();
	}

	/**
	 * Set of items usable as furnace fuel, asked from the platform the way a real furnace decides it
	 * (NeoForge: the furnace_fuels data map and item overrides, Fabric: the vanilla fuel map its
	 * FuelRegistry feeds). Rebuilt only when {@link Platform#fuelSource()} hands out a different
	 * object, which happens after a reload. Only called on the server thread.
	 */
	private static final class FuelSnapshot {
		private record Entry(Object source, Set<Item> items) {}
		private static volatile Entry cache;

		static Set<Item> get() {
			Object source = Platform.fuelSource();
			Entry e = cache;
			if (e != null && e.source == source)return e.items;
			Set<Item> set = new HashSet<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (Platform.isFuel(new ItemStack(item)))set.add(item);
			}
			Set<Item> items = Set.copyOf(set);
			cache = new Entry(source, items);
			return items;
		}
	}

	/**
	 * Set of items that are the input of a smelting recipe. Rebuilt only when the RecipeManager
	 * instance changes (a new one is created on every /reload). Only called on the server thread.
	 */
	private static final class SmeltableSnapshot {
		private record Entry(WeakReference<RecipeManager> manager, Set<Item> items) {}
		private static volatile Entry cache;

		static Set<Item> get(Level level) {
			if (level == null)return Set.of();
			RecipeManager rm = level.getRecipeManager();
			Entry e = cache;
			if (e != null && e.manager.get() == rm)return e.items;
			Set<Item> set = new HashSet<>();
			for (RecipeHolder<SmeltingRecipe> r : rm.getAllRecipesFor(RecipeType.SMELTING)) {
				for (Ingredient ing : r.value().getIngredients()) {
					for (ItemStack is : ing.getItems()) {
						set.add(is.getItem());
					}
				}
			}
			Set<Item> items = Set.copyOf(set);
			cache = new Entry(new WeakReference<>(rm), items);
			return items;
		}
	}
}
