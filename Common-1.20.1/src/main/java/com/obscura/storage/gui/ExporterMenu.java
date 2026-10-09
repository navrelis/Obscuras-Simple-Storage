package com.obscura.storage.gui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;

import com.obscura.storage.Content;
import com.obscura.storage.tile.ExporterBlockEntity;
import com.obscura.storage.util.DataSlots;
import com.obscura.storage.util.FilterAmount;

public class ExporterMenu extends AbstractFilterBlockMenu<ExporterBlockEntity> {
	/** Client message: the ordinal of the new amount. */
	public static final String MSG_AMOUNT = "amount";
	public FilterAmount amount = FilterAmount.STACK;

	public ExporterMenu(int wid, Inventory pinv) {
		this(wid, pinv, null);
	}

	public ExporterMenu(int wid, Inventory pinv, ExporterBlockEntity te) {
		super(Content.exporterMenu.get(), wid, pinv, te, 62);
		addDataSlot(DataSlots.create(v -> amount = FilterAmount.byId(v, FilterAmount.STACK), () -> te != null ? te.getAmount().ordinal() : 0));
	}

	@Override
	protected void handle(CompoundTag tag) {
		int a = readOrdinal(tag, MSG_AMOUNT, FilterAmount.VALUES.length);
		if(a >= 0)te.setAmount(FilterAmount.VALUES[a]);
	}
}
