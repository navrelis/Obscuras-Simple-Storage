package com.obscura.storage.block.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.NetworkInventory;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.item.BoundTerminal;
import com.obscura.storage.menu.StorageTerminalMenu;
import com.obscura.storage.platform.PlatformBlockEntity;
import com.obscura.storage.util.BeaconLevelCalc;
import com.obscura.storage.util.TickerUtil.TickableServer;
import com.obscura.storage.util.Util;

public class StorageTerminalBlockEntity extends PlatformBlockEntity implements MenuProvider, TickableServer, INetworkMember {
	private NetworkInventory itemCache = new NetworkInventory();
	private Map<StoredItemStack, StoredItemStack> items = new HashMap<>();
	private int sort;
	private int searchType;
	private int modes;
	private String lastSearch = "";
	private boolean updateItems;
	private int changeCount;
	private int beaconLevel;
	private long changeTracker = Long.MIN_VALUE;
	private boolean itemsDirty;
	private int slotCount, freeCount;
	/** Network Doctor: the network the item list was last read from (null when not a network). */
	private NetworkStats doctorStats;

	public StorageTerminalBlockEntity(BlockPos pos, BlockState state) {
		super(Content.terminalBE.get(), pos, state);
	}

	public StorageTerminalBlockEntity(BlockEntityType<?> tileEntityTypeIn, BlockPos pos, BlockState state) {
		super(tileEntityTypeIn, pos, state);
	}

	@Override
	public void onLoad() {
		super.onLoad();
		if (!level.isClientSide) {
			BlockState st = level.getBlockState(worldPosition);
			Direction d = st.getValue(AbstractStorageTerminalBlock.FACING);
			TerminalPos p = st.getValue(AbstractStorageTerminalBlock.TERMINAL_POS);
			if(p == TerminalPos.UP)d = Direction.UP;
			if(p == TerminalPos.DOWN)d = Direction.DOWN;
			itemCache.onLoad(level, worldPosition.relative(d), d.getOpposite(), this);
			NetworkDoctor.track(this);
		}
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory plInv, Player arg2) {
		return new StorageTerminalMenu(id, plInv, this);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("menu.obscuras_storage.storage_terminal");
	}

	public Map<StoredItemStack, StoredItemStack> getStacks() {
		updateItems = true;
		return items;
	}

	public boolean isItemsDirty() {
		return itemsDirty;
	}

	public void clearItemsDirty() {
		itemsDirty = false;
	}

	public StoredItemStack pullStack(StoredItemStack stack, long max) {
		if (stack == null)return null;
		IInventoryAccess ii = itemCache.getAccess(level, worldPosition);
		ItemStack ex = ii.pullMatchingStack(stack.getStack(), max);
		if (ex.isEmpty())return null;
		return new StoredItemStack(ex);
	}

	public StoredItemStack pushStack(StoredItemStack stack) {
		if (stack == null)return null;
		IInventoryAccess ii = itemCache.getAccess(level, worldPosition);
		ItemStack r = ii.pushStack(stack.getActualStack());
		if (r.isEmpty())return null;
		return new StoredItemStack(r);
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
		if(updateItems) {
			IInventoryAccess ii = itemCache.getAccess(level, worldPosition);
			NetworkStats stats = doctorStats = NetworkDoctor.statsOf(ii);
			long t0 = NetworkDoctor.begin(stats);
			try {
				refreshItems(ii);
			} finally {
				NetworkDoctor.end(stats, level.getGameTime(), t0);
			}
			updateItems = false;
		}
		if(level.getGameTime() % 40 == (worldPosition.hashCode() & 0x7FFFFFFF) % 40) {
			beaconLevel = findBeaconLevel(level, worldPosition);
		}
	}

	/** Rebuilds the item list when the network changed, timed for the Network Doctor. */
	private void refreshItems(IInventoryAccess ii) {
		IInventoryChangeTracker tr = ii.tracker();
		long ct = tr.getChangeTracker(level);
		if (changeTracker != ct) {
			changeTracker = ct;

			// OPT-008: Reuse existing map to reduce GC pressure
			if (Config.get().runMultithreaded) {
				items = tr.streamWrappedStacks(true).collect(Collectors.groupingByConcurrent(Function.identity(), Util.reducingWithCopy(null, StoredItemStack::merge, StoredItemStack::new)));
			} else {
				items.clear();
				tr.streamWrappedStacks(false).forEach(s -> items.merge(s, s, StoredItemStack::merge));
				items.replaceAll((k, v) -> new StoredItemStack(v));
			}
			slotCount = Mth.clamp(ii.getSlotCount(), 0, Short.MAX_VALUE);
			freeCount = Mth.clamp(ii.getFreeSlotCount(), 0, Short.MAX_VALUE);
			changeCount++;
			itemsDirty = true;
		}
	}

	/** Network Doctor: the network this terminal's item list was last read from, null when none. */
	public NetworkStats getDoctorStats() {
		return doctorStats;
	}

	@Override
	public boolean isOnNetwork(NetworkStats network) {
		return network != null && NetworkDoctor.statsOf(itemCache.getAccess(level, worldPosition)) == network;
	}

	/**
	 * Highest beacon level within 8 blocks (a 17x17x17 cube) of {@code center}. A beacon always has
	 * a block entity, so instead of 4913 getBlockState calls this only walks the block entities of
	 * the (at most four) loaded chunks the cube overlaps, which is usually a few dozen entries.
	 */
	public static int findBeaconLevel(Level level, BlockPos center) {
		int minX = center.getX() - 8, maxX = center.getX() + 8;
		int minY = center.getY() - 8, maxY = center.getY() + 8;
		int minZ = center.getZ() - 8, maxZ = center.getZ() + 8;
		List<BlockPos> beacons = null;
		for (int cx = SectionPos.blockToSectionCoord(minX); cx <= SectionPos.blockToSectionCoord(maxX); cx++) {
			for (int cz = SectionPos.blockToSectionCoord(minZ); cz <= SectionPos.blockToSectionCoord(maxZ); cz++) {
				// Only chunks that are already loaded, like the per-block isLoaded check did
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null)continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					BlockPos p = be.getBlockPos();
					if (p.getX() < minX || p.getX() > maxX || p.getY() < minY || p.getY() > maxY || p.getZ() < minZ || p.getZ() > maxZ)continue;
					if (!level.getBlockState(p).is(Blocks.BEACON))continue;
					if (beacons == null)beacons = new ArrayList<>();
					beacons.add(p);
				}
			}
		}
		if (beacons == null)return 0;
		// Measured after the walk: calcBeaconLevel looks block entities up, which must not happen
		// while iterating a chunk's block entity map.
		int maxBeaconLevel = 0;
		for (BlockPos p : beacons) {
			int bl = BeaconLevelCalc.calcBeaconLevel(level, p.getX(), p.getY(), p.getZ());
			if (bl > maxBeaconLevel) {
				maxBeaconLevel = bl;
			}
		}
		return maxBeaconLevel;
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

	public int getModes() {
		return modes;
	}

	public int getSearchType() {
		return searchType;
	}

	public void setSorting(int newC) {
		sort = newC;
		setChanged();
	}

	public void setModes(int modes) {
		this.modes = modes;
		setChanged();
	}

	public void setSearchType(int searchType) {
		this.searchType = searchType;
		setChanged();
	}

	@Override
	public void saveAdditional(CompoundTag compound, HolderLookup.Provider provider) {
		super.saveAdditional(compound, provider);
		compound.putInt("sorting", sort);
		compound.putInt("modes", modes);
		compound.putInt("searchType", searchType);
	}

	@Override
	public void loadAdditional(CompoundTag compound, HolderLookup.Provider provider) {
		if (compound.contains("sort")) {
			// Convert old format
			int s = compound.getInt("sort");
			int controllMode = (s & 0b000_00_0_11);
			boolean rev = (s & 0b000_00_1_00) > 0;
			// 1.20.1 settings int: the third sort type bit sits at 12, the smart search bit (search type 8) at 11
			int type = ((s & 0b000_11_0_00) >> 3) | (((s >> 12) & 1) << 2);
			searchType = ((s & 0b111_00_0_00) >> 5) | (((s >> 11) & 1) << 3);
			boolean ghostItems = (s & 0b1_0_000_00_0_00) != 0;
			boolean tallMode  =  (s & 0b1_0_0_000_00_0_00) != 0;
			boolean pullFromInv = (s & (1 << 8)) != 0;//Crafting Terminal

			modes = controllMode | (tallMode ? 0x10 : 0) | (pullFromInv ? 0x20 : 0);
			sort = type | (rev ? 0x100 : 0) | (ghostItems ? 0x200 : 0);
		} else {
			sort = compound.getInt("sorting");
			modes = compound.getInt("modes");
			searchType = compound.getInt("searchType");
		}
		super.loadAdditional(compound, provider);
	}

	public String getLastSearch() {
		return lastSearch;
	}

	public void setLastSearch(String string) {
		lastSearch = string;
	}

	public int getBeaconLevel() {
		return beaconLevel;
	}

	public int getChangeCount() {
		return changeCount;
	}

	public int getFreeCount() {
		return freeCount;
	}

	public int getSlotCount() {
		return slotCount;
	}
}
