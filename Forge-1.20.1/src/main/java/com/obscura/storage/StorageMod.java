package com.obscura.storage;

import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import com.obscura.storage.block.FramedInventoryCableConnectorBlock;
import com.obscura.storage.doctor.DoctorCommand;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.tidy.TidyManager;
import com.obscura.storage.block.InventoryProxyBlock;
import com.obscura.storage.block.PaintedFramedInventoryCableBlock;
import com.obscura.storage.item.ILeftClickListener;
import com.obscura.storage.item.InventoryConfiguratorItem;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.platform.LegacyRegistryAliases;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.platform.ShulkerDockCapability;
import com.obscura.storage.util.BlockFilterData;
import com.obscura.storage.util.BuildersReach;
import com.obscura.storage.util.ConditionSnapshots;
import com.obscura.storage.util.FrameLabels;
import com.obscura.storage.util.GameObject;

@Mod(StorageMod.modid)
public class StorageMod {
	// Directly reference a log4j logger.
	public static final Logger LOGGER = LogManager.getLogger();

	public static final String modid = "obscuras_storage";

	/** Same object as {@link Config#get()}; the values are filled in from the Forge config files. */
	public static final Config CONFIG = Config.get();

	// Painted variants of the framed blocks, same registry ids as on Fabric so worlds can move between the loaders
	public static GameObject<PaintedFramedInventoryCableBlock> invCablePainted;
	public static GameObject<FramedInventoryCableConnectorBlock> invCableConnectorPainted;
	public static GameObject<InventoryProxyBlock> invProxyPainted;

	public static Set<Block> multiblockInvs;
	public static Set<Block> blockedBlocks;

	public StorageMod() {
		IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();

		// Config files written before v5.0.0 (old mod id in the file name) move to the new names first
		LegacyConfigFiles.migrateAtStartup();
		ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.commonSpec);
		ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.serverSpec);
		bus.addListener(Config::onLoad);
		bus.addListener(Config::onReload);

		Content.init();
		invCablePainted = Platform.BLOCKS.register("ts.inventory_cable_painted", PaintedFramedInventoryCableBlock::new);
		invCableConnectorPainted = Platform.BLOCKS.register("ts.inventory_cable_connector_painted", FramedInventoryCableConnectorBlock::new);
		invProxyPainted = Platform.BLOCKS.register("ts.inventory_proxy_painted", InventoryProxyBlock::new);

		Content.paintedTile.addBlocks(invCablePainted);
		Content.invCableConnectorTile.addBlocks(invCableConnectorPainted);
		Content.invProxyTile.addBlocks(invProxyPainted);
		Platform.register(bus);
		StorageTags.init();

		bus.addListener(this::setup);

		IEventBus forge = MinecraftForge.EVENT_BUS;
		forge.addListener(StorageMod::onLeftClickBlock);
		forge.addListener(StorageMod::onRightClickBlock);
		forge.addListener(EventPriority.LOWEST, StorageMod::onBlockBreak);
		forge.addListener(StorageMod::onServerTick);
		forge.addListener(StorageMod::onServerAboutToStart);
		forge.addListener(StorageMod::onServerStopped);
		forge.addListener(StorageMod::onServerStopping);
		forge.addListener(StorageMod::onRegisterCommands);
		// Worlds saved before v5.0.0: their registry id maps name the entries toms_storage:*
		forge.addListener(LegacyRegistryAliases::onMissingMappings);
		forge.addListener(StorageMod::onDatapackSync);
		forge.addListener(StorageMod::onLogout);
		// Shulker Dock item handler (Fabric: ItemStorage.SIDED)
		forge.addGenericListener(BlockEntity.class, ShulkerDockCapability::attach);
		// Frame Labels: glow item frames indexed by the block they hang on (no entity scans), the
		// player clicks on them (action bar feedback); the Configurator status push runs in onServerTick
		forge.addListener(EventPriority.LOWEST, StorageMod::onEntityJoin);
		forge.addListener(StorageMod::onEntityLeave);
		forge.addListener(EventPriority.LOWEST, StorageMod::onEntityInteract);
		forge.addListener(EventPriority.LOWEST, StorageMod::onAttackEntity);

		// Not DistExecutor.safeRunWhenOn: in a dev environment it resolves the method reference on every side, which loads the client class on a dedicated server
		if (FMLEnvironment.dist == Dist.CLIENT)StorageModClient.preInit();
	}

	public static ResourceLocation id(String id) {
		return new ResourceLocation(modid, id);
	}

	private void setup(final FMLCommonSetupEvent event) {
		LOGGER.info("Obscura's Simple Storage setup starting");
		NetworkHandler.init();
	}

	/** Inventory Configurator left click, like the Fabric AttackBlockCallback: runs on both sides at the start of a click. */
	private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
		if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START)return;
		ItemStack stack = event.getItemStack();
		if (stack.getItem() instanceof ILeftClickListener listener) {
			if (listener.onLeftClick(stack, event.getPos(), event.getEntity())) {
				event.setCanceled(true);
			}
		}
	}

	/** Runs before the block's use and the item's useOn (Basic Inventory Hopper mode/amount). */
	private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		InteractionResult r = InventoryConfiguratorItem.onRightClickBlock(event.getEntity(), event.getLevel(), event.getHand(), event.getPos());
		if (r != InteractionResult.PASS) {
			event.setCanceled(true);
			event.setCancellationResult(r);
		}
	}

	/**
	 * A block filter goes with its block: drop its filter item and forget it when a player breaks the block.
	 * Lowest priority and not for cancelled breaks, so it only runs when the block really goes.
	 * (Other ways of removing the block are caught by the next inventory connector scan.)
	 */
	private static void onBlockBreak(BlockEvent.BreakEvent event) {
		if (event.getLevel() instanceof Level level && !level.isClientSide) {
			BlockFilterData.get(level).onBlockRemoved(level, event.getPos());
		}
	}

	private static void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase == TickEvent.Phase.END) {
			BuildersReach.tickRestock(event.getServer());
			FrameLabels.tick(event.getServer());
			// Tidy: the running jobs, a bounded slice of work per network and tick
			TidyManager.tick(event.getServer());
		}
	}

	/** Frame Labels: glow item frames are indexed by the block they hang on (Fabric: ServerEntityEvents.ENTITY_LOAD). Not for cancelled joins. */
	private static void onEntityJoin(EntityJoinLevelEvent event) {
		FrameLabels.onEntityLoad(event.getEntity(), event.getLevel());
	}

	/** Also fired when a frame is broken or its chunk unloads (Fabric: ServerEntityEvents.ENTITY_UNLOAD). */
	private static void onEntityLeave(EntityLeaveLevelEvent event) {
		FrameLabels.onEntityUnload(event.getEntity(), event.getLevel());
	}

	/** A player puts an item into a glow frame or rotates it (Fabric: UseEntityCallback). */
	private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		FrameLabels.onFrameInteract(event.getEntity(), event.getTarget());
	}

	/** A player knocks the item out of a glow frame (Fabric: AttackEntityCallback). */
	private static void onAttackEntity(AttackEntityEvent event) {
		FrameLabels.onFrameInteract(event.getEntity(), event.getTarget());
	}

	private static void onServerAboutToStart(ServerAboutToStartEvent event) {
		// A world server config override written under the old mod id, see LegacyConfigFiles
		LegacyConfigFiles.migrateWorld(event.getServer());
		multiblockInvs = null;
		blockedBlocks = null;
	}

	private static void onServerStopped(ServerStoppedEvent event) {
		ConditionSnapshots.clear();
	}

	/** Nothing of a closed world may survive into the next one. */
	private static void onServerStopping(ServerStoppingEvent event) {
		NetworkDoctor.clear();
		FrameLabels.clear();
		TidyManager.clear();
	}

	/** Network Doctor: /obscuras_storage doctor ... (Fabric: CommandRegistrationCallback). */
	private static void onRegisterCommands(RegisterCommandsEvent event) {
		DoctorCommand.register(event.getDispatcher());
	}

	/** After /reload (no player given): the Condition Filter caches fuel and smelting recipe item sets, refresh them with the datapacks. */
	private static void onDatapackSync(OnDatapackSyncEvent event) {
		if (event.getPlayer() == null)ConditionSnapshots.reload(event.getPlayerList().getServer());
	}

	private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		NetworkHandler.onLogout(event.getEntity().getUUID());
	}
}
