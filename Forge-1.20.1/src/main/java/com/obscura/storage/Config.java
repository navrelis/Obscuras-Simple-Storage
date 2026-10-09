package com.obscura.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.common.ForgeConfigSpec.DoubleValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.registries.ForgeRegistries;

import com.electronwill.nightconfig.core.file.FileConfig;

/**
 * Forge twin of the config. The values live in two Forge config files:
 * {@code obscuras_storage-server.toml} (per world, synced to clients, so the client knows the wireless
 * range and the feature switches) and {@code obscuras_storage-common.toml} (the block lists). The TOML keys
 * never changed, so older config files keep working: {@link LegacyConfigFiles} moves files written under
 * the old mod id to the new names, and {@link #migrate} moves values that still equal an old default to the
 * new default.
 *
 * <p>The public fields have the same names and defaults as the Fabric config, the rest of the mod reads
 * them through {@link #get()}.
 */
public class Config {
	/** Version the config is migrated to by {@link #migrate()}. */
	public static final int CURRENT_VERSION = 2;

	private static final Config INSTANCE = new Config();

	/** 0 = config file written before versioning existed (the key was missing), raised by {@link #migrate()}. */
	public int configVersion = 0;
	public boolean onlyTrims = false;
	public int invConnectorScanRange = 16;
	public int wirelessRange = 32;
	public int invConnectorMaxCables = 2048;
	public List<String> multiblockInv = new ArrayList<>();
	public int advWirelessRange = 128;
	public int basicHopperCooldown = 8;
	/** Ejector, Disposal and Exporter: ticks between two operations. */
	public int filterBlockCooldown = 8;
	public int wirelessTermBeaconLvl = 1, wirelessTermBeaconLvlCrossDim = 3;
	public int invLinkBeaconLvl = 0, invLinkBeaconRange = 4096, invLinkBeaconLvlSameDim = 1, invLinkBeaconLvlCrossDim = 3;
	public List<String> blockedBlocks = new ArrayList<>(Arrays.asList("create:belt"));
	public List<String> blockedMods = new ArrayList<>();
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

	/**
	 * Moves a config written by an older version (before v4.0.0, or without a configVersion) to the current
	 * defaults. A value is only replaced when it still equals the old default, so values the owner changed
	 * are kept. Idempotent.
	 *
	 * @return whether something changed
	 */
	public boolean migrate() {
		if (configVersion >= CURRENT_VERSION)return false;
		if (wirelessRange == 16)wirelessRange = 32;
		if (advWirelessRange == 64)advWirelessRange = 128;
		if (wirelessTermBeaconLvlCrossDim == 4)wirelessTermBeaconLvlCrossDim = 3;
		if (invLinkBeaconLvl == 1)invLinkBeaconLvl = 0;
		if (invLinkBeaconLvlCrossDim == 2)invLinkBeaconLvlCrossDim = 3;
		configVersion = CURRENT_VERSION;
		return true;
	}

	public static Config get() {
		return INSTANCE;
	}

	/** A config with every option at its default, for the Network Doctor's config listing. */
	public static Config defaults() {
		return new Config();
	}

	private void reloadConfig() {
		StorageMod.multiblockInvs = toBlocks(multiblockInv);
		StorageMod.blockedBlocks = toBlocks(blockedBlocks);
	}

	private static Set<Block> toBlocks(List<String> ids) {
		return ids.stream().map(ResourceLocation::tryParse).filter(e -> e != null).
				map(id -> ForgeRegistries.BLOCKS.containsKey(id) ? ForgeRegistries.BLOCKS.getValue(id) : null).
				filter(e -> e != null && e != Blocks.AIR).collect(Collectors.toSet());
	}

	public static Set<Block> getMultiblockInvs() {
		if(StorageMod.multiblockInvs == null)INSTANCE.reloadConfig();
		return StorageMod.multiblockInvs;
	}

	public Set<Block> getBlockedBlocks() {
		if(StorageMod.blockedBlocks == null)reloadConfig();
		return StorageMod.blockedBlocks;
	}

	public List<String> getBlockedMods() {
		return blockedMods;
	}

	/** The blockedBlocks option as written (block ids), see {@link #getBlockedBlocks()} for the resolved blocks. */
	public List<String> getBlockedBlockIds() {
		return blockedBlocks;
	}

	// ---- Forge config specs ----

	public static class Server {
		public final IntValue configVersion;
		public final BooleanValue onlyTrims;
		public final ConfigValue<Integer> invConnectorScanRange;
		public final ConfigValue<Integer> wirelessRange;
		public final ConfigValue<Integer> invConnectorMaxCables;
		public final ConfigValue<Integer> advWirelessRange;
		public final ConfigValue<Integer> basicHopperCooldown;
		public final ConfigValue<Integer> filterBlockCooldown;
		public final ConfigValue<Integer> wirelessTermBeaconLvl, wirelessTermBeaconLvlCrossDim;
		public final ConfigValue<Integer> invLinkBeaconLvl, invLinkBeaconRange, invLinkBeaconLvlSameDim, invLinkBeaconLvlCrossDim;
		public final BooleanValue buildersReachEnabled, restockEnabled, itemLocatorEnabled, frameLabelsEnabled;
		public final ConfigValue<Integer> doctorMaxInventories, doctorWarnInventories;
		public final DoubleValue doctorWarnMs;
		public final BooleanValue tidyEnabled, tidyOpsOnly, tidyIncludeDocks;
		public final ConfigValue<Integer> tidyMovesPerTick, tidyMaxSeconds, tidyCooldownSeconds;

		/**
		 * An int option whose out of range values are reset to the default, like the Fabric config does
		 * (defineInRange would clamp them to the nearest bound instead).
		 */
		private static ConfigValue<Integer> ranged(ForgeConfigSpec.Builder b, String key, int def, int min, int max, String... comment) {
			String[] c = Arrays.copyOf(comment, comment.length + 1);
			c[comment.length] = "Range: " + min + " ~ " + (max == Integer.MAX_VALUE ? "2147483647" : max) + " (a value out of range is reset to the default, " + def + ")";
			return b.comment(c).define(key, def, o -> o instanceof Integer i && i >= min && i <= max);
		}

		private Server(ForgeConfigSpec.Builder builder) {
			configVersion = builder.comment("Config format version, used to move settings of older versions to new defaults. Do not change.").
					defineInRange("configVersion", 0, 0, Integer.MAX_VALUE);

			// The TOML keys of the options never change, so older config files keep their values
			invConnectorScanRange = ranged(builder, "inventoryConnectorRange", 16, 1, 64, "Inventory Connector Range");

			onlyTrims = builder.comment("Only Allow Trims to Connect Inventories").
					define("onlyTrimsConnect", false);

			invConnectorMaxCables = ranged(builder, "invCableConnectorMaxScanSize", 2048, 1, 16384, "Inventory Cable Connector Maximum number of cables");

			wirelessRange = ranged(builder, "wirelessReach", 32, 4, 256, "Wireless terminal reach");

			advWirelessRange = ranged(builder, "advWirelessRange", 128, 16, 1024, "Advanced wireless terminal range");

			basicHopperCooldown = ranged(builder, "basicHopperCooldown", 8, 1, 200, "Ticks between two items moved by a Basic Inventory Hopper in Normal mode");

			filterBlockCooldown = ranged(builder, "filterBlockCooldown", 8, 1, 200, "Ticks between two operations of an Ejector, Disposal or Exporter",
					"An Ejector in Pulse mode drops on every redstone pulse instead");

			wirelessTermBeaconLvl = ranged(builder, "wirelessTermBeaconLvl", 1, -1, 4, "Adv Wireless terminal required beacon level for infinite range",
					"Value of 0 only requires a single beacon block nearby",
					"Value of -1 disables this feature entirely");

			wirelessTermBeaconLvlCrossDim = ranged(builder, "wirelessTermBeaconLvlDim", 3, -1, 4, "Adv Wireless terminal required beacon level for cross dimensional access",
					"Value of 0 only requires a single beacon block nearby",
					"Value of -1 disables this feature entirely");

			invLinkBeaconLvl = ranged(builder, "invLinkBeaconLvl", 0, -1, 4, "Inventory Link: beacon level a connector needs to link",
					"Value of 0 links without a beacon (within invLinkBeaconRange)",
					"Value of -1 disables inventory linking entirely");

			invLinkBeaconRange = ranged(builder, "invLinkBeaconRange", 4096, 1, 65536, "Inventory Link: range in blocks in the same dimension");

			invLinkBeaconLvlSameDim = ranged(builder, "invLinkBeaconLvlSameDim", 1, -1, 4, "Inventory Link: beacon level for unlimited range in the same dimension",
					"Value of -1 disables this feature entirely");

			invLinkBeaconLvlCrossDim = ranged(builder, "invLinkBeaconLvlDim", 3, -1, 4, "Inventory Link: beacon level for cross dimensional access",
					"Value of -1 disables this feature entirely");

			buildersReachEnabled = builder.comment("Builder's Reach: pick block pulls a block missing from the inventory out of storage through a carried wireless terminal").
					define("buildersReachEnabled", true);

			restockEnabled = builder.comment("Advanced Wireless Terminal Restock toggle: keeps the held block stack topped up from storage").
					define("restockEnabled", true);

			itemLocatorEnabled = builder.comment("Item Locator: Alt + click in a terminal highlights the inventories that hold an item. The Highlight Connected Inventories button and key outline a whole network").
					define("itemLocatorEnabled", true);

			frameLabelsEnabled = builder.comment("Frame Labels: a glow item frame holding an item on a network inventory makes the network insert only that item there, and fill it first").
					define("frameLabelsEnabled", true);

			doctorMaxInventories = ranged(builder, "doctorMaxInventories", 0, 0, 1000000, "Network Doctor: at most this many inventories per storage network, the rest is skipped (listed as \"over cap\" by /obscuras_storage doctor at)",
					"Value of 0 means no cap");

			doctorWarnMs = builder.comment("Network Doctor: log a warning (at most once per network every 10 minutes) when a storage network averages at least this many milliseconds per tick",
					"Value of 0 disables the warning").
					defineInRange("doctorWarnMs", 5.0, 0.0, 1000.0);

			doctorWarnInventories = ranged(builder, "doctorWarnInventories", 0, 0, 1000000, "Network Doctor: log a warning (at most once per network every 10 minutes) when a storage network has more inventories than this",
					"Value of 0 disables the warning");

			tidyEnabled = builder.comment("Tidy: the terminal button that merges partial stacks and moves stray items into their home inventory").
					define("tidyEnabled", true);

			tidyMovesPerTick = ranged(builder, "tidyMovesPerTick", 8, 1, 64, "Tidy: stack moves per tick and network");

			tidyMaxSeconds = ranged(builder, "tidyMaxSeconds", 30, 5, 600, "Tidy: a run stops after this many seconds (planning included), the moves made stay");

			tidyCooldownSeconds = ranged(builder, "tidyCooldownSeconds", 30, 0, 3600, "Tidy: seconds after a run before the same network can be tidied again");

			tidyOpsOnly = builder.comment("Tidy: only operators (permission level 2) may use the button").
					define("tidyOpsOnly", false);

			tidyIncludeDocks = builder.comment("Tidy: also rearrange the contents of shulker boxes in Shulker Docks").
					define("tidyIncludeDocks", false);
		}
	}

	public static class Common {
		public final ConfigValue<List<? extends String>> multiblockInv;
		public final ConfigValue<List<? extends String>> blockedMods;
		public final ConfigValue<List<? extends String>> blockedBlocks;

		private Common(ForgeConfigSpec.Builder builder) {
			multiblockInv = builder.comment("List of multiblock inventory blocks",
					"The other settings of Obscura's Simple Storage are in obscuras_storage-server.toml, a file PER WORLD",
					"('saves/<world name>/serverconfig'). Put a copy into the 'defaultconfigs' folder to use it for new worlds.").
					translation("config.obscuras_storage.multiblock_inv").
					defineListAllowEmpty(List.of("multiblockInv"), ArrayList::new, s -> s instanceof String);

			blockedMods = builder.comment("List of mod ids whose blocks are ignored by the inventory connector").
					translation("config.obscuras_storage.inv_blocked_mods").
					defineListAllowEmpty(List.of("blockedMods"), ArrayList::new, s -> s instanceof String);

			blockedBlocks = builder.comment("List of block ids ignored by the inventory connector").
					translation("config.obscuras_storage.inv_blocked_blocks").
					defineListAllowEmpty(List.of("blockedBlocks"), () -> new ArrayList<>(List.of("create:belt")), s -> s instanceof String);
		}
	}

	public static final ForgeConfigSpec commonSpec;
	public static final Common COMMON;
	static {
		final Pair<Common, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(Common::new);
		commonSpec = specPair.getRight();
		COMMON = specPair.getLeft();
	}

	public static final ForgeConfigSpec serverSpec;
	public static final Server SERVER;
	static {
		final Pair<Server, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(Server::new);
		serverSpec = specPair.getRight();
		SERVER = specPair.getLeft();
	}

	private static List<String> strings(List<? extends String> in) {
		List<String> out = new ArrayList<>();
		for (String s : in) {
			if (s != null && !s.isBlank())out.add(s.trim());
		}
		return out;
	}

	private void load(ModConfig modConfig) {
		if (modConfig.getSpec() == serverSpec) {
			readServerValues();
			// Only the owner of the file migrates it; a client gets the server's (already migrated) values
			if (modConfig.getConfigData() instanceof FileConfig && migrateServerSpec()) {
				serverSpec.save();
				StorageMod.LOGGER.info("Moved the config {} to the current defaults (version {})", modConfig.getFileName(), configVersion);
			}
		} else if (modConfig.getSpec() == commonSpec) {
			multiblockInv = strings(COMMON.multiblockInv.get());
			blockedMods = strings(COMMON.blockedMods.get());
			blockedBlocks = strings(COMMON.blockedBlocks.get());
			StorageMod.multiblockInvs = null;
			StorageMod.blockedBlocks = null;
		}
	}

	/** Runs {@link #migrate()} and writes the changed values back into the server spec (not saved). */
	boolean migrateServerSpec() {
		if (!migrate())return false;
		SERVER.wirelessRange.set(wirelessRange);
		SERVER.advWirelessRange.set(advWirelessRange);
		SERVER.wirelessTermBeaconLvlCrossDim.set(wirelessTermBeaconLvlCrossDim);
		SERVER.invLinkBeaconLvl.set(invLinkBeaconLvl);
		SERVER.invLinkBeaconLvlCrossDim.set(invLinkBeaconLvlCrossDim);
		SERVER.configVersion.set(configVersion);
		return true;
	}

	void readServerValues() {
		configVersion = SERVER.configVersion.get();
		onlyTrims = SERVER.onlyTrims.get();
		invConnectorScanRange = SERVER.invConnectorScanRange.get();
		wirelessRange = SERVER.wirelessRange.get();
		invConnectorMaxCables = SERVER.invConnectorMaxCables.get();
		advWirelessRange = SERVER.advWirelessRange.get();
		basicHopperCooldown = SERVER.basicHopperCooldown.get();
		filterBlockCooldown = SERVER.filterBlockCooldown.get();
		wirelessTermBeaconLvl = SERVER.wirelessTermBeaconLvl.get();
		wirelessTermBeaconLvlCrossDim = SERVER.wirelessTermBeaconLvlCrossDim.get();
		invLinkBeaconLvl = SERVER.invLinkBeaconLvl.get();
		invLinkBeaconRange = SERVER.invLinkBeaconRange.get();
		invLinkBeaconLvlSameDim = SERVER.invLinkBeaconLvlSameDim.get();
		invLinkBeaconLvlCrossDim = SERVER.invLinkBeaconLvlCrossDim.get();
		buildersReachEnabled = SERVER.buildersReachEnabled.get();
		restockEnabled = SERVER.restockEnabled.get();
		itemLocatorEnabled = SERVER.itemLocatorEnabled.get();
		frameLabelsEnabled = SERVER.frameLabelsEnabled.get();
		doctorMaxInventories = SERVER.doctorMaxInventories.get();
		doctorWarnMs = SERVER.doctorWarnMs.get();
		doctorWarnInventories = SERVER.doctorWarnInventories.get();
		tidyEnabled = SERVER.tidyEnabled.get();
		tidyMovesPerTick = SERVER.tidyMovesPerTick.get();
		tidyMaxSeconds = SERVER.tidyMaxSeconds.get();
		tidyCooldownSeconds = SERVER.tidyCooldownSeconds.get();
		tidyOpsOnly = SERVER.tidyOpsOnly.get();
		tidyIncludeDocks = SERVER.tidyIncludeDocks.get();
	}

	static void onLoad(final ModConfigEvent.Loading event) {
		if (!StorageMod.modid.equals(event.getConfig().getModId()))return;
		INSTANCE.load(event.getConfig());
		StorageMod.LOGGER.info("Config loaded: {}", event.getConfig().getFileName());
	}

	static void onReload(final ModConfigEvent.Reloading event) {
		if (!StorageMod.modid.equals(event.getConfig().getModId()))return;
		INSTANCE.load(event.getConfig());
	}
}
