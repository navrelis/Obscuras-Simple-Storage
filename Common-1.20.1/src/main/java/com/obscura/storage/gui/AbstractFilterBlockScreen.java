package com.obscura.storage.gui;

import java.util.Arrays;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.util.ComponentJoiner;

/**
 * Shared screen of the Ejector, Disposal and Exporter: the block's own 256x256 sheet with the 176x166
 * panel at (0,0), and a button column left of the panel whose first button is the Exact toggle.
 */
public abstract class AbstractFilterBlockScreen<T extends AbstractFilterBlockMenu<?>> extends AbstractFilteredScreen<T> {
	private final ResourceLocation gui;
	private GuiButton exactBtn;
	private int buttons;

	/** @param texture file name of the sheet in textures/gui, without the extension */
	protected AbstractFilterBlockScreen(T screenContainer, Inventory inv, Component titleIn, String texture) {
		super(screenContainer, inv, titleIn);
		this.gui = new ResourceLocation("obscuras_storage", "textures/gui/" + texture + ".png");
	}

	@Override
	protected void init() {
		super.init();
		buttons = 0;
		exactBtn = addButton("filter_exact", b -> {
			menu.exact = !menu.exact;
			send(AbstractFilterBlockMenu.MSG_EXACT, menu.exact);
		});
		exactBtn.iconFactory = s -> s == 1 ? iconTex("match_tag_on") : iconTex("match_tag_off");
		exactBtn.tooltipFactory = s -> tooltip(s == 1 ? "filter_block.exact_on" : "filter_block.exact_off");
		exactBtn.setState(menu.exact ? 1 : 0);
		initButtons();
	}

	/** Adds the block's own buttons below the Exact toggle with {@link #addButton}. */
	protected abstract void initButtons();

	/** Shows the menu's current (synced) values on the buttons, every frame. */
	protected abstract void updateButtons();

	/**
	 * The next icon button of the column left of the panel. The caller sets its icon and tooltip
	 * factories and its first state.
	 *
	 * @param narration key below narrator.obscuras_storage
	 */
	protected GuiButton addButton(String narration, OnPress pressable) {
		GuiButton b = addRenderableWidget(new GuiButton(leftPos - 18, topPos + 5 + 18 * buttons++, 0, pressable));
		b.setMessage(Component.translatable("narrator.obscuras_storage." + narration));
		return b;
	}

	/** Puts the button into {@code state} when it is in another one (which also swaps its tooltip). */
	protected static void show(GuiButton button, int state) {
		if (button.getState() != state)button.setState(state);
	}

	protected static ResourceLocation iconTex(String id) {
		return new ResourceLocation("obscuras_storage", "textures/gui/sprites/icons/" + id + ".png");
	}

	/** A button tooltip, lines separated by a backslash in the language file. */
	protected static Tooltip tooltip(String key, Object... args) {
		return Tooltip.create(Arrays.stream(I18n.get("tooltip.obscuras_storage." + key, args).split("\\\\")).map(Component::literal).collect(ComponentJoiner.joining(Component.empty(), Component.literal("\n"))));
	}

	protected static String lower(Enum<?> e) {
		return e.name().toLowerCase(Locale.ROOT);
	}

	protected void send(String key, boolean value) {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(key, value);
		NetworkHandler.sendDataToServer(tag);
	}

	protected void send(String key, Enum<?> value) {
		CompoundTag tag = new CompoundTag();
		tag.putInt(key, value.ordinal());
		NetworkHandler.sendDataToServer(tag);
	}

	@Override
	public void render(GuiGraphics matrixStack, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(matrixStack, mouseX, mouseY, partialTicks);
		show(exactBtn, menu.exact ? 1 : 0);
		updateButtons();
		super.render(matrixStack, mouseX, mouseY, partialTicks);
		this.renderTooltip(matrixStack, mouseX, mouseY);
	}

	@Override
	protected void renderBg(GuiGraphics matrixStack, float partialTicks, int x, int y) {
		int i = (this.width - this.imageWidth) / 2;
		int j = (this.height - this.imageHeight) / 2;
		matrixStack.blit(gui, i, j, 0, 0, this.imageWidth, this.imageHeight);
	}
}
