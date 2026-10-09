package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

import com.obscura.storage.platform.Platform;
import com.obscura.storage.tile.CraftingTerminalBlockEntity;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

public class TerminalCraftingFiller {
	private static final int GRID_SIZE = 3;
	private CraftingTerminalBlockEntity te;
	private Player player;
	private Int2ObjectMap<List<ItemStack>> allItems = new Int2ObjectOpenHashMap<>();
	private TerminalSyncManager sync;

	public TerminalCraftingFiller(CraftingTerminalBlockEntity te, Player player, TerminalSyncManager sync) {
		this.te = te;
		this.player = player;
		this.sync = sync;
	}

	public void placeRecipe(Recipe<?> recipe) {
		// Validate everything before touching any items: nothing may be removed if the recipe cannot be placed.
		if (recipe == null || recipe.getType() != RecipeType.CRAFTING || !(recipe instanceof CraftingRecipe))return;
		if (!recipe.canCraftInDimensions(GRID_SIZE, GRID_SIZE))return;
		var ings = recipe.getIngredients();
		if (ings.isEmpty() || ings.size() > GRID_SIZE * GRID_SIZE)return;
		int rw = Platform.getRecipeWidth(recipe);
		if (rw == -1) {
			if (recipe instanceof ShapedRecipe sr) {
				rw = sr.getWidth();
			} else {
				int cnt = ings.size();
				if (cnt == 1) {
					rw = 1;
				} else if (cnt <= 4) {
					rw = 2;
				} else {
					rw = 3;
				}
			}
		}
		if (rw < 1 || rw > GRID_SIZE)return;
		for (int i = 0; i < ings.size(); i++) {
			if (ings.get(i).isEmpty())continue;
			if (i % rw >= GRID_SIZE || i / rw >= GRID_SIZE)return;
		}

		te.clear(player);
		sync.fillCraftingFiller(this);
		for (var i : player.getInventory().items) {
			accountStack(i);
		}
		for (int i = 0; i < ings.size(); i++) {
			Ingredient ingr = ings.get(i);
			if (ingr.isEmpty())continue;
			int x = i % rw;
			int y = i / rw;
			boolean filled = false;
			for (int v : ingr.getStackingIds()) {
				var lst = allItems.get(v);
				if (lst != null) {
					for (var item : lst) {
						if (ingr.test(item)) {
							var pull = te.pullStack(new StoredItemStack(item), 1);
							if (pull != null) {
								filled = true;
								te.setCraftSlot(x, y, pull.getActualStack());
								break;
							} else {
								int id = player.getInventory().findSlotMatchingItem(item);
								if (id != -1) {
									te.setCraftSlot(x, y, player.getInventory().removeItem(id, 1));
									filled = true;
									break;
								}
							}
						}
					}
					if (filled)break;
				}
			}
		}
	}

	public void accountStack(ItemStack st) {
		if (st.hasCustomHoverName())return;
		int index = StackedContents.getStackingIndex(st);
		allItems.computeIfAbsent(index, __ -> new ArrayList<>()).add(st);
	}
}
