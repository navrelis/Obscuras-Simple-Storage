package com.obscura.storage.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.obscura.storage.inventory.BlockFilterComponent;

/**
 * Inventory Configurator filters saved before v5.0.0 sit under the Cardinal Components key
 * {@code toms_storage:block_filter}; CCA only reads the component under its current key
 * ({@code obscuras_storage:block_filter}). CCA reads block components at the RETURN of
 * {@code loadWithComponents} (its MixinBlockEntity), from the same tag, so renaming the entry at HEAD
 * makes CCA read the old filter. This covers every block entity (the filter can sit on chests, barrels
 * and other mods' blocks) and exactly the load path CCA itself reads from (chunk loading and
 * BlockEntity.loadStatic both end in loadWithComponents). Tags without the old key are only looked at,
 * never changed.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
	@Inject(method = "loadWithComponents", at = @At("HEAD"))
	private void obscuras_storage$legacyBlockFilter(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
		BlockFilterComponent.migrateLegacyKey(tag);
	}
}
