package com.obscura.storage.network;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.obscura.storage.StorageMod;

import io.netty.buffer.Unpooled;

public class NetworkHandler {
	public static final ResourceLocation DATA_S2C = new ResourceLocation(StorageMod.modid, "data_s2c");
	public static final ResourceLocation DATA_C2S = new ResourceLocation(StorageMod.modid, "data_c2s");
	public static final ResourceLocation OPEN_TERMINAL_C2S = new ResourceLocation(StorageMod.modid, "open_term_c2s");
	public static final ResourceLocation LOCATE_S2C = new ResourceLocation(StorageMod.modid, "locate_result");
	public static final ResourceLocation PICK_FROM_STORAGE_C2S = new ResourceLocation(StorageMod.modid, "pick_from_storage");
	public static final ResourceLocation TOGGLE_RESTOCK_C2S = new ResourceLocation(StorageMod.modid, "toggle_restock");
	public static final ResourceLocation HIGHLIGHT_NETWORK_C2S = new ResourceLocation(StorageMod.modid, "highlight_network");

	public static void sendDataToServer(CompoundTag tag) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeNbt(tag);
		ClientPlayNetworking.send(DATA_C2S, buf);
	}

	public static void sendTo(ServerPlayer player, CompoundTag tag) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeNbt(tag);
		ServerPlayNetworking.send(player, DATA_S2C, buf);
	}

	/** Item Locator reply. The request travels as a terminal interaction (SlotAction.LOCATE) in DATA_C2S. */
	public static void sendLocateResult(ServerPlayer player, LocateResultPacket packet) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		packet.write(buf);
		ServerPlayNetworking.send(player, LOCATE_S2C, buf);
	}

	public static void openTerminal() {
		ClientPlayNetworking.send(OPEN_TERMINAL_C2S, new FriendlyByteBuf(Unpooled.buffer()));
	}

	/** Pick-block found nothing in the inventory. Carries the targeted block and where it was hit: block position, face (3D data value), three location floats relative to the block. */
	public static void pickFromStorage(BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos();
		Vec3 l = hit.getLocation();
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeBlockPos(pos);
		buf.writeByte(hit.getDirection().get3DDataValue());
		buf.writeFloat((float) (l.x - pos.getX()));
		buf.writeFloat((float) (l.y - pos.getY()));
		buf.writeFloat((float) (l.z - pos.getZ()));
		ClientPlayNetworking.send(PICK_FROM_STORAGE_C2S, buf);
	}

	public static void toggleRestock() {
		ClientPlayNetworking.send(TOGGLE_RESTOCK_C2S, new FriendlyByteBuf(Unpooled.buffer()));
	}

	/** The Highlight Connected Inventories key outside a screen: the block pointed at, or null to use the wireless terminal in hand. */
	public static void highlightNetwork(@Nullable BlockPos pos) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeBoolean(pos != null);
		if (pos != null)buf.writeBlockPos(pos);
		ClientPlayNetworking.send(HIGHLIGHT_NETWORK_C2S, buf);
	}
}
