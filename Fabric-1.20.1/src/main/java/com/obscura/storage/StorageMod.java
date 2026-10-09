package com.obscura.storage;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.obscura.storage.block.FramedInventoryCableConnectorBlock;
import com.obscura.storage.doctor.DoctorCommand;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.tidy.TidyManager;
import com.obscura.storage.block.InventoryProxyBlock;
import com.obscura.storage.item.ILeftClickListener;
import com.obscura.storage.item.InventoryConfiguratorItem;
import com.obscura.storage.block.PaintedFramedInventoryCableBlock;
import com.obscura.storage.item.WirelessTerminal;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.util.BlockFilterData;
import com.obscura.storage.util.BuildersReach;
import com.obscura.storage.util.ConditionSnapshots;
import com.obscura.storage.util.FrameLabels;
import com.obscura.storage.util.GameObject;
import com.obscura.storage.util.HighlightNetwork;
import com.obscura.storage.util.IDataReceiver;
import com.obscura.storage.util.PlayerInvUtil;

import io.netty.buffer.ByteBufOutputStream;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

public class StorageMod implements ModInitializer {
	// Directly reference a log4j logger.
	public static final Logger LOGGER = LogManager.getLogger();

	public static final String modid = "obscuras_storage";

	/** Minimum ticks between two Open Terminal key requests of a player, each one starts a full terminal sync. */
	private static final int OPEN_TERMINAL_COOLDOWN = 10;
	private static final Map<UUID, Integer> openTerminalLast = new ConcurrentHashMap<>();

	public static GameObject<PaintedFramedInventoryCableBlock> invCablePainted;
	public static GameObject<FramedInventoryCableConnectorBlock> invCableConnectorPainted;
	public static GameObject<InventoryProxyBlock> invProxyPainted;

	public static final Gson gson = new GsonBuilder().create();
	public static ConfigHolder<Config> configHolder = registerConfig();
	private static Config LOADED_CONFIG = configHolder.getConfig();
	public static Config CONFIG = new Config();
	static {
		// A config file from an older version was migrated while loading, write it back in the new format.
		if (LOADED_CONFIG.wasMigrated())configHolder.save();
	}

	public static Set<Block> multiblockInvs;
	public static Set<Block> blockedBlocks;

	public StorageMod() {
	}

	private static ConfigHolder<Config> registerConfig() {
		// A config file written before v5.0.0 (config/toms_storage.json) moves to the new name first
		LegacyConfigFiles.migrate(FabricLoader.getInstance().getConfigDir());
		return AutoConfig.register(Config.class, GsonConfigSerializer::new);
	}

	public static ResourceLocation id(String id) {
		return new ResourceLocation(modid, id);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onInitialize() {
		LOGGER.info("Obscura's Simple Storage setup starting");
		Content.init();

		invCablePainted = Platform.BLOCKS.register("ts.inventory_cable_painted", PaintedFramedInventoryCableBlock::new);
		invCableConnectorPainted = Platform.BLOCKS.register("ts.inventory_cable_connector_painted", FramedInventoryCableConnectorBlock::new);
		invProxyPainted = Platform.BLOCKS.register("ts.inventory_proxy_painted", InventoryProxyBlock::new);

		Content.paintedTile.addBlocks(invCablePainted);
		Content.invCableConnectorTile.addBlocks(invCableConnectorPainted);
		Content.invProxyTile.addBlocks(invProxyPainted);
		Platform.BLOCK_ENTITY.register();

		ItemStorage.SIDED.registerForBlockEntity((be, side) -> InventoryStorage.of(be.getInv(), side), Content.filingCabinetTile.get());
		ItemStorage.SIDED.registerForBlockEntity((be, side) -> be.getItemStorage(side), Content.invInterfaceTile.get());
		// Shulker Dock: 27 slots while a box is docked, 0 otherwise (Fabric resizes the wrapper per lookup)
		ItemStorage.SIDED.registerForBlockEntity((be, side) -> InventoryStorage.of(be.getInv(), side), Content.shulkerDockTile.get());

		ResourceLocation leftClickId = id("left_click_item_on_block");
		AttackBlockCallback.EVENT.register(leftClickId, (player, world, hand, pos, direction) -> {
			ItemStack stack = player.getItemInHand(hand);
			if (stack.getItem() instanceof ILeftClickListener listener) {
				if (listener.onLeftClick(stack, pos, player)) {
					return InteractionResult.SUCCESS;
				}
			}
			return InteractionResult.PASS;
		});
		AttackBlockCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, leftClickId);

		// Runs before the block's use and the item's useOn (Basic Inventory Hopper mode/amount)
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> InventoryConfiguratorItem.onRightClickBlock(player, world, hand, hit.getBlockPos()));

		ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.DATA_C2S, (s, p, h, buf, rp) -> {
			CompoundTag tag = Platform.readNbtTag(buf);
			s.submit(() -> {
				// submit() returns a future that swallows exceptions, so log them here
				try {
					if(p.containerMenu instanceof IDataReceiver) {
						((IDataReceiver)p.containerMenu).receive(tag);
					}
				} catch (Exception e) {
					LOGGER.warn("Error processing terminal data packet from {}", p.getName().getString(), e);
				}
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.OPEN_TERMINAL_C2S, (s, p, h, buf, rp) -> s.submit(() -> {
			try {
				Integer last = openTerminalLast.get(p.getUUID());
				if(last != null && s.getTickCount() - last < OPEN_TERMINAL_COOLDOWN && s.getTickCount() >= last)return;
				openTerminalLast.put(p.getUUID(), s.getTickCount());
				ItemStack t = PlayerInvUtil.findItem(p, i -> i.getItem() instanceof WirelessTerminal e && e.canOpen(i), ItemStack.EMPTY, Function.identity());
				if(!t.isEmpty())
					((WirelessTerminal)t.getItem()).open(p, t);
			} catch (Exception e) {
				LOGGER.warn("Error opening wireless terminal for {}", p.getName().getString(), e);
			}
		}));

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> openTerminalLast.remove(handler.getPlayer().getUUID()));

		// A block filter goes with its block: drop its filter item and forget it when a player breaks the block.
		// (Other ways of removing the block are caught by the next inventory connector scan.)
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
			if(!world.isClientSide)BlockFilterData.get(world).onBlockRemoved(world, pos);
		});

		// Builder's Reach
		ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.PICK_FROM_STORAGE_C2S, (s, p, h, buf, rp) -> {
			BlockPos pos = buf.readBlockPos();
			// The hit is 13 bytes behind the position; a sender without it (an older version) only sends the block
			BlockHitResult hit = buf.readableBytes() >= 1 + 3 * 4 ? BuildersReach.reportedHit(pos, buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readFloat()) : null;
			s.submit(() -> {
				try {
					BuildersReach.pickFromStorage(p, pos, hit);
				} catch (Exception e) {
					LOGGER.warn("Error picking a block from storage for {}", p.getName().getString(), e);
				}
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.TOGGLE_RESTOCK_C2S, (s, p, h, buf, rp) -> s.submit(() -> {
			try {
				BuildersReach.toggleRestock(p);
			} catch (Exception e) {
				LOGGER.warn("Error toggling restock for {}", p.getName().getString(), e);
			}
		}));
		ServerTickEvents.END_SERVER_TICK.register(BuildersReach::tickRestock);

		// Highlight Connected Inventories key: a pointed at block, or none (the wireless terminal in hand)
		ServerPlayNetworking.registerGlobalReceiver(NetworkHandler.HIGHLIGHT_NETWORK_C2S, (s, p, h, buf, rp) -> {
			BlockPos pos = buf.readBoolean() ? buf.readBlockPos() : null;
			s.submit(() -> {
				try {
					HighlightNetwork.request(p, pos);
				} catch (Exception e) {
					LOGGER.warn("Error highlighting a network for {}", p.getName().getString(), e);
				}
			});
		});

		// Frame Labels: glow item frames indexed by the block they hang on (no entity scans), the
		// player clicks on them (action bar feedback), and the Configurator status push
		ServerEntityEvents.ENTITY_LOAD.register(FrameLabels::onEntityLoad);
		ServerEntityEvents.ENTITY_UNLOAD.register(FrameLabels::onEntityUnload);
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			FrameLabels.onFrameInteract(player, entity);
			return InteractionResult.PASS;
		});
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			FrameLabels.onFrameInteract(player, entity);
			return InteractionResult.PASS;
		});
		ServerTickEvents.END_SERVER_TICK.register(FrameLabels::tick);
		// Tidy: the running jobs, a bounded slice of work per network and tick
		ServerTickEvents.END_SERVER_TICK.register(TidyManager::tick);

		ServerLoginNetworking.registerGlobalReceiver(id("config"), (server, handler, understood, buf, sync, respSender) -> {
		});

		ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, sync) -> {
			FriendlyByteBuf packet = PacketByteBufs.create();
			try (OutputStreamWriter writer = new OutputStreamWriter(new ByteBufOutputStream(packet))){
				gson.toJson(LOADED_CONFIG, writer);
			} catch (IOException e) {
				LOGGER.warn("Error sending config sync", e);
			}
			sender.sendPacket(id("config"), packet);
		});

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			CONFIG = LOADED_CONFIG;
			multiblockInvs = null;
			blockedBlocks = null;
		});
		// The Condition Filter caches fuel and smelting recipe item sets, refresh them with the datapacks.
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			if (success)ConditionSnapshots.reload(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> ConditionSnapshots.clear());
		// Nothing of a closed world may survive into the next one
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			NetworkDoctor.clear();
			FrameLabels.clear();
			TidyManager.clear();
		});
		// Network Doctor: /obscuras_storage doctor ...
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> DoctorCommand.register(dispatcher));

		StorageTags.init();

		configHolder.registerSaveListener((a, b) -> {
			multiblockInvs = null;
			blockedBlocks = null;
			return InteractionResult.PASS;
		});
	}
}
