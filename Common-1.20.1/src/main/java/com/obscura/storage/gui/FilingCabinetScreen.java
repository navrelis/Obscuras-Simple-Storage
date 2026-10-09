package com.obscura.storage.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

import com.mojang.blaze3d.platform.InputConstants;

import com.obscura.storage.platform.PlatformContainerScreen;

public class FilingCabinetScreen extends PlatformContainerScreen<FilingCabinetMenu> {
	private static final ResourceLocation CONTAINER_BACKGROUND = new ResourceLocation("textures/gui/container/generic_54.png");
	private final int containerRows;
	private float currentScroll;
	private int lastScroll;
	private boolean isScrolling;
	private boolean wasClicking;

	public FilingCabinetScreen(FilingCabinetMenu menu, Inventory playerInv, Component title) {
		super(menu, playerInv, title);
		this.containerRows = 5;
		this.imageHeight = 114 + this.containerRows * 18;
		this.inventoryLabelY = this.imageHeight - 94;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		boolean clicking = InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), 0);
		int left = this.leftPos + 174;
		int top = this.topPos + 18;
		int right = left + 14;
		int bottom = top + containerRows * 18;

		if (!this.wasClicking && clicking && mouseX >= left && mouseY >= top && mouseX < right && mouseY < bottom) {
			this.isScrolling = true;
		}
		if (!clicking) this.isScrolling = false;
		this.wasClicking = clicking;

		if (this.isScrolling) {
			this.currentScroll = (mouseY - top - 7.5F) / (bottom - top - 15.0F);
			this.currentScroll = Mth.clamp(this.currentScroll, 0.0F, 1.0F);
		}

		this.renderBackground(graphics, mouseX, mouseY, partialTicks);
		super.render(graphics, mouseX, mouseY, partialTicks);
		drawScroll(graphics, left, top + (int) ((bottom - top - 15) * this.currentScroll), true);
		this.renderTooltip(graphics, mouseX, mouseY);
	}

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		int x = (this.width - this.imageWidth) / 2;
		int y = (this.height - this.imageHeight) / 2;
		graphics.blit(CONTAINER_BACKGROUND, x, y, 0, 0, this.imageWidth, this.containerRows * 18 + 17);
		graphics.blit(CONTAINER_BACKGROUND, x, y + this.containerRows * 18 + 17, 0, 126, this.imageWidth, 96);
	}

	@Override
	protected void containerTick() {
		int maxRows = (this.menu.getContainerSize() + 9 - 1) / 9 - containerRows;
		if (maxRows <= 0) {
			currentScroll = 0;
			if (lastScroll != 0) {
				this.menu.setRow(0);
				this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 0);
				this.lastScroll = 0;
			}
			return;
		}
		int scroll = (int) (currentScroll * maxRows + 0.5D);
		if (lastScroll != scroll) {
			this.menu.setRow(scroll);
			this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, scroll);
			this.lastScroll = scroll;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		int maxRows = (this.menu.getContainerSize() + 9 - 1) / 9 - containerRows;
		if (maxRows <= 0) return true;
		this.currentScroll = (float) (this.currentScroll - scrollY / maxRows);
		this.currentScroll = Mth.clamp(this.currentScroll, 0.0F, 1.0F);
		return true;
	}
}
