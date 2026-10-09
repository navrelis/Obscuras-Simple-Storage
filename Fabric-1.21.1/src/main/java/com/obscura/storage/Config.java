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

@me.shedaniel.autoconfig.annotation.Config(name = StorageMod.modid)
public class Config implements ConfigData {
	/**
	 * 0 = config file written before versioning existed (the key is missing). Raised to
	 * 2 by {@link #migrate()} when the config is loaded.
	 */
	@Excluded
	public int configVersion = 0;
	public boolean onlyTrims = false, runMultithreaded = true;
	public int invConnectorScanRange = 16;
	public int wirelessRange = 32;
	public int invConnectorMaxCables = 2048;
	public int advWirelessRange = 128;
	public int basicHopperCooldown = 8;
	/** Ejector, Disposal and Exporter: ticks between two operations. */
	@Tooltip
	public int filterBlockCooldown = 8;
	@Tooltip
	public int wirelessTermBeaconLvl = 1, wirelessTermBeaconLvlCrossDim = 3;
	@Tooltip
	public int invLinkBeaconLvl = 0, invLinkBeaconRange = 4096, invLinkBeaconLvlSameDim = 1, invLinkBeaconLvlCrossDim = 3;
	//public int inventoryConnectorMaxSlots = Integer.MAX_VALUE;
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

	// Set when migrate() changed something, so StorageMod can write the migrated file right away.
	@Excluded
	private transient boolean migrated;

	/**
	 * Moves a config written by an older version to the current defaults. A value is only replaced when
	 * it still equals the old default, so values the owner changed are kept. Idempotent; StorageMod
	 * writes the file back when {@link #wasMigrated()} is set.
	 *
	 * <p>Options added after version 2 (buildersReachEnabled, restockEnabled, itemLocatorEnabled, the
	 * doctor* options, filterBlockCooldown) need no step here: Gson leaves a key that is missing from
	 * the file at its field default.
	 */
	void migrate() {
		final int CURRENT_VERSION = 2;
		if (configVersion >= CURRENT_VERSION)return;
		if (wirelessRange == 16)wirelessRange = 32;
		if (advWirelessRange == 64)advWirelessRange = 128;
		if (wirelessTermBeaconLvlCrossDim == 4)wirelessTermBeaconLvlCrossDim = 3;
		if (invLinkBeaconLvlCrossDim == 2)invLinkBeaconLvlCrossDim = 3;
		if (basicHopperCooldown == 10)basicHopperCooldown = 8;
		configVersion = CURRENT_VERSION;
		migrated = true;
	}

	public boolean wasMigrated() {
		return migrated;
	}

	@Override
	public void validatePostLoad() {
		migrate();
		invConnectorScanRange = Mth.clamp(invConnectorScanRange, 1, 64);
		wirelessRange = Mth.clamp(wirelessRange, 4, 256);
		invConnectorMaxCables = Mth.clamp(invConnectorMaxCables, 1, 16384);
		advWirelessRange = Mth.clamp(advWirelessRange, 16, 1024);
		basicHopperCooldown = Mth.clamp(basicHopperCooldown, 1, 200);
		filterBlockCooldown = Mth.clamp(filterBlockCooldown, 1, 200);
		wirelessTermBeaconLvl = Mth.clamp(wirelessTermBeaconLvl, -1, 4);
		wirelessTermBeaconLvlCrossDim = Mth.clamp(wirelessTermBeaconLvlCrossDim, -1, 4);
		invLinkBeaconLvl = Mth.clamp(invLinkBeaconLvl, -1, 4);
		invLinkBeaconRange = Mth.clamp(invLinkBeaconRange, 1, 65536);
		invLinkBeaconLvlSameDim = Mth.clamp(invLinkBeaconLvlSameDim, -1, 4);
		invLinkBeaconLvlCrossDim = Mth.clamp(invLinkBeaconLvlCrossDim, -1, 4);
		doctorMaxInventories = Mth.clamp(doctorMaxInventories, 0, 1000000);
		doctorWarnMs = Double.isNaN(doctorWarnMs) ? 5.0 : Mth.clamp(doctorWarnMs, 0, 1000);
		doctorWarnInventories = Mth.clamp(doctorWarnInventories, 0, 1000000);
		tidyMovesPerTick = Mth.clamp(tidyMovesPerTick, 1, 64);
		tidyMaxSeconds = Mth.clamp(tidyMaxSeconds, 5, 600);
		tidyCooldownSeconds = Mth.clamp(tidyCooldownSeconds, 0, 3600);
	}

	public static Config get() {
		return StorageMod.CONFIG;
	}

	/** A config with every option at its default, for the Network Doctor's config listing. */
	public static Config defaults() {
		return new Config();
	}

	public Set<Block> getBlockedBlocks() {
		Set<Block> result = StorageMod.blockedBlocks;
		if (result == null) {
			result = Set.copyOf(blockedBlocks.stream().map(ResourceLocation::tryParse).filter(e -> e != null).
					map(id -> BuiltInRegistries.BLOCK.get(id)).filter(e -> e != null && e != Blocks.AIR).collect(Collectors.toSet()));
			StorageMod.blockedBlocks = result;
		}
		return result;
	}

	public List<String> getBlockedMods() {
		return blockedMods;
	}

	/** The blockedBlocks option as written (block ids), see {@link #getBlockedBlocks()} for the resolved blocks. */
	public List<String> getBlockedBlockIds() {
		return blockedBlocks;
	}
}
