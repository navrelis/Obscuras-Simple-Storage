package com.obscura.storage.screen.widget;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import com.obscura.storage.StorageMod;
import com.obscura.storage.StorageModClient;
import com.obscura.storage.util.ItemLocator;

/**
 * The terminal's Highlight Connected Inventories button: outlines every inventory of the network.
 * Hidden when the server turned the Item Locator off. The tooltip names the key bound to the same action.
 */
public class HighlightNetworkButton extends IconButton {
	private static final ResourceLocation ICON = ResourceLocation.tryBuild(StorageMod.modid, "icons/highlight_network");

	public HighlightNetworkButton(int x, int y, Runnable action) {
		super(x, y, Component.translatable("narrator.obscuras_storage.terminal_highlight_network"), ICON, b -> action.run());
		this.visible = ItemLocator.isEnabled();
		setMessage(Component.translatable("narrator.obscuras_storage.terminal_highlight_network"));
		setTooltip(Tooltip.create(tooltip()));
	}

	private static Component tooltip() {
		MutableComponent t = Component.translatable("tooltip.obscuras_storage.highlight_network");
		KeyMapping key = StorageModClient.highlightNetwork;
		if (key != null && !key.isUnbound())
			t.append("\n").append(Component.translatable("tooltip.obscuras_storage.highlight_network.key", key.getTranslatedKeyMessage()).withStyle(ChatFormatting.GRAY));
		return t;
	}
}
