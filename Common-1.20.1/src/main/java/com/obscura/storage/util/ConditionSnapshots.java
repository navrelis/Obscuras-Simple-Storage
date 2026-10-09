package com.obscura.storage.util;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import com.obscura.storage.platform.Platform;

/**
 * Immutable item sets for the Fuel and Smeltable conditions of the Condition Filter.
 * <p>
 * In 1.20.1 {@link AbstractFurnaceBlockEntity#getFuel()} builds a new map on every call and the
 * smelting recipes have to be scanned, so neither can be asked per tested item. The sets are built
 * on the server thread, only once a filter that uses the condition exists, and are published through
 * volatile fields. The filter's test() only reads the published immutable sets.
 * <p>
 * After a datapack reload ({@link #reload}) the sets are dropped and, if a filter was using them,
 * rebuilt right away, so filters that were created before the reload keep matching correctly.
 * When the server stops everything is cleared, the next world may use other datapacks.
 */
public final class ConditionSnapshots {
	private record SmeltEntry(WeakReference<RecipeManager> manager, Set<Item> items) {}

	private static volatile Set<Item> fuel = Set.of();
	private static volatile boolean fuelBuilt;
	private static volatile boolean fuelUsed;
	private static volatile SmeltEntry smelt;
	private static volatile boolean smeltUsed;
	/** Datapack reloads seen so far, see {@link #reloads()}. */
	private static volatile long reloads;

	private ConditionSnapshots() {
	}

	/** Currently published fuel set, never null. Safe on any thread. */
	public static Set<Item> fuel() {
		return fuel;
	}

	/** Currently published smeltable set, never null. Safe on any thread. */
	public static Set<Item> smeltable() {
		SmeltEntry e = smelt;
		return e != null ? e.items : Set.of();
	}

	/** Marks the fuel set as needed and builds it if missing. Server thread only. */
	public static void requireFuel() {
		fuelUsed = true;
		if (fuelBuilt)return;
		// Fabric: the furnace fuel map (vanilla, tag and FuelRegistry entries). Forge: every item with a burn time.
		fuel = Set.copyOf(Platform.getFuelItems());
		fuelBuilt = true;
	}

	/** Marks the smeltable set as needed and builds it if the recipe manager changed. Server thread only. */
	public static void requireSmeltable(Level level) {
		smeltUsed = true;
		if (level == null)return;
		buildSmeltable(level.getRecipeManager());
	}

	private static void buildSmeltable(RecipeManager rm) {
		SmeltEntry e = smelt;
		if (e != null && e.manager.get() == rm)return;
		Set<Item> set = new HashSet<>();
		for (SmeltingRecipe r : rm.getAllRecipesFor(RecipeType.SMELTING)) {
			for (Ingredient ing : r.getIngredients()) {
				for (ItemStack is : ing.getItems()) {
					set.add(is.getItem());
				}
			}
		}
		smelt = new SmeltEntry(new WeakReference<>(rm), Set.copyOf(set));
	}

	/** Datapack reload (server thread): drop the old sets and rebuild the ones that are in use. */
	public static void reload(MinecraftServer server) {
		// Written on the server thread only
		reloads = reloads + 1;
		fuel = Set.of();
		fuelBuilt = false;
		smelt = null;
		if (fuelUsed)requireFuel();
		if (smeltUsed)buildSmeltable(server.getRecipeManager());
	}

	/**
	 * Changes with every datapack reload. For whoever remembers what a filter answered: tags, fuels and
	 * recipes may say something else about the same item afterwards.
	 */
	public static long reloads() {
		return reloads;
	}

	/** Server stopped: forget everything. */
	public static void clear() {
		fuel = Set.of();
		fuelBuilt = false;
		fuelUsed = false;
		smelt = null;
		smeltUsed = false;
	}
}
