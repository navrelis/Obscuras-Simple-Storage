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
 * {@code obscuras_storage:<path>}), so old worlds load. Fabric API for 1.20.1 has no alias API; this is
 * the same thing Forge does with registry aliases in ForgeRegistry#getRaw.
 *
 * <p>Injection points (the by-name lookups of the 1.20.1 MappedRegistry, which has no
 * {@code getHolder(ResourceLocation)} yet):
 * <ul>
 * <li>{@code get(ResourceLocation)}: Registry#getOptional and #byNameCodec, which read chunk palettes
 * (block states), BlockEntity.loadStatic (block entity ids), ItemStack.of (item ids), stats;</li>
 * <li>{@code get(ResourceKey)}, {@code getHolder(ResourceKey)}: lookups by key, the HolderGetter of
 * NbtUtils.readBlockState (structures, falling blocks) and #holderByNameCodec;</li>
 * <li>{@code getOrCreateHolderOrThrow(ResourceKey)}: the registration lookup, for parity with the
 * aliases of the other loaders.</li>
 * </ul>
 * The argument is replaced at HEAD, before the method reads it. {@code containsKey}, {@code keySet},
 * the id maps and everything Fabric's registry sync uses (it reads the backing maps directly, also for
 * the id map it keeps in {@code <world>/data/fabricRegistry.dat}) are left alone, so registry sync with
 * clients and the saved raw ids are unchanged.
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

	@ModifyVariable(method = "get(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;", at = @At("HEAD"), argsOnly = true)
	private ResourceLocation obscuras_storage$legacyName(ResourceLocation id) {
		if (!LegacyIds.isOld(id))return id;
		return LegacyRegistryAliases.resolve(key(), id, byLocation::containsKey);
	}

	@ModifyVariable(method = {
			"get(Lnet/minecraft/resources/ResourceKey;)Ljava/lang/Object;",
			"getHolder(Lnet/minecraft/resources/ResourceKey;)Ljava/util/Optional;",
			"getOrCreateHolderOrThrow(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;"
	}, at = @At("HEAD"), argsOnly = true)
	private ResourceKey<T> obscuras_storage$legacyKey(ResourceKey<T> key) {
		if (key == null || !LegacyIds.isOld(key.location()))return key;
		ResourceLocation resolved = LegacyRegistryAliases.resolve(key(), key.location(), byLocation::containsKey);
		return resolved == key.location() ? key : ResourceKey.create(key(), resolved);
	}
}
