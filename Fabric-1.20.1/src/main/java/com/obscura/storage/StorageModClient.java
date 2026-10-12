package com.obscura.storage;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPickBlockApplyCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.obscura.storage.block.IConfiguratorHighlight;
import com.obscura.storage.client.CustomRenderTypes;
import com.obscura.storage.client.FrameLabelsClient;

import com.obscura.storage.client.ItemLocatorClient;
import com.obscura.storage.gui.CraftingTerminalScreen;
import com.obscura.storage.gui.DisposalScreen;
import com.obscura.storage.gui.EjectorScreen;
import com.obscura.storage.gui.ExporterScreen;
import com.obscura.storage.gui.FilingCabinetScreen;
import com.obscura.storage.gui.InventoryConfiguratorScreen;
import com.obscura.storage.gui.InventoryLinkScreen;
import com.obscura.storage.gui.ConditionFilterScreen;
import com.obscura.storage.gui.ItemFilterScreen;
import com.obscura.storage.gui.LevelEmitterScreen;
import com.obscura.storage.gui.StorageTerminalMenu;
import com.obscura.storage.gui.StorageTerminalScreen;
import com.obscura.storage.gui.TagItemFilterScreen;
import com.obscura.storage.util.ConfiguratorData;
import com.obscura.storage.util.FrameLabels;
import com.obscura.storage.item.BoundTerminal;
import com.obscura.storage.item.WirelessTerminal;
import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.item.WirelessTerminalItem;
import com.obscura.storage.model.BakedPaintedModel;
import com.obscura.storage.network.LocateResultPacket;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.tile.PaintedBlockEntity;
import com.obscura.storage.util.IDataReceiver;
import com.obscura.storage.util.PlayerInvUtil;

import io.netty.buffer.ByteBufInputStream;

public class StorageModClient implements ClientModInitializer {
	protected static final ResourceLocation PAINT = new ResourceLocation(StorageMod.modid, "paint");
	public static KeyMapping openTerm;
	public static KeyMapping toggleRestock;
	public static KeyMapping highlightNetwork;

	@Override
	public void onInitializeClient() {
		// Key bindings saved under the old names (before v5.0.0). Client entrypoints run before the game
		// creates its Options, so the copied lines are read this very start.
		LegacyFiles.migrateKeyBindings(FabricLoader.getInstance().getGameDir().resolve("options.txt"));
		MenuScreens.register(Content.storageTerminal.get(), StorageTerminalScreen::new);
		MenuScreens.register(Content.craftingTerminalCont.get(), CraftingTerminalScreen::new);
		MenuScreens.register(Content.levelEmitterConatiner.get(), LevelEmitterScreen::new);
		MenuScreens.register(Content.inventoryLink.get(), InventoryLinkScreen::new);
		MenuScreens.register(Content.itemFilterConatiner.get(), ItemFilterScreen::new);
		MenuScreens.register(Content.tagItemFilterConatiner.get(), TagItemFilterScreen::new);
		MenuScreens.register(Content.conditionFilterConatiner.get(), ConditionFilterScreen::new);
		MenuScreens.register(Content.invConfigConatiner.get(), InventoryConfiguratorScreen::new);
		MenuScreens.register(Content.filingCabinetContainer.get(), FilingCabinetScreen::new);
		MenuScreens.register(Content.exporterMenu.get(), ExporterScreen::new);
		MenuScreens.register(Content.ejectorMenu.get(), EjectorScreen::new);
		MenuScreens.register(Content.disposalMenu.get(), DisposalScreen::new);

		BlockRenderLayerMap.INSTANCE.putBlock(Content.paintedTrim.get(), RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(StorageMod.invCablePainted.get(), RenderType.cutout());
		BlockRenderLayerMap.INSTANCE.putBlock(Content.levelEmitter.get(), RenderType.cutout());
		BlockRenderLayerMap.INSTANCE.putBlock(StorageMod.invCableConnectorPainted.get(), RenderType.cutout());
		BlockRenderLayerMap.INSTANCE.putBlock(StorageMod.invProxyPainted.get(), RenderType.cutout());

		ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.DATA_S2C, (mc, h, buf, rp) -> {
			CompoundTag tag = Platform.readNbtTag(buf);
			mc.submit(() -> {
				// Frame Labels status for the Inventory Configurator HUD
				if(tag.contains(FrameLabels.STATUS_KEY)) {
					FrameLabelsClient.receive(tag);
					return;
				}
				// The terminal sync state (TerminalSyncManager) lives in the menu, so deliver to it directly.
				// Going through mc.screen would drop the packet while another screen (JEI/REI/EMI) is open on top.
				if(mc.player != null && mc.player.containerMenu instanceof StorageTerminalMenu menu) {
					menu.receiveClientNBTPacket(tag);
				} else if(mc.screen instanceof IDataReceiver) {
					((IDataReceiver)mc.screen).receive(tag);
				}
			});
		});

		ModelLoadingPlugin.register(new ModelLoadingPlugin() {

			@Override
			public void onInitializeModelLoader(Context ctx) {
				ctx.resolveModel().register(c -> c.id().equals(PAINT) ? new BakedPaintedModel() : null);
			}
		});

		ColorProviderRegistry.BLOCK.register((state, world, pos, tintIndex) -> {
			if (world != null) {
				try {
					BlockState mimicBlock = ((PaintedBlockEntity)world.getBlockEntity(pos)).getPaintedBlockState();
					return Minecraft.getInstance().getBlockColors().getColor(mimicBlock, world, pos, tintIndex);
				} catch (Exception var8) {
					return -1;
				}
			}
			return -1;
		}, Content.paintedTrim.get(), StorageMod.invCablePainted.get(), StorageMod.invCableConnectorPainted.get(), StorageMod.invProxyPainted.get());

		WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((ctx, hr) -> {
			Minecraft mc = Minecraft.getInstance();
			Player player = mc.player;
			if( player == null )
				return true;

			if(!WirelessTerminalItem.isPlayerHolding(player)) {
				drawConfiguratorOutline(ctx.matrixStack());
				return true;
			}

			BlockHitResult lookingAt = (BlockHitResult) player.pick(StorageMod.CONFIG.wirelessRange, 0f, true);
			BlockState state = mc.level.getBlockState(lookingAt.getBlockPos());
			if(state.is(StorageTags.REMOTE_ACTIVATE)) {
				BlockPos pos = lookingAt.getBlockPos();
				Vec3 renderPos = mc.gameRenderer.getMainCamera().getPosition();
				VertexConsumer buf = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
				drawShapeOutline(ctx.matrixStack(), buf, state.getCollisionShape(player.level(), pos), pos.getX() - renderPos.x, pos.getY() - renderPos.y, pos.getZ() - renderPos.z, 1, 1, 1, 0.4f);
				mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
				drawConfiguratorOutline(ctx.matrixStack());
				return false;
			}

			drawConfiguratorOutline(ctx.matrixStack());

			return true;
		});

		WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> ItemLocatorClient.render(ctx.matrixStack()));

		// Item Locator reply. The request travels as a terminal interaction (SlotAction.LOCATE) in DATA_C2S.
		ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.LOCATE_S2C, (mc, h, buf, rp) -> {
			LocateResultPacket packet = LocateResultPacket.read(buf);
			mc.submit(() -> ItemLocatorClient.handleResult(packet));
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> clearWorldState());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> clearWorldState());

		HudRenderCallback.EVENT.register((graphics, tickDelta) -> drawConfiguratorOverlay(graphics));

		try {
			Class<?> clz = Class.forName("com.kqp.inventorytabs.api.TabProviderRegistry");
			Method regSimpleBlock = clz.getDeclaredMethod("registerSimpleBlock", Block.class);
			regSimpleBlock.invoke(null, Content.terminal.get());
			regSimpleBlock.invoke(null, Content.craftingTerminal.get());
		} catch (Throwable e) {
		}

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
			return CompletableFuture.completedFuture(PacketByteBufs.empty());
		});

		openTerm = new KeyMapping("key.obscuras_storage.open_terminal", InputConstants.KEY_B, KeyMapping.CATEGORY_GAMEPLAY);
		KeyBindingHelper.registerKeyBinding(openTerm);
		toggleRestock = new KeyMapping("key.obscuras_storage.toggle_restock", InputConstants.UNKNOWN.getValue(), KeyMapping.CATEGORY_GAMEPLAY);
		KeyBindingHelper.registerKeyBinding(toggleRestock);
		highlightNetwork = new KeyMapping("key.obscuras_storage.highlight_network", InputConstants.KEY_H, KeyMapping.CATEGORY_GAMEPLAY);
		KeyBindingHelper.registerKeyBinding(highlightNetwork);

		// Indigo and Indium register their renderer in their own initializers, so check once the client is up.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			boolean sodium05 = FabricLoader.getInstance().getModContainer("sodium")
					.map(m -> m.getMetadata().getVersion().getFriendlyString().startsWith("0.5")).orElse(false);
			if(!RendererAccess.INSTANCE.hasRenderer() && !sodium05)
				StorageMod.LOGGER.warn("No Fabric Rendering API renderer found: painted blocks render as plain stone. With Sodium, install Indium.");
		});

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

		ItemTooltipCallback.EVENT.register((s, c, l) -> {
			Collections.addAll(l, tooltipExt);
		});
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
		boolean hasTerminal = PlayerInvUtil.findItem(player, s -> s.getItem() instanceof WirelessTerminal && BoundTerminal.isBound(s), false, s -> true);
		if (!hasTerminal)return;
		NetworkHandler.pickFromStorage(hit);
	}

	private static void drawShapeOutline(PoseStack matrices, VertexConsumer vertexConsumer, VoxelShape voxelShape, double d, double e, double f, float g, float h, float i, float j) {
		PoseStack.Pose entry = matrices.last();
		voxelShape.forAllEdges((k, l, m, n, o, p) -> {
			float q = (float)(n - k);
			float r = (float)(o - l);
			float s = (float)(p - m);
			float t = Mth.sqrt(q * q + r * r + s * s);
			q /= t;
			r /= t;
			s /= t;
			vertexConsumer.vertex(entry.pose(), (float)(k + d), (float)(l + e), (float)(m + f)).color(g, h, i, j).normal(entry.normal(), q, r, s).endVertex();
			vertexConsumer.vertex(entry.pose(), (float)(n + d), (float)(o + e), (float)(p + f)).color(g, h, i, j).normal(entry.normal(), q, r, s).endVertex();
		});
	}

	private static ItemStack getConfigurator(Player player) {
		ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
		if (!stack.is(Content.invConfig.get())) {
			stack = player.getItemInHand(InteractionHand.OFF_HAND);
		}
		return stack;
	}

	private static void drawConfiguratorOutline(PoseStack matrices) {
		Minecraft mc = Minecraft.getInstance();
		Player player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}

		ItemStack stack = getConfigurator(player);
		if (!stack.is(Content.invConfig.get())) {
			return;
		}

		// Frame Labels: aqua boxes around the labelled inventories nearby
		FrameLabelsClient.drawBoxes(matrices);

		ConfiguratorData.State state = ConfiguratorData.get(stack);
		if (!state.selecting() && !state.showInvBox()) {
			drawPaintedHighlights(matrices);
			return;
		}

		Vec3 renderPos = mc.gameRenderer.getMainCamera().getPosition();
		VertexConsumer buf = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());

		for (BlockPos pos : state.selection()) {
			double x = pos.getX() - renderPos.x;
			double y = pos.getY() - renderPos.y;
			double z = pos.getZ() - renderPos.z;
			LevelRenderer.renderLineBox(matrices, buf, x, y, z, x + 1, y + 1, z + 1, 1f, 1f, 1f, 0.4f);
		}

		VertexConsumer bufNd = mc.renderBuffers().bufferSource().getBuffer(CustomRenderTypes.linesNoDepth());

		if (state.massSelect() && mc.hitResult instanceof BlockHitResult hr) {
			BlockPos start = state.boxStart();
			BlockPos end = hr.getBlockPos();
			AABB bb = new AABB(
					Math.min(start.getX(), end.getX()),
					Math.min(start.getY(), end.getY()),
					Math.min(start.getZ(), end.getZ()),
					Math.max(start.getX(), end.getX()) + 1,
					Math.max(start.getY(), end.getY()) + 1,
					Math.max(start.getZ(), end.getZ()) + 1
			);
			bb = bb.move(-renderPos.x, -renderPos.y, -renderPos.z);
			LevelRenderer.renderLineBox(matrices, bufNd, bb, 1f, 1f, 0f, 0.4f);
		}

		if (state.isBound()) {
			double x = state.bound().getX() - renderPos.x;
			double y = state.bound().getY() - renderPos.y;
			double z = state.bound().getZ() - renderPos.z;
			LevelRenderer.renderLineBox(matrices, bufNd, x, y, z, x + 1, y + 1, z + 1, 1f, 0f, 0f, 1f);
		}

		mc.renderBuffers().bufferSource().endBatch();
	}

	/** Outlines the real shape of painted and framed blocks near the player, so they can be found while configuring. */
	private static void drawPaintedHighlights(PoseStack matrices) {
		Minecraft mc = Minecraft.getInstance();
		Player player = mc.player;
		VertexConsumer buf = mc.renderBuffers().bufferSource().getBuffer(CustomRenderTypes.linesNoDepth());
		Vec3 renderPos = mc.gameRenderer.getMainCamera().getPosition();

		BlockPos.betweenClosedStream(new AABB(player.blockPosition()).inflate(7)).forEach(pos -> {
			double dist = renderPos.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
			if (dist > 49)return;

			BlockState state = mc.level.getBlockState(pos);
			if (state.getBlock() instanceof IConfiguratorHighlight h) {
				VoxelShape shape = h.getHighlightShape(state, mc.level, pos);
				int color = h.getHighlightColor();
				float a = 1f;
				if (dist > 25) {
					a = 1f - ((float) ((dist - 25) / 24f));
				}

				drawShapeOutline(matrices, buf, shape,
						pos.getX() - renderPos.x, pos.getY() - renderPos.y, pos.getZ() - renderPos.z,
						FastColor.ARGB32.red(color) / 255f, FastColor.ARGB32.green(color) / 255f, FastColor.ARGB32.blue(color) / 255f,
						a);
			}
		});
		mc.renderBuffers().bufferSource().endBatch(CustomRenderTypes.linesNoDepth());
	}

	private static void drawConfiguratorOverlay(GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		Player player = mc.player;
		if (player == null || mc.screen != null) {
			return;
		}

		ItemStack stack = getConfigurator(player);
		if (!stack.is(Content.invConfig.get())) {
			return;
		}

		ConfiguratorData.State state = ConfiguratorData.get(stack);
		List<Component> messages = new ArrayList<>();
		Component leftClk = Component.keybind("key.attack").withStyle(ChatFormatting.GREEN);
		Component rightClk = Component.keybind("key.use").withStyle(ChatFormatting.GREEN);
		Component shift = Component.keybind("key.sneak").withStyle(ChatFormatting.GREEN);

		if (state.isBound()) {
			int x = state.bound().getX();
			int y = state.bound().getY();
			int z = state.bound().getZ();
			if (player.distanceToSqr(x, y, z) < 64 * 64) {
				Component lookingAt = null;
				BlockState bs = mc.level.getBlockState(state.bound());
				ItemStack item = bs.getBlock().getCloneItemStack(mc.level, state.bound(), bs);
				if (!item.isEmpty()) {
					lookingAt = item.getHoverName();
				}
				if (lookingAt == null)lookingAt = Component.literal("?");
				if (mc.showOnlyReducedInfo()) {
					messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.block_alt", lookingAt));
				} else {
					messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.block", x, y, z));
					messages.add(lookingAt);
				}
				if (state.selecting()) {
					if (state.massSelect()) {
						messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.mass_select").withStyle(ChatFormatting.GOLD));
						messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.mass_select.finish", leftClk));
						messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.mass_select.abort", shift, leftClk));
					} else {
						messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.select").withStyle(ChatFormatting.GOLD));
						messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.select.single", rightClk));
						messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.select.finish", rightClk));
						messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.select.start_mass", leftClk));
					}
				}
			} else {
				messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.too_far"));
			}
		} else {
			messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.configure", rightClk));
			messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.show", leftClk));
		}

		// Frame Labels: the label of the inventory looked at
		FrameLabelsClient.appendHud(mc, messages);

		if (!messages.isEmpty()) {
			graphics.renderTooltip(mc.font, messages, Optional.empty(), 5, 25);
		}
	}

	public static void tooltip(String key, List<Component> tooltip, Object... args) {
		tooltip(key, true, tooltip, args);
	}

	public static void tooltip(String key, boolean shift, List<Component> tooltip, Object... args) {
		if(Screen.hasShiftDown()) {
			String[] sp = I18n.get("tooltip.obscuras_storage." + key, args).split("\\\\");
			for (int i = 0; i < sp.length; i++) {
				tooltip.add(Component.literal(sp[i]));
			}
		} else if(shift) {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.hold_shift_for_info").withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
		}
	}

	/**
	 * Contents of a filter item while CTRL (CMD on macOS) is held, otherwise the hint to hold it.
	 *
	 * @param entries one line per filtered item or tag
	 * @param warnEmptyAllow warn when an allow list is empty (it lets nothing through)
	 */
	public static void filterContentsTooltip(List<Component> tooltip, List<Component> entries, boolean allow, boolean warnEmptyAllow) {
		if(Screen.hasControlDown()) {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.item_filter.contents"));
			if (entries.isEmpty()) {
				tooltip.add(Component.translatable("tooltip.obscuras_storage.item_filter.no_items"));
			} else {
				for (Component e : entries) {
					tooltip.add(Component.translatable("tooltip.obscuras_storage.item_filter.prefix", e));
				}
			}
			tooltip.add(Component.translatable(allow ? "tooltip.obscuras_storage.allowList_1" : "tooltip.obscuras_storage.allowList_0"));
			if (warnEmptyAllow && allow && entries.isEmpty()) {
				tooltip.add(Component.translatable("tooltip.obscuras_storage.empty_whitelist_warning").withStyle(ChatFormatting.RED));
			}
		} else {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.hold_control_for_details", Minecraft.ON_OSX ? "CMD" : "CTRL").withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
		}
	}

	private static Component[] tooltipExt = new Component[0];
	public static void setTooltip(Component... string) {
		tooltipExt = string;
	}
}
