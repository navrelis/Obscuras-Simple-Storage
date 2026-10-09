package com.obscura.storage.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.obscura.storage.Config;
import com.obscura.storage.api.MultiblockInventoryAPI;
import com.obscura.storage.inventory.FrameLabel;
import com.obscura.storage.inventory.FrameLabels;

/**
 * Frame Labels feedback while the Inventory Configurator is held: an aqua box around every
 * labelled inventory within 16 blocks, and the label lines in the Configurator HUD.
 *
 * <p>Works from the glow frames the client already has, looked up at most every 5 ticks (never per
 * frame). Only what the client cannot know comes from the server: whether the block is on a network
 * and whether a Configurator filter overrides the label ({@link FrameLabels#STATUS_KEY}).
 */
public final class FrameLabelsClient {
	private static final int REFRESH_TICKS = 5;
	private static final float R = 0.33f, G = 1f, B = 1f, A = 0.9f;
	private static final int LISTED_ITEMS = 2;

	private static Level lastLevel;
	private static long lastRefresh;
	/** The labels near the player, and the same by every block they cover. */
	private static List<Label> labels = List.of();
	private static Map<BlockPos, Label> byBlock = Map.of();
	/** Server status flags by the block the frames hang on. */
	private static Map<BlockPos, Integer> status = Map.of();

	private FrameLabelsClient() {}

	/** Forgets the world: join and disconnect. Keeps no reference to a level that is gone. */
	public static void clear() {
		lastLevel = null;
		labels = List.of();
		byBlock = Map.of();
		status = Map.of();
	}

	/** One labelled inventory: what its frames hold, every block of it (double chest) and its outline. */
	private record Label(List<ItemStack> items, Set<BlockPos> parts, AABB box) {}

	/** The server's status list (DataPacket with {@link FrameLabels#STATUS_KEY}), main thread. */
	public static void receive(CompoundTag tag) {
		ListTag l = tag.getList(FrameLabels.STATUS_KEY, Tag.TAG_INT_ARRAY);
		Map<BlockPos, Integer> m = new HashMap<>();
		for (int i = 0; i < l.size(); i++) {
			int[] a = l.getIntArray(i);
			if (a.length == 4)m.put(new BlockPos(a[0], a[1], a[2]), a[3]);
		}
		status = m;
	}

	private static void refresh(Minecraft mc) {
		Level level = mc.level;
		Player player = mc.player;
		long t = level.getGameTime();
		if (level == lastLevel && t >= lastRefresh && t - lastRefresh < REFRESH_TICKS)return;
		if (level != lastLevel)status = Map.of();
		lastLevel = level;
		lastRefresh = t;
		if (!Config.get().frameLabelsEnabled) {
			labels = List.of();
			byBlock = Map.of();
			return;
		}
		Map<BlockPos, List<ItemStack>> anchors = new HashMap<>();
		AABB area = player.getBoundingBox().inflate(FrameLabels.RANGE + 1);
		for (GlowItemFrame f : level.getEntitiesOfClass(GlowItemFrame.class, area, f -> !f.getItem().isEmpty())) {
			BlockPos a = FrameLabels.anchorOf(f);
			// Frames on walls are decoration: only blocks with a block entity can be inventories
			if (!level.getBlockState(a).hasBlockEntity())continue;
			anchors.computeIfAbsent(a, k -> new ArrayList<>(1)).add(f.getItem().copy());
		}
		if (anchors.isEmpty()) {
			labels = List.of();
			byBlock = Map.of();
			return;
		}
		List<Label> list = new ArrayList<>();
		Map<BlockPos, Label> by = new HashMap<>();
		for (BlockPos anchor : anchors.keySet()) {
			if (by.containsKey(anchor))continue;
			Set<BlockPos> parts = new LinkedHashSet<>();
			parts.add(anchor);
			MultiblockInventoryAPI.EVENT.invoker().detectMultiblocks(level, anchor, level.getBlockState(anchor), p -> parts.add(p.immutable()));
			List<ItemStack> items = new ArrayList<>();
			AABB box = null;
			for (BlockPos p : parts) {
				List<ItemStack> in = anchors.get(p);
				if (in != null)for (ItemStack s : in)addDistinct(items, s);
				box = box == null ? new AABB(p) : box.minmax(new AABB(p));
			}
			Label l = new Label(items, parts, box);
			list.add(l);
			for (BlockPos p : parts)by.put(p, l);
		}
		labels = list;
		byBlock = by;
	}

	/** Same rule as the label: item types, except filter items, which count with their settings. */
	private static void addDistinct(List<ItemStack> items, ItemStack s) {
		boolean filter = FrameLabel.isFilterItem(s);
		for (ItemStack o : items) {
			if (filter ? ItemStack.matches(o, s) : !FrameLabel.isFilterItem(o) && o.is(s.getItem()))return;
		}
		items.add(s);
	}

	/** The aqua boxes, from the Configurator's outline hook. Depth-tested lines. */
	public static void drawBoxes(PoseStack ps) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null)return;
		refresh(mc);
		if (labels.isEmpty())return;
		Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
		Vec3 me = mc.player.position();
		double range = FrameLabels.RANGE * FrameLabels.RANGE;
		VertexConsumer buf = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
		for (Label l : labels) {
			if (l.box().getCenter().distanceToSqr(me) > range)continue;
			LevelRenderer.renderLineBox(ps, buf, l.box().inflate(0.004).move(-cam.x, -cam.y, -cam.z), R, G, B, A);
		}
		mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
	}

	/** The label lines of the Configurator HUD, for the block (or glow frame) the player looks at. */
	public static void appendHud(Minecraft mc, List<Component> messages) {
		if (mc.level == null || mc.player == null)return;
		HitResult hit = mc.hitResult;
		BlockPos target = null;
		if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK)target = bh.getBlockPos();
		else if (hit instanceof EntityHitResult eh && eh.getEntity() instanceof GlowItemFrame f)target = FrameLabels.anchorOf(f);
		if (target == null)return;
		refresh(mc);
		Label l = byBlock.get(target);
		if (l == null || l.items().isEmpty())return;
		int flags = 0;
		boolean known = false;
		for (BlockPos p : l.parts()) {
			Integer s = status.get(p);
			if (s != null) {
				known = true;
				flags |= s;
			}
		}
		Component items = itemsText(l.items());
		if (known && (flags & FrameLabels.CONNECTED) == 0) {
			messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.label", items).withStyle(ChatFormatting.GRAY)
					.append(" ").append(Component.translatable("tooltip.obscuras_storage.config_overlay.label.not_connected").withStyle(ChatFormatting.RED)));
			return;
		}
		MutableComponent line = Component.translatable("tooltip.obscuras_storage.config_overlay.label", items.copy().withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.AQUA);
		if ((flags & FrameLabels.OVERRIDDEN) != 0) {
			messages.add(line.append(" ").append(Component.translatable("tooltip.obscuras_storage.config_overlay.label.overridden").withStyle(ChatFormatting.DARK_GRAY)));
		} else {
			messages.add(line);
			// Until the server's status arrived it is not known whether the label is in effect
			if (known)messages.add(Component.translatable("tooltip.obscuras_storage.config_overlay.label.info", items).withStyle(ChatFormatting.GRAY));
		}
	}

	private static Component itemsText(List<ItemStack> items) {
		MutableComponent names = Component.empty();
		int n = Math.min(items.size(), LISTED_ITEMS);
		for (int i = 0; i < n; i++) {
			if (i > 0)names.append(", ");
			names.append(items.get(i).getHoverName());
		}
		if (items.size() > n)return Component.translatable("tooltip.obscuras_storage.config_overlay.label.more", names, items.size() - n);
		return names;
	}
}
