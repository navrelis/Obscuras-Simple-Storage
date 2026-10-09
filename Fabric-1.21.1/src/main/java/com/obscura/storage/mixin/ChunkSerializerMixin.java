package com.obscura.storage.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import com.obscura.storage.inventory.BlockFilterComponent;
import com.obscura.storage.migration.LegacyBlockFilters;

/**
 * Inventory Configurator filters of a world upgraded from Minecraft 1.20.1 live in a saved data file, not on
 * the block entities; {@link LegacyBlockFilters} moves each one into its block entity's NBT (the Cardinal
 * Components entry) when the chunk is read from disk, before the chunk's block entities are created from
 * that NBT. The NeoForge build does the same from ChunkDataEvent.Load. Costs one volatile read per chunk
 * once no 1.20.1 filter is waiting.
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin {
	@Inject(method = "read", at = @At("HEAD"))
	private static void obscuras_storage$legacyBlockFilters(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<ProtoChunk> cir) {
		LegacyBlockFilters.inject(level.dimension(), pos, tag, BlockFilterComponent.LEGACY_TARGET);
	}
}
