package com.obscura.storage.screen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.obscura.storage.doctor.DoctorFormat;
import com.obscura.storage.inventory.filter.GhostFilter;
import com.obscura.storage.menu.DisposalMenu;
import com.obscura.storage.menu.slot.PhantomSlot;
import com.obscura.storage.screen.widget.IconButton;
import com.obscura.storage.screen.widget.ToggleButton;
import com.obscura.storage.util.IDataReceiver;
import com.obscura.storage.util.NumberFormatUtil;

/**
 * Disposal screen: the ghost filter on the left, and on the plain panel area right of it the warning,
 * the status, the total and the most destroyed items. The counts arrive as a data packet, see
 * {@link DisposalMenu}.
 */
public class DisposalScreen extends AbstractFilterBlockScreen<DisposalMenu> implements IDataReceiver {
	/** The text area of the sheet: x 66..168, y 17..70. */
	private static final int TEXT_X = 66, TEXT_Y = 17, TEXT_W = 102, LINE = 11;
	private static final int TOP_Y = TEXT_Y + 3 * LINE + 2, TOP_STEP = 18;
	private static final int LABEL_COLOR = 0x404040, WARNING_COLOR = 0xAA0000;

	private static record TopItem(ItemStack stack, long count) {}

	private ToggleButton pauseBtn;
	private long total;
	/** Destroyed items per ghost slot, -1 where the slot has no count (empty, or a filter item). */
	private final long[] slotCounts = new long[GhostFilter.SLOTS];
	private final List<TopItem> top = new ArrayList<>();

	public DisposalScreen(DisposalMenu screenContainer, Inventory inv, Component titleIn) {
		super(screenContainer, inv, titleIn, "disposal");
		Arrays.fill(slotCounts, -1);
	}

	@Override
	protected void initButtons() {
		pauseBtn = addRenderableWidget(ToggleButton.builder(leftPos - 18, nextButtonY()).
				name(Component.translatable("narrator.obscuras_storage.disposal_pause")).
				iconOff(icon("disposal_running")).
				iconOn(icon("disposal_paused")).
				build(s -> {
					menu.paused = s;
					send(DisposalMenu.MSG_PAUSED, s);
				}));
		pauseBtn.setTooltip(tooltip("disposal.running"), tooltip("disposal.paused"));
		IconButton resetBtn = addRenderableWidget(new IconButton(leftPos - 18, nextButtonY(), Component.translatable("narrator.obscuras_storage.disposal_reset"), icon("counter_reset"), b -> {
			// Only with Shift held, a stray click must not wipe the counter
			if (hasShiftDown())send(DisposalMenu.MSG_RESET, true);
		}));
		resetBtn.setTooltip(tooltip("disposal.reset"));
		updateButtons();
		// Counts sent while another screen covered this one never arrived: ask for them again
		send(DisposalMenu.MSG_SYNC, true);
	}

	@Override
	protected void updateButtons() {
		pauseBtn.setState(menu.paused);
	}

	@Override
	public void receive(CompoundTag tag) {
		if (!tag.contains(DisposalMenu.STATS_KEY, Tag.TAG_COMPOUND))return;
		CompoundTag stats = tag.getCompound(DisposalMenu.STATS_KEY);
		total = Math.max(0, stats.getLong(DisposalMenu.STATS_TOTAL));
		long[] slots = stats.getLongArray(DisposalMenu.STATS_SLOTS);
		for (int i = 0; i < slotCounts.length; i++)slotCounts[i] = i < slots.length ? slots[i] : -1;
		top.clear();
		ListTag list = stats.getList(DisposalMenu.STATS_TOP, Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size() && top.size() < DisposalMenu.TOP_ITEMS; i++) {
			CompoundTag c = list.getCompound(i);
			ResourceLocation id = ResourceLocation.tryParse(c.getString(DisposalMenu.STATS_ID));
			Item item = id != null ? BuiltInRegistries.ITEM.get(id) : Items.AIR;
			long n = c.getLong(DisposalMenu.STATS_COUNT);
			if (item != Items.AIR && n > 0)top.add(new TopItem(new ItemStack(item), n));
		}
	}

	@Override
	protected void renderLabels(GuiGraphics st, int mouseX, int mouseY) {
		super.renderLabels(st, mouseX, mouseY);
		drawFitted(st, Component.translatable("tooltip.obscuras_storage.disposal.warning"), TEXT_X, TEXT_Y, WARNING_COLOR);
		drawFitted(st, Component.translatable("tooltip.obscuras_storage.filter_block.status", Component.translatable(menu.status.getTranslationKey())), TEXT_X, TEXT_Y + LINE, LABEL_COLOR);
		drawFitted(st, destroyedLine(NumberFormatUtil.formatNumber(total)), TEXT_X, TEXT_Y + 2 * LINE, LABEL_COLOR);
		for (int i = 0; i < top.size(); i++) {
			TopItem t = top.get(i);
			int x = TEXT_X + i * TOP_STEP;
			st.renderItem(t.stack(), x, TOP_Y);
			drawStackSize(st, t.count(), x, TOP_Y);
		}
	}

	private static Component destroyedLine(String count) {
		return Component.translatable("tooltip.obscuras_storage.disposal.destroyed", count);
	}

	/** Draws {@code text}, shrunk when it is wider than the text area (longer translations). */
	private void drawFitted(GuiGraphics st, Component text, int x, int y, int color) {
		int w = font.width(text);
		if (w <= TEXT_W) {
			st.drawString(font, text, x, y, color, false);
			return;
		}
		float scale = (float) TEXT_W / w;
		st.pose().pushPose();
		st.pose().translate(x, y + font.lineHeight * (1 - scale) / 2f, 0);
		st.pose().scale(scale, scale, 1);
		st.drawString(font, text, 0, 0, color, false);
		st.pose().popPose();
	}

	/** The count in the item's lower right corner, in the terminal's compact number format. */
	private void drawStackSize(GuiGraphics st, long size, int x, int y) {
		float scale = 0.6f;
		String s = NumberFormatUtil.formatNumber(size);
		st.pose().pushPose();
		st.pose().translate(x + 16 - font.width(s) * scale, y + 16 - 7 * scale, 200);
		st.pose().scale(scale, scale, 1);
		st.drawString(font, s, 0, 0, 0xFFFFFF, true);
		st.pose().popPose();
	}

	@Override
	public void render(GuiGraphics matrixStack, int mouseX, int mouseY, float partialTicks) {
		super.render(matrixStack, mouseX, mouseY, partialTicks);
		if (!menu.getCarried().isEmpty())return;
		for (int i = 0; i < top.size(); i++) {
			if (isHovering(TEXT_X + i * TOP_STEP, TOP_Y, 16, 16, mouseX, mouseY)) {
				TopItem t = top.get(i);
				matrixStack.renderComponentTooltip(font, List.of(t.stack().getHoverName(), destroyedExact(t.count())), mouseX, mouseY);
				return;
			}
		}
		// The line shows the short form, the exact number is one hover away
		if (isHovering(TEXT_X, TEXT_Y + 2 * LINE, TEXT_W, font.lineHeight, mouseX, mouseY)) {
			matrixStack.renderTooltip(font, destroyedLine(DoctorFormat.count(total)), mouseX, mouseY);
		}
	}

	private static Component destroyedExact(long count) {
		return destroyedLine(DoctorFormat.count(count)).copy().withStyle(ChatFormatting.GRAY);
	}

	/** A plain ghost entry also says how many of that item were destroyed. */
	@Override
	protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
		List<Component> tooltip = super.getTooltipFromContainerItem(stack);
		Slot slot = getSlotUnderMouse();
		if (slot instanceof PhantomSlot && slot.index < slotCounts.length && slotCounts[slot.index] >= 0) {
			tooltip = new ArrayList<>(tooltip);
			tooltip.add(destroyedExact(slotCounts[slot.index]));
		}
		return tooltip;
	}
}
