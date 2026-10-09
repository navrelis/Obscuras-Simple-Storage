package com.obscura.storage.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.obscura.storage.item.WirelessTerminal;
import com.obscura.storage.menu.StorageTerminalMenu;
import com.obscura.storage.network.LocateResultPacket;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.util.HighlightNetwork;
import com.obscura.storage.util.ItemLocator;

/**
 * Client side of the Item Locator and of "Highlight Connected Inventories" (a result without an item):
 * the request cooldown, the result message and the through-wall highlight.
 */
public class ItemLocatorClient {
	private static final long DURATION_MS = 10_000;
	private static final long FADE_MS = 1_000;
	private static final long REQUEST_COOLDOWN_MS = 1_000;
	private static final float R = 0.25f, G = 0.95f, B = 1f;
	/** Outline colour of the connected inventories of a network. */
	private static final float ALL_R = 0.35f, ALL_G = 1f, ALL_B = 0.45f;

	private static List<AABB> boxes = List.of();
	private static ClientLevel level;
	private static boolean network;
	private static long startTime;
	private static long lastRequest;

	/** Screen: true (and starts the cooldown) when a new locate request may be sent now. Mirrors the server's rate limit. */
	public static boolean tryStartRequest() {
		long now = Util.getMillis();
		if (now - lastRequest < REQUEST_COOLDOWN_MS)return false;
		lastRequest = now;
		return true;
	}

	/**
	 * The Highlight Connected Inventories key, pressed in the world: asks the server for the network of the
	 * terminal, cable or connector pointed at, otherwise for the one a wireless terminal in hand is bound to.
	 */
	public static void requestNetwork(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.screen != null || !ItemLocator.isEnabled())return;
		BlockPos target = null;
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && HighlightNetwork.isNetworkBlock(mc.level.getBlockState(hit.getBlockPos())))
			target = hit.getBlockPos();
		else if (!(player.getMainHandItem().getItem() instanceof WirelessTerminal) && !(player.getOffhandItem().getItem() instanceof WirelessTerminal))
			return;
		if (tryStartRequest())NetworkHandler.highlightNetwork(target);
	}

	public static void handleResult(LocateResultPacket p) {
		Minecraft mc = Minecraft.getInstance();
		// LocalPlayer, not Player: its closeContainer() is public in vanilla, Player's is protected
		// (only widened on Fabric by Architectury's access widener).
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null)return;
		if (player.containerMenu instanceof StorageTerminalMenu)player.closeContainer();

		List<AABB> b = buildBoxes(mc.level, p.positions());
		if (b.isEmpty()) {
			clear();
		} else {
			boxes = b;
			network = p.isAll();
			level = mc.level;
			startTime = Util.getMillis();
		}
		player.displayClientMessage(buildMessage(p), true);
	}

	private static MutableComponent buildMessage(LocateResultPacket p) {
		if (p.isAll())return buildAllMessage(p);
		Component name = p.item().getHoverName();
		int total = p.inDimension() + p.otherDimensions();
		if (total == 0) {
			if (p.unknown() == 0)return Component.translatable("chat.obscuras_storage.locate.not_found", name).withStyle(ChatFormatting.RED);
			return Component.translatable("chat.obscuras_storage.locate.only_unknown", name).withStyle(ChatFormatting.GOLD);
		}
		MutableComponent msg = total == 1 ?
				Component.translatable("chat.obscuras_storage.locate.found.one", name) :
					Component.translatable("chat.obscuras_storage.locate.found", name, total);
		if (p.otherDimensions() > 0)msg.append(Component.translatable("chat.obscuras_storage.locate.other_dim", p.otherDimensions()));
		if (p.unknown() > 0)msg.append(Component.translatable("chat.obscuras_storage.locate.unknown", p.unknown()));
		if (p.inDimension() > p.positions().size())msg.append(Component.translatable("chat.obscuras_storage.locate.capped", p.positions().size()));
		return msg.withStyle(ChatFormatting.AQUA);
	}

	private static MutableComponent buildAllMessage(LocateResultPacket p) {
		int total = p.inDimension() + p.otherDimensions();
		if (total == 0) {
			if (p.unknown() == 0)return Component.translatable("chat.obscuras_storage.locate_all.none").withStyle(ChatFormatting.RED);
			return Component.translatable("chat.obscuras_storage.locate_all.only_unknown").withStyle(ChatFormatting.GOLD);
		}
		MutableComponent msg = total == 1 ?
				Component.translatable("chat.obscuras_storage.locate_all.found.one") :
					Component.translatable("chat.obscuras_storage.locate_all.found", total);
		if (p.otherDimensions() > 0)msg.append(Component.translatable("chat.obscuras_storage.locate.other_dim", p.otherDimensions()));
		if (p.unknown() > 0)msg.append(Component.translatable("chat.obscuras_storage.locate.unknown", p.unknown()));
		if (p.inDimension() > p.positions().size())msg.append(Component.translatable("chat.obscuras_storage.locate.capped", p.positions().size()));
		return msg.withStyle(ChatFormatting.GREEN);
	}

	/** One box per position, both halves of a (loaded) double chest merged into one. */
	private static List<AABB> buildBoxes(ClientLevel level, List<BlockPos> positions) {
		List<AABB> out = new ArrayList<>();
		Set<BlockPos> done = new HashSet<>();
		for (BlockPos pos : positions) {
			if (!done.add(pos))continue;
			AABB box = new AABB(pos);
			if (level.hasChunkAt(pos)) {
				BlockState st = level.getBlockState(pos);
				if (st.getBlock() instanceof ChestBlock && st.hasProperty(ChestBlock.TYPE) && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
					BlockPos other = pos.relative(ChestBlock.getConnectedDirection(st));
					if (level.getBlockState(other).is(st.getBlock())) {
						box = box.minmax(new AABB(other));
						done.add(other);
					}
				}
			}
			out.add(box.inflate(0.002));
		}
		return out;
	}

	public static void clear() {
		boxes = List.of();
		level = null;
	}

	/** World render hook. Returns right away unless a highlight is active. */
	public static void render() {
		if (boxes.isEmpty())return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level != level) {
			// Dimension change or new world: the positions no longer apply
			clear();
			return;
		}
		long t = Util.getMillis() - startTime;
		if (t >= DURATION_MS) {
			clear();
			return;
		}
		float pulse = 0.5f + 0.5f * Mth.sin(t * (Mth.TWO_PI / 1200f));
		float a = 0.45f + 0.55f * pulse;
		long left = DURATION_MS - t;
		if (left < FADE_MS)a *= left / (float) FADE_MS;

		PoseStack ps = new PoseStack();
		Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
		MultiBufferSource.BufferSource src = mc.renderBuffers().bufferSource();
		// No depth test: visible through blocks. The render type restores the GL state in endBatch.
		VertexConsumer buf = src.getBuffer(CustomRenderTypes.linesNoDepth());
		float r = network ? ALL_R : R, g = network ? ALL_G : G, b = network ? ALL_B : B;
		for (AABB box : boxes) {
			LevelRenderer.renderLineBox(ps, buf, box.move(-cam.x, -cam.y, -cam.z), r, g, b, a);
		}
		src.endBatch(CustomRenderTypes.linesNoDepth());
	}
}
