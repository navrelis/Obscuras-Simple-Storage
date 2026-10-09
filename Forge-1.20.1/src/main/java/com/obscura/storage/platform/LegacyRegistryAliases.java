package com.obscura.storage.platform;

import java.util.Collection;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.registries.RegistryManager;

import com.obscura.storage.StorageMod;
import com.obscura.storage.migration.LegacyIds;

/**
 * Forge side of the id migration: worlds saved before v5.0.0 name every entry of this mod
 * {@code toms_storage:<name>}, the entries are now {@code obscuras_storage:<name>} (same path). Forge
 * 1.20.1 has no {@code DeferredRegister#addAlias}; two Forge mechanisms cover it:
 *
 * <ol>
 * <li><b>Missing mappings</b> ({@link #onMissingMappings}): a world's level.dat keeps the id map of every
 * Forge registry. Loading it, Forge reports the old names as missing (they are not registered); each one
 * is remapped to the entry of the same path. This keeps the numeric ids of the world, avoids Forge's
 * "missing registry entries" handling, and makes Forge add the old name as an alias of the new one
 * (ForgeRegistry#processMissingEvent).</li>
 * <li><b>Registry aliases</b> ({@link #addAliases}): the same aliases are also added while the entries
 * are registered, so they are in place for data that does not come with a Forge id map (a world from
 * the Fabric build, structure files, config lists, commands) and before any world is loaded.</li>
 * </ol>
 *
 * <p>An alias is resolved by every by-name lookup that goes through ForgeRegistry#getRaw /
 * #getValue / #containsKey, which the vanilla registry wrappers use for {@code get(ResourceLocation)},
 * {@code getOptional} and {@code byNameCodec}: chunk palettes (block states), ItemStack.of (item ids),
 * BlockEntity.loadStatic (block entity ids). It is not resolved by the holder lookups
 * ({@code getHolder(ResourceKey)}, used by NbtUtils.readBlockState), so painted block states convert
 * their old ids themselves (IPaintable#readBlockState). An alias never hides a registered entry: an id
 * that is registered under the old name wins.
 */
public final class LegacyRegistryAliases {
	private LegacyRegistryAliases() {}

	/**
	 * Adds {@code toms_storage:<name> -> obscuras_storage:<name>} for every name to {@code registry}.
	 * Called from the RegisterEvent of the registry, while it is still unlocked.
	 *
	 * @param registry the Forge registry of the event, {@code null} for a vanilla only registry (no aliases there)
	 * @return the number of aliases added
	 */
	public static int addAliases(@Nullable IForgeRegistry<?> registry, Collection<String> names) {
		if (!(registry instanceof ForgeRegistry<?> reg))return 0;
		int n = 0;
		for (String name : names) {
			reg.addAlias(LegacyIds.old(name), new ResourceLocation(StorageMod.modid, name));
			n++;
		}
		return n;
	}

	/**
	 * The entry an old id is remapped to: the registered entry with the same path in this mod's
	 * namespace, or {@code null} (another namespace, or no such entry).
	 */
	public static <T> @Nullable T remapTarget(@Nullable IForgeRegistry<T> active, ResourceLocation oldId) {
		if (active == null || !LegacyIds.isOld(oldId))return null;
		ResourceLocation current = LegacyIds.toCurrent(oldId);
		// getKeys: only real entries, no aliases
		if (!active.getKeys().contains(current))return null;
		return active.getValue(current);
	}

	/** Forge bus, fired while a world's registry id map is loaded, once per registry that has missing ids. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void onMissingMappings(MissingMappingsEvent event) {
		int n = remap(event, (ResourceKey) event.getKey());
		if (n > 0)StorageMod.LOGGER.info("Remapped {} ids of an older version in {} to {}", n, event.getKey().location(), StorageMod.modid);
	}

	/** @return the number of remapped ids */
	public static <T> int remap(MissingMappingsEvent event, ResourceKey<? extends Registry<T>> key) {
		// The event's own registry is the staging copy being loaded, the entries live in the active one
		ForgeRegistry<T> active = RegistryManager.ACTIVE.getRegistry(key);
		int n = 0;
		for (MissingMappingsEvent.Mapping<T> m : event.getMappings(key, LegacyIds.OLD_NAMESPACE)) {
			T target = remapTarget(active, m.getKey());
			if (target == null) {
				StorageMod.LOGGER.warn("No entry for the old id {} in {}", m.getKey(), key.location());
				continue;
			}
			m.remap(target);
			n++;
		}
		return n;
	}
}
