package com.obscura.storage.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import com.obscura.storage.menu.ExporterMenu;
import com.obscura.storage.screen.widget.EnumCycleButton;
import com.obscura.storage.util.FilterAmount;

public class ExporterScreen extends AbstractFilterBlockScreen<ExporterMenu> {
	private EnumCycleButton<FilterAmount> amountBtn;

	public ExporterScreen(ExporterMenu screenContainer, Inventory inv, Component titleIn) {
		super(screenContainer, inv, titleIn, "exporter");
	}

	@Override
	protected void initButtons() {
		amountBtn = addRenderableWidget(new EnumCycleButton<>(leftPos - 18, nextButtonY(), Component.translatable("narrator.obscuras_storage.filter_amount"), "amount", FilterAmount.VALUES, n -> {
			menu.amount = n;
			send(ExporterMenu.MSG_AMOUNT, n);
		}));
		amountBtn.tooltipFactory = s -> tooltip("filter_block.amount_" + lower(s));
		updateButtons();
	}

	@Override
	protected void updateButtons() {
		amountBtn.setState(menu.amount);
	}
}
