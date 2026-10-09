package com.obscura.storage.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.obscura.storage.StorageMod;

public record DataPacket(CompoundTag tag) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<DataPacket> ID = new CustomPacketPayload.Type<>(ResourceLocation.tryBuild(StorageMod.modid, "data"));
	public static final StreamCodec<FriendlyByteBuf, DataPacket> STREAM_CODEC = CustomPacketPayload.codec(DataPacket::write, DataPacket::new);

	/** Same heap limit vanilla uses for NBT in packets; the codec is shared by both directions, so a client can't force unbounded allocations. */
	private static final long MAX_NBT_BYTES = 2097152L;

	private DataPacket(FriendlyByteBuf pb) {
		this((CompoundTag) pb.readNbt(NbtAccounter.create(MAX_NBT_BYTES)));
	}

	private void write(FriendlyByteBuf pb) {
		pb.writeNbt(tag);
	}

	@Override
	public CustomPacketPayload.Type<DataPacket> type() {
		return ID;
	}
}
