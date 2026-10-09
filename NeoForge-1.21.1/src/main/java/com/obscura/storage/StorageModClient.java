package com.obscura.storage;

import javax.annotation.Nullable;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import com.obscura.storage.block.entity.PaintedBlockEntity;
import com.obscura.storage.client.BakedPaintedModel;
import com.obscura.storage.client.ClientUtil;
import com.obscura.storage.client.FrameLabelsClient;
import com.obscura.storage.client.ItemLocatorClient;
import com.obscura.storage.item.WirelessTerminal;
import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.platform.GameObject;
import com.obscura.storage.screen.ConditionFilterScreen;
import com.obscura.storage.screen.CraftingTerminalScreen;
import com.obscura.storage.screen.DisposalScreen;
import com.obscura.storage.screen.EjectorScreen;
import com.obscura.storage.screen.ExporterScreen;
import com.obscura.storage.screen.FilingCabinetScreen;
import com.obscura.storage.screen.InventoryConfiguratorScreen;
import com.obscura.storage.screen.InventoryLinkScreen;
import com.obscura.storage.screen.ItemFilterScreen;
import com.obscura.storage.screen.LevelEmitterScreen;
import com.obscura.storage.screen.StorageTerminalScreen;
import com.obscura.storage.screen.TagItemFilterScreen;
import com.obscura.storage.util.PlayerInvUtil;

public class StorageModClient {
	public static KeyMapping openTerm;
	public static KeyMapping toggleRestock;
	public static KeyMapping highlightNetwork;
	public static final ResourceLocation CONFIGURATOR_OVERLAY_ID = ResourceLocation.tryBuild(StorageMod.modid, "configurator_info");

	public static void preInit(ModContainer mc, IEventBus bus) {
		// Key bindings saved under the old names (before v5.0.0). NeoForge reads the key bindings from
		// options.txt again after mod loading, so the copied lines are picked up this start.
		LegacyFiles.migrateKeyBindings(FMLPaths.GAMEDIR.get().resolve("options.txt"));
		bus.addListener(StorageModClient::registerColors);
		bus.addListener(StorageModClient::initKeybinds);
		bus.addListener(StorageModClient::bakeModels);
		bus.addListener(StorageModClient::registerScreens);
		bus.addListener(StorageModClient::registerOverlays);
		mc.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
	}

	private static void registerScreens(RegisterMenuScreensEvent e) {
		e.register(Content.storageTerminalMenu.get(), StorageTerminalScreen::new);
		e.register(Content.craftingTerminalMenu.get(), CraftingTerminalScreen::new);
		e.register(Content.levelEmitterMenu.get(), LevelEmitterScreen::new);
		e.register(Content.inventoryLink.get(), InventoryLinkScreen::new);
		e.register(Content.itemFilterMenu.get(), ItemFilterScreen::new);
		e.register(Content.tagItemFilterMenu.get(), TagItemFilterScreen::new);
		e.register(Content.conditionFilterMenu.get(), ConditionFilterScreen::new);
		e.register(Content.invConfigMenu.get(), InventoryConfiguratorScreen::new);
		e.register(Content.filingCabinetMenu.get(), FilingCabinetScreen::new);
		e.register(Content.exporterMenu.get(), ExporterScreen::new);
		e.register(Content.ejectorMenu.get(), EjectorScreen::new);
		e.register(Content.disposalMenu.get(), DisposalScreen::new);
	}

	/** Main thread (enqueued from FMLClientSetupEvent). */
	@SuppressWarnings("deprecation")
	public static void clientSetup() {
		// Same layers as the Fabric build (BlockRenderLayerMap). The painted model asks for the
		// layers of the model it actually draws, and these for the unpainted look.
		ItemBlockRenderTypes.setRenderLayer(Content.paintedTrim.get(), RenderType.translucent());
		ItemBlockRenderTypes.setRenderLayer(Content.invCableFramed.get(), RenderType.translucent());
		ItemBlockRenderTypes.setRenderLayer(Content.levelEmitter.get(), RenderType.cutout());
		ItemBlockRenderTypes.setRenderLayer(Content.invCableConnectorFramed.get(), RenderType.translucent());
		ItemBlockRenderTypes.setRenderLayer(Content.invProxy.get(), RenderType.translucent());

		NeoForge.EVENT_BUS.register(StorageModClient.class);
	}

	public static void registerOverlays(RegisterGuiLayersEvent event) {
		event.registerAboveAll(CONFIGURATOR_OVERLAY_ID, StorageModClient::renderConfiguratorOverlay);
	}

	public static void renderConfiguratorOverlay(GuiGraphics gr, DeltaTracker p_348559_) {
		ClientUtil.drawConfiguratorOverlay(gr);
	}

	@SubscribeEvent
	public static void getTooltip(ItemTooltipEvent evt) {
		ClientUtil.collectExtraTooltips(evt.getItemStack(), evt.getToolTip());
	}

	private static void initKeybinds(RegisterKeyMappingsEvent evt) {
		openTerm = new KeyMapping("key.obscuras_storage.open_terminal", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_B), KeyMapping.CATEGORY_GAMEPLAY);
		evt.register(openTerm);
		// Unbound by default, like on Fabric
		toggleRestock = new KeyMapping("key.obscuras_storage.toggle_restock", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, KeyMapping.CATEGORY_GAMEPLAY);
		evt.register(toggleRestock);
		highlightNetwork = new KeyMapping("key.obscuras_storage.highlight_network", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_H), KeyMapping.CATEGORY_GAMEPLAY);
		evt.register(highlightNetwork);
	}

	private static void registerColors(RegisterColorHandlersEvent.Block event) {
		event.register(StorageModClient::getColor, Content.paintedTrim.get(), Content.invCableFramed.get(), Content.invProxy.get(), Content.invCableConnectorFramed.get());
	}

	private static int getColor(BlockState state, @Nullable BlockAndTintGetter world, @Nullable BlockPos pos, int tintIndex) {
		if (world != null && pos != null) {
			try {
				BlockState mimicBlock = ((PaintedBlockEntity)world.getBlockEntity(pos)).getPaintedBlockState();
				return Minecraft.getInstance().getBlockColors().getColor(mimicBlock, world, pos, tintIndex);
			} catch (Exception var8) {
				StorageMod.LOGGER.debug("Failed to get painted block color", var8);
				return -1;
			}
		}
		return -1;
	}

	private static void bakeModels(ModelEvent.ModifyBakingResult event) {
		bindPaintedModel(event, Content.paintedTrim);
		bindPaintedModel(event, Content.invCableFramed);
		bindPaintedModel(event, Content.invProxy);
		bindPaintedModel(event, Content.invCableConnectorFramed);
	}

	private static void bindPaintedModel(ModelEvent.ModifyBakingResult event, GameObject<? extends Block> block) {
		ResourceLocation baseLoc = block.getId();
		block.get().getStateDefinition().getPossibleStates().forEach(st -> {
			ModelResourceLocation resLoc = BlockModelShaper.stateToModelLocation(baseLoc, st);
			BakedModel m = event.getModels().get(resLoc);
			if (m != null && !(m instanceof BakedPaintedModel))
				event.getModels().put(resLoc, new BakedPaintedModel(block.get(), m));
		});
	}

	@SubscribeEvent
	public static void renderWorldOutline(RenderLevelStageEvent evt) {
		if(evt.getStage() == Stage.AFTER_PARTICLES) {
			PoseStack ps = evt.getPoseStack();
			ps.pushPose();
			ClientUtil.drawTerminalOutline(ps);
			ClientUtil.drawConfiguratorOutline(ps);
			ps.popPose();
			ItemLocatorClient.render();
		}
	}

	@SubscribeEvent
	public static void clientTick(ClientTickEvent.Post evt) {
		if (Minecraft.getInstance().player == null)
			return;

		if(openTerm.consumeClick()) {
			NetworkHandler.openTerminal();
		}
		if(toggleRestock.consumeClick()) {
			NetworkHandler.toggleRestock();
		}
		if(highlightNetwork.consumeClick()) {
			ItemLocatorClient.requestNetwork(Minecraft.getInstance());
		}
	}

	@SubscribeEvent
	public static void onLogIn(ClientPlayerNetworkEvent.LoggingIn evt) {
		clearWorldState();
	}

	@SubscribeEvent
	public static void onLogOut(ClientPlayerNetworkEvent.LoggingOut evt) {
		clearWorldState();
	}

	/** Nothing of the world just left may stay on the client. */
	private static void clearWorldState() {
		ItemLocatorClient.clear();
		FrameLabelsClient.clear();
	}

	/**
	 * Builder's Reach: fires right before vanilla looks for the picked item in the inventory. Survival
	 * pick-block that will find nothing there asks the server to pull the block from storage. Vanilla
	 * still runs afterwards (and does nothing, as it finds no slot).
	 */
	@SubscribeEvent
	public static void onPickBlock(InputEvent.InteractionKeyMappingTriggered evt) {
		if (!evt.isPickBlock())return;
		Minecraft mc = Minecraft.getInstance();
		Player player = mc.player;
		if (player == null || mc.level == null)return;
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)return;
		if (player.getAbilities().instabuild || player.isSpectator())return;
		if (!Config.get().buildersReachEnabled)return;
		BlockState state = mc.level.getBlockState(hit.getBlockPos());
		if (state.isAir())return;
		ItemStack stack = state.getCloneItemStack(hit, mc.level, hit.getBlockPos(), player);
		if (stack.isEmpty())return;
		if (player.getInventory().findSlotMatchingItem(stack) != -1)return;
		boolean hasTerminal = PlayerInvUtil.findItem(player, s -> s.getItem() instanceof WirelessTerminal && s.has(Content.boundPosComponent.get()), false, s -> true);
		if (!hasTerminal)return;
		NetworkHandler.pickFromStorage(hit);
	}
}
