package com.obscura.storage.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.util.Priority;

public class InventoryConfiguratorScreen extends AbstractFilteredScreen<InventoryConfiguratorMenu> {
	private static final ResourceLocation GUI_TEXTURES = new ResourceLocation("obscuras_storage", "textures/gui/inventory_configurator.png");
	private static ResourceLocation iconTex(String id) {
		return new ResourceLocation("obscuras_storage", "textures/gui/sprites/icons/" + id + ".png");
	}

	private GuiButton buttonPriority;
	private GuiButton buttonSide;
	private GuiButton buttonSkip;
	private GuiButton buttonKeepLast;
	private GuiButton buttonAddConnected;
	private GuiButton buttonRemoveAll;
	private GuiButton buttonRemoveFilter;

	public InventoryConfiguratorScreen(InventoryConfiguratorMenu container, Inventory playerInventory, Component textComponent) {
		super(container, playerInventory, textComponent);
	}

	@Override
	protected void init() {
		super.init();

		buttonPriority = addRenderableWidget(new GuiButton(leftPos - 18, topPos + 5, 2, b -> {
			menu.priority = Priority.VALUES[(buttonPriority.getState() + 1) % Priority.VALUES.length];
			click(menu.priority.ordinal() << 3);
		}));
		buttonPriority.iconFactory = s -> switch (s) {
		case 0 -> iconTex("priority_lowest");
		case 1 -> iconTex("priority_low");
		case 2 -> iconTex("priority_normal");
		case 3 -> iconTex("priority_high");
		case 4 -> iconTex("priority_highest");
		default -> null;
		};
		buttonPriority.tooltipFactory = s -> Tooltip.create(Component.translatable("tooltip.obscuras_storage.priority_" + s));

		buttonAddConnected = addRenderableWidget(new GuiButton(leftPos - 18, topPos + 5 + 18, 0, b -> {
			click(1);
		}));
		buttonAddConnected.iconFactory = s -> iconTex("add");
		buttonAddConnected.setTooltip(Tooltip.create(Component.translatable("tooltip.obscuras_storage.inv_config.add_connected")));

		buttonRemoveAll = addRenderableWidget(new GuiButton(leftPos - 18, topPos + 5 + 18 * 2, 0, b -> {
			click(2);
		}));
		buttonRemoveAll.iconFactory = s -> iconTex("deny");
		buttonRemoveAll.setTooltip(Tooltip.create(Component.translatable("tooltip.obscuras_storage.inv_config.remove_all_connected")));

		buttonSide = addRenderableWidget(new GuiButton(leftPos - 18, topPos + 5 + 18 * 3, 1, b -> {
			int next = (menu.side.get3DDataValue() + 1) % Direction.values().length;
			click((next << 3) | 3);
		}));
		buttonSide.iconFactory = s -> switch (Direction.from3DDataValue(s)) {
		case DOWN -> iconTex("side_down");
		case UP -> iconTex("side_up");
		case NORTH -> iconTex("side_north");
		case SOUTH -> iconTex("side_south");
		case WEST -> iconTex("side_west");
		case EAST -> iconTex("side_east");
		};
		buttonSide.tooltipFactory = s -> Tooltip.create(Component.translatable("tooltip.obscuras_storage.side_" + Direction.from3DDataValue(s).getSerializedName()));

		buttonSkip = addRenderableWidget(new GuiButton(leftPos - 18, topPos + 5 + 18 * 4, 0, b -> {
			click(((buttonSkip.getState() != 1 ? 1 : 0) << 3) | 4);
		}));
		buttonSkip.iconFactory = s -> s == 1 ? iconTex("skip_inv") : iconTex("include_inv");
		buttonSkip.tooltipFactory = s -> Tooltip.create(Component.translatable("tooltip.obscuras_storage.inv_config." + (s == 1 ? "skip" : "include")));

		buttonKeepLast = addRenderableWidget(new GuiButton(leftPos - 18, topPos + 5 + 18 * 5, 3, b -> {
			click(((buttonKeepLast.getState() != 1 ? 1 : 0) << 3) | 5);
		}));
		buttonKeepLast.iconFactory = s -> s == 1 ? iconTex("keep_last_1") : iconTex("keep_last_off");
		buttonKeepLast.tooltipFactory = s -> Tooltip.create(Component.translatable("tooltip.obscuras_storage.keepLast_" + s));

		buttonRemoveFilter = addRenderableWidget(new GuiButton(leftPos + imageWidth - 22, topPos - 22, 0, b -> {
			click(7);
		}));
		buttonRemoveFilter.iconFactory = s -> iconTex("deny");
		buttonRemoveFilter.setTooltip(Tooltip.create(Component.translatable("tooltip.obscuras_storage.inv_config.remove_filter")));
	}

	private void click(int id) {
		this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
	}

	@Override
	public void render(GuiGraphics matrixStack, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(matrixStack, mouseX, mouseY, partialTicks);
		buttonPriority.setState(menu.priority.ordinal());
		buttonSide.setState(menu.side.get3DDataValue());
		buttonSkip.setState(menu.skip ? 1 : 0);
		buttonKeepLast.setState(menu.keepLast ? 1 : 0);
		super.render(matrixStack, mouseX, mouseY, partialTicks);
		this.renderTooltip(matrixStack, mouseX, mouseY);
	}

	@Override
	protected void renderBg(GuiGraphics matrixStack, float partialTicks, int x, int y) {
		int i = (this.width - this.imageWidth) / 2;
		int j = (this.height - this.imageHeight) / 2;
		matrixStack.blit(GUI_TEXTURES, i, j, 0, 0, this.imageWidth, this.imageHeight);
	}

	@Override
	public boolean mouseClicked(double x, double y, int btn) {
		if (btn == 1) {
			Slot slot = this.hoveredSlot;
			if (slot instanceof FilterSlot && slot.getItem().getItem() instanceof IItemFilter) {
				click((slot.index << 3) | 6);
				return true;
			}
		}
		return super.mouseClicked(x, y, btn);
	}
}