package com.obscura.storage.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import com.mojang.datafixers.util.Pair;

import com.google.common.collect.Lists;

import com.obscura.storage.Content;
import com.obscura.storage.block.entity.StorageTerminalBlockEntity;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.tidy.TidyManager;
import com.obscura.storage.tidy.TidyStatus;
import com.obscura.storage.util.DataSlots;
import com.obscura.storage.util.IntDataSlots;
import com.obscura.storage.util.IDataReceiver;
import com.obscura.storage.util.ItemLocator;
import com.obscura.storage.util.TerminalSyncManager;
import com.obscura.storage.util.TerminalSyncManager.InteractHandler;
import com.obscura.storage.util.TerminalSyncManager.SlotAction;

public class StorageTerminalMenu extends RecipeBookMenu<CraftingInput, CraftingRecipe> implements IDataReceiver, InteractHandler {
	protected StorageTerminalBlockEntity te;
	protected int playerSlotsStart;
	public List<SlotStorage> storageSlotList = new ArrayList<>();
	public List<StoredItemStack> itemList = Lists.<StoredItemStack>newArrayList();
	public List<StoredItemStack> itemListClient = Lists.<StoredItemStack>newArrayList();
	public List<StoredItemStack> itemListClientSorted = Lists.<StoredItemStack>newArrayList();
	public List<SlotData> slotData = new ArrayList<>();
	public TerminalSyncManager sync;
	private int lines;
	protected Inventory pinv;
	public Runnable onPacket;
	public int sorting, modes, searchType = -1, beaconLvl, changeCount;
	public int slotCount, freeCount;
	/** Tidy button state as synced to the client, see {@link TidyStatus}. */
	public int tidyStatus, tidyDone, tidyPlanned, tidyMovesPerTick;
	/** Server: the values the Tidy data slots send, see {@link TidyManager#fillStatus}. */
	private final int[] tidyView = new int[4];
	public String search;
	public boolean noSort;
	public boolean itemsLoaded = false;
	/** Client: incremented for every sync packet received, lets the screen notice updates it missed. */
	public int clientSyncCount;
	/**
	 * Client: incremented for every sync packet that changed more than the counts of listed items
	 * (an item appeared or disappeared). Count-only packets update the listed stacks in place.
	 */
	public int clientListChangeCount;
	public Slot offhand;

	public StorageTerminalMenu(int id, Inventory inv, StorageTerminalBlockEntity te) {
		this(Content.storageTerminalMenu.get(), id, inv, te);
		this.addPlayerSlots(inv, 8, 120);
	}

	public StorageTerminalMenu(MenuType<?> type, int id, Inventory inv, StorageTerminalBlockEntity te) {
		super(type, id);
		this.te = te;
		this.pinv = inv;
		this.sync = new TerminalSyncManager(inv.player.registryAccess());
		addStorageSlots();
		addDataSlot(DataSlots.create(v -> sorting = v, () -> te != null ? te.getSorting() : 0).onUpdate(this::updateGui));
		addDataSlot(DataSlots.create(v -> modes = v, () -> te != null ? te.getModes() : 0).onUpdate(this::updateGui));
		addDataSlot(DataSlots.create(v -> searchType = v, () -> te != null ? te.getSearchType() : 0).onUpdate(this::updateGui));
		addDataSlot(DataSlots.create(v -> beaconLvl = v, () -> te != null ? te.getBeaconLevel() : 0));
		addDataSlot(DataSlots.create(v -> slotCount = v, () -> te != null ? te.getSlotCount() : -1));
		addDataSlot(DataSlots.create(v -> freeCount = v, () -> te != null ? te.getFreeCount() : -1));
		// Tidy button: state, moves done and planned, moves per tick. Filled once per tick on the
		// server (broadcastChanges); the job only moves its numbers every few ticks, and data slots are
		// only sent when they change.
		addDataSlots(IntDataSlots.create(v -> tidyStatus = v, () -> tidyView[0]));
		addDataSlots(IntDataSlots.create(v -> tidyDone = v, () -> tidyView[1]));
		addDataSlots(IntDataSlots.create(v -> tidyPlanned = v, () -> tidyView[2]));
		addDataSlots(IntDataSlots.create(v -> tidyMovesPerTick = v, () -> tidyView[3]));
	}

	private void updateGui() {
		if(onPacket != null)onPacket.run();
	}

	public StorageTerminalMenu(MenuType<?> type, int id, Inventory inv) {
		this(type, id, inv, null);
	}

	protected void addStorageSlots() {
		addStorageSlots(5, 8, 18);
	}

	public StorageTerminalMenu(int id, Inventory inv) {
		this(Content.storageTerminalMenu.get(), id, inv);
		this.addPlayerSlots(inv, 8, 120);
	}

	protected void addPlayerSlots(Inventory playerInventory, int x, int y) {
		this.playerSlotsStart = slots.size() - 1;
		for (int i = 0;i < 3;++i) {
			for (int j = 0;j < 9;++j) {
				addSlot(new Slot(playerInventory, j + i * 9 + 9, x + j * 18, y + i * 18));
			}
		}

		for (int i = 0;i < 9;++i) {
			addSlot(new Slot(playerInventory, i, x + i * 18, y + 58));
		}

		offhand = addSlot(new Slot(playerInventory, playerInventory.items.size() + playerInventory.armor.size(), 0, y + 58) {

			@Override
			public void setByPlayer(ItemStack p_270479_, ItemStack p_299920_) {
				playerInventory.player.onEquipItem(EquipmentSlot.OFFHAND, p_270479_, p_299920_);
				super.setByPlayer(p_270479_, p_299920_);
			}

			@Override
			public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
				return Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);
			}

		});
	}

	/** End (exclusive) of the slot range a shift-pull may fill: main inventory and hotbar, never the offhand. */
	protected int pullEnd() {
		return offhand != null ? slots.indexOf(offhand) : slots.size();
	}

	@Override
	protected Slot addSlot(Slot slotIn) {
		slotData.add(new SlotData(slotIn));
		return super.addSlot(slotIn);
	}

	public void setOffset(int x, int y) {
		slotData.forEach(d -> d.setOffset(x, y));
	}

	public final void addStorageSlots(int lines, int x, int y) {
		storageSlotList.clear();
		this.lines = lines;
		for (int i = 0;i < lines;++i) {
			for (int j = 0;j < 9;++j) {
				this.addSlotToContainer(new SlotStorage(this.te, i * 9 + j, x + j * 18, y + i * 18));
			}
		}
		scrollTo(0.0F);
	}

	protected final void addSlotToContainer(SlotStorage slotStorage) {
		storageSlotList.add(slotStorage);
	}

	public static class SlotStorage {
		/** display position of the inventory slot on the screen x axis */
		public int xDisplayPosition;
		/** display position of the inventory slot on the screen y axis */
		public int yDisplayPosition;
		/** The index of the slot in the inventory. */
		private final int slotIndex;
		/** The inventory we want to extract a slot from. */
		public final StorageTerminalBlockEntity inventory;
		public StoredItemStack stack;

		public SlotStorage(StorageTerminalBlockEntity inventory, int slotIndex, int xPosition, int yPosition) {
			this.xDisplayPosition = xPosition;
			this.yDisplayPosition = yPosition;
			this.slotIndex = slotIndex;
			this.inventory = inventory;
		}

		public ItemStack pullFromSlot(long max) {
			if (stack == null || max < 1 || inventory == null)
				return ItemStack.EMPTY;
			StoredItemStack r = inventory.pullStack(stack, max);
			if (r != null) {
				return r.getActualStack();
			} else
				return ItemStack.EMPTY;
		}

		public ItemStack pushStack(ItemStack pushStack) {
			if(inventory == null)return pushStack;
			StoredItemStack r = inventory.pushStack(new StoredItemStack(pushStack, pushStack.getCount()));
			if (r != null) {
				return r.getActualStack();
			} else
				return ItemStack.EMPTY;
		}

		public int getSlotIndex() {
			return slotIndex;
		}
	}

	/** The terminal this menu is attached to. Null on the client. */
	public StorageTerminalBlockEntity getTerminal() {
		return te;
	}

	@Override
	public boolean stillValid(Player playerIn) {
		return te == null || te.canInteractWith(playerIn, true);
	}

	public final void scrollTo(float p_148329_1_) {
		int i = (this.itemListClientSorted.size() + 9 - 1) / 9 - lines;
		int j = (int) (p_148329_1_ * i + 0.5D);

		if (j < 0) {
			j = 0;
		}

		for (int k = 0;k < lines;++k) {
			for (int l = 0;l < 9;++l) {
				int i1 = l + (k + j) * 9;

				if (i1 >= 0 && i1 < this.itemListClientSorted.size()) {
					setSlotContents(l + k * 9, this.itemListClientSorted.get(i1));
				} else {
					setSlotContents(l + k * 9, null);
				}
			}
		}
	}

	public final void setSlotContents(int id, StoredItemStack stack) {
		storageSlotList.get(id).stack = stack;
	}

	public final SlotStorage getSlotByID(int id) {
		return storageSlotList.get(id);
	}

	@Override
	public void broadcastChanges() {
		if(te == null)return;
		// Network Doctor: the sync is booked to the network the terminal reads, with its packets
		NetworkStats stats = te.getDoctorStats();
		long time = te.getLevel() != null ? te.getLevel().getGameTime() : 0;
		long t0 = NetworkDoctor.begin(stats);
		try {
			Map<StoredItemStack, StoredItemStack> itemsCount = te.getStacks();
			String currentSearch = te.getLastSearch();

			sync.setSearch(currentSearch);
			sync.update(te.getChangeCount(), itemsCount, (ServerPlayer) pinv.player, !currentSearch.equals(search) ? tag -> {
				search = currentSearch;
				tag.putString("s", search);
			} : null);
		} finally {
			NetworkDoctor.end(stats, time, t0);
		}
		int packets = sync.takeSentPackets();
		long bytes = sync.takeSentBytes();
		if (stats != null && packets > 0)stats.addPackets(time, packets, bytes);
		if (pinv.player instanceof ServerPlayer sp)TidyManager.fillStatus(te, sp, tidyView);
		super.broadcastChanges();
	}

	public final void receiveClientNBTPacket(CompoundTag message) {
		clientSyncCount++;
		if(sync.receiveUpdate(pinv.player.registryAccess(), message)) {
			itemList = sync.getAsList();
			if(noSort || (itemsLoaded && sync.wasLastUpdateCountsOnly())) {
				// Same set of items as before: update the listed stacks in place, so the screen can
				// keep its filtered and sorted list unless it sorts by amount.
				itemListClient.forEach(s -> s.setCount(sync.getAmount(s)));
				if (!sync.wasLastUpdateCountsOnly())clientListChangeCount++;
			} else {
				itemListClient = new ArrayList<>(itemList);
				clientListChangeCount++;
			}
			itemsLoaded = true;
			pinv.setChanged();
		}
		if(message.contains("s"))
			search = message.getString("s");
		if(onPacket != null)onPacket.run();
	}

	@Override
	public final ItemStack quickMoveStack(Player playerIn, int index) {
		if (slots.size() > index) {
			if (index > playerSlotsStart && te != null) {
				if (slots.get(index) != null && slots.get(index).hasItem()) {
					Slot slot = slots.get(index);
					ItemStack slotStack = slot.getItem();
					StoredItemStack c = te.pushStack(new StoredItemStack(slotStack, slotStack.getCount()));
					ItemStack itemstack = c != null ? c.getActualStack() : ItemStack.EMPTY;
					slot.set(itemstack);
					if (!playerIn.level().isClientSide)
						broadcastChanges();
				}
			} else {
				return shiftClickItems(playerIn, index);
			}
		}
		return ItemStack.EMPTY;
	}

	protected ItemStack shiftClickItems(Player playerIn, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public void fillCraftSlotsStackedContents(StackedContents itemHelperIn) {
	}

	@Override
	public void clearCraftingContent() {
	}

	@Override
	public int getResultSlotIndex() {
		return 0;
	}

	@Override
	public int getGridWidth() {
		return 0;
	}

	@Override
	public int getGridHeight() {
		return 0;
	}

	@Override
	public int getSize() {
		return 0;
	}

	public void sendMessage(CompoundTag compound) {
		NetworkHandler.sendDataToServer(compound);
	}

	@Override
	public void receive(CompoundTag message) {
		if(pinv.player.isSpectator())return;
		if(message.contains("s")) {
			String search = message.getString("s");
			if (search.length() > 256) search = search.substring(0, 256);
			te.setLastSearch(search);
		}
		sync.receiveInteract(message, this);
		// Tidy button: true = start, false = stop (validated by the manager)
		if(message.contains("tidy") && pinv.player instanceof ServerPlayer sp)
			TidyManager.onClick(sp, this, message.getBoolean("tidy"));
		// Highlight Connected Inventories button: every inventory of the network
		if(message.contains("locate_all") && pinv.player instanceof ServerPlayer sp)
			ItemLocator.locateAll(sp, this);
		if(message.contains("c")) {
			CompoundTag d = message.getCompound("c");
			te.setSorting(Mth.clamp(d.getInt("s"), 0, 0xFFFF));
			te.setSearchType(Mth.clamp(d.getInt("st"), 0, 15));
			te.setModes(Mth.clamp(d.getInt("m"), 0, 0xFFFF));
		}
	}

	@Override
	public RecipeBookType getRecipeBookType() {
		return RecipeBookType.CRAFTING;
	}

	@Override
	public boolean shouldMoveToInventory(int p_150635_) {
		return false;
	}

	@Override
	public void onInteract(StoredItemStack clicked, SlotAction act, boolean mod) {
		ServerPlayer player = (ServerPlayer) pinv.player;
		player.resetLastActionTime();
		if(act == SlotAction.LOCATE) {
			// Read-only, must not fall through to the pull branches below
			ItemLocator.locate(player, this, clicked);
			return;
		}
		if(act == SlotAction.SPACE_CLICK) {
			for (int i = playerSlotsStart + 1;i < playerSlotsStart + 28;i++) {
				quickMoveStack(player, i);
			}
		} else {
			if (act == SlotAction.PULL_OR_PUSH_STACK) {
				ItemStack stack = getCarried();
				if (!stack.isEmpty()) {
					StoredItemStack rem = te.pushStack(new StoredItemStack(stack));
					ItemStack itemstack = rem == null ? ItemStack.EMPTY : rem.getActualStack();
					setCarried(itemstack);
				} else {
					if (clicked == null)return;
					StoredItemStack pulled = te.pullStack(clicked, clicked.getMaxStackSize());
					if(pulled != null) {
						setCarried(pulled.getActualStack());
					}
				}
			} else if (act == SlotAction.PULL_ONE) {
				ItemStack stack = getCarried();
				if (clicked == null)return;
				if (mod) {
					StoredItemStack pulled = te.pullStack(clicked, 1);
					if(pulled != null) {
						ItemStack itemstack = pulled.getActualStack();
						this.moveItemStackTo(itemstack, playerSlotsStart + 1, pullEnd(), true);
						if (itemstack.getCount() > 0)
							te.pushOrDrop(itemstack);
						player.getInventory().setChanged();
					}
				} else {
					if (!stack.isEmpty()) {
						if (ItemStack.isSameItemSameComponents(stack, clicked.getStack()) && stack.getCount() + 1 <= stack.getMaxStackSize()) {
							StoredItemStack pulled = te.pullStack(clicked, 1);
							if (pulled != null) {
								stack.grow(1);
							}
						}
					} else {
						StoredItemStack pulled = te.pullStack(clicked, 1);
						if (pulled != null) {
							setCarried(pulled.getActualStack());
						}
					}
				}
			} else if (act == SlotAction.GET_HALF) {
				ItemStack stack = getCarried();
				if (!stack.isEmpty()) {
					ItemStack stack1 = stack.split(Math.max(Math.min(stack.getCount(), stack.getMaxStackSize()) / 2, 1));
					ItemStack itemstack = te.pushStack(stack1);
					stack.grow(!itemstack.isEmpty() ? itemstack.getCount() : 0);
					setCarried(stack);
				} else {
					if (clicked == null)return;
					StoredItemStack pulled = te.pullStack(clicked, Math.max(Math.min(clicked.getQuantity(), clicked.getMaxStackSize()) / 2, 1));
					if(pulled != null) {
						setCarried(pulled.getActualStack());
					}
				}
			} else if (act == SlotAction.GET_QUARTER) {
				ItemStack stack = getCarried();
				if (!stack.isEmpty()) {
					ItemStack stack1 = stack.split(Math.max(Math.min(stack.getCount(), stack.getMaxStackSize()) / 4, 1));
					ItemStack itemstack = te.pushStack(stack1);
					stack.grow(!itemstack.isEmpty() ? itemstack.getCount() : 0);
					setCarried(stack);
				} else {
					if (clicked == null)return;
					StoredItemStack pulled = te.pullStack(clicked, Math.max(Math.min(clicked.getQuantity(), clicked.getMaxStackSize()) / 4, 1));
					if(pulled != null) {
						setCarried(pulled.getActualStack());
					}
				}
			} else {
				if (clicked == null)return;
				StoredItemStack pulled = te.pullStack(clicked, clicked.getMaxStackSize());
				if(pulled != null) {
					ItemStack itemstack = pulled.getActualStack();
					this.moveItemStackTo(itemstack, playerSlotsStart + 1, pullEnd(), true);
					if (itemstack.getCount() > 0)
						te.pushOrDrop(itemstack);
					player.getInventory().setChanged();
				}
			}
		}
	}

	public static record SlotData(Slot slot, int x, int y) {

		public SlotData(Slot s) {
			this(s, s.x, s.y);
		}

		public void setOffset(int x, int y) {
			slot.x = this.x + x;
			slot.y = this.y + y;
		}
	}

	@Override
	public boolean recipeMatches(RecipeHolder<CraftingRecipe> recipeHolder) {
		return false;
	}
}
