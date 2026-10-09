package com.obscura.storage.network;

import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import com.obscura.storage.client.FrameLabelsClient;
import com.obscura.storage.client.ItemLocatorClient;
import com.obscura.storage.inventory.FrameLabels;
import com.obscura.storage.item.WirelessTerminal;
import com.obscura.storage.menu.StorageTerminalMenu;
import com.obscura.storage.util.BuildersReach;
import com.obscura.storage.util.HighlightNetwork;
import com.obscura.storage.util.IDataReceiver;
import com.obscura.storage.util.PlayerInvUtil;

/**
 * Same packets as the Fabric build: the terminal data channel both ways, Open Terminal key, the
 * Item Locator reply, Builder's Reach pick and Restock toggle and the Highlight Connected Inventories key.
 * Handlers run on the main thread.
 * The client handlers only touch client classes when they run, so this class loads on a dedicated
 * server.
 */
public class NetworkHandler {
	/**
	 * Network protocol of this mod. A client or server with another version (or an older build, whose
	 * channels were in another namespace) is refused when connecting, with NeoForge's channel mismatch
	 * screen.
	 */
	public static final String PROTOCOL_VERSION = "obscuras_storage-5";

	@SubscribeEvent
	public static void onPayloadRegister(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
		registrar.playBidirectional(DataPacket.ID, DataPacket.STREAM_CODEC, new DirectionalPayloadHandler<>(NetworkHandler::handleDataClient, NetworkHandler::handleDataServer));
		registrar.playToServer(OpenTerminalPacket.ID, OpenTerminalPacket.STREAM_CODEC, NetworkHandler::handleTermServer);
		// Item Locator reply. The request travels as a terminal interaction (SlotAction.LOCATE) in DataPacket.
		registrar.playToClient(LocateResultPacket.ID, LocateResultPacket.STREAM_CODEC, NetworkHandler::handleLocateClient);
		// Builder's Reach
		registrar.playToServer(PickFromStoragePacket.ID, PickFromStoragePacket.STREAM_CODEC, NetworkHandler::handlePickServer);
		registrar.playToServer(ToggleRestockPacket.ID, ToggleRestockPacket.STREAM_CODEC, NetworkHandler::handleToggleRestockServer);
		// Highlight Connected Inventories key
		registrar.playToServer(HighlightNetworkPacket.ID, HighlightNetworkPacket.STREAM_CODEC, NetworkHandler::handleHighlightNetworkServer);
	}

	public static void handleDataServer(DataPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			ServerPlayer sender = (ServerPlayer) context.player();
			if(sender.isSpectator())return;
			if(sender.containerMenu instanceof IDataReceiver d) {
				d.receive(packet.tag());
			}
		});
	}

	public static void handleDataClient(DataPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			// Frame Labels status for the Inventory Configurator HUD
			if (packet.tag().contains(FrameLabels.STATUS_KEY)) {
				FrameLabelsClient.receive(packet.tag());
				return;
			}
			// Terminal sync state lives in the menu, which stays open while another screen (a recipe
			// viewer) covers the terminal. Deliver it there so no delta is lost; the terminal screen
			// picks the update up from the menu. Everything else is screen-only state.
			var player = context.player();
			if(player != null && player.containerMenu instanceof StorageTerminalMenu m) {
				m.receiveClientNBTPacket(packet.tag());
			} else if(Minecraft.getInstance().screen instanceof IDataReceiver d) {
				d.receive(packet.tag());
			}
		});
	}

	public static void handleLocateClient(LocateResultPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> ItemLocatorClient.handleResult(packet));
	}

	public static void handleTermServer(OpenTerminalPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			ServerPlayer sender = (ServerPlayer) context.player();
			ItemStack t = PlayerInvUtil.findItem(sender, i -> i.getItem() instanceof WirelessTerminal e && e.canOpen(i), ItemStack.EMPTY, Function.identity());
			if(!t.isEmpty())
				((WirelessTerminal)t.getItem()).open(sender, t);
		});
	}

	public static void handlePickServer(PickFromStoragePacket packet, IPayloadContext context) {
		context.enqueueWork(() -> BuildersReach.pickFromStorage((ServerPlayer) context.player(), packet.pos(), packet.face(), packet.offset()));
	}

	public static void handleToggleRestockServer(ToggleRestockPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> BuildersReach.toggleRestock((ServerPlayer) context.player()));
	}

	public static void handleHighlightNetworkServer(HighlightNetworkPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> HighlightNetwork.request((ServerPlayer) context.player(), packet.pos()));
	}

	public static void sendDataToServer(CompoundTag tag) {
		PacketDistributor.sendToServer(new DataPacket(tag));
	}

	public static void sendTo(ServerPlayer pl, CompoundTag tag) {
		PacketDistributor.sendToPlayer(pl, new DataPacket(tag));
	}

	public static void sendLocateResult(ServerPlayer player, LocateResultPacket packet) {
		PacketDistributor.sendToPlayer(player, packet);
	}

	public static void openTerminal() {
		PacketDistributor.sendToServer(new OpenTerminalPacket());
	}

	public static void pickFromStorage(BlockHitResult hit) {
		PacketDistributor.sendToServer(PickFromStoragePacket.of(hit));
	}

	public static void toggleRestock() {
		PacketDistributor.sendToServer(new ToggleRestockPacket());
	}

	/** The Highlight Connected Inventories key outside a screen: the block pointed at, or null to use the wireless terminal in hand. */
	public static void highlightNetwork(@Nullable BlockPos pos) {
		PacketDistributor.sendToServer(new HighlightNetworkPacket(pos));
	}
}
