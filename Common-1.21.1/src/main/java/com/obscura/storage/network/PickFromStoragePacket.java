package com.obscura.storage.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.obscura.storage.StorageMod;

/**
 * Client to server: pick-block found no match in the inventory. Carries the targeted block and where
 * the player hit it (face and the hit location relative to the block), so the server can ask the block
 * for its pick item with the same hit the client used (multipart blocks pick by the hit). The server
 * does not trust the hit, see BuildersReach#validatedHit.
 *
 * <p>Fixed size: a block position, one byte for the face and three floats.
 */
public record PickFromStoragePacket(BlockPos pos, Direction face, Vec3 offset) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<PickFromStoragePacket> ID = new CustomPacketPayload.Type<>(ResourceLocation.tryBuild(StorageMod.modid, "pick_from_storage"));
	public static final StreamCodec<FriendlyByteBuf, PickFromStoragePacket> STREAM_CODEC = CustomPacketPayload.codec(PickFromStoragePacket::write, PickFromStoragePacket::read);

	/** The packet for what the client's crosshair points at. */
	public static PickFromStoragePacket of(BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos();
		return new PickFromStoragePacket(pos, hit.getDirection(), hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ()));
	}

	private static void write(PickFromStoragePacket p, FriendlyByteBuf b) {
		b.writeBlockPos(p.pos());
		b.writeByte(p.face().get3DDataValue());
		b.writeFloat((float) p.offset().x);
		b.writeFloat((float) p.offset().y);
		b.writeFloat((float) p.offset().z);
	}

	private static PickFromStoragePacket read(FriendlyByteBuf b) {
		BlockPos pos = b.readBlockPos();
		// from3DDataValue wraps out of range values instead of throwing
		Direction face = Direction.from3DDataValue(b.readByte());
		return new PickFromStoragePacket(pos, face, new Vec3(b.readFloat(), b.readFloat(), b.readFloat()));
	}

	@Override
	public CustomPacketPayload.Type<PickFromStoragePacket> type() {
		return ID;
	}
}
