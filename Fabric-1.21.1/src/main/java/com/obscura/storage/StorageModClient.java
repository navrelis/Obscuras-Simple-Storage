package com.obscura.storage;

import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPickBlockApplyCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import com.obscura.storage.block.entity.PaintedBlockEntity;
import com.obscura.storage.client.BakedPaintedModel;
import com.obscura.storage.client.ClientUtil;
import com.obscura.storage.client.FrameLabelsClient;
import com.obscura.storage.client.ItemLocatorClient;
import com.obscura.storage.inventory.FrameLabels;
import com.obscura.storage.item.WirelessTerminal;
import com.obscura.storage.menu.StorageTerminalMenu;
import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.network.DataPacket;
import com.obscura.storage.network.LocateResultPacket;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.platform.GameObject;
import com.obscura.storage.polymorph.PolymorphTerminalWidget;
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
import com.obscura.storage.util.IDataReceiver;
import com.obscura.storage.util.PlayerInvUtil;

import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;

public class StorageModClient implements ClientModInitializer {
	public static KeyMapping openTerm;
	public static KeyMapping toggleRestock;
	public static KeyMapping highlightNetwork;

	@Override
	public void onInitializeClient() {
		// Key bindings saved under the old names (before v5.0.0). Client entrypoints run before the game
		// creates its Options, so the copied lines are read this very start.
		LegacyFiles.migrateKeyBindings(FabricLoader.getInstance().getGameDir().resolve("options.txt"));
		MenuScreens.register(Content.storageTerminalMenu.get(), StorageTerminalScreen::new);
		MenuScreens.register(Content.craftingTerminalMenu.get(), CraftingTerminalScreen::new);
		MenuScreens.register(Content.invConfigMenu.get(), InventoryConfiguratorScreen::new);
		MenuScreens.register(Content.levelEmitterMenu.get(), LevelEmitterScreen::new);
		MenuScreens.register(Content.inventoryLink.get(), InventoryLinkScreen::new);
		MenuScreens.register(Content.itemFilterMenu.get(), ItemFilterScreen::new);
		MenuScreens.register(Content.tagItemFilterMenu.get(), TagItemFilterScreen::new);
		MenuScreens.register(Content.conditionFilterMenu.get(), ConditionFilterScreen::new);
		MenuScreens.register(Content.filingCabinetMenu.get(), FilingCabinetScreen::new);
		MenuScreens.register(Content.exporterMenu.get(), ExporterScreen::new);
		MenuScreens.register(Content.ejectorMenu.get(), EjectorScreen::new);
		MenuScreens.register(Content.disposalMenu.get(), DisposalScreen::new);

		BlockRenderLayerMap.INSTANCE.putBlock(Content.paintedTrim.get(), RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(Content.invCableFramed.get(), RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(Content.levelEmitter.get(), RenderType.cutout());
		BlockRenderLayerMap.INSTANCE.putBlock(Content.invCableConnectorFramed.get(), RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(Content.invProxy.get(), RenderType.translucent());

		ClientPlayNetworking.registerGlobalReceiver(DataPacket.ID, (p, c) -> {
			// Frame Labels status for the Inventory Configurator HUD
			if (p.tag().contains(FrameLabels.STATUS_KEY)) {
				FrameLabelsClient.receive(p.tag());
				return;
			}
			// Terminal sync state lives in the menu, which stays open while another screen (a recipe
			// viewer) covers the terminal. Deliver it there so no delta is lost; the terminal screen
			// picks the update up from the menu. Everything else is screen-only state.
			if(c.player() != null && c.player().containerMenu instanceof StorageTerminalMenu m) {
				m.receiveClientNBTPacket(p.tag());
			} else if(Minecraft.getInstance().screen instanceof IDataReceiver d) {
				d.receive(p.tag());
			}
		});

		ModelLoadingPlugin.register(new ModelLoadingPlugin() {
			private Set<ModelResourceLocation> locs = new HashSet<>();

			@Override
			public void onInitializeModelLoader(Context ctx) {
				bakeModels(locs);
				BakedPaintedModel.clearCache();
				ctx.modifyModelAfterBake().register((p, c) -> {
					if (locs.contains(c.topLevelId()) && !(p instanceof BakedPaintedModel)) {
						return new BakedPaintedModel(p);
					}
					return p;
				});
			}
		});

		ColorProviderRegistry.BLOCK.register((state, world, pos, tintIndex) -> {
			if (world != null) {
				try {
					BlockState mimicBlock = ((PaintedBlockEntity)world.getBlockEntity(pos)).getPaintedBlockState();
					return Minecraft.getInstance().getBlockColors().getColor(mimicBlock, world, pos, tintIndex);
				} catch (Exception var8) {
					StorageMod.LOGGER.debug("Failed to get painted block color", var8);
					return -1;
				}
			}
			return -1;
		}, Content.paintedTrim.get(), Content.invCableFramed.get(), Content.invCableConnectorFramed.get(), Content.invProxy.get());

		WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> {
			PoseStack ps = new PoseStack();
			ps.pushPose();
			ClientUtil.drawTerminalOutline(ps);
			ClientUtil.drawConfiguratorOutline(ps);
			ps.popPose();
			ItemLocatorClient.render();
		});

		ClientPlayNetworking.registerGlobalReceiver(LocateResultPacket.ID, (p, c) -> ItemLocatorClient.handleResult(p));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> clearWorldState());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> clearWorldState());

		ClientLoginNetworking.registerGlobalReceiver(StorageMod.id("config"), (mc, handler, buf, fc) -> {
			Config read;
			try (InputStreamReader reader = new InputStreamReader(new ByteBufInputStream(buf))){
				read = StorageMod.gson.fromJson(reader, Config.class);
			} catch (Exception e) {
				StorageMod.LOGGER.warn("Error loading server config", e);
				return CompletableFuture.completedFuture(null);
			}
			StorageMod.CONFIG = read;
			StorageMod.LOGGER.info("Received server config");
			return CompletableFuture.completedFuture(new FriendlyByteBuf(Unpooled.EMPTY_BUFFER));
		});

		openTerm = new KeyMapping("key.obscuras_storage.open_terminal", InputConstants.KEY_B, KeyMapping.CATEGORY_GAMEPLAY);
		KeyBindingHelper.registerKeyBinding(openTerm);
		toggleRestock = new KeyMapping("key.obscuras_storage.toggle_restock", InputConstants.UNKNOWN.getValue(), KeyMapping.CATEGORY_GAMEPLAY);
		KeyBindingHelper.registerKeyBinding(toggleRestock);
		highlightNetwork = new KeyMapping("key.obscuras_storage.highlight_network", InputConstants.KEY_H, KeyMapping.CATEGORY_GAMEPLAY);
		KeyBindingHelper.registerKeyBinding(highlightNetwork);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null)
				return;

			if(openTerm.consumeClick()) {
				NetworkHandler.openTerminal();
			}
			if(toggleRestock.consumeClick()) {
				NetworkHandler.toggleRestock();
			}
			if(highlightNetwork.consumeClick()) {
				ItemLocatorClient.requestNetwork(client);
			}
		});

		// Builder's Reach: runs right before vanilla looks for the picked item in the inventory
		ClientPickBlockApplyCallback.EVENT.register((player, result, stack) -> {
			onPickBlock(player, result, stack);
			return stack;
		});

		ItemTooltipCallback.EVENT.register((s, t, c, l) -> {
			ClientUtil.collectExtraTooltips(s, l);
		});

		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			mc.gui.layers.add((gr, tr) -> ClientUtil.drawConfiguratorOverlay(gr));
		});

		if (StorageMod.polymorph)PolymorphTerminalWidget.register();
	}

	/** Nothing of the world just left may stay on the client. */
	private static void clearWorldState() {
		ItemLocatorClient.clear();
		FrameLabelsClient.clear();
	}

	/**
	 * Survival pick-block that will find nothing in the inventory: ask the server to pull the block
	 * from storage. Vanilla still runs afterwards (and does nothing, as it finds no slot).
	 */
	private static void onPickBlock(Player player, HitResult result, ItemStack stack) {
		if (stack.isEmpty() || !(result instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)return;
		if (player.getAbilities().instabuild || player.isSpectator())return;
		if (!Config.get().buildersReachEnabled)return;
		if (player.getInventory().findSlotMatchingItem(stack) != -1)return;
		boolean hasTerminal = PlayerInvUtil.findItem(player, s -> s.getItem() instanceof WirelessTerminal && s.has(Content.boundPosComponent.get()), false, s -> true);
		if (!hasTerminal)return;
		NetworkHandler.pickFromStorage(hit);
	}

	private static void bakeModels(Set<ModelResourceLocation> locs) {
		bindPaintedModel(locs, Content.paintedTrim);
		bindPaintedModel(locs, Content.invCableFramed);
		bindPaintedModel(locs, Content.invProxy);
		bindPaintedModel(locs, Content.invCableConnectorFramed);
	}

	private static void bindPaintedModel(Set<ModelResourceLocation> locs, GameObject<? extends Block> block) {
		ResourceLocation baseLoc = block.getId();
		block.get().getStateDefinition().getPossibleStates().forEach(st -> {
			ModelResourceLocation resLoc = BlockModelShaper.stateToModelLocation(baseLoc, st);
			locs.add(resLoc);
		});
	}
}
