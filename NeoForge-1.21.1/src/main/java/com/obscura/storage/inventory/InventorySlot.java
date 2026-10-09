package com.obscura.storage.inventory;

import java.util.function.Consumer;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import com.obscura.storage.inventory.IInventoryAccess.IChangeNotifier;

public class InventorySlot {
	private final IItemHandler handler;
	private final IChangeNotifier notifier;
	private final int id;

	public InventorySlot(IItemHandler h, IChangeNotifier notifier, int i) {
		this.handler = h;
		this.notifier = notifier;
		this.id = i;
	}

	public ItemStack getStack() {
		return handler.getStackInSlot(id);
	}

	public ItemStack extract(int amount) {
		ItemStack is = handler.extractItem(id, amount, false);
		if (!is.isEmpty())notifyChange();
		return is;
	}

	public ItemStack insert(ItemStack stack) {
		int c = stack.getCount();
		ItemStack is = handler.insertItem(id, stack, false);
		if (c != is.getCount())notifyChange();
		return is;
	}

	public boolean transferTo(int amount, InventorySlot to) {
		ItemStack is = handler.extractItem(id, amount, true);
		if (is.isEmpty())return false;
		int ex = is.getCount();
		is = to.handler.insertItem(to.id, is, true);
		if (is.isEmpty()) {
			return move(amount, to);
		} else if (is.getCount() < ex) {//Try inserting less
			int ins = ex - is.getCount();
			is = handler.extractItem(id, ins, true);
			is = to.handler.insertItem(to.id, is, true);
			if (is.isEmpty()) {
				return move(ins, to);
			}
		}
		return false;
	}

	/**
	 * Moves after a successful simulation. IItemHandler has no transactions: if the destination takes
	 * less than simulated after all, the rest goes back to where it came from instead of vanishing.
	 */
	private boolean move(int amount, InventorySlot to) {
		ItemStack is = handler.extractItem(id, amount, false);
		if (is.isEmpty())return false;
		ItemStack rem = to.handler.insertItem(to.id, is, false);
		if (!rem.isEmpty()) {
			rem = handler.insertItem(id, rem, false);
			if (!rem.isEmpty())rem = to.handler.insertItem(to.id, rem, false);
			if (!rem.isEmpty()) {
				com.obscura.storage.StorageMod.LOGGER.warn("Inventory slot transfer could not put back {}", rem);
			}
		}
		notifyChange();
		to.notifyChange();
		return rem.getCount() < is.getCount();
	}

	/**
	 * {@link #transferTo} for callers that need the exact amount: simulates, then moves up to
	 * {@code amount} items into {@code to} and returns how many arrived there (0 when nothing moved).
	 * Whatever the destination refuses after all goes back to this slot, like {@link #move} does; if
	 * even that fails (IItemHandler has no transactions) the rest is handed to {@code orphans}, so the
	 * caller can keep it from vanishing.
	 */
	public int transferCount(int amount, InventorySlot to, Consumer<ItemStack> orphans) {
		if (amount <= 0)return 0;
		ItemStack is = handler.extractItem(id, amount, true);
		if (is.isEmpty())return 0;
		int ex = is.getCount();
		int rest = to.handler.insertItem(to.id, is, true).getCount();
		if (rest >= ex)return 0;
		is = handler.extractItem(id, ex - rest, false);
		if (is.isEmpty())return 0;
		int taken = is.getCount();
		ItemStack rem = to.handler.insertItem(to.id, is, false);
		int moved = taken - rem.getCount();
		if (!rem.isEmpty()) {
			rem = handler.insertItem(id, rem, false);
			if (!rem.isEmpty()) {
				int back = rem.getCount();
				rem = to.handler.insertItem(to.id, rem, false);
				moved += back - rem.getCount();
			}
			if (!rem.isEmpty())orphans.accept(rem);
		}
		notifyChange();
		to.notifyChange();
		return moved;
	}

	private void notifyChange() {
		notifier.onSlotChanged(this);
	}

	/** The tracker that handed this slot out. */
	public IChangeNotifier getNotifier() {
		return notifier;
	}

	/**
	 * The position this slot stands for. Unlike the Fabric Transfer API, where an insert-only
	 * destination has no slot view, every NeoForge slot is a fixed index into its handler, so this is
	 * never null; shared code only uses it to know whether a search can continue after this slot.
	 */
	public Object getView() {
		return this;
	}

	protected IItemHandler getHandler() {
		return handler;
	}

	protected int getId() {
		return id;
	}
}
