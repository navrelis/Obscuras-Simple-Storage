package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;

import net.minecraftforge.fml.config.ModConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;

import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.util.BlockFilterData;
import com.obscura.storage.util.RemoteConnections;

/** Moving data written before v5.0.0 (mod id toms_storage) to the obscuras_storage names (Forge). */
@DisplayName("v5.0.0 id migration (Forge)")
public class MigrationTest {
	private static final String OLD = "toms_storage";

	@Test
	@DisplayName("old ids map to the same path in the new namespace")
	void ids() {
		assertEquals(OLD, LegacyIds.OLD_NAMESPACE);
		assertEquals("obscuras_storage", StorageMod.modid);
		assertEquals(new ResourceLocation(OLD, "storage_terminal"), LegacyIds.old("storage_terminal"));
		assertEquals(new ResourceLocation("obscuras_storage", "storage_terminal"), LegacyIds.toCurrent(LegacyIds.old("storage_terminal")));
		assertEquals(new ResourceLocation("obscuras_storage", "ts.inventory_proxy_painted"), LegacyIds.toCurrent(LegacyIds.old("ts.inventory_proxy_painted")));
		ResourceLocation other = new ResourceLocation("chest");
		assertSame(other, LegacyIds.toCurrent(other));
		assertEquals("obscuras_storage:trims", LegacyIds.toCurrent("toms_storage:trims"));
		assertEquals("minecraft:logs", LegacyIds.toCurrent("minecraft:logs"));
	}

	@Test
	@DisplayName("old ids in NBT: painted block states and item NBT strings")
	void nbtStrings() {
		CompoundTag state = new CompoundTag();
		state.putString("Name", "toms_storage:trim");
		assertEquals("obscuras_storage:trim", LegacyIds.currentBlockState(state).getString("Name"));
		assertEquals("toms_storage:trim", state.getString("Name"), "input changed");

		CompoundTag tag = new CompoundTag();
		ListTag items = new ListTag();
		CompoundTag stack = new CompoundTag();
		stack.putString("id", "toms_storage:item_filter");
		items.add(stack);
		tag.put("Filter", items);
		ListTag tags = new ListTag();
		tags.add(StringTag.valueOf("toms_storage:trims"));
		tag.put("tags", tags);
		assertTrue(LegacyIds.migrateStrings(tag));
		assertEquals("obscuras_storage:item_filter", tag.getList("Filter", 10).getCompound(0).getString("id"));
		assertEquals("obscuras_storage:trims", tag.getList("tags", 8).getString(0));
		assertFalse(LegacyIds.migrateStrings(tag), "second run");
	}

	@Test
	@DisplayName("a file moves once, keeps its content, and the old one stays as .migrated")
	void fileMovesOnce(@TempDir Path dir) throws IOException {
		Path old = dir.resolve("old.toml");
		Path neu = dir.resolve("new.toml");
		assertFalse(LegacyFiles.migrate(old, neu), "nothing to move");
		Files.writeString(old, "a = 1\n");
		assertTrue(LegacyFiles.migrate(old, neu));
		assertEquals("a = 1\n", Files.readString(neu));
		assertFalse(Files.exists(old));
		assertTrue(Files.exists(dir.resolve("old.toml" + LegacyFiles.MIGRATED_SUFFIX)));
		Files.writeString(old, "a = 2\n");
		assertFalse(LegacyFiles.migrate(old, neu), "never overwrites");
		assertEquals("a = 1\n", Files.readString(neu));
	}

	@Test
	@DisplayName("the saved data files (Inventory Link channels, block filters) move to the new names once")
	void savedDataFiles(@TempDir Path dataDir) throws IOException {
		assertEquals("toms_storage_rc", RemoteConnections.LEGACY_ID);
		assertEquals("obscuras_storage_rc", RemoteConnections.ID);
		assertEquals("toms_storage_block_filters", BlockFilterData.LEGACY_ID);
		assertEquals("obscuras_storage_block_filters", BlockFilterData.ID);
		Files.write(dataDir.resolve("toms_storage_rc.dat"), new byte[] {1, 2, 3});
		Files.write(dataDir.resolve("toms_storage_block_filters.dat"), new byte[] {4, 5});
		assertTrue(RemoteConnections.migrateLegacyFile(dataDir));
		assertTrue(BlockFilterData.migrateLegacyFile(dataDir));
		assertEquals(3, Files.size(dataDir.resolve("obscuras_storage_rc.dat")));
		assertEquals(2, Files.size(dataDir.resolve("obscuras_storage_block_filters.dat")));
		assertFalse(RemoteConnections.migrateLegacyFile(dataDir), "second run");
		assertFalse(BlockFilterData.migrateLegacyFile(dataDir), "second run");
	}

	@Test
	@DisplayName("toms_storage-{common,server}.toml move to obscuras_storage-*.toml and still get the configVersion migration")
	void forgeConfigFiles(@TempDir Path dir) throws IOException {
		assertEquals("toms_storage-server.toml", LegacyConfigFiles.oldFileName(ModConfig.Type.SERVER));
		assertEquals("toms_storage-common.toml", LegacyConfigFiles.oldFileName(ModConfig.Type.COMMON));
		assertEquals("obscuras_storage-server.toml", LegacyConfigFiles.fileName(ModConfig.Type.SERVER));
		assertEquals("obscuras_storage-common.toml", LegacyConfigFiles.fileName(ModConfig.Type.COMMON));
		Files.writeString(dir.resolve("toms_storage-server.toml"), "configVersion = 0\nwirelessReach = 16\ninventoryConnectorRange = 24\n", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("toms_storage-common.toml"), "blockedMods = [\"create\"]\n", StandardCharsets.UTF_8);
		assertTrue(LegacyConfigFiles.migrateDir(dir));
		assertFalse(LegacyConfigFiles.migrateDir(dir), "second run");
		assertTrue(Files.exists(dir.resolve("toms_storage-server.toml.migrated")));
		assertTrue(Files.exists(dir.resolve("toms_storage-common.toml.migrated")));

		CommentedConfig cfg = new TomlParser().parse(Files.readString(dir.resolve("obscuras_storage-server.toml")));
		Config.serverSpec.correct(cfg);
		Config.serverSpec.setConfig(cfg);
		Config c = new Config();
		c.readServerValues();
		assertEquals(24, c.invConnectorScanRange, "owner value kept");
		assertTrue(c.migrateServerSpec());
		assertEquals(32, c.wirelessRange, "old default moved to the new default");
		assertEquals(2, c.configVersion);
	}

	@Test
	@DisplayName("a world server config found only when the server starts replaces the defaults Forge just wrote")
	void worldServerConfigSafetyNet(@TempDir Path dir) throws IOException {
		int[] unloads = {0};
		assertFalse(LegacyConfigFiles.migrateWorldDir(dir, () -> unloads[0]++), "nothing to do");
		assertEquals(0, unloads[0]);
		Files.writeString(dir.resolve("toms_storage-server.toml"), "wirelessReach = 99\n", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("obscuras_storage-server.toml"), "wirelessReach = 32\n", StandardCharsets.UTF_8);
		assertTrue(LegacyConfigFiles.migrateWorldDir(dir, () -> unloads[0]++));
		assertEquals(1, unloads[0]);
		assertEquals("wirelessReach = 99\n", Files.readString(dir.resolve("obscuras_storage-server.toml")));
		assertEquals("wirelessReach = 32\n", Files.readString(dir.resolve("obscuras_storage-server.toml.generated")));
		assertFalse(Files.exists(dir.resolve("toms_storage-server.toml")));
		assertFalse(LegacyConfigFiles.migrateWorldDir(dir, () -> unloads[0]++), "second run");
	}

	@Test
	@DisplayName("key bindings saved under the old names are copied to the new names")
	void keyBindings(@TempDir Path dir) throws IOException {
		Path options = dir.resolve("options.txt");
		Files.write(options, List.of("key_key.toms_storage.open_terminal:key.keyboard.g", "key_key.toms_storage.toggle_restock:key.keyboard.r:SHIFT", "fov:0.5"), StandardCharsets.UTF_8);
		assertTrue(LegacyFiles.migrateKeyBindings(options));
		List<String> lines = Files.readAllLines(options, StandardCharsets.UTF_8);
		assertTrue(lines.contains("key_key.obscuras_storage.open_terminal:key.keyboard.g"));
		// Forge saves the key modifier after the key
		assertTrue(lines.contains("key_key.obscuras_storage.toggle_restock:key.keyboard.r:SHIFT"));
		assertTrue(lines.contains("fov:0.5"));
		assertFalse(LegacyFiles.migrateKeyBindings(options), "second run");
		assertNull(LegacyFiles.migrateKeyBindings(List.of("key_key.jump:key.keyboard.space")));
	}
}
