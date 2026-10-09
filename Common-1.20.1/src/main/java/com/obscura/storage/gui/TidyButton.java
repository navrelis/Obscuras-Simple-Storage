package com.obscura.storage.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import com.obscura.storage.tidy.TidyStatus;
import com.obscura.storage.util.StoredItemStack;

/**
 * The terminal's Tidy button (broom). Idle: click starts a run on the network. Running: a progress bar
 * in rows 13 and 14 (vanilla durability colours), a click stops the run. Disabled: the disabled
 * background and the icon at half brightness, the tooltip says why. The state comes from the menu's data
 * slots, which the server fills (see {@code TidyManager#fillStatus}).
 */
public class TidyButton extends GuiButton {
	private static final ResourceLocation ICON = new ResourceLocation("obscuras_storage", "textures/gui/sprites/icons/tidy.png");
	private static final String T = "tooltip.obscuras_storage.tidy.";
	private final StorageTerminalMenu menu;
	private int shownStatus = Integer.MIN_VALUE, shownDone = -1, shownPlanned = -1, shownSync = -1;

	public TidyButton(int x, int y, StorageTerminalMenu menu) {
		super(x, y, 0, b -> ((TidyButton) b).click());
		this.menu = menu;
		this.iconFactory = s -> ICON;
		setMessage(Component.translatable("narrator.obscuras_storage.terminal_tidy"));
	}

	private void click() {
		int st = menu.tidyStatus;
		if (!TidyStatus.isClickable(st))return;
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("tidy", !TidyStatus.isRunning(st));
		menu.sendMessage(tag);
	}

	@Override
	public void renderWidget(GuiGraphics st, int mouseX, int mouseY, float pt) {
		this.active = TidyStatus.isClickable(menu.tidyStatus);
		updateTooltip();
		super.renderWidget(st, mouseX, mouseY, pt);
	}

	@Override
	protected void drawIcon(GuiGraphics st, ResourceLocation icon, int x, int y) {
		if (!this.active)st.setColor(0.5f, 0.5f, 0.5f, this.alpha);
		super.drawIcon(st, icon, x, y);
		st.setColor(1.0f, 1.0f, 1.0f, this.alpha);
		if (TidyStatus.isRunning(menu.tidyStatus)) {
			int planned = menu.tidyPlanned, done = Math.min(menu.tidyDone, planned);
			int w = planned > 0 ? Math.round(12f * done / planned) : 0;
			st.fill(x + 2, y + 13, x + 14, y + 15, 0xFF000000);
			if (w > 0)st.fill(x + 2, y + 13, x + 2 + w, y + 14, 0xFF00FF00);
		}
	}

	/** Rebuilds the tooltip when what it shows changed. */
	private void updateTooltip() {
		int status = menu.tidyStatus;
		boolean idle = TidyStatus.state(status) == TidyStatus.IDLE;
		if (status == shownStatus && menu.tidyDone == shownDone && menu.tidyPlanned == shownPlanned && (!idle || menu.clientSyncCount == shownSync))return;
		shownStatus = status;
		shownDone = menu.tidyDone;
		shownPlanned = menu.tidyPlanned;
		shownSync = menu.clientSyncCount;
		MutableComponent t;
		switch (TidyStatus.state(status)) {
		case TidyStatus.PLANNING -> t = Component.translatable(T + "running").append("\n")
				.append(Component.translatable(T + "planning").withStyle(ChatFormatting.GRAY)).append("\n")
				.append(Component.translatable(T + "stop").withStyle(ChatFormatting.DARK_GRAY));
		case TidyStatus.RUNNING -> t = Component.translatable(T + "running").append("\n")
				.append(Component.translatable(T + "progress", Component.literal(String.valueOf(Math.min(menu.tidyDone, menu.tidyPlanned))).withStyle(ChatFormatting.YELLOW), menu.tidyPlanned).withStyle(ChatFormatting.GRAY)).append("\n")
				.append(Component.translatable(T + "stop").withStyle(ChatFormatting.DARK_GRAY));
		case TidyStatus.IDLE -> {
			t = Component.translatable(T + "title").append("\n").append(Component.translatable(T + "desc").withStyle(ChatFormatting.GRAY));
			int est = estimateMoves();
			if (est > 0) {
				int mpt = Math.max(1, menu.tidyMovesPerTick);
				int secs = Math.max(1, (est + mpt * 20 - 1) / (mpt * 20));
				t.append("\n").append(Component.translatable(T + "estimate", est, secs).withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		default -> t = Component.translatable(T + "title").append("\n").append(reason(status).withStyle(ChatFormatting.RED));
		}
		setTooltip(Tooltip.create(t));
	}

	private static MutableComponent reason(int status) {
		return switch (TidyStatus.state(status)) {
		case TidyStatus.NOTHING -> Component.translatable(T + "disabled.nothing");
		case TidyStatus.COOLDOWN -> Component.translatable(T + "disabled.cooldown", TidyStatus.value(status));
		case TidyStatus.OTHER -> Component.translatable(T + "disabled.other");
		case TidyStatus.NO_NETWORK -> Component.translatable(T + "disabled.no_network");
		default -> Component.translatable(T + "disabled.server");
		};
	}

	/**
	 * A rough estimate from what the terminal knows: the slots in use minus the slots the stored items
	 * need at full stacks. Each freed slot takes at least one move; moving strays home is not counted.
	 */
	private int estimateMoves() {
		if (menu.slotCount <= 0 || menu.slotCount >= Short.MAX_VALUE || menu.freeCount < 0 || menu.freeCount >= Short.MAX_VALUE)return 0;
		long need = 0;
		for (StoredItemStack s : menu.itemListClient) {
			long q = s.getQuantity();
			if (q <= 0)continue;
			int max = Math.max(1, s.getMaxStackSize());
			need += (q + max - 1) / max;
		}
		long used = menu.slotCount - menu.freeCount;
		return (int) Math.max(0, Math.min(Integer.MAX_VALUE, used - need));
	}
}
