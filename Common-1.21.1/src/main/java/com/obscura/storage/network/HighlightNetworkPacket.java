package com.obscura.storage.network;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.obscura.storage.StorageMod;

/**
 * Client to server: the Highlight Connected Inventories key was pressed in the world. Carries the block
 * the crosshair points at, or null when the wireless terminal in hand should be used. The server checks
 * the block, see {@code HighlightNetwork#request}.
 *
 * <p>Fixed size: a flag and, with a block, one block position.
 */
public record HighlightNetworkPacket(@Nullable BlockPos pos) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<HighlightNetworkPacket> ID = new CustomPacketPayload.Type<>(ResourceLocation.tryBuild(StorageMod.modid, "highlight_network"));
	public static final StreamCodec<FriendlyByteBuf, HighlightNetworkPacket> STREAM_CODEC = CustomPacketPayload.codec(HighlightNetworkPacket::write, HighlightNetworkPacket::read);

	private static void write(HighlightNetworkPacket p, FriendlyByteBuf b) {
		b.writeBoolean(p.pos != null);
		if (p.pos != null)b.writeBlockPos(p.pos);
	}

	private static HighlightNetworkPacket read(FriendlyByteBuf b) {
		return new HighlightNetworkPacket(b.readBoolean() ? b.readBlockPos() : null);
	}

	@Override
	public CustomPacketPayload.Type<HighlightNetworkPacket> type() {
		return ID;
	}
}
