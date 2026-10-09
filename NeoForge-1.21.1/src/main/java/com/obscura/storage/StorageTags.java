package com.obscura.storage;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public class StorageTags {
	public static final TagKey<Block> REMOTE_ACTIVATE = BlockTags.create(ResourceLocation.tryBuild(StorageMod.modid, "remote_activate"));
	public static final TagKey<Block> TRIMS = BlockTags.create(ResourceLocation.tryBuild(StorageMod.modid, "trims"));
	public static final TagKey<Block> INV_CONNECTOR_SKIP = BlockTags.create(ResourceLocation.tryBuild(StorageMod.modid, "inventory_connector_skip"));
	public static final TagKey<Block> INV_CONFIG_SKIP = BlockTags.create(ResourceLocation.tryBuild(StorageMod.modid, "inventory_configurator_skip"));
	/** Blocks the Tidy button never moves items into or out of (machines, redstone parts). */
	public static final TagKey<Block> TIDY_SKIP = BlockTags.create(ResourceLocation.tryBuild(StorageMod.modid, "tidy_skip"));
}
