package com.obscura.storage.jade;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.obscura.storage.StorageMod;
import com.obscura.storage.block.AbstractFilterBlock;
import com.obscura.storage.block.BasicInventoryHopperBlock;
import com.obscura.storage.block.InventoryConnectorBlock;
import com.obscura.storage.block.entity.AbstractFilterBlockEntity;
import com.obscura.storage.block.entity.BasicInventoryHopperBlockEntity;
import com.obscura.storage.block.entity.InventoryConnectorBlockEntity;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin
public class JadePlugin implements IWailaPlugin {
	public static final ResourceLocation INVENTORY_CONNECTOR = ResourceLocation.tryBuild(StorageMod.modid, "inventory_connector");
	public static final ResourceLocation INVENTORY_CONFIG = ResourceLocation.tryBuild(StorageMod.modid, "inventory_config");
	public static final ResourceLocation INVENTORY_HOPPER = ResourceLocation.tryBuild(StorageMod.modid, "inventory_hopper");
	public static final ResourceLocation FILTER_BLOCK = ResourceLocation.tryBuild(StorageMod.modid, "filter_block");

	@Override
	public void register(IWailaCommonRegistration registration) {
		registration.registerBlockDataProvider(InventoryConnectorProvider.INSTANCE, InventoryConnectorBlockEntity.class);
		registration.registerBlockDataProvider(InventoryConfigProvider.INSTANCE, BlockEntity.class);
		registration.registerBlockDataProvider(InventoryHopperProvider.INSTANCE, BasicInventoryHopperBlockEntity.class);
		registration.registerBlockDataProvider(FilterBlockProvider.INSTANCE, AbstractFilterBlockEntity.class);
	}

	@Override
	public void registerClient(IWailaClientRegistration registration) {
		registration.registerBlockComponent(InventoryConnectorProvider.INSTANCE, InventoryConnectorBlock.class);
		registration.registerBlockComponent(InventoryConfigProvider.INSTANCE, Block.class);
		registration.registerBlockComponent(InventoryHopperProvider.INSTANCE, BasicInventoryHopperBlock.class);
		registration.registerBlockComponent(FilterBlockProvider.INSTANCE, AbstractFilterBlock.class);
	}
}
