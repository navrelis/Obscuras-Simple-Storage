package com.obscura.storage;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Set;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.obscura.storage.api.MultiblockInventoryAPI;
import com.obscura.storage.doctor.DoctorCommand;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.tidy.TidyManager;
import com.obscura.storage.inventory.BlockFilterComponent;
import com.obscura.storage.inventory.FrameLabels;
import com.obscura.storage.inventory.PlatformItemHandler;
import com.obscura.storage.inventory.VanillaMultiblockInventories;
import com.obscura.storage.inventory.filter.TagFilter;
import com.obscura.storage.item.ILeftClickListener;
import com.obscura.storage.item.WirelessTerminal;
import com.obscura.storage.migration.LegacyBlockFilters;
import com.obscura.storage.migration.LegacyItemComponents;
import com.obscura.storage.network.DataPacket;
import com.obscura.storage.network.HighlightNetworkPacket;
import com.obscura.storage.network.LocateResultPacket;
import com.obscura.storage.network.OpenTerminalPacket;
import com.obscura.storage.network.PickFromStoragePacket;
import com.obscura.storage.network.ToggleRestockPacket;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.platform.PlatformItem;
import com.obscura.storage.util.BuildersReach;
import com.obscura.storage.util.HighlightNetwork;
import com.obscura.storage.util.IDataReceiver;
import com.obscura.storage.util.PlayerInvUtil;
import com.obscura.storage.util.TickerUtil.OnLoadListener;
import com.obscura.storage.util.WorldStates;

import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

public class StorageMod implements ModInitializer {
	// Directly reference a log4j logger.
	public static final Logger LOGGER = LogManager.getLogger();

	public static final String modid = "obscuras_storage";

	public static final Gson gson = new GsonBuilder().create();
	public static ConfigHolder<Config> configHolder = registerConfig();
	private static Config LOADED_CONFIG = configHolder.getConfig();
	public static volatile Config CONFIG = new Config();
	static {
		// A config file from an older version was migrated while loading, write it back in the new format.
		if (LOADED_CONFIG.wasMigrated())configHolder.save();
	}

	public static boolean polymorph;
	public static volatile Set<Block> blockedBlocks;

	public StorageMod() {
	}

	private static ConfigHolder<Config> registerConfig() {
		// A config file written before v5.0.0 (config/toms_storage.json) moves to the new name first
		LegacyConfigFiles.migrate(FabricLoader.getInstance().getConfigDir());
		return AutoConfig.register(Config.class, GsonConfigSerializer::new);
	}

	public static ResourceLocation id(String id) {
		return ResourceLocation.tryBuild(modid, id);
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Obscura's Simple Storage Setup starting");
		Content.init();

		Platform.DATA_COMPONENT_TYPES.runRegistration();
		Platform.BLOCKS.runRegistration();
		Platform.ITEMS.runRegistration();
		Platform.BLOCK_ENTITY.runRegistration();
		Platform.MENU_TYPE.runRegistration();

		PayloadTypeRegistry.playS2C().register(DataPacket.ID, DataPacket.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(DataPacket.ID, DataPacket.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(DataPacket.ID, (p, c) -> {
			if(c.player().isSpectator()) return;
			if(c.player().containerMenu instanceof IDataReceiver d) {
				d.receive(p.tag());
			}
		});

		// Item Locator reply. The request travels as a terminal interaction (SlotAction.LOCATE) in DataPacket.
		PayloadTypeRegistry.playS2C().register(LocateResultPacket.ID, LocateResultPacket.STREAM_CODEC);

		PayloadTypeRegistry.playC2S().register(OpenTerminalPacket.ID, OpenTerminalPacket.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(OpenTerminalPacket.ID, (p, c) -> {
			ItemStack t = PlayerInvUtil.findItem(c.player(), i -> i.getItem() instanceof WirelessTerminal e && e.canOpen(i), ItemStack.EMPTY, Function.identity());
			if(!t.isEmpty())
				((WirelessTerminal)t.getItem()).open(c.player(), t);
		});

		// Builder's Reach
		PayloadTypeRegistry.playC2S().register(PickFromStoragePacket.ID, PickFromStoragePacket.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PickFromStoragePacket.ID, (p, c) -> BuildersReach.pickFromStorage(c.player(), p.pos(), p.face(), p.offset()));
		PayloadTypeRegistry.playC2S().register(ToggleRestockPacket.ID, ToggleRestockPacket.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ToggleRestockPacket.ID, (p, c) -> BuildersReach.toggleRestock(c.player()));
		ServerTickEvents.END_SERVER_TICK.register(BuildersReach::tickRestock);

		// Highlight Connected Inventories key: a pointed at block, or none (the wireless terminal in hand)
		PayloadTypeRegistry.playC2S().register(HighlightNetworkPacket.ID, HighlightNetworkPacket.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(HighlightNetworkPacket.ID, (p, c) -> HighlightNetwork.request(c.player(), p.pos()));

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
			FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
			try (OutputStreamWriter writer = new OutputStreamWriter(new ByteBufOutputStream(packet))){
				gson.toJson(LOADED_CONFIG, writer);
			} catch (IOException e) {
				LOGGER.warn("Error sending config sync", e);
			}
			sender.sendPacket(id("config"), packet);
		});

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			CONFIG = LOADED_CONFIG;
			blockedBlocks = null;
			// Worlds upgraded from Minecraft 1.20.1: before any world or player data is read
			LegacyItemComponents.setRegistries(server.registryAccess());
			LegacyBlockFilters.load(server.getWorldPath(LevelResource.ROOT).resolve("data"));
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			WorldStates.clearWorldStates();
			NetworkDoctor.clear();
			FrameLabels.clear();
			TidyManager.clear();
		});
		// After every chunk was saved: the 1.20.1 filters that were applied leave their file
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			LegacyBlockFilters.save();
			LegacyItemComponents.setRegistries(null);
		});
		// Network Doctor: /obscuras_storage doctor ...
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> DoctorCommand.register(dispatcher));
		// Tag contents change in place on /reload; tag filters cache their resolved item sets.
		CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> TagFilter.onTagsReloaded());

		ItemStorage.SIDED.registerForBlockEntity((be, side) -> PlatformItemHandler.of(be), Content.connectorBE.get());
		ItemStorage.SIDED.registerForBlockEntity((be, side) -> PlatformItemHandler.of(be), Content.invInterfaceBE.get());
		ItemStorage.SIDED.registerForBlockEntity((be, side) -> InventoryStorage.of(be.getInv(), side), Content.filingCabinetBE.get());
		// Shulker Dock: 27 slots while a box is docked, 0 otherwise (Fabric resizes the wrapper per lookup)
		ItemStorage.SIDED.registerForBlockEntity((be, side) -> InventoryStorage.of(be.getInv(), side), Content.shulkerDockBE.get());
		ItemStorage.SIDED.registerForBlockEntity((be, side) -> PlatformItemHandler.of(be), Content.invProxyBE.get());

		ResourceLocation at = ResourceLocation.tryBuild(modid, "left_click_item_on_block");
		ResourceLocation rl = ResourceLocation.tryBuild(modid, "use_item_first");
		AttackBlockCallback.EVENT.register(at, (player, world, hand, pos, direction) -> {
			ItemStack is = player.getItemInHand(hand);
			if (is.getItem() instanceof ILeftClickListener l)
				if (l.onLeftClick(is, pos, player))
					return InteractionResult.SUCCESS;
			return InteractionResult.PASS;
		});
		AttackBlockCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, at);

		UseBlockCallback.EVENT.register(rl, (player, world, hand, hitResult) -> {
			BlockPos pos = hitResult.getBlockPos();
			ItemStack is = player.getItemInHand(hand);
			if (is.getItem() instanceof PlatformItem l)
				return l.onRightClick(player, is, pos, hand);
			return InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, rl);

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
			if (entity != null) {
				BlockFilterComponent comp = StorageModComponents.BLOCK_FILTER.getNullable(entity);
				if (comp != null) {
					comp.remove(world, pos);
				}
			}
		});

		ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, w) -> {
			if (be instanceof OnLoadListener l)
				w.getServer().tell(new TickTask(0, () -> {
					if (!be.isRemoved()) l.onLoad();
				}));
		});

		polymorph = FabricLoader.getInstance().isModLoaded("polymorph");

		configHolder.registerSaveListener((a, b) -> {
			blockedBlocks = null;
			return InteractionResult.PASS;
		});

		MultiblockInventoryAPI.EVENT.register(VanillaMultiblockInventories::checkChest);
	}
}
