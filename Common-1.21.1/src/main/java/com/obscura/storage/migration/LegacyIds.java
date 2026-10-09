package com.obscura.storage.migration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import com.obscura.storage.StorageMod;

/**
 * The ids this mod used before v5.0.0. Everything the mod registered, saved or synced used to live in
 * the {@value #OLD_NAMESPACE} namespace; since v5.0.0 it lives in {@link StorageMod#modid}. The paths
 * never changed, so an old id is always the new id with the old namespace; the only exception are the
 * few entries the Minecraft 1.20.1 builds registered under another path, see {@link #renamedPath}.
 *
 * <p>Worlds written by an older version are loaded through registry aliases (NeoForge: the built-in
 * {@code DeferredRegister#addAlias}; Fabric: the {@code MappedRegistryMixin}), so every old id that is
 * read from a chunk, an item stack, a block entity or a data attachment resolves to the new entry.
 * The few string keys that are not registry lookups (the Cardinal Components key of the block filter,
 * saved data and config file names, key binding names) are moved by the classes in this package.
 */
public final class LegacyIds {
	/** Namespace (mod id) used up to v4.x. Only ever read, never written. */
	public static final String OLD_NAMESPACE = "toms_storage";

	/** The id of the block filter data (Cardinal Components key on Fabric, attachment type on NeoForge). */
	public static final String BLOCK_FILTER_PATH = "block_filter";

	private LegacyIds() {}

	/** {@code toms_storage:<path>} */
	public static ResourceLocation old(String path) {
		return ResourceLocation.fromNamespaceAndPath(OLD_NAMESPACE, path);
	}

	/** Whether {@code id} is in the old namespace. Null safe. */
	public static boolean isOld(ResourceLocation id) {
		return id != null && OLD_NAMESPACE.equals(id.getNamespace());
	}

	/** The new id of an old one: same path, new namespace. Any other id is returned unchanged. */
	public static ResourceLocation toCurrent(ResourceLocation id) {
		return isOld(id) ? ResourceLocation.fromNamespaceAndPath(StorageMod.modid, id.getPath()) : id;
	}

	/** {@code toms_storage:block_filter} as a string, the old NBT key of the block filter. */
	public static String oldBlockFilterKey() {
		return OLD_NAMESPACE + ":" + BLOCK_FILTER_PATH;
	}

	/** {@code obscuras_storage:block_filter} as a string, the current NBT key of the block filter. */
	public static String blockFilterKey() {
		return StorageMod.modid + ":" + BLOCK_FILTER_PATH;
	}
	/**
	 * Entries the mod's 1.20.1 builds registered under another path than this build, per registry: old
	 * path to current path. Worlds upgraded from 1.20.1 name them so in chunk palettes, block entity ids
	 * and item stacks. The removed Filtered Inventory Cable Connector (block, item and block entity, kept
	 * by 1.20.1 only to convert itself on its first tick) becomes the normal connector, its block
	 * entity hands out the filter items it still held (see InventoryCableConnectorBlockEntity). The blocks
	 * 1.20.1 swapped a painted framed block for ({@code ts.inventory_*_painted}, no item of their own) are
	 * the framed blocks here, which keep the paint in the same block entity ({@code painted},
	 * {@code inventory_cable_connector}, {@code inventory_proxy}); the painted cable had no connection
	 * properties, PaintedBlockEntity works them out again after loading.
	 */
	private static final Map<ResourceKey<? extends Registry<?>>, Map<String, String>> RENAMED_1_20_1 = Map.of(
			Registries.BLOCK_ENTITY_TYPE, Map.of(
					"inventory_cable_connector", "inv_cable_connector",
					"inventory_cable_connector_filtered", "inv_cable_connector",
					"inventory_proxy", "inv_proxy"),
			Registries.BLOCK, Map.of(
					"inventory_cable_connector_filtered", "inventory_cable_connector",
					// 1.20.1 turned a painted Framed Inventory Cable / Connector / Inventory Proxy into these blocks
					"ts.inventory_cable_painted", "inventory_cable_framed",
					"ts.inventory_cable_connector_painted", "inventory_cable_connector_framed",
					"ts.inventory_proxy_painted", "inventory_proxy"),
			Registries.ITEM, Map.of("inventory_cable_connector_filtered", "inventory_cable_connector"));
	private static final Set<String> RENAMED_PATHS = RENAMED_1_20_1.values().stream().flatMap(m -> m.keySet().stream()).collect(Collectors.toUnmodifiableSet());

	/**
	 * The current path of an entry that the 1.20.1 builds registered as {@code path} in {@code registry}
	 * (in either namespace), or null when the path was not renamed.
	 */
	public static String renamedPath(ResourceKey<? extends Registry<?>> registry, String path) {
		Map<String, String> m = RENAMED_1_20_1.get(registry);
		return m == null ? null : m.get(path);
	}

	/** The 1.20.1 paths that are now registered as {@code currentPath} in {@code registry}. */
	public static List<String> renamedFrom(ResourceKey<? extends Registry<?>> registry, String currentPath) {
		Map<String, String> m = RENAMED_1_20_1.get(registry);
		if (m == null)return List.of();
		return m.entrySet().stream().filter(e -> e.getValue().equals(currentPath)).map(Map.Entry::getKey).sorted().toList();
	}

	/**
	 * Whether a by-name lookup of {@code id} may have to be redirected: an id in the old namespace, or
	 * one of the renamed 1.20.1 paths in the mod's namespace. Cheap, for the registry hooks' fast path.
	 */
	public static boolean mayBeLegacy(ResourceLocation id) {
		if (id == null)return false;
		if (OLD_NAMESPACE.equals(id.getNamespace()))return true;
		return StorageMod.modid.equals(id.getNamespace()) && RENAMED_PATHS.contains(id.getPath());
	}
}
