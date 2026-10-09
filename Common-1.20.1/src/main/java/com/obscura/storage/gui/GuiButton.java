package com.obscura.storage.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;

public class GuiButton extends ButtonExt {
	public static final ResourceLocation BUTTON_TEXTURES = new ResourceLocation("obscuras_storage", "textures/gui/filter_buttons.png");
	private static final ResourceLocation SLOT_BUTTON = widgetTex("slot_button");
	private static final ResourceLocation SLOT_BUTTON_HOVERED = widgetTex("slot_button_hovered");
	private static final ResourceLocation SLOT_BUTTON_DISABLED = widgetTex("slot_button_disabled");
	private static final ResourceLocation SMALL_BUTTON = widgetTex("small_button");
	private static final ResourceLocation SMALL_BUTTON_HOVERED = widgetTex("small_button_hovered");
	private static final ResourceLocation SMALL_BUTTON_DISABLED = widgetTex("small_button_disabled");
	private static final ResourceLocation SMALL_BUTTON_SELECTED = widgetTex("small_button_selected");
	private static final ResourceLocation SMALL_BUTTON_SELECTED_HOVERED = widgetTex("small_button_selected_hovered");

	public ResourceLocation texture;
	public int tile;
	private int state;
	public int texX = 0;
	public int texY = 0;
	public Int2ObjectFunction<Tooltip> tooltipFactory;
	public Int2ObjectFunction<ResourceLocation> iconFactory;
	public boolean drawBackground = true;

	public GuiButton(int x, int y, int tile, OnPress pressable) {
		super(x, y, 16, 16, Component.empty(), pressable);
		this.tile = tile;
		this.texture = BUTTON_TEXTURES;
	}

	/**
	 * Draws this button to the screen.
	 */
	@Override
	public void renderWidget(GuiGraphics st, int mouseX, int mouseY, float pt) {
		if (this.visible) {
			int x = getX();
			int y = getY();
			this.isHovered = mouseX >= x && mouseY >= y && mouseX < x + this.width && mouseY < y + this.height;
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
			if (iconFactory != null) {
				if (drawBackground) {
					st.blit(getSlotBg(this.active, this.isHoveredOrFocused()), x, y, 0, 0, this.width, this.height, this.width, this.height);
				}
				ResourceLocation icon = iconFactory.get(state);
				if (icon != null) {
					drawIcon(st, icon, x, y);
				}
			} else {
				st.blit(texture, x, y, texX + state * 16, texY + tile * 16, this.width, this.height);
			}
		}
	}

	/** Draws the icon of the current state on top of the background; buttons with extra drawing (Tidy) override it. */
	protected void drawIcon(GuiGraphics st, ResourceLocation icon, int x, int y) {
		st.blit(icon, x, y, 0, 0, this.width, this.height, this.width, this.height);
	}

	private static ResourceLocation getSlotBg(boolean active, boolean hovered) {
		if (!active)return SLOT_BUTTON_DISABLED;
		return hovered ? SLOT_BUTTON_HOVERED : SLOT_BUTTON;
	}

	private static ResourceLocation widgetTex(String id) {
		return new ResourceLocation("obscuras_storage", "textures/gui/sprites/widget/" + id + ".png");
	}

	private static ResourceLocation getSmallBg(boolean active, boolean hovered, boolean selected) {
		if (!active)return SMALL_BUTTON_DISABLED;
		if (selected)return hovered ? SMALL_BUTTON_SELECTED_HOVERED : SMALL_BUTTON_SELECTED;
		return hovered ? SMALL_BUTTON_HOVERED : SMALL_BUTTON;
	}

	public static class CompositeButton extends GuiButton {
		public int texY_button = 16;
		public CompositeButton(int x, int y, int tile, OnPress pressable) {
			super(x, y, tile, pressable);
		}

		/**
		 * Draws this button to the screen.
		 */
		@Override
		public void renderWidget(GuiGraphics st, int mouseX, int mouseY, float pt) {
			if (this.visible) {
				int x = getX();
				int y = getY();
				this.isHovered = mouseX >= x && mouseY >= y && mouseX < x + this.width && mouseY < y + this.height;
				RenderSystem.enableBlend();
				RenderSystem.defaultBlendFunc();
				RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
				if (iconFactory != null) {
					if (drawBackground) {
						st.blit(getSlotBg(this.active, this.isHoveredOrFocused()), x, y, 0, 0, this.width, this.height, this.width, this.height);
					}
					ResourceLocation icon = iconFactory.get(getState());
					if (icon != null) {
						st.blit(icon, x, y, 0, 0, this.width, this.height, this.width, this.height);
					}
				} else {
					st.setColor(1.0f, 1.0f, 1.0f, this.alpha);
					int i = this.getYImage(this.isHoveredOrFocused());
					st.blit(texture, x, y, texX + i * 16, this.texY_button, this.width, this.height);
					st.blit(texture, x, y, texX + tile * 16 + getState() * 16, texY, this.width, this.height);
					st.setColor(1.0f, 1.0f, 1.0f, 1.0f);
				}
			}
		}
	}

	public void setState(int state) {
		this.state = state;
		if(tooltipFactory != null)setTooltip(tooltipFactory.apply(state));
	}

	public int getState() {
		return state;
	}
}