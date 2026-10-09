package com.obscura.storage.screen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.component.DataComponentPatch;
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
import com.mojang.serialization.JsonOps;

import com.google.common.base.Predicates;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.gson.JsonElement;

import com.obscura.storage.Config;
import com.obscura.storage.StorageMod;
import com.obscura.storage.StorageModClient;
import com.obscura.storage.client.ClientUtil;
import com.obscura.storage.client.ItemLocatorClient;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.inventory.StoredItemStack.ComparatorAmount;
import com.obscura.storage.inventory.StoredItemStack.IStoredItemStackComparator;
import com.obscura.storage.inventory.StoredItemStack.SortingTypes;
import com.obscura.storage.menu.StorageTerminalMenu;
import com.obscura.storage.menu.StorageTerminalMenu.SlotStorage;
import com.obscura.storage.screen.widget.EnumCycleButton;
import com.obscura.storage.screen.widget.HighlightNetworkButton;
import com.obscura.storage.screen.widget.TerminalSearchModeButton;
import com.obscura.storage.screen.widget.TidyButton;
import com.obscura.storage.screen.widget.ToggleButton;
import com.obscura.storage.util.ComponentJoiner;
import com.obscura.storage.util.IAutoFillTerminal;
import com.obscura.storage.util.ItemLocator;
import com.obscura.storage.util.NumberFormatUtil;
import com.obscura.storage.util.PopupMenuManager;
import com.obscura.storage.util.TerminalSyncManager.SlotAction;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

public abstract class AbstractStorageTerminalScreen<T extends StorageTerminalMenu> extends PlatformContainerScreen<T> {
	private static final ResourceLocation SCROLLER_SPRITE = ResourceLocation.parse("container/creative_inventory/scroller");
	private static final ResourceLocation SCROLLER_DISABLED_SPRITE = ResourceLocation.parse("container/creative_inventory/scroller_disabled");
	private static final ResourceLocation FLOATING_SLOT = ResourceLocation.tryBuild(StorageMod.modid, "widget/floating_slot");

	private static final LoadingCache<StoredItemStack, List<String>> tooltipCache =
			CacheBuilder.newBuilder().expireAfterAccess(60, TimeUnit.SECONDS).maximumSize(10000).build(CacheLoader.from(key -> {
				var mc = Minecraft.getInstance();
				var flag = mc.options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL;
				return key.getStack().getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, flag).
						stream().map(Component::getString).collect(Collectors.toList());
			}));

	private static final LoadingCache<StoredItemStack, String> componentCache =
			CacheBuilder.newBuilder().expireAfterAccess(60, TimeUnit.SECONDS).maximumSize(10000).build(CacheLoader.from(key -> {
				var ctx = Minecraft.getInstance().level.registryAccess().createSerializationContext(JsonOps.COMPRESSED);
				return DataComponentPatch.CODEC.encodeStart(ctx, key.getStack().getComponentsPatch()).
						mapOrElse(JsonElement::toString, e -> "");
			}));

	private static final LoadingCache<StoredItemStack, List<String>> tagCache =
			CacheBuilder.newBuilder().expireAfterAccess(60, TimeUnit.SECONDS).maximumSize(10000).build(CacheLoader.from(
					key -> key.getStack().getTags().map(t -> t.location().toString()).toList()
					));

	/** Amount scrolled in Creative mode inventory (0 = top, 1 = bottom) */
	protected float currentScroll;
	/** True if the scrollbar is being dragged */
	protected boolean isScrolling;
	/**
	 * True if the left mouse button was held down last time drawScreen was
	 * called.
	 */
	private boolean refreshItemList;
	private int lastSyncCount = -1, lastListChangeCount = -1;
	protected boolean wasClicking;
	protected EditBox searchField;
	protected int slotIDUnderMouse = -1, controllMode, rowCount;
	private String searchLast = "";
	private String lastSeenQuery = "";
	private long lastKeystrokeTime;
	private long lastContentRefresh;
	private boolean searchPending;
	protected boolean loadedSearch = false, ghostItems, tallMode;
	public final int textureSlotCount, guiHeight, slotStartX, slotStartY;
	private IStoredItemStackComparator comparator = new ComparatorAmount(false);
	protected EnumCycleButton<SortingTypes> buttonSortingType;
	protected EnumCycleButton<ControllMode> buttonCtrlMode;
	protected TerminalSearchModeButton buttonSearchType;
	protected ToggleButton buttonDirection, buttonGhostMode, buttonTallMode;
	protected TidyButton buttonTidy;
	protected HighlightNetworkButton buttonHighlight;
	private Comparator<StoredItemStack> sortComp;
	private Object2IntMap<StoredItemStack> ghostSortMap = new Object2IntOpenHashMap<>();
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
		controllMode = (menu.modes & 0xF) % ControllMode.VALUES.length;
		boolean rev = (menu.sorting & 0x100) > 0;
		int type = menu.sorting & 0xFF;
		comparator = SortingTypes.VALUES[type % SortingTypes.VALUES.length].create(rev);
		int searchType = menu.searchType;
		ghostItems = (menu.sorting & 0x200) == 0;
		boolean tallMode  =  (menu.modes & 0x10) != 0;
		if (tallMode != this.tallMode) {
			this.tallMode = tallMode;
			init();
		}
		if (searchType != -1) {
			if(!searchField.isFocused() && (searchType & 1) > 0) {
				searchField.setFocused(true);
			}
			buttonSearchType.setSearchType(searchType);
			if(!loadedSearch && menu.search != null) {
				loadedSearch = true;
				if((searchType & 2) > 0)
					searchField.setValue(menu.search);
			}
		}
		buttonSortingType.setState(SortingTypes.VALUES[type % SortingTypes.VALUES.length]);
		buttonDirection.setState(rev);
		buttonCtrlMode.setState(ControllMode.VALUES[controllMode]);
		buttonGhostMode.setState(ghostItems);
		buttonTallMode.setState(tallMode);
	}

	protected void sendUpdate() {
		CompoundTag c = new CompoundTag();
		int sort = 0;
		sort |= (comparator.type() & 0xFF);
		sort |= (comparator.isReversed() ? 0x100 : 0);
		sort |= (ghostItems ? 0 : 0x200);
		c.putInt("s", sort);
		c.putInt("st", buttonSearchType.getSearchType());
		c.putInt("m", writeModes());
		CompoundTag msg = new CompoundTag();
		msg.put("c", c);
		menu.sendMessage(msg);
	}

	protected int writeModes() {
		int d = 0;
		d |= (controllMode & 0xF);
		d |= (tallMode ? 0x10 : 0);
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
		Slot offh = getMenu().offhand;
		if (minecraft.options.mainHand().get() == HumanoidArm.RIGHT) {
			offh.x = -26;
		} else {
			offh.x = 186;
		}
		inventoryLabelY = imageHeight - 92;
		super.init();
		this.searchField = new EditBox(getFont(), this.leftPos + 82, this.topPos + 6, 89, this.getFont().lineHeight, Component.translatable("narrator.obscuras_storage.terminal_search"));
		this.searchField.setMaxLength(100);
		this.searchField.setBordered(false);
		this.searchField.setVisible(true);
		this.searchField.setTextColor(16777215);
		this.searchField.setValue(searchLast);
		searchLast = "";
		lastSeenQuery = "";
		addWidget(searchField);
		buttonSortingType = addRenderableWidget(new EnumCycleButton<>(leftPos - 18, topPos + 5, Component.translatable("narrator.obscuras_storage.terminal_sort"), "sort", SortingTypes.VALUES, n -> {
			comparator = n.create(comparator.isReversed());
			buttonSortingType.setState(n);
			sendUpdate();
			refreshItemList = true;
		}));
		buttonSortingType.icon = s -> ResourceLocation.tryBuild(StorageMod.modid, switch (s) {
			case AMOUNT -> "icons/sort_amount";
			case NAME -> "icons/sort_name";
			case BY_MOD -> "icons/sort_by_mod";
			case RARITY -> "icons/sort_rarity";
			case REGISTRY_ID -> "icons/sort_registry_id";
			case DURABILITY -> "icons/sort_durability";
			case ITEM_GROUP -> "icons/sort_item_group";
		});
		buttonDirection = addRenderableWidget(ToggleButton.builder(leftPos - 18, topPos + 5 + 18).
				name(Component.translatable("narrator.obscuras_storage.terminal_sort_rev")).
				iconOff(ResourceLocation.tryBuild(StorageMod.modid, "icons/sort_desc")).
				iconOn(ResourceLocation.tryBuild(StorageMod.modid, "icons/sort_asc")).
				build(n -> {
					comparator.setReversed(n);
					buttonDirection.setState(n);
					sendUpdate();
					refreshItemList = true;
				}));
		buttonSearchType = addRenderableWidget(new TerminalSearchModeButton(leftPos - 18, topPos + 5 + 18*2, popup, IAutoFillTerminal.hasSync() || this instanceof CraftingTerminalScreen, this::sendUpdate));
		buttonCtrlMode = addRenderableWidget(new EnumCycleButton<>(leftPos - 18, topPos + 5 + 18*3, Component.translatable("narrator.obscuras_storage.terminal_control"), "control", ControllMode.VALUES, n -> {
			controllMode = n.ordinal();
			buttonCtrlMode.setState(n);
			sendUpdate();
		}));
		buttonGhostMode = addRenderableWidget(ToggleButton.builder(leftPos - 18, topPos + 5 + 18*4).
				name(Component.translatable("narrator.obscuras_storage.terminal_ghost_items")).
				iconOff(ResourceLocation.tryBuild(StorageMod.modid, "icons/keep_last_off")).
				iconOn(ResourceLocation.tryBuild(StorageMod.modid, "icons/keep_last_0")).
				build(n -> {
					ghostItems = n;
					buttonGhostMode.setState(n);
					sendUpdate();
				}));
		buttonGhostMode.setTooltip(Tooltip.create(Component.translatable("tooltip.obscuras_storage.ghostMode_off")), Tooltip.create(Component.translatable("tooltip.obscuras_storage.ghostMode_on")));
		buttonTallMode = addRenderableWidget(ToggleButton.builder(leftPos - 18, topPos + 5 + 18*5).
				name(Component.translatable("narrator.obscuras_storage.terminal_tall_mode")).
				iconOff(ResourceLocation.tryBuild(StorageMod.modid, "icons/tall_terminal_off")).
				iconOn(ResourceLocation.tryBuild(StorageMod.modid, "icons/tall_terminal_on")).
				build(n -> {
					tallMode = n;
					buttonTallMode.setState(n);
					sendUpdate();
					init();
				}));
		buttonTallMode.setTooltip(Tooltip.create(Component.translatable("tooltip.obscuras_storage.tallMode_off")), Tooltip.create(Component.translatable("tooltip.obscuras_storage.tallMode_on")));
		// Appended under Tall Mode, so no existing button moves (the Crafting Terminal re-lays them out)
		buttonTidy = addRenderableWidget(new TidyButton(leftPos - 18, topPos + 5 + 18*6, menu));
		buttonHighlight = addRenderableWidget(new HighlightNetworkButton(leftPos - 18, topPos + 5 + 18*7, this::highlightNetwork));
		buttonSortingType.tooltipFactory = s -> Tooltip.create(Component.translatable("tooltip.obscuras_storage.sorting_" + s.name().toLowerCase(Locale.ROOT)));
		buttonCtrlMode.tooltipFactory = s -> Tooltip.create(Arrays.stream(I18n.get("tooltip.obscuras_storage.ctrlMode_" + s.name().toLowerCase(Locale.ROOT)).split("\\\\")).map(Component::literal).collect(ComponentJoiner.joining(Component.empty(), Component.literal("\n"))));
		updateSearch();
	}

	private static Pattern buildPattern(String s) {
		try {
			return Pattern.compile(s, Pattern.CASE_INSENSITIVE);
		} catch (Throwable ignore) {
			try {
				return Pattern.compile(Pattern.quote(s), Pattern.CASE_INSENSITIVE);
			} catch (Throwable ignored) {
				return null;
			}
		}
	}

	protected void updateSearch() {
		// Sync packets are delivered to the menu (also while another screen covers this one).
		if (menu.clientSyncCount != lastSyncCount) {
			lastSyncCount = menu.clientSyncCount;
			// Count-only packets are applied to the listed stacks in place by the menu, so the
			// filtered and sorted list stays valid unless items came or went, or it is ordered by
			// amount. Skipping the re-filter/re-sort there is what keeps a busy network cheap.
			if (menu.clientListChangeCount != lastListChangeCount || comparator instanceof ComparatorAmount)
				refreshItemList = true;
			lastListChangeCount = menu.clientListChangeCount;
		}
		String searchString = searchField.getValue();
		boolean queryChanged = !searchLast.equals(searchString);
		long now = System.currentTimeMillis();
		// The typing debounce must not be disabled by incoming content refreshes: on a busy network
		// a sync packet can arrive every tick, and letting that bypass the debounce makes the full
		// re-filter+re-sort run 20x/s exactly when the list is large enough for it to hurt.
		// Trailing debounce: re-arm only when the typed text ACTUALLY changed since the last tick,
		// so a burst of keystrokes collapses into one re-filter 150ms after the last of them. Arming
		// on queryChanged alone would re-arm every tick while the query differs from searchLast and
		// so never release; arming only once per burst would instead time the window from the FIRST
		// keystroke and re-filter several times mid-word.
		if (queryChanged && !searchString.equals(lastSeenQuery)) {
			lastSeenQuery = searchString;
			lastKeystrokeTime = now;
			searchPending = true;
		}
		if (!queryChanged) {
			//typed text is back at / has reached the applied query: nothing pending. lastSeenQuery
			//must be reset too, or retyping the same text later is seen as "unchanged" and skips the
			//debounce entirely.
			searchPending = false;
			lastSeenQuery = searchString;
		}
		boolean large = getMenu().itemListClient.size() >= 1000;
		if (searchPending && large && now - lastKeystrokeTime < 150) {
			return;
		}
		// Multiple content refreshes arriving between re-filters coalesce into one: while the query
		// is unchanged, rate-limit pure content refreshes on large lists. Small lists stay instant.
		if (refreshItemList && !queryChanged && !searchPending && large && now - lastContentRefresh < 150) {
			return;
		}
		if (refreshItemList || queryChanged) {
			searchPending = false;
			lastContentRefresh = now;
			getMenu().itemListClientSorted.clear();
			Predicate<StoredItemStack> pred = null;
			String[] or = searchString.split("\\|");
			for (int i = 0; i < or.length; i++) {
				String part = or[i].trim();
				if (part.isEmpty())continue;
				String[] sp = part.split(" ");
				Predicate<StoredItemStack> p = Predicates.alwaysTrue();
				for (int j = 0; j < sp.length; j++) {
					String s = sp[j].toLowerCase();
					if (s.startsWith("@")) {
						String fs = s.substring(1);
						p = p.and(is -> BuiltInRegistries.ITEM.getKey(is.getStack().getItem()).getNamespace().contains(fs));
					} else if (s.startsWith("#")) {
						String fs = s.substring(1);
						p = p.and(is -> {
							try {
								return tagCache.get(is).stream().anyMatch(st -> st.contains(fs));
							} catch (Exception e) {
								return false;
							}
						});
					} else if (s.startsWith("$")) {
						Pattern m = buildPattern(s.substring(1));
						if (m == null)continue;
						p = p.and(is -> {
							if (is.getStack().getComponentsPatch().isEmpty())return false;
							try {
								return m.matcher(componentCache.get(is)).find();
							} catch (Exception e) {
								return false;
							}
						});
					} else {
						Pattern m = buildPattern(s);
						if (m == null)continue;
						p = p.and(is -> {
							try {
								if (m.matcher(is.getDisplayNameLower()).find()) {
									return true;
								}
								for (String lp : tooltipCache.get(is)) {
									if (m.matcher(lp).find()) {
										return true;
									}
								}
							} catch (Exception e) {
							}
							return false;
						});
					}
				}
				if (pred == null)pred = p;
				else pred = pred.or(p);
			}
			if (pred == null)pred = Predicates.alwaysTrue();
			try {
				for (int i = 0;i < getMenu().itemListClient.size();i++) {
					StoredItemStack is = getMenu().itemListClient.get(i);
					if (is != null && is.getStack() != null) {
						if (pred.test(is))
							addStackToClientList(is);
					}
				}
				// A comparator that ever violates its general contract must degrade to one unsorted
				// frame, never wedge the screen by leaving refreshItemList/searchLast untouched.
				Collections.sort(getMenu().itemListClientSorted, menu.noSort ? sortComp : comparator);
			} catch (Exception e) {
			} finally {
				refreshItemList = false;
				this.searchLast = searchString;
			}
			if(queryChanged) {
				getMenu().scrollTo(0);
				this.currentScroll = 0;
				int searchType = buttonSearchType.getSearchType();
				if (searchType != -1) {
					if ((searchType & 4) > 0) {
						IAutoFillTerminal.sync(searchString);
					}
					if ((searchType & 2) > 0) {
						CompoundTag nbt = new CompoundTag();
						nbt.putString("s", searchString);
						menu.sendMessage(nbt);
					}
				}
				onUpdateSearch(searchString);
			} else {
				getMenu().scrollTo(this.currentScroll);
			}
		}
	}

	private void addStackToClientList(StoredItemStack is) {
		getMenu().itemListClientSorted.add(is);
	}

	@Override
	protected void containerTick() {
		updateSearch();
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

		if(menu.itemsLoaded && ghostItems && hasShiftDown()) {
			if(!menu.noSort || refreshItemList) {
				List<StoredItemStack> list = getMenu().itemListClientSorted;
				ghostSortMap.clear();
				ghostSortMap.defaultReturnValue(Integer.MAX_VALUE);
				for (int m = 0; m < list.size(); m++) {
					ghostSortMap.put(list.get(m), m);
				}
				sortComp = Comparator.comparing(ghostSortMap::getInt);
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
		st.blitSprite(this.needsScrollBars() ? SCROLLER_SPRITE : SCROLLER_DISABLED_SPRITE, i, j + (int) ((k - j - 17) * this.currentScroll), 12, 15);

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
		if(isHovering(176, 4, 16, 10, mouseX, mouseY)) {
			st.renderComponentTooltip(font, List.of(
					Component.translatable("tooltip.obscuras_storage.terminal_stat.slots",
							menu.slotCount == Short.MAX_VALUE ?
									Component.translatable("tooltip.obscuras_storage.terminal_stat.alot") : menu.slotCount
							),
					Component.translatable("tooltip.obscuras_storage.terminal_stat.free",
							menu.freeCount == Short.MAX_VALUE ?
									Component.translatable("tooltip.obscuras_storage.terminal_stat.alot") : menu.freeCount,
									menu.freeCount == Short.MAX_VALUE || menu.slotCount == Short.MAX_VALUE ?
											"?" : Math.round(menu.freeCount / (float) menu.slotCount * 100)
							),
					Component.translatable("tooltip.obscuras_storage.terminal_stat.unique", menu.itemList.size())
					), mouseX, mouseY);
		}
		if (popup.render(st, font, mouseX, mouseY)) {
			if (this.menu.getCarried().isEmpty() && slotIDUnderMouse != -1) {
				SlotStorage slot = getMenu().storageSlotList.get(slotIDUnderMouse);
				if(slot.stack != null) {
					Component locateHint = ItemLocator.isEnabled() ? Component.translatable("tooltip.obscuras_storage.locate_hint").withStyle(ChatFormatting.DARK_GRAY) : null;
					List<Component> extra = new ArrayList<>(2);
					if (slot.stack.getQuantity() > 9999) {
						extra.add(Component.translatable("tooltip.obscuras_storage.amount", slot.stack.getQuantity()));
					}
					if (locateHint != null)extra.add(locateHint);
					ClientUtil.setTooltip(extra.toArray(Component[]::new));
					try {
						st.renderTooltip(font, slot.stack.getQuantity() == 0 ? slot.stack.getStack() : slot.stack.getActualStack(), mouseX, mouseY);
					} catch (IllegalStateException e) {
						if (locateHint != null)ClientUtil.setTooltip(Component.translatable("tooltip.obscuras_storage.amount", slot.stack.getQuantity()), locateHint);
						else ClientUtil.setTooltip(Component.translatable("tooltip.obscuras_storage.amount", slot.stack.getQuantity()));
						st.renderTooltip(font, slot.stack.getStack(), mouseX, mouseY);//Render with stack size 1
					}
					ClientUtil.setTooltip();
				}
			} else {
				this.renderTooltip(st, mouseX, mouseY);
			}
		} else clearTooltipForNextRenderPass();
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
		// Pass 1: Render all item icons
		for (int i = 0;i < term.storageSlotList.size();i++) {
			SlotStorage slot = term.storageSlotList.get(i);
			if (slot.stack != null) {
				try {
					ItemStack stack = slot.stack.getStack().copyWithCount(1);
					st.renderItem(stack, slot.xDisplayPosition, slot.yDisplayPosition);
				} catch (Exception e) {
					StorageMod.LOGGER.debug("Failed to render item in terminal slot", e);
				}
			}
		}
		// Pass 2: Render all decorations/text overlays and check hover
		for (int i = 0;i < term.storageSlotList.size();i++) {
			SlotStorage slot = term.storageSlotList.get(i);
			if (slot.stack != null) {
				try {
					ItemStack stack = slot.stack.getStack().copyWithCount(1);
					int x = slot.xDisplayPosition, y = slot.yDisplayPosition;
					st.renderItemDecorations(font, stack, x, y);
					drawStackSize(st, getFont(), slot.stack.getQuantity(), x, y);
				} catch (Exception e) {
					CrashReport report = CrashReport.forThrowable(e, "Rendering item stack in terminal");
					report.addCategory("Item details:").
					setDetail("Item Id", () -> BuiltInRegistries.ITEM.getKey(slot.stack.getStack().getItem()).toString()).
					setDetail("Item Count", slot.stack.getQuantity()).
					setDetail("Item Components", () -> componentCache.get(slot.stack));
					throw new ReportedException(report);
				}
			}
			if (mouseX >= getGuiLeft() + slot.xDisplayPosition - 1 && mouseY >= getGuiTop() + slot.yDisplayPosition - 1 && mouseX < getGuiLeft() + slot.xDisplayPosition + 17 && mouseY < getGuiTop() + slot.yDisplayPosition + 17) {
				renderSlotHighlight(st, slot.xDisplayPosition, slot.yDisplayPosition, 0);
				slotHover = i;
			}
		}
		return slotHover;
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
		} else if (GLFW.glfwGetKey(minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE) != GLFW.GLFW_RELEASE) {
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

	@Override
	public boolean charTyped(char pCodePoint, int pModifiers) {
		if(popup.charTyped(pCodePoint, pModifiers))return true;
		if(searchField.charTyped(pCodePoint, pModifiers))return true;
		return super.charTyped(pCodePoint, pModifiers);
	}

	@Override
	public boolean mouseScrolled(double p_mouseScrolled_1_, double p_mouseScrolled_3_, double xd, double p_mouseScrolled_5_) {
		if (!this.needsScrollBars()) {
			return false;
		} else {
			int i = ((this.menu).itemListClientSorted.size() + 9 - 1) / 9 - rowCount;
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
		st.blitSprite(FLOATING_SLOT, this.leftPos + offh.x - 8, this.topPos + offh.y - 8, 32, 32);
	}

	protected void onUpdateSearch(String text) {}

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

	public static enum ControllMode {
		AE, RS, DEF,
		;
		public static final ControllMode[] VALUES = values();
	}

	public int getRowCount() {
		return rowCount;
	}

	public boolean isSmartItemSearchOn() {
		return (buttonSearchType.getSearchType() & 8) == 0;
	}

	@Override
	public void getExclusionAreas(Consumer<Box> consumer) {
		consumer.accept(new Box(leftPos - 30, topPos, 30, imageHeight));
	}
}
