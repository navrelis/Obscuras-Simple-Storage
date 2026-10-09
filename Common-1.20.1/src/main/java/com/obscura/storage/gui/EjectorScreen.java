package com.obscura.storage.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import com.obscura.storage.util.EjectorMode;
import com.obscura.storage.util.FilterAmount;

public class EjectorScreen extends AbstractFilterBlockScreen<EjectorMenu> {
	private GuiButton modeBtn, amountBtn;

	public EjectorScreen(EjectorMenu screenContainer, Inventory inv, Component titleIn) {
		super(screenContainer, inv, titleIn, "ejector");
	}

	@Override
	protected void initButtons() {
		modeBtn = addButton("ejector_mode", b -> {
			menu.mode = EjectorMode.VALUES[(menu.mode.ordinal() + 1) % EjectorMode.VALUES.length];
			send(EjectorMenu.MSG_MODE, menu.mode);
		});
		modeBtn.iconFactory = s -> iconTex("ejector_mode_" + lower(EjectorMode.byId(s)));
		modeBtn.tooltipFactory = s -> tooltip("ejector.mode_" + lower(EjectorMode.byId(s)));
		modeBtn.setState(menu.mode.ordinal());
		amountBtn = addButton("filter_amount", b -> {
			menu.amount = FilterAmount.VALUES[(menu.amount.ordinal() + 1) % FilterAmount.VALUES.length];
			send(EjectorMenu.MSG_AMOUNT, menu.amount);
		});
		amountBtn.iconFactory = s -> iconTex("amount_" + lower(FilterAmount.byId(s, FilterAmount.ITEM)));
		amountBtn.tooltipFactory = s -> tooltip("filter_block.amount_" + lower(FilterAmount.byId(s, FilterAmount.ITEM)));
		amountBtn.setState(menu.amount.ordinal());
	}

	@Override
	protected void updateButtons() {
		show(modeBtn, menu.mode.ordinal());
		show(amountBtn, menu.amount.ordinal());
	}
}
