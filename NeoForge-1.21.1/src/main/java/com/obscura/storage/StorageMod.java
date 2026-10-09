package com.obscura.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import com.obscura.storage.api.MultiblockInventoryAPI;
import com.obscura.storage.inventory.PlatformItemHandler;
import com.obscura.storage.inventory.VanillaMultiblockInventories;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.polymorph.PolymorphTerminalWidget;

//The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(StorageMod.modid)
public class StorageMod {
	public static final String modid = "obscuras_storage";

	// Directly reference a log4j logger.
	public static final Logger LOGGER = LogManager.getLogger();

	public static boolean polymorph;

	public StorageMod(ModContainer mc, IEventBus bus) {
		bus.addListener(this::setup);
		bus.addListener(this::doClientStuff);
		if (FMLEnvironment.dist == Dist.CLIENT)StorageModClient.preInit(mc, bus);
		bus.addListener(this::registerCapabilities);

		// Config files written before v5.0.0 (old mod id in the file name) move to the new names first
		LegacyConfigFiles.migrateAtStartup();
		mc.registerConfig(ModConfig.Type.COMMON, Config.commonSpec);
		mc.registerConfig(ModConfig.Type.SERVER, Config.serverSpec);
		bus.register(Config.get());
		bus.register(NetworkHandler.class);

		Content.init();

		Platform.register(bus);

		polymorph = ModList.get().isLoaded("polymorph");
	}

	public static ResourceLocation id(String id) {
		return ResourceLocation.tryBuild(modid, id);
	}

	private void setup(final FMLCommonSetupEvent event) {
		LOGGER.info("Obscura's Simple Storage Setup starting");
		MultiblockInventoryAPI.EVENT.register(VanillaMultiblockInventories::checkChest);
	}

	private void doClientStuff(final FMLClientSetupEvent event) {
		event.enqueueWork(StorageModClient::clientSetup);
		if (polymorph)PolymorphTerminalWidget.register();
	}

	private void registerCapabilities(RegisterCapabilitiesEvent event) {
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, Content.openCrateBE.get(), (be, side) -> new InvWrapper(be));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, Content.connectorBE.get(), (be, side) -> new PlatformItemHandler(be));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, Content.invInterfaceBE.get(), (be, side) -> new PlatformItemHandler(be));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, Content.filingCabinetBE.get(), (be, side) -> new InvWrapper(be.getInv()));
		// Shulker Dock: 27 slots while a box is docked, 0 otherwise (InvWrapper reads the size live)
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, Content.shulkerDockBE.get(), (be, side) -> new InvWrapper(be.getInv()));
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, Content.invProxyBE.get(), (be, side) -> new PlatformItemHandler(be));
	}
}
