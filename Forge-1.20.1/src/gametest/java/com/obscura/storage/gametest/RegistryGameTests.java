package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.eq;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;

import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;

/**
 * Forge only: the registry ids must be exactly the ones the Fabric 1.20.1 build registers, so a world
 * can move between the loaders without losing blocks, items or block entities.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class RegistryGameTests {
	private static final Set<String> BLOCKS = Set.of("inventory_connector", "storage_terminal", "trim", "open_crate", "painted_trim",
			"inventory_cable", "inventory_cable_framed", "inventory_cable_connector", "inventory_cable_connector_filtered",
			"inventory_cable_connector_framed", "inventory_proxy", "crafting_terminal", "basic_inventory_hopper", "level_emitter",
			"inventory_interface", "filing_cabinet", "shulker_dock", "exporter", "ejector", "disposal",
			"ts.inventory_cable_painted", "ts.inventory_cable_connector_painted", "ts.inventory_proxy_painted");
	private static final Set<String> ITEMS = Set.of("inventory_connector", "storage_terminal", "trim", "open_crate", "painted_trim",
			"inventory_cable", "inventory_cable_framed", "inventory_cable_connector", "inventory_cable_connector_filtered",
			"inventory_cable_connector_framed", "inventory_proxy", "crafting_terminal", "basic_inventory_hopper", "level_emitter",
			"inventory_interface", "filing_cabinet", "shulker_dock", "exporter", "ejector", "disposal",
			"paint_kit", "wireless_terminal", "adv_wireless_terminal", "item_filter", "polymorphic_item_filter", "tag_item_filter",
			"condition_filter", "inventory_configurator");
	private static final Set<String> BLOCK_ENTITIES = Set.of("inventory_connector", "storage_terminal", "open_crate", "painted",
			"inventory_cable_connector", "inventory_cable_connector_filtered", "inventory_proxy", "crafting_terminal",
			"basic_inventory_hopper", "level_emitter", "inventory_interface", "filing_cabinet", "shulker_dock",
			"exporter", "ejector", "disposal");
	private static final Set<String> MENUS = Set.of("storage_terminal", "crafting_terminal", "level_emitter", "inventory_link",
			"item_filter", "tag_item_filter", "condition_filter", "inventory_configurator", "filing_cabinet",
			"exporter", "ejector", "disposal");

	private static Set<String> ours(IForgeRegistry<?> reg) {
		return reg.getKeys().stream().filter(k -> k.getNamespace().equals(StorageMod.modid)).map(ResourceLocation::getPath).collect(Collectors.toCollection(TreeSet::new));
	}

	@GameTest(template = GameTestUtil.EMPTY)
	public void registryIdsMatchFabric(GameTestHelper h) {
		eq(ours(ForgeRegistries.BLOCKS), new TreeSet<>(BLOCKS), "block ids");
		eq(ours(ForgeRegistries.ITEMS), new TreeSet<>(ITEMS), "item ids");
		eq(ours(ForgeRegistries.BLOCK_ENTITY_TYPES), new TreeSet<>(BLOCK_ENTITIES), "block entity ids");
		eq(ours(ForgeRegistries.MENU_TYPES), new TreeSet<>(MENUS), "menu ids");
		// The painted variants use the block entity of the block they were painted from
		h.assertTrue(Content.paintedTile.get().isValid(StorageMod.invCablePainted.get().defaultBlockState()), "painted cable block entity");
		h.assertTrue(Content.invCableConnectorTile.get().isValid(StorageMod.invCableConnectorPainted.get().defaultBlockState()), "painted connector block entity");
		h.assertTrue(Content.invProxyTile.get().isValid(StorageMod.invProxyPainted.get().defaultBlockState()), "painted proxy block entity");
		h.succeed();
	}
}
