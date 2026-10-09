package com.obscura.storage.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import com.obscura.storage.StorageMod;
import com.obscura.storage.StorageModClient;
import com.obscura.storage.item.WirelessTerminal;
import com.obscura.storage.util.BuildersReach;
import com.obscura.storage.util.HighlightNetwork;
import com.obscura.storage.util.IDataReceiver;
import com.obscura.storage.util.PlayerInvUtil;

/**
 * Forge networking: one {@link SimpleChannel} carrying the same messages as the Fabric channels
 * (terminal data both ways, open terminal key, Item Locator result, Builder's Reach pick and restock toggle,
 * Highlight Connected Inventories key).
 */
public class NetworkHandler {
	/**
	 * Network protocol of this mod. A client or server with another version (or an older build, whose
	 * channel was in another namespace) is refused when connecting.
	 */
	public static final String PROTOCOL_VERSION = "obscuras_storage-5";
	public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
			new ResourceLocation(StorageMod.modid, "main"),
			() -> PROTOCOL_VERSION,
			PROTOCOL_VERSION::equals,
			PROTOCOL_VERSION::equals
			);

	/** Minimum ticks between two Open Terminal key requests of a player, each one starts a full terminal sync. */
	private static final int OPEN_TERMINAL_COOLDOWN = 10;
	private static final Map<UUID, Integer> openTerminalLast = new ConcurrentHashMap<>();

	public static record DataPacket(CompoundTag tag) {
		static DataPacket read(net.minecraft.network.FriendlyByteBuf buf) {
			return new DataPacket(buf.readAnySizeNbt());
		}

		void write(net.minecraft.network.FriendlyByteBuf buf) {
			buf.writeNbt(tag);
		}
	}

	public static record OpenTerminalPacket() {}
	/** Pick-block target: the block, the face (3D data value, -1 when not sent) and the hit location relative to the block. */
	public static record PickFromStoragePacket(BlockPos pos, int face, float dx, float dy, float dz) {
		/** Face byte plus three floats, what follows the block position. */
		private static final int HIT_BYTES = 1 + 3 * 4;

		static PickFromStoragePacket read(net.minecraft.network.FriendlyByteBuf buf) {
			BlockPos pos = buf.readBlockPos();
			// A sender without the hit (an older version) only sends the block
			if (buf.readableBytes() < HIT_BYTES)return new PickFromStoragePacket(pos, -1, 0, 0, 0);
			return new PickFromStoragePacket(pos, buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readFloat());
		}

		void write(net.minecraft.network.FriendlyByteBuf buf) {
			buf.writeBlockPos(pos);
			buf.writeByte(face);
			buf.writeFloat(dx);
			buf.writeFloat(dy);
			buf.writeFloat(dz);
		}
	}
	public static record ToggleRestockPacket() {}
	/** The Highlight Connected Inventories key: the block pointed at, null for the wireless terminal in hand. */
	public static record HighlightNetworkPacket(@Nullable BlockPos pos) {
		static HighlightNetworkPacket read(net.minecraft.network.FriendlyByteBuf buf) {
			return new HighlightNetworkPacket(buf.readBoolean() ? buf.readBlockPos() : null);
		}

		void write(net.minecraft.network.FriendlyByteBuf buf) {
			buf.writeBoolean(pos != null);
			if (pos != null)buf.writeBlockPos(pos);
		}
	}

	public static void init() {
		int id = 0;
		INSTANCE.registerMessage(id++, DataPacket.class, DataPacket::write, DataPacket::read, NetworkHandler::handleData);
		INSTANCE.registerMessage(id++, OpenTerminalPacket.class, (p, b) -> {}, b -> new OpenTerminalPacket(), NetworkHandler::handleOpenTerminal);
		INSTANCE.registerMessage(id++, LocateResultPacket.class, LocateResultPacket::write, LocateResultPacket::read, NetworkHandler::handleLocate);
		INSTANCE.registerMessage(id++, PickFromStoragePacket.class, PickFromStoragePacket::write, PickFromStoragePacket::read, NetworkHandler::handlePick);
		INSTANCE.registerMessage(id++, ToggleRestockPacket.class, (p, b) -> {}, b -> new ToggleRestockPacket(), NetworkHandler::handleRestock);
		// Appended last: the ids of the messages above stay as they are
		INSTANCE.registerMessage(id++, HighlightNetworkPacket.class, HighlightNetworkPacket::write, HighlightNetworkPacket::read, NetworkHandler::handleHighlightNetwork);
	}

	private static void handleData(DataPacket packet, Supplier<NetworkEvent.Context> ctx) {
		NetworkEvent.Context c = ctx.get();
		if(c.getDirection() == NetworkDirection.PLAY_TO_SERVER) {
			ServerPlayer p = c.getSender();
			c.enqueueWork(() -> {
				try {
					if(p != null && p.containerMenu instanceof IDataReceiver r) {
						r.receive(packet.tag());
					}
				} catch (Exception e) {
					StorageMod.LOGGER.warn("Error processing terminal data packet from {}", p.getName().getString(), e);
				}
			});
		} else if(c.getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
			c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> StorageModClient.receiveData(packet.tag())));
		}
		c.setPacketHandled(true);
	}

	private static void handleOpenTerminal(OpenTerminalPacket packet, Supplier<NetworkEvent.Context> ctx) {
		NetworkEvent.Context c = ctx.get();
		ServerPlayer p = c.getSender();
		if(p != null) {
			c.enqueueWork(() -> {
				try {
					int now = p.server.getTickCount();
					Integer last = openTerminalLast.get(p.getUUID());
					if(last != null && now - last < OPEN_TERMINAL_COOLDOWN && now >= last)return;
					openTerminalLast.put(p.getUUID(), now);
					ItemStack t = PlayerInvUtil.findItem(p, i -> i.getItem() instanceof WirelessTerminal e && e.canOpen(i), ItemStack.EMPTY, Function.identity());
					if(!t.isEmpty())
						((WirelessTerminal)t.getItem()).open(p, t);
				} catch (Exception e) {
					StorageMod.LOGGER.warn("Error opening wireless terminal for {}", p.getName().getString(), e);
				}
			});
		}
		c.setPacketHandled(true);
	}

	private static void handleLocate(LocateResultPacket packet, Supplier<NetworkEvent.Context> ctx) {
		NetworkEvent.Context c = ctx.get();
		if(c.getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
			c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> StorageModClient.receiveLocate(packet)));
		}
		c.setPacketHandled(true);
	}

	private static void handlePick(PickFromStoragePacket packet, Supplier<NetworkEvent.Context> ctx) {
		NetworkEvent.Context c = ctx.get();
		ServerPlayer p = c.getSender();
		if(p != null) {
			c.enqueueWork(() -> {
				try {
					BuildersReach.pickFromStorage(p, packet.pos(), BuildersReach.reportedHit(packet.pos(), packet.face(), packet.dx(), packet.dy(), packet.dz()));
				} catch (Exception e) {
					StorageMod.LOGGER.warn("Error picking a block from storage for {}", p.getName().getString(), e);
				}
			});
		}
		c.setPacketHandled(true);
	}

	private static void handleRestock(ToggleRestockPacket packet, Supplier<NetworkEvent.Context> ctx) {
		NetworkEvent.Context c = ctx.get();
		ServerPlayer p = c.getSender();
		if(p != null) {
			c.enqueueWork(() -> {
				try {
					BuildersReach.toggleRestock(p);
				} catch (Exception e) {
					StorageMod.LOGGER.warn("Error toggling restock for {}", p.getName().getString(), e);
				}
			});
		}
		c.setPacketHandled(true);
	}

	private static void handleHighlightNetwork(HighlightNetworkPacket packet, Supplier<NetworkEvent.Context> ctx) {
		NetworkEvent.Context c = ctx.get();
		ServerPlayer p = c.getSender();
		if(p != null) {
			c.enqueueWork(() -> {
				try {
					HighlightNetwork.request(p, packet.pos());
				} catch (Exception e) {
					StorageMod.LOGGER.warn("Error highlighting a network for {}", p.getName().getString(), e);
				}
			});
		}
		c.setPacketHandled(true);
	}

	/** A player left: forget its Open Terminal cooldown. */
	public static void onLogout(UUID player) {
		openTerminalLast.remove(player);
	}

	public static void sendDataToServer(CompoundTag tag) {
		INSTANCE.sendToServer(new DataPacket(tag));
	}

	public static void sendTo(ServerPlayer player, CompoundTag tag) {
		INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new DataPacket(tag));
	}

	/** Item Locator reply. The request travels as a terminal interaction (SlotAction.LOCATE) in the data packet. */
	public static void sendLocateResult(ServerPlayer player, LocateResultPacket packet) {
		INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}

	public static void openTerminal() {
		INSTANCE.sendToServer(new OpenTerminalPacket());
	}

	/** Pick-block found nothing in the inventory. Carries the targeted block and where it was hit; the server recomputes the item from them. */
	public static void pickFromStorage(BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos();
		Vec3 l = hit.getLocation();
		INSTANCE.sendToServer(new PickFromStoragePacket(pos, hit.getDirection().get3DDataValue(), (float) (l.x - pos.getX()), (float) (l.y - pos.getY()), (float) (l.z - pos.getZ())));
	}

	public static void toggleRestock() {
		INSTANCE.sendToServer(new ToggleRestockPacket());
	}

	/** The Highlight Connected Inventories key outside a screen: the block pointed at, or null to use the wireless terminal in hand. */
	public static void highlightNetwork(@Nullable BlockPos pos) {
		INSTANCE.sendToServer(new HighlightNetworkPacket(pos));
	}
}
