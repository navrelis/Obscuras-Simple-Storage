package com.obscura.storage.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import com.obscura.storage.util.IAutoFillTerminal;
import com.obscura.storage.util.PopupMenuManager;
import com.obscura.storage.util.PopupMenuManager.ButtonElement;
import com.obscura.storage.util.SearchModes;

/**
 * Search options of a terminal. A click opens a popup with one toggle per option (auto focus, keep, sync, smart item search).
 */
public class TerminalSearchModeButton extends ButtonExt {
	private static final ResourceLocation SLOT_BUTTON = widgetTex("slot_button");
	private static final ResourceLocation SLOT_BUTTON_HOVERED = widgetTex("slot_button_hovered");
	private static final ResourceLocation SLOT_BUTTON_DISABLED = widgetTex("slot_button_disabled");
	private static final ResourceLocation ICON = iconTex("search_mode");
	private static final ResourceLocation AUTO = iconTex("search_mode_auto");
	private static final ResourceLocation KEEP = iconTex("search_mode_keep");
	private static final ResourceLocation SYNC = iconTex("search_mode_sync");
	private int searchType = -1;

	public TerminalSearchModeButton(int x, int y, PopupMenuManager menu, boolean canSync, Runnable sendUpdate) {
		super(x, y, 16, 16, Component.empty(), handleClick(menu, canSync, sendUpdate));
	}

	private static OnPress handleClick(PopupMenuManager menu, boolean canSync, Runnable sendUpdate) {
		return b0 -> {
			TerminalSearchModeButton b = (TerminalSearchModeButton) b0;
			String sh = IAutoFillTerminal.getHandlerNameOr(I18n.get("tooltip.obscuras_storage.recipe_book"));
			menu.open(b.getX() + 16, b.getY() + 16,
					new ButtonElement(
							() -> Component.translatable("tooltip.obscuras_storage.opt.search_auto", yesNo((b.searchType & SearchModes.AUTO) != 0)),
							() -> {
								b.flipBit(SearchModes.AUTO);
								sendUpdate.run();
							}),
					new ButtonElement(
							() -> Component.translatable("tooltip.obscuras_storage.opt.search_keep", yesNo((b.searchType & SearchModes.KEEP) != 0)),
							() -> {
								b.flipBit(SearchModes.KEEP);
								sendUpdate.run();
							}),
					new ButtonElement(
							() -> {
								return canSync ?
										Component.translatable("tooltip.obscuras_storage.opt.search_sync", sh, yesNo((b.searchType & SearchModes.SYNC) != 0)) :
											Component.literal("").append(Component.translatable("tooltip.obscuras_storage.opt.search_sync", sh, CommonComponents.GUI_NO).withStyle(ChatFormatting.DARK_GRAY));
							},
							() -> {
								b.flipBit(SearchModes.SYNC);
								sendUpdate.run();
							}),
					new ButtonElement(
							() -> {
								return canSync ?
										Component.translatable("tooltip.obscuras_storage.opt.search_smart", sh, yesNo(SearchModes.isSmartSearch(b.searchType))) :
											Component.literal("").append(Component.translatable("tooltip.obscuras_storage.opt.search_smart", sh, CommonComponents.GUI_NO).withStyle(ChatFormatting.DARK_GRAY));
							},
							() -> {
								b.flipBit(SearchModes.NO_SMART);
								sendUpdate.run();
							})
					);
		};
	}

	private static Component yesNo(boolean v) {
		return v ? CommonComponents.GUI_YES : CommonComponents.GUI_NO;
	}

	private void flipBit(int i) {
		setSearchType(searchType ^ i);
	}

	@Override
	public void renderWidget(GuiGraphics st, int mouseX, int mouseY, float pt) {
		if (this.visible) {
			int x = getX();
			int y = getY();
			this.isHovered = mouseX >= x && mouseY >= y && mouseX < x + this.width && mouseY < y + this.height;
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
			ResourceLocation bg = this.active ? (this.isHoveredOrFocused() ? SLOT_BUTTON_HOVERED : SLOT_BUTTON) : SLOT_BUTTON_DISABLED;
			st.blit(bg, x, y, 0, 0, this.width, this.height, this.width, this.height);
			int s = Math.max(searchType, 0);
			blitIcon(st, ICON, x, y);
			if((s & SearchModes.AUTO) != 0)blitIcon(st, AUTO, x, y);
			if((s & SearchModes.KEEP) != 0)blitIcon(st, KEEP, x, y);
			if((s & SearchModes.SYNC) != 0)blitIcon(st, SYNC, x, y);
		}
	}

	private void blitIcon(GuiGraphics st, ResourceLocation icon, int x, int y) {
		st.blit(icon, x + 1, y + 1, 0, 0, this.width - 2, this.height - 2, this.width - 2, this.height - 2);
	}

	public void setSearchType(int searchType) {
		if (this.searchType != searchType) {
			setTooltip(Tooltip.create(Component.translatable(SearchModes.tooltipKey(searchType), IAutoFillTerminal.getHandlerNameOr(I18n.get("tooltip.obscuras_storage.recipe_book")))));
		}
		this.searchType = searchType;
	}

	public int getSearchType() {
		return searchType;
	}

	private static ResourceLocation iconTex(String id) {
		return new ResourceLocation("obscuras_storage", "textures/gui/sprites/icons/" + id + ".png");
	}

	private static ResourceLocation widgetTex(String id) {
		return new ResourceLocation("obscuras_storage", "textures/gui/sprites/widget/" + id + ".png");
	}
}
