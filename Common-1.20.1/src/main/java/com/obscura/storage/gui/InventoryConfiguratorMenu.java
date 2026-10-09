package com.obscura.storage.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.Content;
import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.item.InventoryConfiguratorItem;
import com.obscura.storage.util.BlockFilter;
import com.obscura.storage.util.ConfiguratorData;
import com.obscura.storage.util.DataSlots;
import com.obscura.storage.util.Priority;

public class InventoryConfiguratorMenu extends AbstractFilteredMenu {
	private final BlockPos pos;
	private final BlockFilter filter;
	private final InteractionHand hand;
	public Direction side = Direction.DOWN;
	public Priority priority = Priority.NORMAL;
	public boolean skip;
	public boolean keepLast;
	private boolean closed;

	public InventoryConfiguratorMenu(int wid, Inventory pinv) {
		this(wid, pinv, null, null, null);
		addDataSlot(DataSlots.set(v -> priority = Priority.VALUES[v % Priority.VALUES.length]));
		addDataSlot(DataSlots.set(v -> side = Direction.from3DDataValue(v)));
		addDataSlot(DataSlots.set(v -> skip = v != 0));
		addDataSlot(DataSlots.set(v -> keepLast = v != 0));
	}

	public InventoryConfiguratorMenu(int wid, Inventory pinv, BlockPos pos, BlockFilter filter, InteractionHand hand) {
		super(Content.invConfigConatiner.get(), wid, pinv);
		this.pos = pos;
		this.filter = filter;
		this.hand = hand;

		Container inv = filter == null ? new SimpleContainer(1) : filter.filter;
		addSlot(new FilterSlot(inv, 0, 80, 32));

		for (int k = 0; k < 3; ++k) {
			for (int i1 = 0; i1 < 9; ++i1) {
				this.addSlot(new Slot(pinv, i1 + k * 9 + 9, 8 + i1 * 18, 84 + k * 18));
			}
		}

		for (int l = 0; l < 9; ++l) {
			this.addSlot(new Slot(pinv, l, 8 + l * 18, 142));
		}

		if (filter != null) {
			addDataSlot(DataSlots.create(v -> priority = Priority.VALUES[v % Priority.VALUES.length], () -> filter.getPriority().ordinal()));
			addDataSlot(DataSlots.create(v -> side = Direction.from3DDataValue(v), () -> filter.getSide().get3DDataValue()));
			addDataSlot(DataSlots.create(v -> skip = v != 0, () -> filter.skip() ? 1 : 0));
			addDataSlot(DataSlots.create(v -> keepLast = v != 0, () -> filter.isKeepLast() ? 1 : 0));
		}
	}

	@Override
	public boolean stillValid(Player player) {
		return (pos == null || player.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < 256) && !closed;
	}

	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (filter == null || player.level().isClientSide || id < 0) {
			return false;
		}
		int mode = id & 0b111;
		int arg = id >> 3;
		switch (mode) {
		case 0:
			if (arg < Priority.VALUES.length) {
				filter.setPriority(Priority.VALUES[arg]);
			}
			break;

		case 1: {
			ItemStack is = player.getItemInHand(hand);
			if (is.getItem() instanceof InventoryConfiguratorItem) {
				ConfiguratorData.Configurator c = new ConfiguratorData.Configurator(is, player.level().getGameTime());
				c.startSelection(pos, filter.getConnectedBlocks());
				closed = true;
			}
		}
			break;

		case 2: {
			filter.getConnectedBlocks().clear();
			filter.getConnectedBlocks().add(filter.getMainPos());
			filter.markDirty();
			ItemStack is = player.getItemInHand(hand);
			if (is.getItem() instanceof InventoryConfiguratorItem) {
				ConfiguratorData.Configurator c = new ConfiguratorData.Configurator(is, player.level().getGameTime());
				c.setSelection(filter.getConnectedBlocks());
			}
		}
			break;

		case 3:
			filter.setSide(Direction.from3DDataValue(arg));
			break;

		case 4:
			filter.setSkip(arg != 0);
			break;

		case 5:
			filter.setKeepLast(arg != 0);
			break;

		case 6: {
			if (arg >= 0 && arg < slots.size()) {
				Slot slot = slots.get(arg);
				if (slot instanceof FilterSlot && slot.getItem().getItem() instanceof IItemFilter f) {
					f.openGui(slot.getItem(), player, () -> slot.getItem().getItem() == f, filter::markFilterDirty);
				}
			}
		}
			break;

		case 7:
			closed = true;
			BlockFilter.removeAt(player.level(), pos);
			break;

		default:
			break;
		}

		player.level().blockEntityChanged(pos);
		return true;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		if (filter != null) {
			filter.markDirty();
		}
	}
}