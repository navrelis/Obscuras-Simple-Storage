package com.obscura.storage;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public class StorageTags {
	public static final TagKey<Block> REMOTE_ACTIVATE = TagKey.create(Registries.BLOCK, new ResourceLocation("obscuras_storage:remote_activate"));
	public static final TagKey<Block> INV_CONNECTOR_SKIP = TagKey.create(Registries.BLOCK, new ResourceLocation("obscuras_storage:inventory_connector_skip"));
	public static final TagKey<Block> INV_CONFIG_SKIP = TagKey.create(Registries.BLOCK, new ResourceLocation("obscuras_storage:inventory_configurator_skip"));
	/** Blocks the Tidy button never moves items into or out of (machines, redstone parts). */
	public static final TagKey<Block> TIDY_SKIP = TagKey.create(Registries.BLOCK, new ResourceLocation("obscuras_storage:tidy_skip"));

	public static void init() {}
}
