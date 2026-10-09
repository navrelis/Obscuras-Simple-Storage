package com.obscura.storage.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import com.obscura.storage.StorageMod;
import com.obscura.storage.client.ClientUtil;
import com.obscura.storage.menu.LevelEmitterMenu;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.screen.widget.ToggleButton;

public class LevelEmitterScreen extends AbstractFilteredScreen<LevelEmitterMenu> {
	private static final ResourceLocation gui = ResourceLocation.tryBuild(StorageMod.modid, "textures/gui/level_emitter.png");
	private ToggleButton lessThanBtn;
	private ToggleButton analogBtn;
	private EditBox textF;
	private List<AmountBtn> amountBtns = new ArrayList<>();

	public LevelEmitterScreen(LevelEmitterMenu screenContainer, Inventory inv, Component titleIn) {
		super(screenContainer, inv, titleIn);
		menu.onPacket = this::receive;
	}

	@Override
	public void render(GuiGraphics matrixStack, int mouseX, int mouseY, float partialTicks) {
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
		textF.setValue(Integer.toString(menu.count));
		textF.setResponder(t -> {
			try {
				int c = Integer.parseInt(t);
				if(c >= 0) {
					menu.count = c;
					updateTooltips();
					send();
				}
			} catch (NumberFormatException e) {
			}
		});
		addRenderableWidget(textF);
		lessThanBtn = ToggleButton.builder(leftPos - 18, topPos + 5).
				iconOff(ResourceLocation.tryBuild(StorageMod.modid, "icons/greater_than")).
				iconOn(ResourceLocation.tryBuild(StorageMod.modid, "icons/less_than")).
				build(s -> {
					lessThanBtn.setState(s);
					menu.lessThan = s;
					updateTooltips();
					send();
				});
		lessThanBtn.setTooltip(Tooltip.create(ClientUtil.multilineTooltip("tooltip.obscuras_storage.level_emitter.greater_than")), Tooltip.create(ClientUtil.multilineTooltip("tooltip.obscuras_storage.level_emitter.less_than")));
		lessThanBtn.setState(menu.lessThan);
		addRenderableWidget(lessThanBtn);
		analogBtn = ToggleButton.builder(leftPos - 18, topPos + 23).
				iconOff(ResourceLocation.tryBuild(StorageMod.modid, "icons/emitter_digital")).
				iconOn(ResourceLocation.tryBuild(StorageMod.modid, "icons/emitter_analog")).
				build(s -> {
					analogBtn.setState(s);
					menu.analog = s;
					send();
				});
		analogBtn.setState(menu.analog);
		addRenderableWidget(analogBtn);
		updateTooltips();
		amountBtns.add(new AmountBtn( 20, 0,    1,  1, 20));
		amountBtns.add(new AmountBtn( 45, 0,   10, 16, 25));
		amountBtns.add(new AmountBtn( 75, 0,  100, 32, 30));
		amountBtns.add(new AmountBtn(110, 0, 1000, 64, 35));

		amountBtns.add(new AmountBtn( 20, 40,    -1,  -1, 20));
		amountBtns.add(new AmountBtn( 45, 40,   -10, -16, 25));
		amountBtns.add(new AmountBtn( 75, 40,  -100, -32, 30));
		amountBtns.add(new AmountBtn(110, 40, -1000, -64, 35));
	}

	private void receive() {
		lessThanBtn.setState(menu.lessThan);
		analogBtn.setState(menu.analog);
		textF.setValue(Integer.toString(menu.count));
		updateTooltips();
	}

	/** The analog button explains the signal for the current comparison and amount. */
	private void updateTooltips() {
		String analog = menu.lessThan ? "tooltip.obscuras_storage.level_emitter.mode_analog_less" : "tooltip.obscuras_storage.level_emitter.mode_analog_greater";
		analogBtn.setTooltip(Tooltip.create(ClientUtil.multilineTooltip("tooltip.obscuras_storage.level_emitter.mode_digital")), Tooltip.create(ClientUtil.multilineTooltip(analog, menu.count)));
		analogBtn.setState(menu.analog);
	}

	private void send() {
		CompoundTag mainTag = new CompoundTag();
		mainTag.putInt("count", menu.count);
		mainTag.putBoolean("lessThan", menu.lessThan);
		mainTag.putBoolean("analog", menu.analog);
		NetworkHandler.sendDataToServer(mainTag);
	}

	private class AmountBtn {
		private Button btn;
		private int v, sv;
		private final Component normalMsg;
		private final Component shiftedMsg;
		private boolean lastShiftState;
		public AmountBtn(int x, int y, int v, int sv, int len) {
			this.normalMsg = Component.literal((v > 0 ? "+" : "") + v);
			this.shiftedMsg = Component.literal((sv > 0 ? "+" : "") + sv);
			btn = Button.builder(normalMsg, this::evt).bounds(leftPos + x, topPos + y + 16, len, 20).build();
			addRenderableWidget(btn);
			this.v = v;
			this.sv = sv;
		}

		private void evt(Button b) {
			menu.count += hasShiftDown() ? sv : v;
			if(menu.count < 0)menu.count = 0;
			textF.setValue(Integer.toString(menu.count));
			updateTooltips();
			send();
		}

		private void update() {
			boolean shifted = hasShiftDown();
			if (shifted != lastShiftState) {
				lastShiftState = shifted;
				btn.setMessage(shifted ? shiftedMsg : normalMsg);
			}
		}
	}

	@Override
	public void getExclusionAreas(Consumer<Box> consumer) {
		consumer.accept(new Box(leftPos - 20, topPos, 25, 43));
	}
}
