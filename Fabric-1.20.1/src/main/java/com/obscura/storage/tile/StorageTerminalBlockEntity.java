package com.obscura.storage.tile;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.block.AbstractStorageTerminalBlock;
import com.obscura.storage.block.AbstractStorageTerminalBlock.TerminalPos;
import com.obscura.storage.doctor.INetworkMember;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.gui.StorageTerminalMenu;
import com.obscura.storage.item.BoundTerminal;
import com.obscura.storage.util.MergedStorage;
import com.obscura.storage.util.StorageSnapshot;
import com.obscura.storage.util.StoredItemStack;
import com.obscura.storage.util.TickerUtil.TickableServer;

public class StorageTerminalBlockEntity extends BlockEntity implements MenuProvider, TickableServer, INetworkMember {
	private Storage<ItemVariant> itemHandler;
	/** Game time of the last lookup of {@link #itemHandler}, so it is looked up at most once per tick. */
	private long handlerTick = Long.MIN_VALUE;
	/** Item totals of the network when the handler is not a shared inventory connector storage. */
	private final StorageSnapshot ownSnapshot = new StorageSnapshot();
	/** Ticks between two reads of the network while a menu is open. */
	private static final int REBUILD_INTERVAL = 4;
	private static final int MAX_SEARCH_LENGTH = 100;
	private long lastRebuild;
	private boolean forceRebuild;
	private int sort;
	private String lastSearch = "";
	private boolean updateItems;
	private boolean tracked;
	private int beaconLevel;

	public StorageTerminalBlockEntity(BlockPos pos, BlockState state) {
		super(Content.terminalTile.get(), pos, state);
	}

	public StorageTerminalBlockEntity(BlockEntityType<?> tileEntityTypeIn, BlockPos pos, BlockState state) {
		super(tileEntityTypeIn, pos, state);
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory plInv, Player arg2) {
		return new StorageTerminalMenu(id, plInv, this);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("menu.obscuras_storage.storage_terminal");
	}

	/** The storage this terminal reads and writes, looked up on demand (once per tick). Null when nothing is attached. */
	public Storage<ItemVariant> getItemHandler() {
		return handler();
	}

	/**
	 * The attached storage. Looked up when asked and remembered for the rest of the game tick, so it does not
	 * depend on a menu being open: Builder's Reach and Restock pull without one, and a swapped chest is seen.
	 */
	private Storage<ItemVariant> handler() {
		if(level == null || level.isClientSide)return itemHandler;
		long time = level.getGameTime();
		if(handlerTick != time) {
			handlerTick = time;
			itemHandler = lookupHandler();
		}
		return itemHandler;
	}

	private Storage<ItemVariant> lookupHandler() {
		BlockState st = level.getBlockState(worldPosition);
		if(!st.hasProperty(AbstractStorageTerminalBlock.FACING) || !st.hasProperty(AbstractStorageTerminalBlock.TERMINAL_POS))return null;
		Direction d = getAttachedDirection(st);
		Storage<ItemVariant> found = ItemStorage.SIDED.find(level, worldPosition.relative(d), d.getOpposite());
		if(found == null) {
			Container inv = HopperBlockEntity.getContainerAt(level, worldPosition.relative(d));
			if(inv != null)found = InventoryStorage.of(inv, d.getOpposite());
		}
		return found;
	}

	/** The block the terminal is attached to. */
	public BlockPos getAttachedPos() {
		return worldPosition.relative(getAttachedDirection(getBlockState()));
	}

	private static Direction getAttachedDirection(BlockState st) {
		Direction d = st.getValue(AbstractStorageTerminalBlock.FACING);
		TerminalPos p = st.getValue(AbstractStorageTerminalBlock.TERMINAL_POS);
		if(p == TerminalPos.UP)d = Direction.UP;
		if(p == TerminalPos.DOWN)d = Direction.DOWN;
		return d;
	}

	/**
	 * The item totals every open menu of this terminal syncs from. Called by each menu on every
	 * broadcast; the network is only re-read while at least one menu asks, and once for all of them.
	 */
	public StorageSnapshot getSnapshot() {
		updateItems = true;
		return currentSnapshot();
	}

	private StorageSnapshot currentSnapshot() {
		// A whole inventory connector network is shared with every other reader of it (other terminals, level emitters)
		return itemHandler instanceof MergedStorage m ? m.getSnapshot() : ownSnapshot;
	}

	public StoredItemStack pullStack(StoredItemStack stack, long max) {
		ItemStack st = stack.getStack();
		Storage<ItemVariant> handler = handler();
		if(handler == null)return null;

		try (Transaction transaction = Transaction.openOuter()) {
			long ext = handler.extract(ItemVariant.of(stack.getStack()), max, transaction);
			if(ext > 0) {
				transaction.commit();
				forceRebuild = true;
				return new StoredItemStack(st, ext);
			}
		}
		return null;
	}

	public StoredItemStack pullStackFuzzy(StoredItemStack stack, long max) {
		return pullStack(stack, max);
	}

	public StoredItemStack pushStack(StoredItemStack stack) {
		Storage<ItemVariant> handler = stack != null ? handler() : null;
		if(handler != null) {
			try (Transaction transaction = Transaction.openOuter()) {
				long ins = handler.insert(ItemVariant.of(stack.getStack()), stack.getQuantity(), transaction);
				if(ins == 0)return stack;
				transaction.commit();
				forceRebuild = true;
				if(ins == stack.getQuantity())return null;
				return new StoredItemStack(stack.getStack(), stack.getQuantity() - ins);
			}
		}
		return stack;
	}

	public ItemStack pushStack(ItemStack itemstack) {
		StoredItemStack is = pushStack(new StoredItemStack(itemstack));
		return is == null ? ItemStack.EMPTY : is.getActualStack();
	}

	public void pushOrDrop(ItemStack st) {
		if(st.isEmpty())return;
		StoredItemStack st0 = pushStack(new StoredItemStack(st));
		if(st0 != null) {
			dropItem(st0.getActualStack());
		}
	}

	public void dropItem(ItemStack stack) {
		Containers.dropItemStack(level, worldPosition.getX() + .5f, worldPosition.getY() + .5f, worldPosition.getZ() + .5f, stack);
	}

	@Override
	public void updateServer() {
		long time = level.getGameTime();
		if(!tracked) {
			tracked = true;
			NetworkDoctor.track(this);
		}
		if(updateItems) {
			// The contents are read only every few ticks, or right after this terminal moved items itself.
			// The read is shared with every viewer.
			Storage<ItemVariant> current = handler();
			boolean force = forceRebuild;
			if(force || time < lastRebuild || time - lastRebuild >= REBUILD_INTERVAL) {
				forceRebuild = false;
				lastRebuild = time;
				// Network Doctor: the read is booked to the network the terminal is attached to
				NetworkStats stats = NetworkDoctor.statsOf(current);
				long t0 = NetworkDoctor.begin(stats);
				try {
					currentSnapshot().refresh(current, time, force ? 1 : REBUILD_INTERVAL);
				} finally {
					NetworkDoctor.end(stats, time, t0);
				}
			}
			updateItems = false;
		}
		if(time % 40 == 5) {
			beaconLevel = scanBeaconLevel();
		}
	}

	/** Highest beacon level in range 8 of this terminal, only looked for when beacon range is enabled at all. */
	private int scanBeaconLevel() {
		if(Config.get().wirelessTermBeaconLvl == -1)return 0;
		AABB box = new AABB(worldPosition).inflate(8);
		int minX = Mth.floor(box.minX), maxX = Mth.floor(box.maxX);
		int minY = Mth.floor(box.minY), maxY = Mth.floor(box.maxY);
		int minZ = Mth.floor(box.minZ), maxZ = Mth.floor(box.maxZ);
		int best = 0;
		for (int cx = minX >> 4;cx <= maxX >> 4;cx++) {
			for (int cz = minZ >> 4;cz <= maxZ >> 4;cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if(chunk == null)continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if(!(be instanceof BeaconBlockEntity))continue;
					BlockPos p = be.getBlockPos();
					if(p.getX() < minX || p.getX() > maxX || p.getY() < minY || p.getY() > maxY || p.getZ() < minZ || p.getZ() > maxZ)continue;
					if(be.getBlockState().is(Blocks.BEACON)) {
						best = Math.max(best, InventoryCableConnectorBlockEntity.calcBeaconLevel(level, p.getX(), p.getY(), p.getZ()));
					}
				}
			}
		}
		return best;
	}

	public boolean canInteractWith(Player player, boolean menuCheck) {
		if(level.getBlockEntity(worldPosition) != this)return false;
		int d = 6;
		// Only wireless terminals bound to this terminal count, one bound elsewhere must not shadow them
		int termReach = BoundTerminal.reachTo(player, level, worldPosition);
		if(Config.get().wirelessTermBeaconLvl != -1 && beaconLevel >= Config.get().wirelessTermBeaconLvl && termReach > 0) {
			if(Config.get().wirelessTermBeaconLvlCrossDim != -1 && beaconLevel >= Config.get().wirelessTermBeaconLvlCrossDim)return true;
			else return player.level() == level;
		}
		d = Math.max(d, termReach);
		if (menuCheck)d += (d / 4);
		return player.level() == level && !(player.distanceToSqr(this.worldPosition.getX() + 0.5D, this.worldPosition.getY() + 0.5D, this.worldPosition.getZ() + 0.5D) > d*d);
	}

	public int getSorting() {
		return sort;
	}

	public void setSorting(int newC) {
		sort = newC;
		setChanged();
	}

	@Override
	public void saveAdditional(CompoundTag compound) {
		compound.putInt("sort", sort);
	}

	@Override
	public void load(CompoundTag compound) {
		sort = compound.getInt("sort");
		super.load(compound);
	}

	public String getLastSearch() {
		return lastSearch;
	}

	public void setLastSearch(String string) {
		// The client field is limited to 100 characters, a modified client could send anything
		lastSearch = string.length() > MAX_SEARCH_LENGTH ? string.substring(0, MAX_SEARCH_LENGTH) : string;
	}

	public int getBeaconLevel() {
		return beaconLevel;
	}

	/** Network Doctor: the network this terminal read its items from last, null when it is not attached to one. */
	public NetworkStats getDoctorStats() {
		return NetworkDoctor.statsOf(itemHandler);
	}

	@Override
	public boolean isOnNetwork(NetworkStats network) {
		return network != null && level != null && !level.isClientSide && NetworkDoctor.statsOf(handler()) == network;
	}

	/** Slots of the attached storage, capped to what a menu data slot can carry. */
	public int getSlotCount() {
		return Mth.clamp(currentSnapshot().getSlotCount(), 0, Short.MAX_VALUE);
	}

	/** Empty slots of the attached storage, capped to what a menu data slot can carry. */
	public int getFreeCount() {
		return Mth.clamp(currentSnapshot().getFreeSlotCount(), 0, Short.MAX_VALUE);
	}
}
