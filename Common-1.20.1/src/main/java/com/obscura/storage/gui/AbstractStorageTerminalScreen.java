package com.obscura.storage.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import com.obscura.storage.Config;
import com.obscura.storage.StorageMod;
import com.obscura.storage.StorageModClient;
import com.obscura.storage.client.ItemLocatorClient;
import com.obscura.storage.gui.StorageTerminalMenu.SlotAction;
import com.obscura.storage.gui.StorageTerminalMenu.SlotStorage;
import com.obscura.storage.platform.PlatformContainerScreen;
import com.obscura.storage.platform.PlatformEditBox;
import com.obscura.storage.util.ComponentJoiner;
import com.obscura.storage.util.IAutoFillTerminal;
import com.obscura.storage.util.IDataReceiver;
import com.obscura.storage.util.ItemLocator;
import com.obscura.storage.util.NumberFormatUtil;
import com.obscura.storage.util.PopupMenuManager;
import com.obscura.storage.util.SearchModes;
import com.obscura.storage.util.StoredItemStack;
import com.obscura.storage.util.StoredItemStack.ComparatorAmount;
import com.obscura.storage.util.StoredItemStack.IStoredItemStackComparator;
import com.obscura.storage.util.StoredItemStack.SortingTypes;
import com.obscura.storage.util.TerminalSearch;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

public abstract class AbstractStorageTerminalScreen<T extends StorageTerminalMenu> extends PlatformContainerScreen<T> implements IDataReceiver {
	private static final ResourceLocation FLOATING_SLOT = new ResourceLocation("obscuras_storage", "textures/gui/sprites/widget/floating_slot.png");
	private static final LoadingCache<StoredItemStack, List<String>> tooltipCache = CacheBuilder.newBuilder().expireAfterAccess(5, TimeUnit.SECONDS).build(new CacheLoader<StoredItemStack, List<String>>() {

		@Override
		public List<String> load(StoredItemStack key) throws Exception {
			return key.getStack().getTooltipLines(Minecraft.getInstance().player, getTooltipFlag()).stream().map(Component::getString).collect(Collectors.toList());
		}

	});
	private static final LoadingCache<StoredItemStack, String> nbtCache = CacheBuilder.newBuilder().expireAfterAccess(5, TimeUnit.SECONDS).build(CacheLoader.from(
			key -> String.valueOf(key.getStack().getTag())
			));
	private static final LoadingCache<StoredItemStack, List<String>> tagCache = CacheBuilder.newBuilder().expireAfterAccess(5, TimeUnit.SECONDS).build(CacheLoader.from(
			key -> key.getStack().getTags().map(t -> t.location().toString()).toList()
			));
	protected Minecraft mc = Minecraft.getInstance();

	/** Amount scrolled in Creative mode inventory (0 = top, 1 = bottom) */
	protected float currentScroll;
	/** True if the scrollbar is being dragged */
	protected boolean isScrolling;
	/**
	 * True if the left mouse button was held down last time drawScreen was
	 * called.
	 */
	private boolean refreshItemList;
	protected boolean wasClicking;
	protected PlatformEditBox searchField;
	protected int slotIDUnderMouse = -1, controllMode, rowCount, searchType;
	private String searchLast = "";
	private long lastKeystrokeTime, lastContentRefresh;
	private boolean searchPending;
	private String lastSeenQuery = "";
	protected boolean loadedSearch = false, ghostItems, tallMode;
	public final int textureSlotCount, guiHeight, slotStartX, slotStartY;
	private IStoredItemStackComparator comparator = new ComparatorAmount(false);
	protected GuiButton buttonSortingType, buttonDirection, buttonCtrlMode, buttonGhostMode, buttonTallMode;
	protected TidyButton buttonTidy;
	protected HighlightNetworkButton buttonHighlight;
	protected TerminalSearchModeButton buttonSearchType;
	private Comparator<StoredItemStack> sortComp;
	/** Items that failed to render, so each is only logged once. */
	private final Set<Item> brokenItems = new HashSet<>();
	protected PopupMenuManager popup = new PopupMenuManager(this);

	public AbstractStorageTerminalScreen(T screenContainer, Inventory inv, Component titleIn, int textureSlotCount, int guiHeight, int slotStartX, int slotStartY) {
		super(screenContainer, inv, titleIn);
		screenContainer.onPacket = this::onPacket;
		this.textureSlotCount = textureSlotCount;
		this.guiHeight = guiHeight;
		this.slotStartX = slotStartX;
		this.slotStartY = slotStartY;
	}

	protected void onPacket() {
		// also runs when the sync packet was delivered straight to the menu (another screen is open on top)
		// Amount-only updates already reached the displayed objects, so the list only has to be sorted again when it is sorted by amount
		boolean countOnly = menu.countOnlyUpdate;
		menu.countOnlyUpdate = false;
		if(!countOnly || comparator.type() == 0)refreshItemList = true;
		int s = menu.terminalData;
		controllMode = (s & 0b000_00_0_11) % ControllMode.VALUES.length;
		boolean rev = (s & 0b000_00_1_00) > 0;
		int type = ((s >> 3) & 0b11) | (((s >> 12) & 0b1) << 2);
		comparator = SortingTypes.VALUES[type % SortingTypes.VALUES.length].create(rev);
		searchType = SearchModes.unpack(s);
		ghostItems = (s & 0b1_0_000_00_0_00) == 0;
		boolean tallMode  =  (s & 0b1_0_0_000_00_0_00) != 0;
		if (tallMode != this.tallMode) {
			this.tallMode = tallMode;
			init();
		}
		if(!searchField.isFocused() && (searchType & 1) > 0) {
			searchField.setFocused(true);
		}
		buttonSortingType.setState(type);
		buttonDirection.setState(rev ? 1 : 0);
		buttonSearchType.setSearchType(searchType);
		buttonCtrlMode.setState(controllMode);
		buttonGhostMode.setState(ghostItems ? 1 : 0);
		buttonTallMode.setState(tallMode ? 1 : 0);

		if(!loadedSearch && menu.search != null) {
			loadedSearch = true;
			if((searchType & 2) > 0)
				searchField.setValue(menu.search);
		}
	}

	protected void sendUpdate() {
		CompoundTag c = new CompoundTag();
		c.putInt("d", updateData());
		CompoundTag msg = new CompoundTag();
		msg.put("c", c);
		menu.sendMessage(msg);
	}

	protected int updateData() {
		int d = 0;
		d |= (controllMode & 0b000_0_11);
		d |= ((comparator.isReversed() ? 1 : 0) << 2);
		d |= ((comparator.type() & 0b11) << 3);
		d |= ((comparator.type() & 0b100) << 10);
		d |= SearchModes.pack(searchType);
		d |= ((ghostItems ? 0 : 1) << 9);
		d |= ((tallMode ? 1 : 0) << 10);
		return d;
	}

	@Override
	protected void init() {
		clearWidgets();
		if (tallMode) {
			int guiSize = guiHeight - textureSlotCount * 18;
			rowCount = (height - 30 - guiSize) / 18;
			rowCount = Math.min(rowCount, 20);
			imageHeight = guiSize + rowCount * 18;
			menu.setOffset(0, (rowCount - textureSlotCount) * 18);
			menu.addStorageSlots(rowCount, slotStartX + 1, slotStartY + 1);
		} else {
			rowCount = textureSlotCount;
			menu.setOffset(0, 0);
			menu.addStorageSlots(rowCount, slotStartX + 1, slotStartY + 1);
		}
		// offhand slot on the side of the player's offhand (set after the offset, which resets slot positions)
		Slot offh = getMenu().offhand;
		if (offh != null)offh.x = minecraft.options.mainHand().get() == HumanoidArm.RIGHT ? -26 : 186;
		inventoryLabelY = imageHeight - 92;
		super.init();
		this.searchField = new PlatformEditBox(getFont(), this.leftPos + 82, this.topPos + 6, 89, this.getFont().lineHeight, Component.translatable("narrator.obscuras_storage.terminal_search"));
		this.searchField.setMaxLength(100);
		this.searchField.setBordered(false);
		this.searchField.setVisible(true);
		this.searchField.setTextColor(16777215);
		this.searchField.setValue(searchLast);
		searchLast = "";
		lastSeenQuery = "";
		addWidget(searchField);
		buttonSortingType = addRenderableWidget(makeButton(leftPos - 18, topPos + 5, 0, b -> {
			comparator = SortingTypes.VALUES[(comparator.type() + 1) % SortingTypes.VALUES.length].create(comparator.isReversed());
			buttonSortingType.setState(comparator.type());
			sendUpdate();
			refreshItemList = true;
		}));
		buttonDirection = addRenderableWidget(makeButton(leftPos - 18, topPos + 5 + 18, 1, b -> {
			comparator.setReversed(!comparator.isReversed());
			buttonDirection.setState(comparator.isReversed() ? 1 : 0);
			sendUpdate();
			refreshItemList = true;
		}));
		buttonSearchType = addRenderableWidget(new TerminalSearchModeButton(leftPos - 18, topPos + 5 + 18*2, popup, IAutoFillTerminal.hasSync() || this instanceof CraftingTerminalScreen, () -> {
			searchType = buttonSearchType.getSearchType();
			sendUpdate();
		}));
		buttonSearchType.setSearchType(searchType);
		buttonCtrlMode = addRenderableWidget(makeButton(leftPos - 18, topPos + 5 + 18*3, 3, b -> {
			controllMode = (controllMode + 1) % ControllMode.VALUES.length;
			buttonCtrlMode.setState(controllMode);
			sendUpdate();
		}));
		buttonGhostMode = addRenderableWidget(makeButton(leftPos - 18, topPos + 5 + 18*4, 5, b -> {
			ghostItems = !ghostItems;
			buttonGhostMode.setState(ghostItems ? 1 : 0);
			sendUpdate();
		}));
		buttonTallMode = addRenderableWidget(makeButton(leftPos - 18, topPos + 5 + 18*5, 6, b -> {
			tallMode = !tallMode;
			buttonTallMode.setState(tallMode ? 1 : 0);
			sendUpdate();
			init();
		}));
		// Appended under Tall Mode, so no existing button moves (the Crafting Terminal re-lays them out)
		buttonTidy = addRenderableWidget(new TidyButton(leftPos - 18, topPos + 5 + 18*6, getMenu()));
		buttonHighlight = addRenderableWidget(new HighlightNetworkButton(leftPos - 18, topPos + 5 + 18*7, this::highlightNetwork));
		buttonSortingType.tooltipFactory = s -> Tooltip.create(Component.translatable("tooltip.obscuras_storage.sorting_" + s));
		buttonCtrlMode.tooltipFactory = s -> Tooltip.create(Arrays.stream(I18n.get("tooltip.obscuras_storage.ctrlMode_" + s).split("\\\\")).map(Component::literal).collect(ComponentJoiner.joining(Component.empty(), Component.literal("\n"))));
		buttonGhostMode.tooltipFactory = s -> Tooltip.create(Component.translatable("tooltip.obscuras_storage.ghostMode_" + s));
		buttonTallMode.tooltipFactory = s -> Tooltip.create(Component.translatable("tooltip.obscuras_storage.tallMode_" + s));
		updateSearch();
	}

	protected void updateSearch() {
		String searchString = searchField.getValue();
		boolean queryChanged = !searchLast.equals(searchString);
		long now = System.currentTimeMillis();
		// Trailing debounce for typing: re-arm on every actual change of the typed text.
		if (queryChanged && !searchString.equals(lastSeenQuery)) {
			lastSeenQuery = searchString;
			lastKeystrokeTime = now;
			searchPending = true;
		}
		if (!queryChanged) { searchPending = false; lastSeenQuery = searchString; }
		boolean large = getMenu().itemListClient.size() >= 1000;
		if (searchPending && large && now - lastKeystrokeTime < 150) {
			return;
		}
		// Coalesce pure content refreshes (sync packets) on large lists to one re-filter per 150 ms.
		if (refreshItemList && !queryChanged && !searchPending && large && now - lastContentRefresh < 150) {
			return;
		}
		if (refreshItemList || queryChanged) {
			searchPending = false;
			lastContentRefresh = now;
			getMenu().itemListClientSorted.clear();
			// "a b | c": a AND b, OR c. Terms can be @mod, #tag, $nbt or text/regex for name and tooltip.
			Predicate<TerminalSearch.Target> pred = TerminalSearch.compile(searchString);
			SearchTarget target = new SearchTarget();
			try {
				for (int i = 0;i < getMenu().itemListClient.size();i++) {
					StoredItemStack is = getMenu().itemListClient.get(i);
					if (is != null && is.getStack() != null) {
						target.is = is;
						if (pred.test(target))
							addStackToClientList(is);
					}
				}
				Collections.sort(getMenu().itemListClientSorted, menu.noSort ? sortComp : comparator);
			} catch (Exception e) {
			} finally {
				refreshItemList = false;
				this.searchLast = searchString;
			}
			if (queryChanged) {
				getMenu().scrollTo(0);
				this.currentScroll = 0;
				if ((searchType & 4) > 0) {
					IAutoFillTerminal.sync(searchString);
				}
				if ((searchType & 2) > 0) {
					CompoundTag nbt = new CompoundTag();
					nbt.putString("s", searchString);
					menu.sendMessage(nbt);
				}
				onUpdateSearch(searchString);
			} else {
				getMenu().scrollTo(this.currentScroll);
			}
		}
	}

	/** The search's view of a listed stack, reads go through the caches above. */
	private static class SearchTarget implements TerminalSearch.Target {
		private StoredItemStack is;

		@Override
		public String displayNameLower() {
			return is.getDisplayNameLower();
		}

		@Override
		public String modId() {
			return is.getModId();
		}

		@Override
		public List<String> tooltipLines() {
			return tooltipCache.getUnchecked(is);
		}

		@Override
		public List<String> tags() {
			return tagCache.getUnchecked(is);
		}

		@Override
		public boolean hasData() {
			return is.getStack().hasTag();
		}

		@Override
		public String data() {
			return nbtCache.getUnchecked(is);
		}
	}

	private void addStackToClientList(StoredItemStack is) {
		getMenu().itemListClientSorted.add(is);
	}

	public static TooltipFlag getTooltipFlag(){
		return Minecraft.getInstance().options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL;
	}

	@Override
	protected void containerTick() {
		updateSearch();
		searchField.tick();
	}

	@Override
	public void render(GuiGraphics st, int mouseX, int mouseY, float partialTicks) {
		boolean flag = GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_RELEASE;
		int i = this.leftPos;
		int j = this.topPos;
		int k = i + 174;
		int l = j + 18;
		int i1 = k + 14;
		int j1 = l + rowCount * 18;

		if(ghostItems && hasShiftDown()) {
			if(!menu.noSort) {
				List<StoredItemStack> list = getMenu().itemListClientSorted;
				Object2IntMap<StoredItemStack> map = new Object2IntOpenHashMap<>();
				map.defaultReturnValue(Integer.MAX_VALUE);
				for (int m = 0; m < list.size(); m++) {
					map.put(list.get(m), m);
				}
				sortComp = Comparator.comparing(map::getInt);
				menu.noSort = true;
			}
		} else if(menu.noSort) {
			sortComp = null;
			menu.noSort = false;
			refreshItemList = true;
			menu.itemListClient = new ArrayList<>(menu.itemList);
		}

		if (!this.wasClicking && flag && mouseX >= k && mouseY >= l && mouseX < i1 && mouseY < j1) {
			this.isScrolling = this.needsScrollBars();
		}

		if (!flag) {
			this.isScrolling = false;
		}
		this.wasClicking = flag;

		if (this.isScrolling) {
			this.currentScroll = (mouseY - l - 7.5F) / (j1 - l - 15.0F);
			this.currentScroll = Mth.clamp(this.currentScroll, 0.0F, 1.0F);
			getMenu().scrollTo(this.currentScroll);
		}
		super.render(st, mouseX, mouseY, partialTicks);

		i = k;
		j = l;
		k = j1;
		drawScroll(st, i, j + (int) ((k - j - 17) * this.currentScroll), this.needsScrollBars());

		searchField.render(st, mouseX, mouseY, partialTicks);

		if(menu.beaconLvl >= 0) {
			int x = 176;
			int y = 24 + rowCount * 18;
			st.renderItem(new ItemStack(Items.BEACON), leftPos + x, topPos + y);

			if(isHovering(x, y, 16, 16, mouseX, mouseY)) {
				String info;
				if(Config.get().wirelessTermBeaconLvlCrossDim != -1 && menu.beaconLvl >= Config.get().wirelessTermBeaconLvlCrossDim)info = "\\" + I18n.get("tooltip.obscuras_storage.terminal_beacon.anywhere");
				else if(Config.get().wirelessTermBeaconLvl != -1 && menu.beaconLvl >= Config.get().wirelessTermBeaconLvl)info = "\\" + I18n.get("tooltip.obscuras_storage.terminal_beacon.sameDim");
				else info = "";
				st.renderComponentTooltip(font, Arrays.stream(I18n.get("tooltip.obscuras_storage.terminal_beacon", menu.beaconLvl, info).split("\\\\")).map(Component::literal).collect(Collectors.toList()), mouseX, mouseY);
			}
		}

		if(popup.render(st, font, mouseX, mouseY)) {
			if(isHovering(176, 4, 16, 10, mouseX, mouseY)) {
				st.renderComponentTooltip(font, statTooltip(), mouseX, mouseY);
			} else if(this.menu.getCarried().isEmpty() && slotIDUnderMouse != -1) {
				SlotStorage slot = getMenu().storageSlotList.get(slotIDUnderMouse);
				if(slot.stack != null) {
					List<Component> extra = new ArrayList<>(2);
					if (slot.stack.getQuantity() > 9999) {
						extra.add(Component.translatable("tooltip.obscuras_storage.amount", slot.stack.getQuantity()));
					}
					if (ItemLocator.isEnabled())extra.add(Component.translatable("tooltip.obscuras_storage.locate_hint").withStyle(ChatFormatting.DARK_GRAY));
					StorageModClient.setTooltip(extra.toArray(Component[]::new));
					st.renderTooltip(font, slot.stack.getQuantity() == 0 ? slot.stack.getStack() : slot.stack.getActualStack(), mouseX, mouseY);
					StorageModClient.setTooltip();
				}
			} else
				this.renderTooltip(st, mouseX, mouseY);
		}
	}

	/** Slots, free slots and item types of the network, shown when hovering the "i" in the top right corner. */
	private List<Component> statTooltip() {
		int slots = menu.slotCount, free = menu.freeCount;
		boolean alotSlots = slots >= Short.MAX_VALUE, alotFree = free >= Short.MAX_VALUE;
		return List.of(
				Component.translatable("tooltip.obscuras_storage.terminal_stat.slots",
						alotSlots ? Component.translatable("tooltip.obscuras_storage.terminal_stat.alot") : slots),
				Component.translatable("tooltip.obscuras_storage.terminal_stat.free",
						alotFree ? Component.translatable("tooltip.obscuras_storage.terminal_stat.alot") : free,
								alotFree || alotSlots || slots <= 0 ? "?" : Math.round(free / (float) slots * 100)),
				Component.translatable("tooltip.obscuras_storage.terminal_stat.unique", menu.itemList.size())
				);
	}

	@Override
	protected void renderLabels(GuiGraphics st, int mouseX, int mouseY) {
		super.renderLabels(st, mouseX, mouseY);
		st.drawString(font, "i", 180, 6, 4210752, false);
		st.pose().pushPose();
		slotIDUnderMouse = drawSlots(st, mouseX, mouseY);
		st.pose().popPose();
	}

	protected int drawSlots(GuiGraphics st, int mouseX, int mouseY) {
		StorageTerminalMenu term = getMenu();
		int slotHover = -1;
		for (int i = 0;i < term.storageSlotList.size();i++) {
			if(drawSlot(st, term.storageSlotList.get(i), mouseX, mouseY))slotHover = i;
		}
		return slotHover;
	}

	protected boolean drawSlot(GuiGraphics st, SlotStorage slot, int mouseX, int mouseY) {
		if (slot.stack != null) {
			int i = slot.xDisplayPosition, j = slot.yDisplayPosition;
			PoseStack.Pose pose = st.pose().last();
			try {
				ItemStack stack = slot.stack.getStack().copy().split(1);

				st.renderItem(stack, i, j);
				st.renderItemDecorations(font, stack, i, j);

				drawStackSize(st, getFont(), slot.stack.getQuantity(), i, j);
			} catch (Exception e) {
				// A broken item must not take the whole screen down, it is left out and the rest still draws.
				while (st.pose().last() != pose && !st.pose().clear())st.pose().popPose();
				RenderSystem.enableDepthTest();
				if (brokenItems.add(slot.stack.getStack().getItem()))
					StorageMod.LOGGER.warn("Failed to render item {} in the terminal", BuiltInRegistries.ITEM.getKey(slot.stack.getStack().getItem()), e);
			}
		}

		if (mouseX >= getGuiLeft() + slot.xDisplayPosition - 1 && mouseY >= getGuiTop() + slot.yDisplayPosition - 1 && mouseX < getGuiLeft() + slot.xDisplayPosition + 17 && mouseY < getGuiTop() + slot.yDisplayPosition + 17) {
			int l = slot.xDisplayPosition;
			int t = slot.yDisplayPosition;
			renderSlotHighlight(st, l, t, 0);
			return true;
		}
		return false;
	}

	private void drawStackSize(GuiGraphics st, Font fr, long size, int x, int y) {
		float scaleFactor = 0.6f;
		RenderSystem.disableDepthTest();
		RenderSystem.disableBlend();
		String stackSize = NumberFormatUtil.formatNumber(size);
		st.pose().pushPose();
		st.pose().scale(scaleFactor, scaleFactor, scaleFactor);
		st.pose().translate(0, 0, 450);
		float inverseScaleFactor = 1.0f / scaleFactor;
		int X = (int) (((float) x + 0 + 16.0f - fr.width(stackSize) * scaleFactor) * inverseScaleFactor);
		int Y = (int) (((float) y + 0 + 16.0f - 7.0f * scaleFactor) * inverseScaleFactor);
		st.drawString(fr, stackSize, X, Y, size == 0 ? 0xFFFF00 : 16777215, true);
		st.pose().popPose();
		RenderSystem.enableDepthTest();
	}

	protected boolean needsScrollBars() {
		return this.getMenu().itemListClientSorted.size() > rowCount * 9;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
		if (popup.mouseClick(mouseX, mouseY, mouseButton))return true;
		if (slotIDUnderMouse > -1 && mouseButton == 0 && hasAltDown() && menu.getCarried().isEmpty() && ItemLocator.isEnabled()) {
			// Item Locator. Alt is not used by any control mode, a carried stack keeps the normal insert click.
			StoredItemStack stack = getMenu().getSlotByID(slotIDUnderMouse).stack;
			if (stack != null) {
				if (stack.getQuantity() <= 0) {
					minecraft.player.displayClientMessage(Component.translatable("chat.obscuras_storage.locate.not_found", stack.getStack().getHoverName()).withStyle(ChatFormatting.RED), true);
				} else if (ItemLocatorClient.tryStartRequest()) {
					storageSlotClick(stack, SlotAction.LOCATE, false);
				}
			}
			return true;
		}
		if (slotIDUnderMouse > -1) {
			if (isPullOne(mouseButton)) {
				if (getMenu().getSlotByID(slotIDUnderMouse).stack != null && getMenu().getSlotByID(slotIDUnderMouse).stack.getQuantity() > 0) {
					storageSlotClick(getMenu().getSlotByID(slotIDUnderMouse).stack, SlotAction.PULL_ONE, isTransferOne(mouseButton));
					return true;
				}
				return true;
			} else if (pullHalf(mouseButton)) {
				if (!menu.getCarried().isEmpty()) {
					storageSlotClick(null, hasControlDown() ? SlotAction.GET_QUARTER : SlotAction.GET_HALF, false);
				} else {
					if (getMenu().getSlotByID(slotIDUnderMouse).stack != null && getMenu().getSlotByID(slotIDUnderMouse).stack.getQuantity() > 0) {
						storageSlotClick(getMenu().getSlotByID(slotIDUnderMouse).stack, hasControlDown() ? SlotAction.GET_QUARTER : SlotAction.GET_HALF, false);
						return true;
					}
				}
			} else if (pullNormal(mouseButton)) {
				if (!menu.getCarried().isEmpty()) {
					storageSlotClick(null, SlotAction.PULL_OR_PUSH_STACK, false);
				} else {
					if (getMenu().getSlotByID(slotIDUnderMouse).stack != null) {
						if (getMenu().getSlotByID(slotIDUnderMouse).stack.getQuantity() > 0) {
							storageSlotClick(getMenu().getSlotByID(slotIDUnderMouse).stack, hasShiftDown() ? SlotAction.SHIFT_PULL : SlotAction.PULL_OR_PUSH_STACK, false);
							return true;
						}
					}
				}
			}
		} else if (GLFW.glfwGetKey(mc.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE) != GLFW.GLFW_RELEASE) {
			storageSlotClick(null, SlotAction.SPACE_CLICK, false);
		} else {
			if (isHovering(searchField.getX() - leftPos, searchField.getY() - topPos, 89, this.getFont().lineHeight, mouseX, mouseY)) {
				if(mouseButton == 1)
					searchField.setValue("");
				else
					return super.mouseClicked(mouseX, mouseY, mouseButton);
			} else {
				searchField.setFocused(false);
				return super.mouseClicked(mouseX, mouseY, mouseButton);
			}
		}
		return true;
	}

	/** The Highlight Connected Inventories button and key: the server answers with the positions of the whole network. */
	private void highlightNetwork() {
		if (!ItemLocator.isEnabled() || !ItemLocatorClient.tryStartRequest())return;
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("locate_all", true);
		menu.sendMessage(tag);
	}

	/** Whether a text field of this screen has the focus, so typed keys belong to it. */
	protected boolean isTyping() {
		return searchField.isFocused();
	}

	protected void storageSlotClick(StoredItemStack slotStack, SlotAction act, boolean mod) {
		menu.sync.sendInteract(slotStack, act, mod);
	}

	public boolean isPullOne(int mouseButton) {
		switch (ctrlm()) {
		case AE:
			return mouseButton == 1 && hasShiftDown();
		case RS:
			return mouseButton == 2;
		case DEF:
			return mouseButton == 1 && !menu.getCarried().isEmpty();
		default:
			return false;
		}
	}

	public boolean isTransferOne(int mouseButton) {
		switch (ctrlm()) {
		case AE:
			return hasShiftDown() && hasControlDown();//not in AE
		case RS:
			return hasShiftDown() && mouseButton == 2;
		case DEF:
			return mouseButton == 1 && hasShiftDown();
		default:
			return false;
		}
	}

	public boolean pullHalf(int mouseButton) {
		switch (ctrlm()) {
		case AE:
			return mouseButton == 1;
		case RS:
			return mouseButton == 1;
		case DEF:
			return mouseButton == 1 && menu.getCarried().isEmpty();
		default:
			return false;
		}
	}

	public boolean pullNormal(int mouseButton) {
		switch (ctrlm()) {
		case AE:
		case RS:
		case DEF:
			return mouseButton == 0;
		default:
			return false;
		}
	}

	private ControllMode ctrlm() {
		return ControllMode.VALUES[controllMode];
	}

	public Font getFont() {
		return font;
	}

	@Override
	public boolean keyPressed(int pKeyCode, int pScanCode, int pModifiers) {
		if (popup.keyPressed(pKeyCode, pScanCode, pModifiers))return true;
		if (pKeyCode == 256) {
			this.onClose();
			return true;
		}
		if(pKeyCode == GLFW.GLFW_KEY_TAB)return super.keyPressed(pKeyCode, pScanCode, pModifiers);
		if (this.searchField.keyPressed(pKeyCode, pScanCode, pModifiers) || this.searchField.canConsumeInput()) {
			return true;
		}
		if (!isTyping() && StorageModClient.highlightNetwork != null && StorageModClient.highlightNetwork.matches(pKeyCode, pScanCode)) {
			highlightNetwork();
			return true;
		}
		return super.keyPressed(pKeyCode, pScanCode, pModifiers);
	}

	@Override
	public boolean charTyped(char p_charTyped_1_, int p_charTyped_2_) {
		if(popup.charTyped(p_charTyped_1_, p_charTyped_2_))return true;
		if(searchField.charTyped(p_charTyped_1_, p_charTyped_2_))return true;
		return super.charTyped(p_charTyped_1_, p_charTyped_2_);
	}

	@Override
	public boolean mouseScrolled(double p_mouseScrolled_1_, double p_mouseScrolled_3_, double p_mouseScrolled_5_) {
		if (!this.needsScrollBars()) {
			return false;
		} else {
			int i = ((this.menu).itemListClientSorted.size() + 9 - 1) / 9 - rowCount;
			if (i <= 0) return true;
			this.currentScroll = (float)(this.currentScroll - p_mouseScrolled_5_ / i);
			this.currentScroll = Mth.clamp(this.currentScroll, 0.0F, 1.0F);
			this.menu.scrollTo(this.currentScroll);
			return true;
		}
	}

	public abstract ResourceLocation getGui();

	@Override
	protected void renderBg(GuiGraphics st, float partialTicks, int mouseX, int mouseY) {
		if(tallMode) {
			st.blit(getGui(), this.leftPos, this.topPos, 0, 0, this.imageWidth, slotStartY);
			int guiStart = textureSlotCount * 18 + slotStartY;
			int guiRStart = rowCount * 18 + slotStartY;
			int guiSize = guiHeight - textureSlotCount * 18 - slotStartY;
			st.blit(getGui(), this.leftPos, this.topPos + guiRStart, 0, guiStart, this.imageWidth, guiSize);
			int scrollbarW = 25;
			st.blit(getGui(), this.leftPos, this.topPos + slotStartY, 0, slotStartY, slotStartX + 9 * 18 + scrollbarW, 18);
			for (int i = 1;i < rowCount - 1;i++) {
				st.blit(getGui(), this.leftPos, this.topPos + slotStartY + i * 18, 0, slotStartY + 18, slotStartX + 9 * 18 + scrollbarW, 18);
			}
			st.blit(getGui(), this.leftPos, this.topPos + slotStartY + (rowCount - 1) * 18, 0, slotStartY + (textureSlotCount - 1) * 18, slotStartX + 9 * 18 + scrollbarW, 18);
		} else
			st.blit(getGui(), this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

		Slot offh = getMenu().offhand;
		if (offh != null)st.blit(FLOATING_SLOT, this.leftPos + offh.x - 8, this.topPos + offh.y - 8, 0, 0, 32, 32, 32, 32);
	}

	public GuiButton makeButton(int x, int y, int tile, OnPress pressable) {
		GuiButton btn = new GuiButton(x, y, tile, pressable);
		btn.iconFactory = s -> switch (tile) {
		case 0 -> switch (s) {
		case 0 -> iconTex("sort_amount");
		case 1 -> iconTex("sort_name");
		case 2 -> iconTex("sort_by_mod");
		case 3 -> iconTex("sort_rarity");
		case 4 -> iconTex("sort_registry_id");
		case 5 -> iconTex("sort_durability");
		case 6 -> iconTex("sort_item_group");
		default -> null;
		};
		case 1 -> s == 1 ? iconTex("sort_asc") : iconTex("sort_desc");
		case 3 -> switch (s) {
		case 0 -> iconTex("control_ae");
		case 1 -> iconTex("control_rs");
		case 2 -> iconTex("control_def");
		default -> null;
		};
		case 4 -> s == 1 ? iconTex("refill_on") : iconTex("refill_off");
		case 5 -> s == 1 ? iconTex("keep_last_0") : iconTex("keep_last_off");
		case 6 -> s == 1 ? iconTex("tall_terminal_on") : iconTex("tall_terminal_off");
		default -> null;
		};
		return btn;
	}

	private static ResourceLocation iconTex(String id) {
		return new ResourceLocation("obscuras_storage", "textures/gui/sprites/icons/" + id + ".png");
	}

	protected void onUpdateSearch(String text) {}

	/** Whether the recipe viewers may look into the terminal's items (craftable check, missing item highlight). */
	public boolean isSmartItemSearchOn() {
		return SearchModes.isSmartSearch(searchType);
	}

	@Override
	public void receive(CompoundTag tag) {
		menu.receiveClientNBTPacket(tag);
		refreshItemList = true;
	}

	private FakeSlot fakeSlotUnderMouse = new FakeSlot();
	private static class FakeSlot extends Slot {
		private static final Container DUMMY = new SimpleContainer(1);

		public FakeSlot() {
			super(DUMMY, 0, Integer.MIN_VALUE, Integer.MIN_VALUE);
		}

		@Override
		public boolean allowModification(Player p_150652_) {
			return false;
		}

		@Override
		public void set(ItemStack p_40240_) {}

		@Override
		public ItemStack remove(int p_40227_) {
			return ItemStack.EMPTY;
		}
	}

	@Override
	public Slot getSlotUnderMouse() {
		Slot s = super.getSlotUnderMouse();
		if(s != null)return s;
		if(slotIDUnderMouse > -1 && getMenu().getSlotByID(slotIDUnderMouse).stack != null) {
			fakeSlotUnderMouse.container.setItem(0, getMenu().getSlotByID(slotIDUnderMouse).stack.getStack());
			return fakeSlotUnderMouse;
		}
		return null;
	}
}
