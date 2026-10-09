package com.obscura.storage.platform;

import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import com.obscura.storage.migration.LegacyIds;

/**
 * Fabric counterpart of Forge's registry aliases (Fabric API for 1.20.1 has no alias API): a by-name
 * lookup of a missing {@code toms_storage:<path>} id in one of the registries this mod registers into
 * resolves to {@code obscuras_storage:<path>}. Applied by {@code MappedRegistryMixin}.
 *
 * <p>Rules, same as Forge's aliases:
 * <ul>
 * <li>only ids in the old namespace are looked at, every other id is returned untouched;</li>
 * <li>only the registries listed in {@link #REGISTRIES};</li>
 * <li>an id that is registered under the old name is never redirected;</li>
 * <li>the id is only redirected when the new id is registered.</li>
 * </ul>
 */
public final class LegacyRegistryAliases {
	/** Every registry this mod registers entries into on Fabric. */
	public static final Set<ResourceKey<? extends Registry<?>>> REGISTRIES = Set.of(
			Registries.BLOCK, Registries.ITEM, Registries.BLOCK_ENTITY_TYPE, Registries.MENU,
			Registries.CREATIVE_MODE_TAB);

	private LegacyRegistryAliases() {}

	/**
	 * Slow path of the mixin, only called for ids in the old namespace.
	 *
	 * @param registry the registry that is asked
	 * @param id an id in the old namespace
	 * @param registered whether the registry has an entry of that name (no alias involved)
	 * @return the id to look up instead, or {@code id} itself
	 */
	public static ResourceLocation resolve(ResourceKey<? extends Registry<?>> registry, ResourceLocation id, Predicate<ResourceLocation> registered) {
		if (!LegacyIds.isOld(id) || !REGISTRIES.contains(registry) || registered.test(id))return id;
		ResourceLocation current = LegacyIds.toCurrent(id);
		return registered.test(current) ? current : id;
	}
}
