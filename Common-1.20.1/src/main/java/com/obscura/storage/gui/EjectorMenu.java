package com.obscura.storage.gui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;

import com.obscura.storage.Content;
import com.obscura.storage.tile.EjectorBlockEntity;
import com.obscura.storage.util.DataSlots;
import com.obscura.storage.util.EjectorMode;
import com.obscura.storage.util.FilterAmount;

public class EjectorMenu extends AbstractFilterBlockMenu<EjectorBlockEntity> {
	/** Client messages: the ordinal of the new mode / amount. */
	public static final String MSG_MODE = "mode", MSG_AMOUNT = "amount";
	public EjectorMode mode = EjectorMode.CONTINUOUS;
	public FilterAmount amount = FilterAmount.ITEM;

	public EjectorMenu(int wid, Inventory pinv) {
		this(wid, pinv, null);
	}

	public EjectorMenu(int wid, Inventory pinv, EjectorBlockEntity te) {
		super(Content.ejectorMenu.get(), wid, pinv, te, 62);
		addDataSlot(DataSlots.create(v -> mode = EjectorMode.byId(v), () -> te != null ? te.getMode().ordinal() : 0));
		addDataSlot(DataSlots.create(v -> amount = FilterAmount.byId(v, FilterAmount.ITEM), () -> te != null ? te.getAmount().ordinal() : 0));
	}

	@Override
	protected void handle(CompoundTag tag) {
		int m = readOrdinal(tag, MSG_MODE, EjectorMode.VALUES.length);
		if(m >= 0)te.setMode(EjectorMode.VALUES[m]);
		int a = readOrdinal(tag, MSG_AMOUNT, FilterAmount.VALUES.length);
		if(a >= 0)te.setAmount(FilterAmount.VALUES[a]);
	}
}
