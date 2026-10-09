package com.obscura.storage.network;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;

public class NetworkHandler {

	public static void sendDataToServer(CompoundTag tag) {
		ClientPlayNetworking.send(new DataPacket(tag));
	}

	public static void sendTo(ServerPlayer player, CompoundTag tag) {
		ServerPlayNetworking.send(player, new DataPacket(tag));
	}

	public static void sendLocateResult(ServerPlayer player, LocateResultPacket packet) {
		ServerPlayNetworking.send(player, packet);
	}

	public static void openTerminal() {
		ClientPlayNetworking.send(new OpenTerminalPacket());
	}

	public static void pickFromStorage(BlockHitResult hit) {
		ClientPlayNetworking.send(PickFromStoragePacket.of(hit));
	}

	public static void toggleRestock() {
		ClientPlayNetworking.send(new ToggleRestockPacket());
	}

	/** The Highlight Connected Inventories key outside a screen: the block pointed at, or null to use the wireless terminal in hand. */
	public static void highlightNetwork(@Nullable BlockPos pos) {
		ClientPlayNetworking.send(new HighlightNetworkPacket(pos));
	}
}
