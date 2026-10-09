package com.obscura.storage;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock;
import net.neoforged.neoforge.event.level.BlockEvent.BreakEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import com.obscura.storage.doctor.DoctorCommand;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.inventory.FrameLabels;
import com.obscura.storage.inventory.PlatformInventoryAccess;
import com.obscura.storage.inventory.filter.TagFilter;
import com.obscura.storage.item.ILeftClickListener;
import com.obscura.storage.migration.LegacyBlockFilters;
import com.obscura.storage.migration.LegacyItemComponents;
import com.obscura.storage.platform.FabricDataCompat;
import com.obscura.storage.tidy.TidyManager;
import com.obscura.storage.util.BuildersReach;
import com.obscura.storage.util.WorldStates;

/** Game bus events, the NeoForge counterparts of the Fabric callbacks registered in StorageMod. */
@EventBusSubscriber(modid = StorageMod.modid)
public class ServerEvents {

	/** A broken block drops the filter item of its Inventory Configurator filter (Fabric: PlayerBlockBreakEvents.AFTER). */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onBlockBreak(BreakEvent event) {
		if (event.isCanceled() || !(event.getLevel() instanceof Level level) || level.isClientSide)return;
		PlatformInventoryAccess.removeBlockFilterAt(level, event.getPos());
	}

	@SubscribeEvent(priority = EventPriority.LOW)
	public static void onLeftClick(LeftClickBlock event) {
		if (event.getEntity().level().isClientSide)return;
		ItemStack is = event.getEntity().getItemInHand(InteractionHand.MAIN_HAND);
		if (is.getItem() instanceof ILeftClickListener l) {
			if (l.onLeftClick(is, event.getPos(), event.getEntity()))
				event.setCanceled(true);
		}
	}

	/** A world server config override written under the old mod id, see LegacyConfigFiles. */
	@SubscribeEvent
	public static void onServerAboutToStart(ServerAboutToStartEvent event) {
		LegacyConfigFiles.migrateWorld(event.getServer());
		// Worlds upgraded from Minecraft 1.20.1: before any world or player data is read
		LegacyItemComponents.setRegistries(event.getServer().registryAccess());
		LegacyBlockFilters.load(event.getServer().getWorldPath(LevelResource.ROOT).resolve("data"));
	}

	@SubscribeEvent
	public static void onServerStarting(ServerStartingEvent event) {
		// Every mod's blocks are registered by now
		Config.get().invalidateBlockedBlocks();
	}

	/** Nothing of a closed world may survive into the next one (integrated server: same JVM). */
	@SubscribeEvent
	public static void onServerStopping(ServerStoppingEvent event) {
		WorldStates.clearWorldStates();
		NetworkDoctor.clear();
		FrameLabels.clear();
		TidyManager.clear();
	}

	/** After every chunk was saved: the 1.20.1 filters that were applied leave their file. */
	@SubscribeEvent
	public static void onServerStopped(ServerStoppedEvent event) {
		LegacyBlockFilters.save();
		LegacyItemComponents.setRegistries(null);
	}

	/** Network Doctor: /obscuras_storage doctor ... (Fabric: CommandRegistrationCallback). */
	@SubscribeEvent
	public static void onRegisterCommands(RegisterCommandsEvent event) {
		DoctorCommand.register(event.getDispatcher());
	}

	/** Tag contents change in place on /reload; tag filters cache their resolved item sets. */
	@SubscribeEvent
	public static void onTagsUpdated(TagsUpdatedEvent event) {
		TagFilter.onTagsReloaded();
	}

	/** Builder's Reach: Restock, end of every server tick. */
	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event) {
		BuildersReach.tickRestock(event.getServer());
		FrameLabels.tick(event.getServer());
		// Tidy: the running jobs, a bounded slice of work per network and tick
		TidyManager.tick(event.getServer());
	}

	/** Frame Labels: glow item frames are indexed by the block they hang on (Fabric: ServerEntityEvents.ENTITY_LOAD). */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onEntityJoin(EntityJoinLevelEvent event) {
		FrameLabels.onEntityLoad(event.getEntity(), event.getLevel());
	}

	/** Also fired when a frame is broken or its chunk unloads (Fabric: ServerEntityEvents.ENTITY_UNLOAD). */
	@SubscribeEvent
	public static void onEntityLeave(EntityLeaveLevelEvent event) {
		FrameLabels.onEntityUnload(event.getEntity(), event.getLevel());
	}

	/** A player puts an item into a glow frame or rotates it (Fabric: UseEntityCallback). */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		FrameLabels.onFrameInteract(event.getEntity(), event.getTarget());
	}

	/** A player knocks the item out of a glow frame (Fabric: AttackEntityCallback). */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onAttackEntity(AttackEntityEvent event) {
		FrameLabels.onFrameInteract(event.getEntity(), event.getTarget());
	}

	/** Fabric-compatible block filter data for block entities that are not the mod's own, see FabricDataCompat. */
	@SubscribeEvent
	public static void onChunkSave(ChunkDataEvent.Save event) {
		FabricDataCompat.onChunkSave(event.getData());
	}

	/** Fired on the chunk IO thread; only edits the chunk's NBT. */
	@SubscribeEvent
	public static void onChunkLoad(ChunkDataEvent.Load event) {
		FabricDataCompat.onChunkLoad(event.getData());
		// Inventory Configurator filters of a world upgraded from Minecraft 1.20.1, see LegacyBlockFilters
		if (event.getLevel() instanceof Level level)
			LegacyBlockFilters.inject(level.dimension(), event.getChunk().getPos(), event.getData(), FabricDataCompat.LEGACY_TARGET);
	}
}
