package com.obscura.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.ConfigEntry.Gui.Excluded;
import me.shedaniel.autoconfig.annotation.ConfigEntry.Gui.Tooltip;

@me.shedaniel.autoconfig.annotation.Config(name = "obscuras_storage")
public class Config implements ConfigData {
	/**
	 * 0 = config file written before versioning existed (the key is missing). Raised to
	 * 2 by {@link #migrate()} when the config is loaded.
	 */
	@Excluded
	public int configVersion = 0;
	public boolean onlyTrims = false;
	public int invConnectorScanRange = 16;
	public int wirelessRange = 32;
	public int invConnectorMaxCables = 2048;
	public List<String> multiblockInv = new ArrayList<>();
	public int advWirelessRange = 128;
	public int basicHopperCooldown = 8;
	/** Ejector, Disposal and Exporter: ticks between two operations. */
	@Tooltip
	public int filterBlockCooldown = 8;
	@Tooltip
	public int wirelessTermBeaconLvl = 1, wirelessTermBeaconLvlCrossDim = 3;
	@Tooltip
	public int invLinkBeaconLvl = 0, invLinkBeaconRange = 4096, invLinkBeaconLvlSameDim = 1, invLinkBeaconLvlCrossDim = 3;
	public List<String> blockedBlocks = new ArrayList<>(Arrays.asList("create:belt"));
	public List<String> blockedMods = new ArrayList<>();
	/** Pick-block (middle click) pulls a block missing from the inventory out of storage through a carried wireless terminal. */
	@Tooltip
	public boolean buildersReachEnabled = true;
	/** The Advanced Wireless Terminal's Restock toggle keeps the held block stack topped up from storage. */
	@Tooltip
	public boolean restockEnabled = true;
	/** Alt + click in a terminal highlights the inventories that hold an item; the Highlight Connected Inventories button and key outline a whole network. */
	@Tooltip
	public boolean itemLocatorEnabled = true;
	/** Frame Labels: a glow item frame holding an item on a network inventory makes the network insert only that item there, and fill it first. */
	@Tooltip
	public boolean frameLabelsEnabled = true;
	/** Network Doctor: at most this many inventories per network, the rest is skipped ("over cap"). 0 = no cap. */
	@Tooltip
	public int doctorMaxInventories = 0;
	/** Network Doctor: log a warning when a network averages at least this many ms per tick. 0 = off. */
	@Tooltip
	public double doctorWarnMs = 5.0;
	/** Network Doctor: log a warning when a network has more inventories than this. 0 = off. */
	@Tooltip
	public int doctorWarnInventories = 0;
	/** Tidy: the terminal button that merges partial stacks and moves stray items into their home inventory. */
	@Tooltip
	public boolean tidyEnabled = true;
	/** Tidy: stack moves per tick and network. */
	@Tooltip
	public int tidyMovesPerTick = 8;
	/** Tidy: a run stops after this many seconds (planning included); the moves made stay. */
	@Tooltip
	public int tidyMaxSeconds = 30;
	/** Tidy: seconds after a run before the network can be tidied again. */
	@Tooltip
	public int tidyCooldownSeconds = 30;
	/** Tidy: only operators (permission level 2) may use the button. */
	@Tooltip
	public boolean tidyOpsOnly = false;
	/** Tidy: also rearrange the contents of shulker boxes in Shulker Docks. */
	@Tooltip
	public boolean tidyIncludeDocks = false;

	// Keys of config files written before configVersion 2. Only read so the values can be moved to the
	// renamed fields, then set to null again (the serializer does not write nulls, so they disappear from
	// the file on the next save).
	@Excluded
	public Integer invRange;
	@Excluded
	public Integer wirelessTermBeaconLvlDim;
	@Excluded
	public Integer invLinkBeaconLvlDim;

	// Set when migrate() changed something, so StorageMod can write the migrated file right away.
	@Excluded
	private transient boolean migrated;

	/**
	 * Moves a config written by an older version to the current keys and defaults. A value is only
	 * replaced when it still equals the old default, so values the owner changed are kept. Idempotent; the
	 * file on disk is rewritten the next time the config is saved.
	 *
	 * <p>Options added after version 2 (buildersReachEnabled, restockEnabled, itemLocatorEnabled, frameLabelsEnabled,
	 * filterBlockCooldown and the doctor* and tidy* options) need no step here: Gson leaves a key that is missing from the file at its field
	 * default.
	 */
	void migrate() {
		final int currentVersion = 2;
		if (invRange != null) {
			migrated = true;
			invConnectorScanRange = invRange;
			invRange = null;
		}
		if (wirelessTermBeaconLvlDim != null) {
			migrated = true;
			wirelessTermBeaconLvlCrossDim = wirelessTermBeaconLvlDim;
			wirelessTermBeaconLvlDim = null;
		}
		if (invLinkBeaconLvlDim != null) {
			migrated = true;
			invLinkBeaconLvlCrossDim = invLinkBeaconLvlDim;
			invLinkBeaconLvlDim = null;
		}
		if (configVersion >= currentVersion)return;
		if (wirelessRange == 16)wirelessRange = 32;
		if (advWirelessRange == 64)advWirelessRange = 128;
		if (wirelessTermBeaconLvlCrossDim == 4)wirelessTermBeaconLvlCrossDim = 3;
		if (invLinkBeaconLvl == 1)invLinkBeaconLvl = 0;
		if (invLinkBeaconLvlCrossDim == 2)invLinkBeaconLvlCrossDim = 3;
		configVersion = currentVersion;
		migrated = true;
	}

	public boolean wasMigrated() {
		return migrated;
	}

	@Override
	public void validatePostLoad() throws ValidationException {
		migrate();
		// Out of range values are moved to the nearest bound, like the 1.21.1 config does
		invConnectorScanRange = clamp("invConnectorScanRange", invConnectorScanRange, 1, 64);
		wirelessRange = clamp("wirelessRange", wirelessRange, 4, 256);
		invConnectorMaxCables = clamp("invConnectorMaxCables", invConnectorMaxCables, 1, 16384);
		advWirelessRange = clamp("advWirelessRange", advWirelessRange, 16, 1024);
		basicHopperCooldown = clamp("basicHopperCooldown", basicHopperCooldown, 1, 200);
		filterBlockCooldown = clamp("filterBlockCooldown", filterBlockCooldown, 1, 200);
		wirelessTermBeaconLvl = clamp("wirelessTermBeaconLvl", wirelessTermBeaconLvl, -1, 4);
		wirelessTermBeaconLvlCrossDim = clamp("wirelessTermBeaconLvlCrossDim", wirelessTermBeaconLvlCrossDim, -1, 4);
		invLinkBeaconLvl = clamp("invLinkBeaconLvl", invLinkBeaconLvl, -1, 4);
		invLinkBeaconRange = clamp("invLinkBeaconRange", invLinkBeaconRange, 1, 65536);
		invLinkBeaconLvlSameDim = clamp("invLinkBeaconLvlSameDim", invLinkBeaconLvlSameDim, -1, 4);
		invLinkBeaconLvlCrossDim = clamp("invLinkBeaconLvlCrossDim", invLinkBeaconLvlCrossDim, -1, 4);
		doctorMaxInventories = clamp("doctorMaxInventories", doctorMaxInventories, 0, 1000000);
		doctorWarnMs = Double.isNaN(doctorWarnMs) ? 5.0 : Math.min(1000.0, Math.max(0.0, doctorWarnMs));
		doctorWarnInventories = clamp("doctorWarnInventories", doctorWarnInventories, 0, 1000000);
		tidyMovesPerTick = clamp("tidyMovesPerTick", tidyMovesPerTick, 1, 64);
		tidyMaxSeconds = clamp("tidyMaxSeconds", tidyMaxSeconds, 5, 600);
		tidyCooldownSeconds = clamp("tidyCooldownSeconds", tidyCooldownSeconds, 0, 3600);
		StorageMod.LOGGER.info("Config loaded");
	}

	private static int clamp(String name, int value, int min, int max) {
		int r = Mth.clamp(value, min, max);
		if(r != value)StorageMod.LOGGER.warn("{} out of bounds ({} ~ {}), using {}", name, min, max, r);
		return r;
	}

	private void reloadConfig() {
		StorageMod.multiblockInvs = multiblockInv.stream().map(ResourceLocation::tryParse).filter(e -> e != null).map(id -> BuiltInRegistries.BLOCK.get(id)).filter(e -> e != null && e != Blocks.AIR).collect(Collectors.toSet());
		StorageMod.blockedBlocks = blockedBlocks.stream().map(ResourceLocation::tryParse).filter(e -> e != null).map(id -> BuiltInRegistries.BLOCK.get(id)).filter(e -> e != null && e != Blocks.AIR).collect(Collectors.toSet());
	}

	public static Set<Block> getMultiblockInvs() {
		if(StorageMod.multiblockInvs == null)StorageMod.CONFIG.reloadConfig();
		return StorageMod.multiblockInvs;
	}

	public Set<Block> getBlockedBlocks() {
		if(StorageMod.blockedBlocks == null)StorageMod.CONFIG.reloadConfig();
		return StorageMod.blockedBlocks;
	}

	public List<String> getBlockedMods() {
		return blockedMods;
	}

	/** The blockedBlocks option as written (block ids), see {@link #getBlockedBlocks()} for the resolved blocks. */
	public List<String> getBlockedBlockIds() {
		return blockedBlocks;
	}

	public static Config get() {
		return StorageMod.CONFIG;
	}

	/** A config with every option at its default, for the Network Doctor's config listing. */
	public static Config defaults() {
		return new Config();
	}
}
