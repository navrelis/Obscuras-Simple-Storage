package com.obscura.storage;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.common.ModConfigSpec.DoubleValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;

/**
 * NeoForge twin of the Fabric (Cloth AutoConfig) config. Same Java field names, defaults and limits as
 * the Fabric {@code Config}, so the shared code reads {@code Config.get().x} on both loaders.
 *
 * <p>The values live in two files: the SERVER config ({@code config/obscuras_storage-server.toml}, or a
 * per-world copy in {@code saves/<world>/serverconfig/}; synced to clients) and the COMMON config
 * ({@code config/obscuras_storage-common.toml}) for the connector block lists. The TOML keys never
 * changed, so older config files keep working: {@link LegacyConfigFiles} moves files written under the
 * old mod id to the new names, and {@link #migrate} moves values that still equal an old default to the
 * new default.
 */
public class Config {
	private static final Config INSTANCE = new Config();

	/** Config layout version, see {@link #migrate}. Stored in the SERVER config. */
	public static final int CURRENT_VERSION = 2;

	// Java defaults match the spec defaults: shared code may read them before a world (and with it the
	// SERVER config) is loaded, e.g. item tooltips in the main menu.
	public boolean onlyTrims = false, runMultithreaded = true;
	public int invConnectorScanRange = 16;
	public int wirelessRange = 32;
	/** Old NeoForge field name: invConnectorCableRange (TOML key invCableConnectorMaxScanSize). */
	public int invConnectorMaxCables = 2048;
	public int advWirelessRange = 128;
	public int basicHopperCooldown = 8;
	/** Ejector, Disposal and Exporter: ticks between two operations. */
	public int filterBlockCooldown = 8;
	public int wirelessTermBeaconLvl = 1, wirelessTermBeaconLvlCrossDim = 3;
	public int invLinkBeaconLvl = 0, invLinkBeaconRange = 4096, invLinkBeaconLvlSameDim = 1, invLinkBeaconLvlCrossDim = 3;
	/** Pick-block (middle click) pulls a block missing from the inventory out of storage through a carried wireless terminal. */
	public boolean buildersReachEnabled = true;
	/** The Advanced Wireless Terminal's Restock toggle keeps the held block stack topped up from storage. */
	public boolean restockEnabled = true;
	/** Alt + click in a terminal highlights the inventories that hold an item; the Highlight Connected Inventories button and key outline a whole network. */
	public boolean itemLocatorEnabled = true;
	/** Frame Labels: a glow item frame holding an item on a network inventory makes the network insert only that item there, and fill it first. */
	public boolean frameLabelsEnabled = true;
	/** Network Doctor: at most this many inventories per network, the rest is skipped ("over cap"). 0 = no cap. */
	public int doctorMaxInventories = 0;
	/** Network Doctor: log a warning when a network averages at least this many ms per tick. 0 = off. */
	public double doctorWarnMs = 5.0;
	/** Network Doctor: log a warning when a network has more inventories than this. 0 = off. */
	public int doctorWarnInventories = 0;
	/** Tidy: the terminal button that merges partial stacks and moves stray items into their home inventory. */
	public boolean tidyEnabled = true;
	/** Tidy: stack moves per tick and network. */
	public int tidyMovesPerTick = 8;
	/** Tidy: a run stops after this many seconds (planning included); the moves made stay. */
	public int tidyMaxSeconds = 30;
	/** Tidy: seconds after a run before the network can be tidied again. */
	public int tidyCooldownSeconds = 30;
	/** Tidy: only operators (permission level 2) may use the button. */
	public boolean tidyOpsOnly = false;
	/** Tidy: also rearrange the contents of shulker boxes in Shulker Docks. */
	public boolean tidyIncludeDocks = false;

	private volatile List<String> blockedMods = List.of();
	private volatile List<String> blockedBlockIds = List.of("create:belt");
	/** Resolved lazily: the COMMON config loads while other mods are still registering their blocks. */
	private volatile Set<Block> blockedBlocks;

	public static Config get() {
		return INSTANCE;
	}

	/** A config with every option at its default, for the Network Doctor's config listing. */
	public static Config defaults() {
		return new Config();
	}

	public static class Server {
		public IntValue configVersion;
		public IntValue invConnectorScanRange;
		public IntValue invConnectorMaxCables;
		public IntValue wirelessRange;
		public BooleanValue onlyTrimsConnect, runMultithreaded;
		public IntValue advWirelessRange;
		public IntValue wirelessTermBeaconLvl, wirelessTermBeaconLvlCrossDim;
		public IntValue invLinkBeaconLvl, invLinkBeaconRange, invLinkBeaconLvlSameDim, invLinkBeaconLvlCrossDim;
		public IntValue basicHopperCooldown;
		public IntValue filterBlockCooldown;
		public BooleanValue buildersReachEnabled, restockEnabled, itemLocatorEnabled, frameLabelsEnabled;
		public IntValue doctorMaxInventories, doctorWarnInventories;
		public DoubleValue doctorWarnMs;
		public BooleanValue tidyEnabled, tidyOpsOnly, tidyIncludeDocks;
		public IntValue tidyMovesPerTick, tidyMaxSeconds, tidyCooldownSeconds;

		private Server(ModConfigSpec.Builder builder) {
			// Default 0: a config file written before versioning existed has no such key, NeoForge fills
			// it in with this default, and migrate() then brings the file to CURRENT_VERSION. A brand new
			// file goes through the same (no-op) migration.
			configVersion = builder.comment("Config layout version, do not change. Used to move old default values to the new defaults.").
					defineInRange("configVersion", 0, 0, Integer.MAX_VALUE);

			invConnectorScanRange = builder.comment("Inventory Connector Range").
					translation("config.obscuras_storage.inventory_connector_range").
					defineInRange("inventoryConnectorRange", 16, 1, 64);

			onlyTrimsConnect = builder.comment("Only Allow Trims to Connect Inventories").
					translation("config.obscuras_storage.only_trims_connect").
					define("onlyTrimsConnect", false);

			invConnectorMaxCables = builder.comment("Inventory Cable Connector Maximum number of cables").
					translation("config.obscuras_storage.inv_cable_connector_max_scan").
					defineInRange("invCableConnectorMaxScanSize", 2048, 1, 16384);

			wirelessRange = builder.comment("Wireless terminal reach").
					translation("config.obscuras_storage.wireless_reach").
					defineInRange("wirelessReach", 32, 4, 256);

			advWirelessRange = builder.comment("Advanced wireless terminal reach").
					translation("config.obscuras_storage.adv_wireless_range").
					defineInRange("advWirelessRange", 128, 16, 1024);

			wirelessTermBeaconLvl = builder.comment("Adv Wireless terminal required beacon level for unlimited range in the same dimension",
					"Value of 0 only requires a single beacon block nearby",
					"Value of -1 disables this feature entirely").
					translation("config.obscuras_storage.adv_wireless_beacon_lvl").
					defineInRange("wirelessTermBeaconLvl", 1, -1, 4);

			wirelessTermBeaconLvlCrossDim = builder.comment("Adv Wireless terminal required beacon level for cross dimensional access",
					"Value of 0 only requires a single beacon block nearby",
					"Value of -1 disables this feature entirely").
					translation("config.obscuras_storage.adv_wireless_beacon_lvl_dim").
					defineInRange("wirelessTermBeaconLvlDim", 3, -1, 4);

			invLinkBeaconLvl = builder.comment("Inventory Cable Connector required beacon level for inventory linking",
					"Value of 0 only requires a single beacon block",
					"Value of -1 disables this feature entirely").
					translation("config.obscuras_storage.inv_link_beacon_lvl").
					defineInRange("invLinkBeaconLvl", 0, -1, 4);

			invLinkBeaconRange = builder.comment("Inventory Linking range (blocks, same dimension) without a strong enough beacon").
					translation("config.obscuras_storage.inv_link_beacon_range").
					defineInRange("invLinkBeaconRange", 4096, 1, 65536);

			invLinkBeaconLvlSameDim = builder.comment("Inventory Cable Connector required beacon level for same dimension access with unlimited range",
					"Value of 0 only requires a single beacon block",
					"Value of -1 disables this feature entirely").
					translation("config.obscuras_storage.inv_link_beacon_lvl_same_dim").
					defineInRange("invLinkBeaconLvlSameDim", 1, -1, 4);

			invLinkBeaconLvlCrossDim = builder.comment("Inventory Cable Connector required beacon level for cross dimensional access",
					"Value of 0 only requires a single beacon block",
					"Value of -1 disables this feature entirely").
					translation("config.obscuras_storage.inv_link_beacon_lvl_dim").
					defineInRange("invLinkBeaconLvlCrossDim", 3, -1, 4);

			runMultithreaded = builder.comment("Use multithreading to improve performance").
					translation("config.obscuras_storage.run_multithreaded").
					define("runMultithreaded", true);

			basicHopperCooldown = builder.comment("Base tick cooldown for Basic Inventory Hopper item transfer").
					translation("config.obscuras_storage.basic_hopper_cooldown").
					defineInRange("basicHopperCooldown", 8, 1, 200);

			filterBlockCooldown = builder.comment("Ticks between two operations of an Ejector, Disposal or Exporter",
					"An Ejector in Pulse mode drops on every redstone pulse instead").
					translation("config.obscuras_storage.filter_block_cooldown").
					defineInRange("filterBlockCooldown", 8, 1, 200);

			buildersReachEnabled = builder.comment("Builder's Reach: pick-block (middle click) pulls a block missing from the inventory out of storage through a carried wireless terminal").
					translation("text.autoconfig.obscuras_storage.option.buildersReachEnabled").
					define("buildersReachEnabled", true);

			restockEnabled = builder.comment("The Advanced Wireless Terminal's Restock toggle keeps the held block stack topped up from storage").
					translation("text.autoconfig.obscuras_storage.option.restockEnabled").
					define("restockEnabled", true);

			itemLocatorEnabled = builder.comment("Item Locator: Alt + click in a terminal highlights the inventories that hold an item. The Highlight Connected Inventories button and key outline a whole network").
					translation("text.autoconfig.obscuras_storage.option.itemLocatorEnabled").
					define("itemLocatorEnabled", true);

			frameLabelsEnabled = builder.comment("Frame Labels: a glow item frame holding an item on a network inventory makes the network insert only that item there, and fill it first").
					translation("text.autoconfig.obscuras_storage.option.frameLabelsEnabled").
					define("frameLabelsEnabled", true);

			doctorMaxInventories = builder.comment("Network Doctor: at most this many inventories per storage network, the rest is skipped (listed as \"over cap\" by /obscuras_storage doctor at)",
					"Value of 0 means no cap").
					translation("text.autoconfig.obscuras_storage.option.doctorMaxInventories").
					defineInRange("doctorMaxInventories", 0, 0, 1000000);

			doctorWarnMs = builder.comment("Network Doctor: log a warning (at most once per network every 10 minutes) when a storage network averages at least this many milliseconds per tick",
					"Value of 0 disables the warning").
					translation("text.autoconfig.obscuras_storage.option.doctorWarnMs").
					defineInRange("doctorWarnMs", 5.0, 0.0, 1000.0);

			doctorWarnInventories = builder.comment("Network Doctor: log a warning (at most once per network every 10 minutes) when a storage network has more inventories than this",
					"Value of 0 disables the warning").
					translation("text.autoconfig.obscuras_storage.option.doctorWarnInventories").
					defineInRange("doctorWarnInventories", 0, 0, 1000000);

			tidyEnabled = builder.comment("Tidy: the terminal button that merges partial stacks and moves stray items into their home inventory").
					translation("text.autoconfig.obscuras_storage.option.tidyEnabled").
					define("tidyEnabled", true);

			tidyMovesPerTick = builder.comment("Tidy: stack moves per tick and network").
					translation("text.autoconfig.obscuras_storage.option.tidyMovesPerTick").
					defineInRange("tidyMovesPerTick", 8, 1, 64);

			tidyMaxSeconds = builder.comment("Tidy: a run stops after this many seconds (planning included), the moves made stay").
					translation("text.autoconfig.obscuras_storage.option.tidyMaxSeconds").
					defineInRange("tidyMaxSeconds", 30, 5, 600);

			tidyCooldownSeconds = builder.comment("Tidy: seconds after a run before the same network can be tidied again").
					translation("text.autoconfig.obscuras_storage.option.tidyCooldownSeconds").
					defineInRange("tidyCooldownSeconds", 30, 0, 3600);

			tidyOpsOnly = builder.comment("Tidy: only operators (permission level 2) may use the button").
					translation("text.autoconfig.obscuras_storage.option.tidyOpsOnly").
					define("tidyOpsOnly", false);

			tidyIncludeDocks = builder.comment("Tidy: also rearrange the contents of shulker boxes in Shulker Docks").
					translation("text.autoconfig.obscuras_storage.option.tidyIncludeDocks").
					define("tidyIncludeDocks", false);
		}
	}

	public static class Common {
		public ConfigValue<List<? extends String>> blockedMods;
		public ConfigValue<List<? extends String>> blockedBlocks;

		public Common(ModConfigSpec.Builder builder) {
			builder.comment("IMPORTANT NOTICE:",
					"THIS IS ONLY THE COMMON CONFIG. It does not contain all the values adjustable for Obscura's Simple Storage",
					"The other settings are in obscuras_storage-server.toml",
					"A copy of that file in 'saves/<world name>/serverconfig' overrides it for that world only.",
					"The server configuration is synced to clients when playing multiplayer.").
			define("importantInfo", true);

			blockedMods = builder.comment("List of mod ids whose blocks is ignored by the inventory connector").
					translation("config.obscuras_storage.inv_blocked_mods").
					defineListAllowEmpty("blockedMods", List.of(), () -> "", s -> s instanceof String);

			blockedBlocks = builder.comment("List of block ids ignored by the inventory connector").
					translation("config.obscuras_storage.inv_blocked_blocks").
					defineListAllowEmpty("blockedBlocks", List.of("create:belt"), () -> "", s -> s instanceof String);
		}
	}

	static final ModConfigSpec commonSpec;
	public static final Common COMMON;
	static {
		final Pair<Common, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(Common::new);
		commonSpec = specPair.getRight();
		COMMON = specPair.getLeft();
	}

	static final ModConfigSpec serverSpec;
	public static final Server SERVER;
	static {
		final Pair<Server, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(Server::new);
		serverSpec = specPair.getRight();
		SERVER = specPair.getLeft();
	}

	/**
	 * Moves a SERVER config written by an older version (before v4.0.0) to
	 * the current defaults. A value is only replaced when it still equals the old default, so values the
	 * owner changed are kept. Same steps as the Fabric {@code Config#migrate}. Idempotent.
	 *
	 * @return whether anything was written (the caller then saves the file)
	 */
	static boolean migrate(Server s) {
		if (s.configVersion.get() >= CURRENT_VERSION)return false;
		if (s.wirelessRange.get() == 16)s.wirelessRange.set(32);
		if (s.advWirelessRange.get() == 64)s.advWirelessRange.set(128);
		if (s.wirelessTermBeaconLvlCrossDim.get() == 4)s.wirelessTermBeaconLvlCrossDim.set(3);
		if (s.invLinkBeaconLvlCrossDim.get() == 2)s.invLinkBeaconLvlCrossDim.set(3);
		if (s.basicHopperCooldown.get() == 10)s.basicHopperCooldown.set(8);
		s.configVersion.set(CURRENT_VERSION);
		return true;
	}

	private void load(ModConfig modConfig, boolean initialLoad) {
		if(modConfig.getType() == Type.SERVER) {
			// Only the owning side migrates: a client receiving the synced config of a remote server gets
			// the values that server already migrated (its file holds CURRENT_VERSION).
			if (initialLoad && SERVER.configVersion.get() < CURRENT_VERSION) {
				try {
					if (migrate(SERVER)) {
						serverSpec.save();
						StorageMod.LOGGER.info("Migrated {} to config version {}", modConfig.getFileName(), CURRENT_VERSION);
					}
				} catch (RuntimeException e) {
					StorageMod.LOGGER.warn("Could not migrate {}", modConfig.getFileName(), e);
				}
			}
			onlyTrims = SERVER.onlyTrimsConnect.get();
			invConnectorScanRange = SERVER.invConnectorScanRange.get();
			invConnectorMaxCables = SERVER.invConnectorMaxCables.get();
			wirelessRange = SERVER.wirelessRange.get();
			advWirelessRange = SERVER.advWirelessRange.get();
			wirelessTermBeaconLvl = SERVER.wirelessTermBeaconLvl.get();
			wirelessTermBeaconLvlCrossDim = SERVER.wirelessTermBeaconLvlCrossDim.get();
			invLinkBeaconLvl = SERVER.invLinkBeaconLvl.get();
			invLinkBeaconRange = SERVER.invLinkBeaconRange.get();
			invLinkBeaconLvlSameDim = SERVER.invLinkBeaconLvlSameDim.get();
			invLinkBeaconLvlCrossDim = SERVER.invLinkBeaconLvlCrossDim.get();
			runMultithreaded = SERVER.runMultithreaded.getAsBoolean();
			basicHopperCooldown = SERVER.basicHopperCooldown.get();
			filterBlockCooldown = SERVER.filterBlockCooldown.get();
			buildersReachEnabled = SERVER.buildersReachEnabled.getAsBoolean();
			restockEnabled = SERVER.restockEnabled.getAsBoolean();
			itemLocatorEnabled = SERVER.itemLocatorEnabled.getAsBoolean();
			frameLabelsEnabled = SERVER.frameLabelsEnabled.getAsBoolean();
			doctorMaxInventories = SERVER.doctorMaxInventories.get();
			doctorWarnMs = SERVER.doctorWarnMs.get();
			doctorWarnInventories = SERVER.doctorWarnInventories.get();
			tidyEnabled = SERVER.tidyEnabled.getAsBoolean();
			tidyMovesPerTick = SERVER.tidyMovesPerTick.get();
			tidyMaxSeconds = SERVER.tidyMaxSeconds.get();
			tidyCooldownSeconds = SERVER.tidyCooldownSeconds.get();
			tidyOpsOnly = SERVER.tidyOpsOnly.getAsBoolean();
			tidyIncludeDocks = SERVER.tidyIncludeDocks.getAsBoolean();
		} else if(modConfig.getType() == Type.COMMON) {
			blockedMods = List.copyOf(COMMON.blockedMods.get());
			blockedBlockIds = List.copyOf(COMMON.blockedBlocks.get());
			blockedBlocks = null;
		}
	}

	@SubscribeEvent
	public void onLoad(final ModConfigEvent.Loading configEvent) {
		if (!StorageMod.modid.equals(configEvent.getConfig().getModId()))return;
		StorageMod.LOGGER.info("Loaded Obscura's Simple Storage config file {}", configEvent.getConfig().getFileName());
		load(configEvent.getConfig(), true);
	}

	@SubscribeEvent
	public void onFileChange(final ModConfigEvent.Reloading configEvent) {
		if (!StorageMod.modid.equals(configEvent.getConfig().getModId()))return;
		StorageMod.LOGGER.info("Obscura's Simple Storage config just got changed on the file system!");
		load(configEvent.getConfig(), false);
	}

	/** Drops the resolved block list, e.g. when a server starts (other mods' blocks are registered by then). */
	public void invalidateBlockedBlocks() {
		blockedBlocks = null;
	}

	public Set<Block> getBlockedBlocks() {
		Set<Block> result = blockedBlocks;
		if (result == null) {
			result = Set.copyOf(blockedBlockIds.stream().map(ResourceLocation::tryParse).filter(e -> e != null).
					map(BuiltInRegistries.BLOCK::get).filter(e -> e != null && e != Blocks.AIR).
					collect(Collectors.toSet()));
			blockedBlocks = result;
		}
		return result;
	}

	public List<String> getBlockedMods() {
		return blockedMods;
	}

	/** The blockedBlocks option as written (block ids), see {@link #getBlockedBlocks()} for the resolved blocks. */
	public List<String> getBlockedBlockIds() {
		return blockedBlockIds;
	}
}
