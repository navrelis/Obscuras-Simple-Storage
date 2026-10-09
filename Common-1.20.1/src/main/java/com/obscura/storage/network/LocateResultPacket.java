package com.obscura.storage.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.platform.Platform;
import com.obscura.storage.util.ItemLocator;

import io.netty.handler.codec.DecoderException;

/**
 * Item Locator reply (server to client).
 *
 * @param item the located item (count 1, only the NBT the terminal syncs, so its name matches the terminal entry); empty for "all connected inventories"
 * @param positions inventories to highlight in the player's dimension, nearest first, at most {@link ItemLocator#MAX_POSITIONS} ({@link ItemLocator#MAX_ALL_POSITIONS} for all)
 * @param inDimension matching inventories in the player's dimension (may exceed positions.size())
 * @param otherDimensions matching inventories in other dimensions
 * @param unknown matching inventories without a known position
 */
public record LocateResultPacket(ItemStack item, List<BlockPos> positions, int inDimension, int otherDimensions, int unknown) {

	/** True for "all connected inventories": no item was searched. */
	public boolean isAll() {
		return item.isEmpty();
	}

	public void write(FriendlyByteBuf buf) {
		Item i = item.getItem();
		CompoundTag tag = Platform.getSyncTag(item);
		buf.writeId(BuiltInRegistries.ITEM, i);
		buf.writeNbt(tag);
		buf.writeVarInt(positions.size());
		for (BlockPos p : positions) {
			buf.writeBlockPos(p);
		}
		buf.writeVarInt(inDimension);
		buf.writeVarInt(otherDimensions);
		buf.writeVarInt(unknown);
	}

	public static LocateResultPacket read(FriendlyByteBuf buf) {
		ItemStack item = new ItemStack(buf.readById(BuiltInRegistries.ITEM));
		item.setTag(buf.readNbt());
		int size = buf.readVarInt();
		if (size < 0 || size > (item.isEmpty() ? ItemLocator.MAX_ALL_POSITIONS : ItemLocator.MAX_POSITIONS))throw new DecoderException("Too many positions: " + size);
		List<BlockPos> positions = new ArrayList<>(size);
		for (int i = 0;i < size;i++) {
			positions.add(buf.readBlockPos());
		}
		return new LocateResultPacket(item, positions, buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
	}
}
