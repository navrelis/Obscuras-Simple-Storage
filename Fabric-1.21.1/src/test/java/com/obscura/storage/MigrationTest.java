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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;

import com.obscura.storage.inventory.BlockFilterComponent;
import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.platform.LegacyRegistryAliases;

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
		assertEquals(ResourceLocation.fromNamespaceAndPath(OLD, "storage_terminal"), LegacyIds.old("storage_terminal"));
		assertEquals(ResourceLocation.fromNamespaceAndPath("obscuras_storage", "storage_terminal"), LegacyIds.toCurrent(LegacyIds.old("storage_terminal")));
		ResourceLocation other = ResourceLocation.withDefaultNamespace("chest");
		assertSame(other, LegacyIds.toCurrent(other));
		assertEquals("toms_storage:block_filter", LegacyIds.oldBlockFilterKey());
		assertEquals("obscuras_storage:block_filter", LegacyIds.blockFilterKey());
	}

	@Test
	@DisplayName("registry alias rules: old namespace, listed registries, only when the new id exists and the old does not")
	void aliasRules() {
		ResourceLocation old = LegacyIds.old("inventory_cable");
		ResourceLocation current = ResourceLocation.fromNamespaceAndPath("obscuras_storage", "inventory_cable");
		Set<ResourceLocation> onlyNew = Set.of(current);
		assertEquals(current, LegacyRegistryAliases.resolve(Registries.BLOCK, old, onlyNew::contains));
		assertEquals(current, LegacyRegistryAliases.resolve(Registries.DATA_COMPONENT_TYPE, old, onlyNew::contains));
		// the old id is registered itself: never redirected
		Set<ResourceLocation> both = Set.of(old, current);
		assertSame(old, LegacyRegistryAliases.resolve(Registries.BLOCK, old, both::contains));
		// the new id does not exist: stays unknown
		assertSame(old, LegacyRegistryAliases.resolve(Registries.BLOCK, old, Set.<ResourceLocation>of()::contains));
		// a registry the mod does not register into
		assertSame(old, LegacyRegistryAliases.resolve(Registries.ENTITY_TYPE, old, onlyNew::contains));
		// another namespace
		ResourceLocation stone = ResourceLocation.withDefaultNamespace("inventory_cable");
		assertSame(stone, LegacyRegistryAliases.resolve(Registries.BLOCK, stone, onlyNew::contains));
		for (var reg : List.of(Registries.BLOCK, Registries.ITEM, Registries.BLOCK_ENTITY_TYPE, Registries.MENU, Registries.DATA_COMPONENT_TYPE, Registries.CREATIVE_MODE_TAB)) {
			assertTrue(LegacyRegistryAliases.REGISTRIES.contains(reg), reg.toString());
		}
	}

	@Test
	@DisplayName("ids the 1.20.1 builds registered under another path resolve in both namespaces, only in their registry")
	void renamed1201Ids() {
		ResourceLocation proxyBe = ResourceLocation.fromNamespaceAndPath("obscuras_storage", "inv_proxy");
		ResourceLocation framed = ResourceLocation.fromNamespaceAndPath("obscuras_storage", "inventory_cable_framed");
		Set<ResourceLocation> registered = Set.of(proxyBe, framed);
		for (String ns : List.of("obscuras_storage", "toms_storage")) {
			assertEquals(proxyBe, LegacyRegistryAliases.resolve(Registries.BLOCK_ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(ns, "inventory_proxy"), registered::contains));
			assertEquals(framed, LegacyRegistryAliases.resolve(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(ns, "ts.inventory_cable_painted"), registered::contains));
			assertTrue(LegacyIds.mayBeLegacy(ResourceLocation.fromNamespaceAndPath(ns, "inventory_proxy")));
		}
		// The block inventory_proxy is registered under its own name: never redirected
		ResourceLocation proxyBlock = ResourceLocation.fromNamespaceAndPath("obscuras_storage", "inventory_proxy");
		assertSame(proxyBlock, LegacyRegistryAliases.resolve(Registries.BLOCK, proxyBlock, Set.of(proxyBlock)::contains));
		// A renamed path only applies to its registry
		ResourceLocation painted = ResourceLocation.fromNamespaceAndPath("obscuras_storage", "ts.inventory_cable_painted");
		assertSame(painted, LegacyRegistryAliases.resolve(Registries.ITEM, painted, registered::contains));
		// The mod's other ids skip the slow path
		assertFalse(LegacyIds.mayBeLegacy(ResourceLocation.fromNamespaceAndPath("obscuras_storage", "inventory_cable")));
		assertEquals(List.of("inventory_cable_connector", "inventory_cable_connector_filtered"), LegacyIds.renamedFrom(Registries.BLOCK_ENTITY_TYPE, "inv_cable_connector"));
	}

	@Test
	@DisplayName("the registry mixin leaves other lookups alone")
	void mixinIsTransparent() {
		assertSame(Blocks.STONE, BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace("stone")));
		// No entries of the mod are registered in a unit test: an old id stays unknown, nothing throws
		assertTrue(BuiltInRegistries.BLOCK.getOptional(LegacyIds.old("stone")).isEmpty());
		assertTrue(BuiltInRegistries.ITEM.getHolder(LegacyIds.old("inventory_cable")).isEmpty());
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
	@DisplayName("config/toms_storage.json moves to config/obscuras_storage.json and still gets the configVersion migration")
	void fabricConfigFile(@TempDir Path configDir) throws IOException {
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
		assertEquals(8, c.basicHopperCooldown);
		assertEquals(24, c.invConnectorScanRange, "owner value kept");
		assertEquals(List.of("create"), c.blockedMods);
		assertEquals(2, c.configVersion);
		assertTrue(c.wasMigrated());
	}

	@Test
	@DisplayName("key bindings saved under the old names are copied to the new names")
	void keyBindings() {
		List<String> in = List.of(
				"version:3955",
				"key_key.toms_storage.open_terminal:key.keyboard.g",
				"key_key.toms_storage.toggle_restock:key.keyboard.r:SHIFT",
				"key_key.jump:key.keyboard.space");
		List<String> out = LegacyFiles.migrateKeyBindings(in);
		assertEquals(6, out.size());
		assertTrue(out.containsAll(in), "old lines are kept");
		assertTrue(out.contains("key_key.obscuras_storage.open_terminal:key.keyboard.g"));
		assertTrue(out.contains("key_key.obscuras_storage.toggle_restock:key.keyboard.r:SHIFT"));
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

	@Test
	@DisplayName("the Cardinal Components block filter moves from the old key to the new one")
	void blockFilterKey() {
		CompoundTag data = new CompoundTag();
		data.putBoolean("keepLast", true);
		CompoundTag comp = new CompoundTag();
		comp.put("data", data);
		CompoundTag cca = new CompoundTag();
		cca.put("toms_storage:block_filter", comp);
		cca.put("othermod:thing", new CompoundTag());
		CompoundTag be = new CompoundTag();
		be.put("cardinal_components", cca);
		assertTrue(BlockFilterComponent.migrateLegacyKey(be));
		CompoundTag after = be.getCompound("cardinal_components");
		assertFalse(after.contains("toms_storage:block_filter"));
		assertTrue(after.getCompound("obscuras_storage:block_filter").getCompound("data").getBoolean("keepLast"));
		assertTrue(after.contains("othermod:thing"), "other components untouched");
		assertFalse(BlockFilterComponent.migrateLegacyKey(be), "second run");
		assertFalse(BlockFilterComponent.migrateLegacyKey(new CompoundTag()));

		// an entry under the new key wins
		CompoundTag newer = new CompoundTag();
		newer.putBoolean("marker", true);
		CompoundTag cca2 = new CompoundTag();
		cca2.put("toms_storage:block_filter", comp.copy());
		cca2.put("obscuras_storage:block_filter", newer);
		CompoundTag be2 = new CompoundTag();
		be2.put("cardinal_components", cca2);
		assertTrue(BlockFilterComponent.migrateLegacyKey(be2));
		assertTrue(be2.getCompound("cardinal_components").getCompound("obscuras_storage:block_filter").getBoolean("marker"));
		assertFalse(be2.getCompound("cardinal_components").contains("toms_storage:block_filter"));
	}
}
