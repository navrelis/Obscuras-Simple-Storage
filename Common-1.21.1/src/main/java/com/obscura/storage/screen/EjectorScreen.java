package com.obscura.storage.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import com.obscura.storage.menu.EjectorMenu;
import com.obscura.storage.screen.widget.EnumCycleButton;
import com.obscura.storage.util.EjectorMode;
import com.obscura.storage.util.FilterAmount;

public class EjectorScreen extends AbstractFilterBlockScreen<EjectorMenu> {
	private EnumCycleButton<EjectorMode> modeBtn;
	private EnumCycleButton<FilterAmount> amountBtn;

	public EjectorScreen(EjectorMenu screenContainer, Inventory inv, Component titleIn) {
		super(screenContainer, inv, titleIn, "ejector");
	}

	@Override
	protected void initButtons() {
		modeBtn = addRenderableWidget(new EnumCycleButton<>(leftPos - 18, nextButtonY(), Component.translatable("narrator.obscuras_storage.ejector_mode"), "ejector_mode", EjectorMode.VALUES, n -> {
			menu.mode = n;
			send(EjectorMenu.MSG_MODE, n);
		}));
		modeBtn.tooltipFactory = s -> tooltip("ejector.mode_" + lower(s));
		amountBtn = addRenderableWidget(new EnumCycleButton<>(leftPos - 18, nextButtonY(), Component.translatable("narrator.obscuras_storage.filter_amount"), "amount", FilterAmount.VALUES, n -> {
			menu.amount = n;
			send(EjectorMenu.MSG_AMOUNT, n);
		}));
		amountBtn.tooltipFactory = s -> tooltip("filter_block.amount_" + lower(s));
		updateButtons();
	}

	@Override
	protected void updateButtons() {
		modeBtn.setState(menu.mode);
		amountBtn.setState(menu.amount);
	}
}
