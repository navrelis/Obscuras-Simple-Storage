package com.obscura.storage.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import com.mojang.datafixers.util.Pair;

import com.google.common.collect.Lists;

import com.obscura.storage.Content;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.platform.PlatformRecipeMenu;
import com.obscura.storage.tidy.TidyManager;
import com.obscura.storage.tidy.TidyStatus;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.DataSlots;
import com.obscura.storage.util.IDataReceiver;
import com.obscura.storage.util.IntDataSlots;
import com.obscura.storage.util.ItemLocator;
import com.obscura.storage.util.StoredItemStack;
import com.obscura.storage.util.TerminalSyncManager;
import com.obscura.storage.util.TerminalSyncManager.InteractHandler;

public class StorageTerminalMenu extends PlatformRecipeMenu implements IDataReceiver, InteractHandler {
	protected StorageTerminalBlockEntity te;
	protected int playerSlotsStart;
	protected List<SlotStorage> storageSlotList = new ArrayList<>();
	public List<StoredItemStack> itemList = Lists.<StoredItemStack>newArrayList();
	public List<StoredItemStack> itemListClient = Lists.<StoredItemStack>newArrayList();
	public List<StoredItemStack> itemListClientSorted = Lists.<StoredItemStack>newArrayList();
	public List<SlotData> slotData = new ArrayList<>();
	public TerminalSyncManager sync = new TerminalSyncManager();
	private int lines;
	protected Inventory pinv;
	public Runnable onPacket;
	public int terminalData, beaconLvl, slotCount, freeCount;
	/** Tidy button state as synced to the client, see {@link TidyStatus}. */
	public int tidyStatus, tidyDone, tidyPlanned, tidyMovesPerTick;
	/** Client: counts the item list packets received, so the Tidy tooltip knows when its estimate is outdated. */
	public int clientSyncCount;
	/** Server: the values the Tidy data slots send, see {@link TidyManager#fillStatus}. */
	private final int[] tidyView = new int[4];
	public String search;
	public boolean noSort;
	/** Client: the last update only changed amounts, no item type was added or removed (no re-sort needed unless sorted by amount). */
	public boolean countOnlyUpdate;
	/** The player's offhand, shown next to the terminal like in 1.21.1. Null on menus that have no player slots. */
	public Slot offhand;
	/** Main inventory (27) plus hotbar (9), added by {@link #addPlayerSlots} right before the offhand slot. */
	private static final int PLAYER_INV_SLOTS = 36;

	public StorageTerminalMenu(int id, Inventory inv, StorageTerminalBlockEntity te) {
		this(Content.storageTerminal.get(), id, inv, te);
		this.addPlayerSlots(inv, 8, 120);
	}

	public StorageTerminalMenu(MenuType<?> type, int id, Inventory inv, StorageTerminalBlockEntity te) {
		super(type, id);
		this.te = te;
		this.pinv = inv;
		addStorageSlots();
		addDataSlot(DataSlots.create(v -> {
			terminalData = v;
			countOnlyUpdate = false;
			if(onPacket != null)
				onPacket.run();
		}, () -> te != null ? te.getSorting() : 0));
		addDataSlot(DataSlots.create(v -> beaconLvl = v, () -> te != null ? te.getBeaconLevel() : -1));
		addDataSlot(DataSlots.create(v -> slotCount = v, () -> te != null ? te.getSlotCount() : 0));
		addDataSlot(DataSlots.create(v -> freeCount = v, () -> te != null ? te.getFreeCount() : 0));
		// Tidy button: state, moves done and planned, moves per tick. Filled once per tick on the
		// server (broadcastChanges); a data slot is only sent when its value changes. Each is a full int
		// (two 16 bit data slots): vanilla sends a data slot as a short.
		addDataSlots(IntDataSlots.create(v -> tidyStatus = v, () -> tidyView[0]));
		addDataSlots(IntDataSlots.create(v -> tidyDone = v, () -> tidyView[1]));
		addDataSlots(IntDataSlots.create(v -> tidyPlanned = v, () -> tidyView[2]));
		addDataSlots(IntDataSlots.create(v -> tidyMovesPerTick = v, () -> tidyView[3]));
	}

	public StorageTerminalMenu(MenuType<?> type, int id, Inventory inv) {
		this(type, id, inv, null);
	}

	protected void addStorageSlots() {
		addStorageSlots(5, 8, 18);
	}

	public StorageTerminalMenu(int id, Inventory inv) {
		this(Content.storageTerminal.get(), id, inv);
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

		// The offhand slot floats next to the terminal, the screen puts it on the side of the offhand.
		offhand = addSlot(new Slot(playerInventory, playerInventory.items.size() + playerInventory.armor.size(), 0, y + 58) {

			@Override
			public void set(ItemStack stack) {
				playerInventory.player.onEquipItem(EquipmentSlot.OFFHAND, getItem(), stack);
				super.set(stack);
			}

			@Override
			public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
				return Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);
			}
		});
	}

	/**
	 * End (exclusive) of the main inventory and hotbar slots. Items pulled from the terminal only go there:
	 * the offhand slot right after them stays free for the player to fill by hand.
	 */
	protected int playerInvEnd() {
		return Math.min(playerSlotsStart + 1 + PLAYER_INV_SLOTS, slots.size());
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

	public static enum SlotAction {
		PULL_OR_PUSH_STACK, PULL_ONE, SPACE_CLICK, SHIFT_PULL, GET_HALF, GET_QUARTER,//CRAFT
		/** Item Locator: highlight every inventory holding the clicked item. Appended last to keep the ordinals stable. */
		LOCATE;
		public static final SlotAction[] VALUES = values();
	}

	@Override
	public void broadcastChanges() {
		if(te == null)return;
		// Network Doctor: the sync is booked to the network the terminal reads, with its packets
		NetworkStats stats = te.getDoctorStats();
		long time = te.getLevel() != null ? te.getLevel().getGameTime() : 0;
		long t0 = NetworkDoctor.begin(stats);
		try {
			sync.update(te.getSnapshot(), (ServerPlayer) pinv.player, !te.getLastSearch().equals(search) ? tag -> {
				search = te.getLastSearch();
				tag.putString("s", search);
			} : null);
		} finally {
			NetworkDoctor.end(stats, time, t0);
		}
		int packets = sync.takeSentPackets();
		long bytes = sync.takeSentBytes();
		if(stats != null && packets > 0)stats.addPackets(time, packets, bytes);
		if(pinv.player instanceof ServerPlayer sp)TidyManager.fillStatus(te, sp, tidyView);
		super.broadcastChanges();
	}

	public final void receiveClientNBTPacket(CompoundTag message) {
		boolean listPacket = sync.receiveUpdate(message);
		if(listPacket)clientSyncCount++;
		if(listPacket) {
			if(sync.isStructuralChange()) {
				itemList = sync.getAsList();
				if(noSort) {
					itemListClient.forEach(s -> s.setCount(sync.getAmount(s)));
				} else {
					itemListClient = new ArrayList<>(itemList);
				}
			} else if(noSort) {
				itemListClient.forEach(s -> s.setCount(sync.getAmount(s)));
			}
			// Amounts are updated in place in the objects the lists share, nothing to rebuild for them
			countOnlyUpdate = !sync.isStructuralChange() && onPacket != null;
			pinv.setChanged();
		} else {
			countOnlyUpdate = false;
		}
		if(message.contains("s")) {
			search = message.getString("s");
			countOnlyUpdate = false;
		}
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
	public boolean recipeMatches(Recipe<? super CraftingContainer> recipeIn) {
		return false;
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
			te.setLastSearch(message.getString("s"));
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
			te.setSorting(d.getInt("d"));
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
						this.moveItemStackTo(itemstack, playerSlotsStart + 1, playerInvEnd(), true);
						if (itemstack.getCount() > 0)
							te.pushOrDrop(itemstack);
						player.getInventory().setChanged();
					}
				} else {
					if (!stack.isEmpty()) {
						if (ItemStack.isSameItemSameTags(stack, clicked.getStack()) && stack.getCount() + 1 <= stack.getMaxStackSize()) {
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
					this.moveItemStackTo(itemstack, playerSlotsStart + 1, playerInvEnd(), true);
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

	public StorageTerminalBlockEntity getTerminal() {
		return te;
	}
}
