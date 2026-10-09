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
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;

import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.platform.LegacyRegistryAliases;
import com.obscura.storage.util.BlockFilterData;
import com.obscura.storage.util.RemoteConnections;

/** Moving data written before v5.0.0 (mod id toms_storage) to the obscuras_storage names. */
@DisplayName("v5.0.0 id migration")
public class MigrationTest {
	private static final String OLD = "toms_storage";

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@DisplayName("old ids map to the same path in the new namespace")
	void ids() {
		assertEquals(OLD, LegacyIds.OLD_NAMESPACE);
		assertEquals("obscuras_storage", StorageMod.modid);
		assertEquals(new ResourceLocation(OLD, "storage_terminal"), LegacyIds.old("storage_terminal"));
		assertEquals(new ResourceLocation("obscuras_storage", "storage_terminal"), LegacyIds.toCurrent(LegacyIds.old("storage_terminal")));
		// the 1.20.1 painted variants keep their ts.* paths
		assertEquals(new ResourceLocation("obscuras_storage", "ts.inventory_cable_painted"), LegacyIds.toCurrent(LegacyIds.old("ts.inventory_cable_painted")));
		ResourceLocation other = new ResourceLocation("chest");
		assertSame(other, LegacyIds.toCurrent(other));
		assertEquals("obscuras_storage:trims", LegacyIds.toCurrent("toms_storage:trims"));
		assertEquals("minecraft:logs", LegacyIds.toCurrent("minecraft:logs"));
		assertEquals("toms_storage", LegacyIds.toCurrent("toms_storage"), "not an id");
		assertNull(LegacyIds.toCurrent((String) null));
	}

	@Test
	@DisplayName("a painted block state with an old block id is read under the new id, the tag itself is not changed")
	void blockStateTag() {
		CompoundTag old = new CompoundTag();
		old.putString("Name", "toms_storage:inventory_cable");
		CompoundTag props = new CompoundTag();
		props.putString("east", "true");
		old.put("Properties", props);
		CompoundTag current = LegacyIds.currentBlockState(old);
		assertEquals("obscuras_storage:inventory_cable", current.getString("Name"));
		assertEquals("true", current.getCompound("Properties").getString("east"));
		assertEquals("toms_storage:inventory_cable", old.getString("Name"), "input changed");
		CompoundTag stone = new CompoundTag();
		stone.putString("Name", "minecraft:stone");
		assertSame(stone, LegacyIds.currentBlockState(stone));
		assertSame(null, LegacyIds.currentBlockState(null));
	}

	@Test
	@DisplayName("old ids inside item NBT (painted states, tags, stacks in filters) are rewritten, nothing else")
	void itemTagStrings() {
		CompoundTag tag = new CompoundTag();
		CompoundTag bet = new CompoundTag();
		CompoundTag block = new CompoundTag();
		block.putString("Name", "toms_storage:trim");
		bet.put("block", block);
		tag.put("BlockEntityTag", bet);
		ListTag tags = new ListTag();
		tags.add(StringTag.valueOf("toms_storage:trims"));
		tags.add(StringTag.valueOf("minecraft:logs"));
		tag.put("tags", tags);
		ListTag items = new ListTag();
		CompoundTag stack = new CompoundTag();
		stack.putString("id", "toms_storage:storage_terminal");
		stack.putByte("Count", (byte) 1);
		items.add(stack);
		tag.put("Items", items);
		tag.putString("name", "toms_storage is a name, not an id");
		assertTrue(LegacyIds.migrateStrings(tag));
		assertEquals("obscuras_storage:trim", tag.getCompound("BlockEntityTag").getCompound("block").getString("Name"));
		assertEquals("obscuras_storage:trims", tag.getList("tags", 8).getString(0));
		assertEquals("minecraft:logs", tag.getList("tags", 8).getString(1));
		assertEquals("obscuras_storage:storage_terminal", tag.getList("Items", 10).getCompound(0).getString("id"));
		assertEquals("toms_storage is a name, not an id", tag.getString("name"));
		assertFalse(LegacyIds.migrateStrings(tag), "second run");
		assertFalse(LegacyIds.migrateStrings(null));
	}

	@Test
	@DisplayName("registry alias rules: old namespace, listed registries, only when the new id exists and the old does not")
	void aliasRules() {
		ResourceLocation old = LegacyIds.old("inventory_cable");
		ResourceLocation current = new ResourceLocation("obscuras_storage", "inventory_cable");
		Set<ResourceLocation> onlyNew = Set.of(current);
		assertEquals(current, LegacyRegistryAliases.resolve(Registries.BLOCK, old, onlyNew::contains));
		assertEquals(current, LegacyRegistryAliases.resolve(Registries.BLOCK_ENTITY_TYPE, old, onlyNew::contains));
		// the old id is registered itself: never redirected
		Set<ResourceLocation> both = Set.of(old, current);
		assertSame(old, LegacyRegistryAliases.resolve(Registries.BLOCK, old, both::contains));
		// the new id does not exist: stays unknown
		assertSame(old, LegacyRegistryAliases.resolve(Registries.BLOCK, old, Set.<ResourceLocation>of()::contains));
		// a registry the mod does not register into
		assertSame(old, LegacyRegistryAliases.resolve(Registries.ENTITY_TYPE, old, onlyNew::contains));
		// another namespace
		ResourceLocation stone = new ResourceLocation("inventory_cable");
		assertSame(stone, LegacyRegistryAliases.resolve(Registries.BLOCK, stone, onlyNew::contains));
		for (var reg : List.of(Registries.BLOCK, Registries.ITEM, Registries.BLOCK_ENTITY_TYPE, Registries.MENU, Registries.CREATIVE_MODE_TAB)) {
			assertTrue(LegacyRegistryAliases.REGISTRIES.contains(reg), reg.toString());
		}
	}

	@Test
	@DisplayName("the registry mixin leaves other lookups alone")
	void mixinIsTransparent() {
		assertSame(Blocks.STONE, BuiltInRegistries.BLOCK.get(new ResourceLocation("stone")));
		assertSame(Blocks.STONE, BuiltInRegistries.BLOCK.get(ResourceKey.create(Registries.BLOCK, new ResourceLocation("stone"))));
		// No entries of the mod are registered in a unit test: an old id stays unknown, nothing throws
		assertTrue(BuiltInRegistries.BLOCK.getOptional(LegacyIds.old("stone")).isEmpty());
		assertTrue(BuiltInRegistries.ITEM.getHolder(ResourceKey.create(Registries.ITEM, LegacyIds.old("inventory_cable"))).isEmpty());
		assertFalse(BuiltInRegistries.BLOCK.containsKey(LegacyIds.old("stone")));
	}

	@Test
	@DisplayName("a file moves once, keeps its content, and the old one stays as .migrated")
	void fileMovesOnce(@TempDir Path dir) throws IOException {
		Path old = dir.resolve("old.json");
		Path neu = dir.resolve("new.json");
		assertFalse(LegacyFiles.migrate(old, neu), "nothing to move");
		Files.writeString(old, "{\"a\":1}");
		assertTrue(LegacyFiles.migrate(old, neu));
		assertEquals("{\"a\":1}", Files.readString(neu));
		assertFalse(Files.exists(old));
		assertEquals("{\"a\":1}", Files.readString(dir.resolve("old.json" + LegacyFiles.MIGRATED_SUFFIX)));
		assertFalse(Files.exists(dir.resolve("new.json.tmp")), "temporary file left behind");
		// an old file that shows up again never replaces the new one
		Files.writeString(old, "{\"a\":2}");
		assertFalse(LegacyFiles.migrate(old, neu));
		assertEquals("{\"a\":1}", Files.readString(neu));
		assertEquals("{\"a\":2}", Files.readString(old));
	}

	@Test
	@DisplayName("the saved data files (Inventory Link channels, block filters) move to the new names once")
	void savedDataFiles(@TempDir Path dataDir) throws IOException {
		assertEquals("toms_storage_rc", RemoteConnections.LEGACY_ID);
		assertEquals("obscuras_storage_rc", RemoteConnections.ID);
		assertEquals("toms_storage_block_filters", BlockFilterData.LEGACY_ID);
		assertEquals("obscuras_storage_block_filters", BlockFilterData.ID);
		assertFalse(RemoteConnections.migrateLegacyFile(dataDir), "no old file");
		Files.write(dataDir.resolve("toms_storage_rc.dat"), new byte[] {1, 2, 3});
		Files.write(dataDir.resolve("toms_storage_block_filters.dat"), new byte[] {4, 5});
		assertTrue(RemoteConnections.migrateLegacyFile(dataDir));
		assertTrue(BlockFilterData.migrateLegacyFile(dataDir));
		assertEquals(3, Files.size(dataDir.resolve("obscuras_storage_rc.dat")));
		assertEquals(2, Files.size(dataDir.resolve("obscuras_storage_block_filters.dat")));
		assertTrue(Files.exists(dataDir.resolve("toms_storage_rc.dat.migrated")));
		assertTrue(Files.exists(dataDir.resolve("toms_storage_block_filters.dat.migrated")));
		assertFalse(RemoteConnections.migrateLegacyFile(dataDir), "second run");
		assertFalse(BlockFilterData.migrateLegacyFile(dataDir), "second run");
	}

	@Test
	@DisplayName("config/toms_storage.json moves to config/obscuras_storage.json and still gets the configVersion migration")
	void fabricConfigFile(@TempDir Path configDir) throws Exception {
		assertEquals("toms_storage.json", LegacyConfigFiles.oldFileName());
		assertEquals("obscuras_storage.json", LegacyConfigFiles.fileName());
		String oldJson = "{\"wirelessRange\":16,\"basicHopperCooldown\":10,\"invConnectorScanRange\":24,\"blockedMods\":[\"create\"]}";
		Files.writeString(configDir.resolve("toms_storage.json"), oldJson, StandardCharsets.UTF_8);
		assertTrue(LegacyConfigFiles.migrate(configDir));
		assertFalse(LegacyConfigFiles.migrate(configDir), "second run");
		Path moved = configDir.resolve("obscuras_storage.json");
		assertTrue(Files.exists(configDir.resolve("toms_storage.json.migrated")));
		Config c = new Gson().fromJson(Files.readString(moved, StandardCharsets.UTF_8), Config.class);
		c.validatePostLoad();
		assertEquals(32, c.wirelessRange, "old default moved to the new default");
		assertEquals(10, c.basicHopperCooldown, "owner value kept");
		assertEquals(24, c.invConnectorScanRange, "owner value kept");
		assertEquals(List.of("create"), c.blockedMods);
		assertEquals(2, c.configVersion);
		assertTrue(c.wasMigrated());
	}

	@Test
	@DisplayName("key bindings saved under the old names are copied to the new names")
	void keyBindings() {
		List<String> in = List.of(
				"version:3465",
				"key_key.toms_storage.open_terminal:key.keyboard.g",
				"key_key.toms_storage.toggle_restock:key.keyboard.r",
				"key_key.jump:key.keyboard.space");
		List<String> out = LegacyFiles.migrateKeyBindings(in);
		assertEquals(6, out.size());
		assertTrue(out.containsAll(in), "old lines are kept");
		assertTrue(out.contains("key_key.obscuras_storage.open_terminal:key.keyboard.g"));
		assertTrue(out.contains("key_key.obscuras_storage.toggle_restock:key.keyboard.r"));
		// already migrated, or set under the new name: nothing to do
		assertNull(LegacyFiles.migrateKeyBindings(out));
		assertNull(LegacyFiles.migrateKeyBindings(List.of("key_key.jump:key.keyboard.space")));
		List<String> mixed = LegacyFiles.migrateKeyBindings(List.of(
				"key_key.obscuras_storage.open_terminal:key.keyboard.b",
				"key_key.toms_storage.open_terminal:key.keyboard.g",
				"key_key.toms_storage.toggle_restock:key.keyboard.r"));
		assertEquals(4, mixed.size());
		assertFalse(mixed.contains("key_key.obscuras_storage.open_terminal:key.keyboard.g"), "a binding under the new name wins");
		assertTrue(mixed.contains("key_key.obscuras_storage.toggle_restock:key.keyboard.r"));
	}

	@Test
	@DisplayName("options.txt is rewritten once")
	void keyBindingsFile(@TempDir Path dir) throws IOException {
		Path options = dir.resolve("options.txt");
		assertFalse(LegacyFiles.migrateKeyBindings(options), "no file");
		Files.write(options, List.of("key_key.toms_storage.open_terminal:key.keyboard.g", "fov:0.5"), StandardCharsets.UTF_8);
		assertTrue(LegacyFiles.migrateKeyBindings(options));
		List<String> lines = Files.readAllLines(options, StandardCharsets.UTF_8);
		assertTrue(lines.contains("key_key.obscuras_storage.open_terminal:key.keyboard.g"));
		assertTrue(lines.contains("fov:0.5"));
		assertFalse(LegacyFiles.migrateKeyBindings(options), "second run");
	}
}
