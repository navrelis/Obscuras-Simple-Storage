package com.obscura.storage.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.obscura.storage.components.ConditionFilterComponent;
import com.obscura.storage.menu.ConditionFilterMenu;
import com.obscura.storage.util.FilterCondition;

public class ConditionFilterScreen extends AbstractFilteredScreen<ConditionFilterMenu> {
	/** Vanilla dispenser background: a 3x3 slot grid at (61, 16) above the player inventory. */
	private static final ResourceLocation GUI_TEXTURES = ResourceLocation.withDefaultNamespace("textures/gui/container/dispenser.png");
	private static final int GRID_X = 61, GRID_Y = 16, CELL = 18;
	private static final int COLOR_REQUIRED = 0xFF3FBF3F, COLOR_EXCLUDED = 0xFFCF3F3F;

	private static List<ItemStack> icons;
	private final List<ConditionButton> buttons = new ArrayList<>();

	public ConditionFilterScreen(ConditionFilterMenu container, Inventory playerInventory, Component textComponent) {
		super(container, playerInventory, textComponent);
	}

	private static ItemStack icon(FilterCondition c) {
		if (icons == null) {
			List<ItemStack> l = new ArrayList<>();
			for (FilterCondition fc : FilterCondition.values()) {
				l.add(switch (fc) {
				case DAMAGED -> {
					ItemStack s = new ItemStack(Items.IRON_SWORD);
					s.setDamageValue(s.getMaxDamage() / 2);
					yield s;
				}
				case ENCHANTED -> new ItemStack(Items.ENCHANTED_BOOK);
				case RENAMED -> new ItemStack(Items.NAME_TAG);
				case STACKABLE -> new ItemStack(Items.COBBLESTONE);
				case FOOD -> new ItemStack(Items.BREAD);
				case FUEL -> new ItemStack(Items.COAL);
				case SMELTABLE -> new ItemStack(Items.FURNACE);
				case BLOCK -> new ItemStack(Items.GRASS_BLOCK);
				case HAS_CONTENTS -> new ItemStack(Items.SHULKER_BOX);
				});
			}
			icons = l;
		}
		return icons.get(c.ordinal());
	}

	@Override
	protected void init() {
		super.init();
		buttons.clear();
		for (FilterCondition c : FilterCondition.values()) {
			int i = c.ordinal();
			ConditionButton b = addRenderableWidget(new ConditionButton(leftPos + GRID_X + (i % 3) * CELL, topPos + GRID_Y + (i / 3) * CELL, c));
			buttons.add(b);
		}
	}

	private int stateOf(FilterCondition c) {
		return new ConditionFilterComponent(menu.required, menu.excluded).getState(c.ordinal());
	}

	private void click(FilterCondition c) {
		int next = ConditionFilterComponent.nextState(stateOf(c));
		this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, ConditionFilterComponent.encodeButton(c.ordinal(), next));
	}

	private List<Component> tooltipFor(FilterCondition c) {
		int state = stateOf(c);
		List<Component> tt = new ArrayList<>();
		tt.add(Component.translatable(c.nameKey()).withStyle(ChatFormatting.WHITE));
		tt.add(Component.translatable(c.descKey()).withStyle(ChatFormatting.GRAY));
		ChatFormatting color = state == ConditionFilterComponent.STATE_REQUIRED ? ChatFormatting.GREEN : state == ConditionFilterComponent.STATE_EXCLUDED ? ChatFormatting.RED : ChatFormatting.YELLOW;
		tt.add(Component.translatable("tooltip.obscuras_storage.condition_state", Component.translatable(stateKey(state))).withStyle(color));
		tt.add(Component.translatable("tooltip.obscuras_storage.condition_click").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
		return tt;
	}

	private static String stateKey(int state) {
		return switch (state) {
		case ConditionFilterComponent.STATE_REQUIRED -> "tooltip.obscuras_storage.condition_state.required";
		case ConditionFilterComponent.STATE_EXCLUDED -> "tooltip.obscuras_storage.condition_state.excluded";
		default -> "tooltip.obscuras_storage.condition_state.ignore";
		};
	}

	@Override
	public void render(GuiGraphics gr, int mouseX, int mouseY, float partialTicks) {
		super.render(gr, mouseX, mouseY, partialTicks);
		for (ConditionButton b : buttons) {
			if (b.isHovered()) {
				gr.renderComponentTooltip(font, tooltipFor(b.condition), mouseX, mouseY);
				return;
			}
		}
		this.renderTooltip(gr, mouseX, mouseY);
	}

	@Override
	protected void renderBg(GuiGraphics gr, float partialTicks, int x, int y) {
		int i = (this.width - this.imageWidth) / 2;
		int j = (this.height - this.imageHeight) / 2;
		gr.blit(GUI_TEXTURES, i, j, 0, 0, this.imageWidth, this.imageHeight);
	}

	@Override
	public void getExclusionAreas(Consumer<Box> consumer) {
	}

	/** One 18x18 cell of the grid: the trait icon, framed green when Required and red when Excluded. */
	private class ConditionButton extends Button {
		private final FilterCondition condition;

		ConditionButton(int x, int y, FilterCondition condition) {
			super(x, y, CELL, CELL, Component.translatable(condition.nameKey()), b -> click(condition), Button.DEFAULT_NARRATION);
			this.condition = condition;
		}

		@Override
		public void renderWidget(GuiGraphics gr, int mouseX, int mouseY, float pt) {
			int x = getX(), y = getY();
			int state = stateOf(condition);
			if (state != ConditionFilterComponent.STATE_IGNORE) {
				int col = state == ConditionFilterComponent.STATE_REQUIRED ? COLOR_REQUIRED : COLOR_EXCLUDED;
				gr.fill(x, y, x + CELL, y + CELL, (col & 0x00FFFFFF) | 0x66000000);
				gr.renderOutline(x, y, CELL, CELL, col);
			}
			gr.renderItem(icon(condition), x + 1, y + 1);
			gr.renderItemDecorations(font, icon(condition), x + 1, y + 1, "");
			if (state != ConditionFilterComponent.STATE_IGNORE) {
				boolean req = state == ConditionFilterComponent.STATE_REQUIRED;
				gr.pose().pushPose();
				gr.pose().translate(0, 0, 200);
				gr.drawString(font, req ? "✔" : "✘", x + CELL - 8, y + CELL - 9, req ? 0xFF55FF55 : 0xFFFF5555, true);
				gr.pose().popPose();
			}
			this.isHovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
			if (this.isHovered) {
				gr.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0x80FFFFFF);
			}
		}

		@Override
		public void updateWidgetNarration(NarrationElementOutput out) {
			defaultButtonNarrationText(out);
		}
	}
}
