package com.obscura.storage.network;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.StorageMod;
import com.obscura.storage.util.ItemLocator;

import io.netty.buffer.ByteBuf;

/**
 * Item Locator reply (server to client).
 *
 * @param item the located item (count 1); empty for "all connected inventories" (Highlight Connected Inventories)
 * @param positions inventories to highlight in the player's dimension, nearest first, at most {@link ItemLocator#MAX_POSITIONS} ({@link ItemLocator#MAX_ALL_POSITIONS} for all)
 * @param inDimension matching inventories in the player's dimension (may exceed positions.size())
 * @param otherDimensions matching inventories in other dimensions
 * @param unknown matching inventories without a known position
 */
public record LocateResultPacket(ItemStack item, List<BlockPos> positions, int inDimension, int otherDimensions, int unknown) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<LocateResultPacket> ID = new CustomPacketPayload.Type<>(ResourceLocation.tryBuild(StorageMod.modid, "locate_result"));
	/** The same bytes with a different limit: an item locate sends at most 64 positions, "all" up to 256. */
	private static final StreamCodec<ByteBuf, List<BlockPos>> ITEM_POSITIONS = BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(ItemLocator.MAX_POSITIONS));
	private static final StreamCodec<ByteBuf, List<BlockPos>> ALL_POSITIONS = BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(ItemLocator.MAX_ALL_POSITIONS));
	public static final StreamCodec<RegistryFriendlyByteBuf, LocateResultPacket> STREAM_CODEC = StreamCodec.of(LocateResultPacket::write, LocateResultPacket::read);

	/** True for "all connected inventories": no item was searched. */
	public boolean isAll() {
		return item.isEmpty();
	}

	private static StreamCodec<ByteBuf, List<BlockPos>> positionsCodec(ItemStack item) {
		return item.isEmpty() ? ALL_POSITIONS : ITEM_POSITIONS;
	}

	private static void write(RegistryFriendlyByteBuf buf, LocateResultPacket p) {
		ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.item);
		positionsCodec(p.item).encode(buf, p.positions);
		ByteBufCodecs.VAR_INT.encode(buf, p.inDimension);
		ByteBufCodecs.VAR_INT.encode(buf, p.otherDimensions);
		ByteBufCodecs.VAR_INT.encode(buf, p.unknown);
	}

	private static LocateResultPacket read(RegistryFriendlyByteBuf buf) {
		ItemStack item = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
		List<BlockPos> positions = positionsCodec(item).decode(buf);
		return new LocateResultPacket(item, positions, ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf));
	}

	@Override
	public CustomPacketPayload.Type<LocateResultPacket> type() {
		return ID;
	}
}
