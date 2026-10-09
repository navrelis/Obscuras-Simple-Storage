package com.obscura.storage;

import org.ladysnake.cca.api.v3.block.BlockComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.block.BlockComponentInitializer;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.google.common.base.Predicates;

import com.obscura.storage.inventory.BlockFilterComponent;

public class StorageModComponents implements BlockComponentInitializer {
	public static final ComponentKey<BlockFilterComponent> BLOCK_FILTER =
			ComponentRegistry.getOrCreate(ResourceLocation.tryBuild(StorageMod.modid, "block_filter"), BlockFilterComponent.class);

	@Override
	public void registerBlockComponentFactories(BlockComponentFactoryRegistry registry) {
		// Every block entity can carry a block filter: the Inventory Configurator filters vanilla and modded
		// chests, barrels and so on too. The component is one small object per block entity and stays
		// empty (nothing is saved, nothing ticks) until a filter is created.
		registry.beginRegistration(BlockEntity.class, BLOCK_FILTER).filter(Predicates.alwaysTrue()).impl(BlockFilterComponent.class).end(BlockFilterComponent::new);
	}

}
