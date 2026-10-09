package com.obscura.storage.gui;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.Content;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.tile.InventoryCableConnectorBlockEntity;
import com.obscura.storage.util.IDataReceiver;
import com.obscura.storage.util.RemoteConnections;

public class InventoryLinkMenu extends AbstractContainerMenu implements IDataReceiver {
	private InventoryCableConnectorBlockEntity te;
	private Inventory pinv;
	private boolean sentList;

	public InventoryLinkMenu(int id, Inventory playerInv) {
		this(id, playerInv, null);
	}

	public InventoryLinkMenu(int id, Inventory playerInv, InventoryCableConnectorBlockEntity tile) {
		super(Content.inventoryLink.get(), id);
		this.te = tile;
		this.pinv = playerInv;
	}

	@Override
	public void receive(CompoundTag tag) {
		if(pinv.player.isSpectator() || te == null)return;
		UUID id = null;
		if(tag.contains("id"))id = tag.getUUID("id");
		if(id == null) {
			UUID chn = RemoteConnections.get(pinv.player.level()).makeChannel(tag, pinv.player);
			if (chn != null) te.setChannel(chn);
		} else if(tag.getBoolean("select")) {
			RemoteConnections.Channel c = RemoteConnections.get(pinv.player.level()).getChannel(id);
			if(c != null && c.canAccess(pinv.player))
				te.setChannel(id);
		} else if(tag.contains(RemoteConnections.PUBLIC_TAG)) {
			RemoteConnections.get(pinv.player.level()).editChannel(id, tag.getBoolean(RemoteConnections.PUBLIC_TAG), pinv.player.getUUID());
		} else {
			RemoteConnections.get(pinv.player.level()).removeChannel(id, pinv.player.getUUID());
		}
		sentList = false;
	}

	@Override
	public boolean stillValid(Player p_38874_) {
		return te != null ? te.stillValid(p_38874_) : true;
	}

	@Override
	public void broadcastChanges() {
		if(te == null)return;
		if(!sentList) {
			CompoundTag mainTag = new CompoundTag();
			UUID chn = te.getChannel();
			if(chn != null)
				mainTag.putUUID("selected", chn);

			mainTag.put("list", RemoteConnections.get(pinv.player.level()).listChannels(pinv.player));
			mainTag.putInt("lvl", te.getBeaconLevel());

			NetworkHandler.sendTo((ServerPlayer) pinv.player, mainTag);
			sentList = true;
		}
		super.broadcastChanges();
	}

	@Override
	public ItemStack quickMoveStack(Player p_38941_, int p_38942_) {
		return ItemStack.EMPTY;
	}
}
