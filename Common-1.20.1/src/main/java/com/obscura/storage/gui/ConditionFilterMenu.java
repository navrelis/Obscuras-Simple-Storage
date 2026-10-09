package com.obscura.storage.gui;

import java.util.function.BooleanSupplier;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.Content;
import com.obscura.storage.util.ConditionFilterState;
import com.obscura.storage.util.DataSlots;

public class ConditionFilterMenu extends AbstractFilteredMenu {
	private ItemStack stack;
	/** Synced to the client so the screen can show the state of each condition. */
	public int required, excluded;
	private BooleanSupplier isValid;
	private Runnable refresh;

	public ConditionFilterMenu(int wid, Inventory pinv, ItemStack stack, BooleanSupplier isValid, Runnable refresh) {
		this(wid, pinv);
		this.stack = stack;
		this.isValid = isValid;
		this.refresh = refresh;
	}

	public ConditionFilterMenu(int wid, Inventory pinv) {
		super(Content.conditionFilterConatiner.get(), wid, pinv);
		this.isValid = () -> true;

		for(int k = 0; k < 3; ++k) {
			for(int i1 = 0; i1 < 9; ++i1) {
				this.addSlot(new Slot(pinv, i1 + k * 9 + 9, 8 + i1 * 18, 84 + k * 18));
			}
		}

		for(int l = 0; l < 9; ++l) {
			this.addSlot(new Slot(pinv, l, 8 + l * 18, 142));
		}
		addDataSlot(DataSlots.create(v -> required = v, () -> current().required()));
		addDataSlot(DataSlots.create(v -> excluded = v, () -> current().excluded()));
	}

	private ConditionFilterState current() {
		return stack == null ? ConditionFilterState.EMPTY : ConditionFilterState.read(stack);
	}

	/**
	 * Button id = condition index * 3 + new state, see {@link ConditionFilterState#encodeButton}.
	 * Invalid ids and clicks from spectators are ignored.
	 */
	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (stack == null || player.isSpectator() || !isValid.getAsBoolean())return false;
		int condition = ConditionFilterState.buttonCondition(id);
		int state = ConditionFilterState.buttonState(id);
		if (condition < 0 || state < 0)return false;
		current().withState(condition, state).write(stack);
		return true;
	}

	@Override
	public boolean stillValid(Player playerIn) {
		return isValid.getAsBoolean();
	}

	@Override
	public ItemStack quickMoveStack(Player playerIn, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public void removed(Player playerIn) {
		super.removed(playerIn);
		if (refresh != null)refresh.run();
	}
}
