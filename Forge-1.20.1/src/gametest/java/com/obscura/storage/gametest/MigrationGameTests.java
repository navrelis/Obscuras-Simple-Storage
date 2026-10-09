package com.obscura.storage.gametest;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.registries.RegistryManager;

import com.obscura.storage.Content;
import com.obscura.storage.LegacyConfigFiles;
import com.obscura.storage.StorageMod;
import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.platform.LegacyRegistryAliases;

/**
 * Worlds, configs and saved data written before v5.0.0 (mod id {@code toms_storage}) load in this
 * version: registry aliases and the remapping of the missing ids of a world's registry id map, block
 * states, block entities, item stacks with old ids in their NBT, the block filter and Inventory Link saved
 * data, the removed Filtered Connector (still converts itself) and the config files.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class MigrationGameTests {

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public void oldRegistryIdsResolve(GameTestHelper h) {
		// The creative tab registry is vanilla only on Forge 1.20.1: no aliases there (tab ids are not saved anywhere)
		boolean tabs = RegistryManager.ACTIVE.getRegistry(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB) != null;
		LegacyMigrationChecks.checkVanillaRegistries(h, false, tabs);
		for (IForgeRegistry<?> reg : List.of(ForgeRegistries.BLOCKS, ForgeRegistries.ITEMS, ForgeRegistries.BLOCK_ENTITY_TYPES, ForgeRegistries.MENU_TYPES)) {
			int n = 0;
			for (ResourceLocation id : Set.copyOf(reg.getKeys())) {
				if (!id.getNamespace().equals(StorageMod.modid))continue;
				ResourceLocation old = LegacyIds.old(id.getPath());
				h.assertTrue(reg.getValue(old) == reg.getValue(id), reg.getRegistryName() + " " + old + ": ForgeRegistry#getValue");
				h.assertTrue(reg.containsKey(old), reg.getRegistryName() + " " + old + ": alias");
				h.assertFalse(reg.getKeys().contains(old), reg.getRegistryName() + " " + old + " is registered itself");
				n++;
			}
			h.assertTrue(n > 0, "no entries in " + reg.getRegistryName());
		}
		h.succeed();
	}

	/**
	 * The remapping of a world's registry id map (level.dat): Forge reports the old names as missing
	 * mappings, each one is remapped to the entry of the same path.
	 */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public void missingMappingsAreRemapped(GameTestHelper h) throws ReflectiveOperationException {
		checkRemap(h, ForgeRegistries.Keys.BLOCKS, ForgeRegistries.BLOCKS, "storage_terminal", Content.terminal.get());
		checkRemap(h, ForgeRegistries.Keys.BLOCKS, ForgeRegistries.BLOCKS, "ts.inventory_cable_painted", StorageMod.invCablePainted.get());
		checkRemap(h, ForgeRegistries.Keys.ITEMS, ForgeRegistries.ITEMS, "inventory_cable_connector_filtered", Content.invCableConnectorFiltered.get().asItem());
		checkRemap(h, ForgeRegistries.Keys.ITEMS, ForgeRegistries.ITEMS, "item_filter", Content.itemFilter.get());
		checkRemap(h, ForgeRegistries.Keys.BLOCK_ENTITY_TYPES, ForgeRegistries.BLOCK_ENTITY_TYPES, "filing_cabinet", Content.filingCabinetTile.get());
		// Unknown old ids and other namespaces are left to Forge
		h.assertTrue(LegacyRegistryAliases.remapTarget(ForgeRegistries.BLOCKS, LegacyIds.old("no_such_block")) == null, "unknown old id remapped");
		h.assertTrue(LegacyRegistryAliases.remapTarget(ForgeRegistries.BLOCKS, new ResourceLocation("minecraft", "storage_terminal")) == null, "other namespace remapped");
		h.succeed();
	}

	private static <T> void checkRemap(GameTestHelper h, ResourceKey<? extends Registry<T>> key, IForgeRegistry<T> reg, String path, T expected) throws ReflectiveOperationException {
		MissingMappingsEvent.Mapping<T> old = new MissingMappingsEvent.Mapping<>(reg, reg, LegacyIds.old(path), 12345);
		MissingMappingsEvent.Mapping<T> other = new MissingMappingsEvent.Mapping<>(reg, reg, new ResourceLocation("othermod", path), 12346);
		MissingMappingsEvent event = new MissingMappingsEvent(key, reg, List.of(old, other));
		LegacyRegistryAliases.onMissingMappings(event);
		GameTestUtil.eq(field(old, "action"), MissingMappingsEvent.Action.REMAP, path + " action");
		h.assertTrue(field(old, "target") == expected, path + " remapped to " + field(old, "target"));
		GameTestUtil.eq(field(other, "action"), MissingMappingsEvent.Action.DEFAULT, "other namespace touched");
	}

	private static Object field(Object o, String name) throws ReflectiveOperationException {
		Field f = MissingMappingsEvent.Mapping.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public void oldBlockStatesLoad(GameTestHelper h) {
		LegacyMigrationChecks.checkBlockStates(h, false);
		h.succeed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public void oldItemStacksLoad(GameTestHelper h) {
		LegacyMigrationChecks.checkItemStacks(h);
		h.succeed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public void oldBlockEntitiesLoad(GameTestHelper h) {
		LegacyMigrationChecks.checkBlockEntities(h, new BlockPos(2, 2, 2), new BlockPos(5, 2, 5));
		h.succeed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public void oldBlockFiltersLoad(GameTestHelper h) {
		BlockPos chest = new BlockPos(2, 2, 2);
		BlockPos cabinet = new BlockPos(5, 2, 5);
		h.setBlock(chest, Blocks.CHEST);
		h.setBlock(cabinet, Content.filingCabinet.get());
		LegacyMigrationChecks.checkBlockFilterData(h, chest, cabinet);
		h.succeed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public void oldSavedDataLoads(GameTestHelper h) {
		LegacyMigrationChecks.checkSavedData(h);
		h.succeed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100)
	public void oldFilteredConnectorConverts(GameTestHelper h) {
		BlockPos pos = new BlockPos(3, 2, 3);
		LegacyMigrationChecks.checkFilteredConnectorLoads(h, pos);
		// The converter block replaces itself with a normal Inventory Cable Connector on its first tick
		h.setBlock(pos, Content.invCableConnectorFiltered.get());
		h.succeedWhen(() -> h.assertBlockPresent(Content.invCableConnector.get(), pos));
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public void oldConfigFilesMove(GameTestHelper h) {
		Path dir = null;
		try {
			dir = Files.createTempDirectory("obscura-config-migration");
			String server = "configVersion = 0\nwirelessReach = 16\n";
			String common = "blockedMods = [\"create\"]\n";
			Files.writeString(dir.resolve("toms_storage-server.toml"), server, StandardCharsets.UTF_8);
			Files.writeString(dir.resolve("toms_storage-common.toml"), common, StandardCharsets.UTF_8);
			GameTestUtil.eq(LegacyConfigFiles.fileName(ModConfig.Type.SERVER), "obscuras_storage-server.toml", "server file name");
			GameTestUtil.eq(LegacyConfigFiles.fileName(ModConfig.Type.COMMON), "obscuras_storage-common.toml", "common file name");
			h.assertTrue(LegacyConfigFiles.migrateDir(dir), "nothing migrated");
			GameTestUtil.eq(Files.readString(dir.resolve("obscuras_storage-server.toml")), server, "server config content");
			GameTestUtil.eq(Files.readString(dir.resolve("obscuras_storage-common.toml")), common, "common config content");
			h.assertTrue(Files.exists(dir.resolve("toms_storage-server.toml" + LegacyFiles.MIGRATED_SUFFIX)), "old server file not kept");
			h.assertFalse(Files.exists(dir.resolve("toms_storage-server.toml")), "old server file still active");
			// Runs once: an old file that shows up again never replaces the new one
			Files.writeString(dir.resolve("toms_storage-server.toml"), "wirelessReach = 99\n", StandardCharsets.UTF_8);
			h.assertFalse(LegacyConfigFiles.migrateDir(dir), "migrated over an existing file");
			GameTestUtil.eq(Files.readString(dir.resolve("obscuras_storage-server.toml")), server, "the new file was overwritten");
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			LegacyMigrationChecks.delete(dir);
		}
		h.succeed();
	}

	/**
	 * The ServerAboutToStart safety net for a world's server config that the startup scan did not see:
	 * Forge has just written the new file from the defaults; it is kept aside and the old file is moved in.
	 */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public void oldWorldServerConfigMovesAfterForgeWroteDefaults(GameTestHelper h) {
		Path dir = null;
		try {
			dir = Files.createTempDirectory("obscura-serverconfig-migration");
			String old = "configVersion = 2\nwirelessReach = 99\n";
			String generated = "configVersion = 2\nwirelessReach = 32\n";
			Files.writeString(dir.resolve("toms_storage-server.toml"), old, StandardCharsets.UTF_8);
			Files.writeString(dir.resolve("obscuras_storage-server.toml"), generated, StandardCharsets.UTF_8);
			int[] unloads = {0};
			h.assertTrue(LegacyConfigFiles.migrateWorldDir(dir, () -> unloads[0]++), "not migrated");
			GameTestUtil.eq(unloads[0], 1, "server configs unloaded before the move");
			GameTestUtil.eq(Files.readString(dir.resolve("obscuras_storage-server.toml")), old, "old values in use");
			GameTestUtil.eq(Files.readString(dir.resolve("obscuras_storage-server.toml" + LegacyConfigFiles.GENERATED_SUFFIX)), generated, "generated file kept");
			h.assertTrue(Files.exists(dir.resolve("toms_storage-server.toml" + LegacyFiles.MIGRATED_SUFFIX)), "old file not kept");
			// Once: nothing to do the next time
			h.assertFalse(LegacyConfigFiles.migrateWorldDir(dir, () -> unloads[0]++), "ran twice");
			GameTestUtil.eq(unloads[0], 1, "configs unloaded without a reason");
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			LegacyMigrationChecks.delete(dir);
		}
		h.succeed();
	}
}
