package com.obscura.storage.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.util.ComponentJoiner;
import com.obscura.storage.util.IDataReceiver;

public class LevelEmitterScreen extends AbstractFilteredScreen<LevelEmitterMenu> implements IDataReceiver {
	private static final ResourceLocation gui = new ResourceLocation("obscuras_storage", "textures/gui/level_emitter.png");
	private static ResourceLocation iconTex(String id) {
		return new ResourceLocation("obscuras_storage", "textures/gui/sprites/icons/" + id + ".png");
	}

	private GuiButton lessThanBtn, analogBtn;
	private EditBox textF;
	private boolean lt, analog;
	private int count = 1;
	private List<AmountBtn> amountBtns = new ArrayList<>();

	public LevelEmitterScreen(LevelEmitterMenu screenContainer, Inventory inv, Component titleIn) {
		super(screenContainer, inv, titleIn);
	}

	@Override
	public void render(GuiGraphics matrixStack, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(matrixStack, mouseX, mouseY, partialTicks);
		amountBtns.forEach(AmountBtn::update);
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
	protected void init() {
		clearWidgets();
		amountBtns.clear();
		super.init();
		textF = new EditBox(font, leftPos + 70, topPos + 41, 89, font.lineHeight, Component.translatable("narrator.obscuras_storage.level_emitter_amount"));
		textF.setMaxLength(100);
		textF.setBordered(false);
		textF.setVisible(true);
		textF.setTextColor(16777215);
		textF.setValue(Integer.toString(count));
		textF.setResponder(t -> {
			try {
				int c = Integer.parseInt(t);
				if(c >= 0) {
					count = c;
					updateTooltips();
					send();
				}
			} catch (NumberFormatException e) {
			}
		});
		addRenderableWidget(textF);
		lessThanBtn = new GuiButton(leftPos - 18, topPos + 5, 0, b -> {
			lt = !lt;
			lessThanBtn.setState(lt ? 1 : 0);
			updateTooltips();
			send();
		});
		lessThanBtn.iconFactory = s -> s == 1 ? iconTex("less_than") : iconTex("greater_than");
		lessThanBtn.tooltipFactory = s -> Tooltip.create(Arrays.stream(I18n.get("tooltip.obscuras_storage.lvlEm_lt_" + s).split("\\\\")).map(Component::literal).collect(ComponentJoiner.joining(Component.empty(), Component.literal("\n"))));
		lessThanBtn.setState(lt ? 1 : 0);
		addRenderableWidget(lessThanBtn);
		analogBtn = new GuiButton(leftPos - 18, topPos + 23, 0, b -> {
			analog = !analog;
			updateTooltips();
			send();
		});
		analogBtn.iconFactory = s -> s == 1 ? iconTex("emitter_analog") : iconTex("emitter_digital");
		// The analog button explains the signal for the current comparison and amount
		analogBtn.tooltipFactory = s -> s == 1 ? tooltip(lt ? "tooltip.obscuras_storage.level_emitter.mode_analog_less" : "tooltip.obscuras_storage.level_emitter.mode_analog_greater", count) : tooltip("tooltip.obscuras_storage.level_emitter.mode_digital");
		updateTooltips();
		addRenderableWidget(analogBtn);
		amountBtns.add(new AmountBtn( 20, 0,    1,  1, 20));
		amountBtns.add(new AmountBtn( 45, 0,   10, 16, 25));
		amountBtns.add(new AmountBtn( 75, 0,  100, 32, 30));
		amountBtns.add(new AmountBtn(110, 0, 1000, 64, 35));

		amountBtns.add(new AmountBtn( 20, 40,    -1,  -1, 20));
		amountBtns.add(new AmountBtn( 45, 40,   -10, -16, 25));
		amountBtns.add(new AmountBtn( 75, 40,  -100, -32, 30));
		amountBtns.add(new AmountBtn(110, 40, -1000, -64, 35));
	}

	@Override
	public void receive(CompoundTag tag) {
		count = tag.getInt("count");
		boolean lt = tag.getBoolean("lessThan");
		lessThanBtn.setState(lt ? 1 : 0);
		this.lt = lt;
		this.analog = tag.getBoolean("analog");
		textF.setValue(Integer.toString(count));
		updateTooltips();
	}

	/** Re-applies the tooltips and icon of the toggle buttons (the analog one depends on the comparison and the amount). */
	private void updateTooltips() {
		if(lessThanBtn != null)lessThanBtn.setState(lt ? 1 : 0);
		if(analogBtn != null)analogBtn.setState(analog ? 1 : 0);
	}

	private static Tooltip tooltip(String key, Object... args) {
		return Tooltip.create(Arrays.stream(I18n.get(key, args).split("\\\\")).map(Component::literal).collect(ComponentJoiner.joining(Component.empty(), Component.literal("\n"))));
	}

	private void send() {
		CompoundTag mainTag = new CompoundTag();
		mainTag.putInt("count", count);
		mainTag.putBoolean("lessThan", lt);
		mainTag.putBoolean("analog", analog);
		NetworkHandler.sendDataToServer(mainTag);
	}

	private class AmountBtn {
		private Button btn;
		private int v, sv;
		public AmountBtn(int x, int y, int v, int sv, int len) {
			btn = new ButtonExt(leftPos + x, topPos + y + 16, len, 20, Component.literal((v > 0 ? "+" : "") + v), this::evt);
			addRenderableWidget(btn);
			this.v = v;
			this.sv = sv;
		}

		private void evt(Button b) {
			count += hasShiftDown() ? sv : v;
			if(count < 0)count = 0;
			textF.setValue(Integer.toString(count));
			updateTooltips();
			send();
		}

		private void update() {
			if(hasShiftDown()) {
				btn.setMessage(Component.literal((sv > 0 ? "+" : "") + sv));
			} else {
				btn.setMessage(Component.literal((v > 0 ? "+" : "") + v));
			}
		}
	}
}
