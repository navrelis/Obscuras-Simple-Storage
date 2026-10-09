package com.obscura.storage.rei;

import com.obscura.storage.Content;
import com.obscura.storage.gui.AbstractStorageTerminalScreen;
import com.obscura.storage.util.IAutoFillTerminal;
import com.obscura.storage.util.IAutoFillTerminal.ISearchHandler;

import dev.architectury.event.CompoundEventResult;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.entry.filtering.base.BasicFilteringRule;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import me.shedaniel.rei.api.common.util.EntryStacks;
import me.shedaniel.rei.plugin.common.BuiltinPlugin;

public class REIPlugin implements REIClientPlugin {

	@Override
	public void registerTransferHandlers(TransferHandlerRegistry recipeHelper) {
		recipeHelper.register(new ReiTransferHandler());
	}

	@Override
	public void registerBasicEntryFiltering(BasicFilteringRule<?> rule) {
		// Legacy filtered connector is no longer obtainable, hide it
		rule.hide(EntryStacks.of(Content.invCableConnectorFiltered.get()));
	}

	public static void setReiSearchText(String text) {
		REIRuntime.getInstance().getSearchTextField().setText(text);
	}

	@Override
	public void registerCategories(CategoryRegistry registry) {
		registry.addWorkstations(BuiltinPlugin.CRAFTING, EntryStacks.of(Content.craftingTerminal.get()));
	}

	@Override
	public void registerScreens(ScreenRegistry registry) {
		registry.registerFocusedStack((scr, point) -> {
			if(scr instanceof AbstractStorageTerminalScreen<?> t) {
				net.minecraft.world.inventory.Slot sl = t.getSlotUnderMouse();
				if(sl != null)return CompoundEventResult.interruptTrue(EntryStack.of(VanillaEntryTypes.ITEM, sl.getItem()));
			}
			return CompoundEventResult.pass();
		});
		registry.registerDraggableStackVisitor(new ReiGhostIngredientHandler());
	}

	static {
		IAutoFillTerminal.updateSearch.add(new ISearchHandler() {

			@Override
			public void setSearch(String text) {
				REIRuntime.getInstance().getSearchTextField().setText(text);
			}

			@Override
			public String getSearch() {
				return REIRuntime.getInstance().getSearchTextField().getText();
			}

			@Override
			public String getName() {
				return "REI";
			}
		});
	}
}
