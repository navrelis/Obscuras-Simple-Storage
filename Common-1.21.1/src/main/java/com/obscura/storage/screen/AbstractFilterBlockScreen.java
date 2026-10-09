package com.obscura.storage.screen;

import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import com.obscura.storage.StorageMod;
import com.obscura.storage.client.ClientUtil;
import com.obscura.storage.menu.AbstractFilterBlockMenu;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.screen.widget.ToggleButton;

/**
 * Shared screen of the Ejector, Disposal and Exporter: the block's own 256x256 sheet with the 176x166
 * panel at (0,0), and a button column left of the panel whose first button is the Exact toggle.
 */
public abstract class AbstractFilterBlockScreen<T extends AbstractFilterBlockMenu<?>> extends AbstractFilteredScreen<T> {
	private final ResourceLocation gui;
	private ToggleButton exactBtn;
	private int buttons;

	/** @param texture file name of the sheet in textures/gui, without the extension */
	protected AbstractFilterBlockScreen(T screenContainer, Inventory inv, Component titleIn, String texture) {
		super(screenContainer, inv, titleIn);
		this.gui = ResourceLocation.tryBuild(StorageMod.modid, "textures/gui/" + texture + ".png");
	}

	@Override
	protected void init() {
		super.init();
		buttons = 0;
		exactBtn = addRenderableWidget(ToggleButton.builder(leftPos - 18, nextButtonY()).
				name(Component.translatable("narrator.obscuras_storage.filter_exact")).
				iconOff(icon("match_tag_off")).
				iconOn(icon("match_tag_on")).
				build(s -> {
					menu.exact = s;
					send(AbstractFilterBlockMenu.MSG_EXACT, s);
				}));
		exactBtn.setTooltip(tooltip("filter_block.exact_off"), tooltip("filter_block.exact_on"));
		initButtons();
	}

	/** Adds the block's own buttons below the Exact toggle, each at {@link #nextButtonY()}. */
	protected abstract void initButtons();

	/** Shows the menu's current (synced) values on the buttons, every frame. */
	protected abstract void updateButtons();

	/** Top edge of the next button in the column left of the panel. */
	protected int nextButtonY() {
		return topPos + 5 + 18 * buttons++;
	}

	protected static ResourceLocation icon(String name) {
		return ResourceLocation.tryBuild(StorageMod.modid, "icons/" + name);
	}

	/** A button tooltip, lines separated by a backslash in the language file. */
	protected static Tooltip tooltip(String key, Object... args) {
		return Tooltip.create(ClientUtil.multilineTooltip("tooltip.obscuras_storage." + key, args));
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
		exactBtn.setState(menu.exact);
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

	@Override
	public void getExclusionAreas(Consumer<Box> consumer) {
		consumer.accept(new Box(leftPos - 20, topPos, 25, 7 + 18 * buttons));
	}
}
