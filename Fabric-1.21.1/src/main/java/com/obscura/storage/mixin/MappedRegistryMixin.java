package com.obscura.storage.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.platform.LegacyRegistryAliases;

/**
 * Registry aliases for the ids this mod used before v5.0.0 ({@code toms_storage:<path>} resolves to
 * {@code obscuras_storage:<path>}), so old worlds load. Fabric API for 1.21.1 has no alias API; this is
 * the same thing NeoForge does in its patched MappedRegistry (BaseMappedRegistry#resolve).
 *
 * <p>Injection points: the by-name lookups that NeoForge resolves aliases in, and nothing else.
 * <ul>
 * <li>{@code get(ResourceLocation)}: Registry#getOptional, BlockEntity.loadStatic (block entity ids),
 * DataComponentPatch keys in item stacks, stats;</li>
 * <li>{@code getHolder(ResourceLocation)}: Registry#byNameCodec and #holderByNameCodec, which read
 * chunk palettes (block states), item stacks (item ids) and every other codec by name;</li>
 * <li>{@code get(ResourceKey)}, {@code getHolder(ResourceKey)}: HolderGetter/registry lookups by key
 * (NbtUtils.readBlockState, tag loading);</li>
 * <li>{@code getOrCreateHolderOrThrow(ResourceKey)}: the registration lookup, for parity with
 * NeoForge.</li>
 * </ul>
 * The argument is replaced at HEAD, before the method reads it. {@code containsKey}, {@code keySet},
 * the id maps and everything Fabric's registry sync uses (it reads the backing maps directly) are left
 * alone, so registry sync with clients is unchanged.
 *
 * <p>Cost for every other lookup: one namespace comparison. The rest only runs for ids in the old
 * namespace; see {@link LegacyRegistryAliases#resolve} for the rules (listed registries only, never
 * when the old id is registered, only when the new id is).
 */
@Mixin(MappedRegistry.class)
public abstract class MappedRegistryMixin<T> {
	@Shadow
	@Final
	private Map<ResourceLocation, Holder.Reference<T>> byLocation;

	@Shadow
	public abstract ResourceKey<? extends Registry<T>> key();

	@ModifyVariable(method = {
			"get(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;",
			"getHolder(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;"
	}, at = @At("HEAD"), argsOnly = true)
	private ResourceLocation obscuras_storage$legacyName(ResourceLocation id) {
		if (!LegacyIds.mayBeLegacy(id))return id;
		return LegacyRegistryAliases.resolve(key(), id, byLocation::containsKey);
	}

	@ModifyVariable(method = {
			"get(Lnet/minecraft/resources/ResourceKey;)Ljava/lang/Object;",
			"getHolder(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;",
			"getOrCreateHolderOrThrow(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;"
	}, at = @At("HEAD"), argsOnly = true)
	private ResourceKey<T> obscuras_storage$legacyKey(ResourceKey<T> key) {
		if (key == null || !LegacyIds.mayBeLegacy(key.location()))return key;
		ResourceLocation resolved = LegacyRegistryAliases.resolve(key(), key.location(), byLocation::containsKey);
		return resolved == key.location() ? key : ResourceKey.create(key.registryKey(), resolved);
	}
}
