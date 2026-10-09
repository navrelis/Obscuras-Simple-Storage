package com.obscura.storage.util;

import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.tile.ShulkerDockBlockEntity;

/**
 * The live 27 slots of the box docked in a Shulker Dock, as seen by connectors, hoppers and
 * comparators. One instance per dock for its whole life: platform wrappers (Fabric's
 * {@code InventoryStorage}, Forge's {@code InvWrapper}) and slots handed out by them may outlive a
 * box, so the guards here are what keeps a stale wrapper from moving items.
 *
 * <ul>
 * <li>No box docked: size 0, every slot reads empty, nothing can be placed, nothing can be taken.</li>
 * <li>Box docked: 27 slots with the vanilla shulker box rule ({@link #canStore}).</li>
 * </ul>
 */
public class ShulkerDockContainer implements Container, ITidyExempt {
	public static final int SLOTS = 27;
	private final ShulkerDockBlockEntity owner;
	private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

	public ShulkerDockContainer(ShulkerDockBlockEntity owner) {
		this.owner = owner;
	}

	/**
	 * Whether {@code stack} may go into a docked box: what vanilla lets into a shulker box
	 * ({@code canFitInsideContainerItems}, which excludes shulker boxes), minus bundles (vanilla
	 * 1.20.1 still lets those in) and minus every dockable item, so a box added to the dockable tag
	 * by a pack can never be nested either.
	 */
	public static boolean canStore(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem().canFitInsideContainerItems() &&
				!(stack.getItem() instanceof BundleItem) && !stack.is(ShulkerDockBlockEntity.DOCKABLE);
	}

	private boolean valid(int slot) {
		return owner.hasBox() && slot >= 0 && slot < SLOTS;
	}

	@Override
	public int getContainerSize() {
		return owner.hasBox() ? SLOTS : 0;
	}

	@Override
	public boolean isEmpty() {
		if (!owner.hasBox())return true;
		for (ItemStack s : items) {
			if (!s.isEmpty())return false;
		}
		return true;
	}

	@Override
	public ItemStack getItem(int slot) {
		return valid(slot) ? items.get(slot) : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItem(int slot, int count) {
		if (!valid(slot))return ItemStack.EMPTY;
		ItemStack r = ContainerHelper.removeItem(items, slot, count);
		if (!r.isEmpty())setChanged();
		return r;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		if (!valid(slot))return ItemStack.EMPTY;
		return ContainerHelper.takeItem(items, slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		if (!valid(slot)) {
			// Nothing legitimate writes here (size 0, canPlaceItem false). Should anything still do it,
			// the stack must not vanish: the dock drops it.
			if (!stack.isEmpty())owner.rescue(stack);
			return;
		}
		items.set(slot, stack);
		if (stack.getCount() > getMaxStackSize())stack.setCount(getMaxStackSize());
		owner.invalidateComparator();
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return valid(slot) && canStore(stack);
	}

	@Override
	public void setChanged() {
		owner.setChanged();
	}

	@Override
	public boolean stillValid(Player player) {
		if (!owner.hasBox() || owner.getLevel() == null || owner.getLevel().getBlockEntity(owner.getBlockPos()) != owner)return false;
		return player.distanceToSqr(owner.getBlockPos().getX() + 0.5D, owner.getBlockPos().getY() + 0.5D, owner.getBlockPos().getZ() + 0.5D) <= 64.0D;
	}

	/** Not used by the platforms; empties the slots like any container would. */
	@Override
	public void clearContent() {
		clear();
		setChanged();
	}

	// ---- owner side, never called by wrappers ----

	/** The slots, for the owner to read (write-back, comparator). */
	public List<ItemStack> items() {
		return items;
	}

	/** Replaces all slots with {@code from} (owner only, when a box is docked or loaded). */
	public void load(List<ItemStack> from) {
		for (int i = 0; i < SLOTS; i++)items.set(i, i < from.size() ? from.get(i) : ItemStack.EMPTY);
	}

	/** Empties all slots without any notification (owner only, the box was taken out). */
	public void clear() {
		for (int i = 0; i < SLOTS; i++)items.set(i, ItemStack.EMPTY);
	}
}
