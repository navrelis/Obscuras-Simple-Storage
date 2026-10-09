package com.obscura.storage.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import com.obscura.storage.util.FilterAmount;

public class ExporterScreen extends AbstractFilterBlockScreen<ExporterMenu> {
	private GuiButton amountBtn;

	public ExporterScreen(ExporterMenu screenContainer, Inventory inv, Component titleIn) {
		super(screenContainer, inv, titleIn, "exporter");
	}

	@Override
	protected void initButtons() {
		amountBtn = addButton("filter_amount", b -> {
			menu.amount = FilterAmount.VALUES[(menu.amount.ordinal() + 1) % FilterAmount.VALUES.length];
			send(ExporterMenu.MSG_AMOUNT, menu.amount);
		});
		amountBtn.iconFactory = s -> iconTex("amount_" + lower(FilterAmount.byId(s, FilterAmount.STACK)));
		amountBtn.tooltipFactory = s -> tooltip("filter_block.amount_" + lower(FilterAmount.byId(s, FilterAmount.STACK)));
		amountBtn.setState(menu.amount.ordinal());
	}

	@Override
	protected void updateButtons() {
		show(amountBtn, menu.amount.ordinal());
	}
}
