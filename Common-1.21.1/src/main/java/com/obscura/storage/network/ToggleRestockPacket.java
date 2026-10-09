package com.obscura.storage.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.obscura.storage.StorageMod;

/** Client to server: the Toggle Restock key was pressed. */
public class ToggleRestockPacket implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ToggleRestockPacket> ID = new CustomPacketPayload.Type<>(ResourceLocation.tryBuild(StorageMod.modid, "toggle_restock"));
	public static final StreamCodec<FriendlyByteBuf, ToggleRestockPacket> STREAM_CODEC = CustomPacketPayload.codec((a, b) -> {}, ToggleRestockPacket::new);

	public ToggleRestockPacket() {}
	private ToggleRestockPacket(FriendlyByteBuf buffer) {}

	@Override
	public CustomPacketPayload.Type<ToggleRestockPacket> type() {
		return ID;
	}
}
