package com.obscura.storage.inventory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import com.google.common.collect.MapMaker;

import com.obscura.storage.StorageTags;
import com.obscura.storage.api.MultiblockInventoryAPI;
import com.obscura.storage.inventory.filter.IFilter;
import com.obscura.storage.inventory.filter.ItemFilter;
import com.obscura.storage.inventory.filter.ItemPredicate;
import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.migration.LegacyData;
import com.obscura.storage.util.BlockFaceReference;
import com.obscura.storage.util.Priority;

public class BlockFilter implements IFilter {
	private BlockPos pos;
	private Direction side;
	private Set<BlockPos> connected;
	public SimpleContainer filter = new SimpleContainer(1);
	private boolean skip, keepLast;
	private Priority priority;
	private ItemFilter itemFilter = ItemFilter.TRUE;
	private boolean filterNeedsUpdate = true;
	private boolean multiblockFilled;
	/**
	 * One wrapper per input access, by identity. A filter usually covers several inventories (a
	 * filtered connector, a multiblock), and a single-entry cache rebuilt the wrapper, and with it
	 * the tracker's whole state, on every scan as soon as it was used for two of them. Both keys and
	 * values are weak: a wrapper strongly references its input, so a strong value would keep the
	 * weak key alive forever. The wrapper stays reachable through the connector using it.
	 */
	private final Map<IInventoryAccess, IInventoryAccess> wrapCache = new MapMaker().weakKeys().weakValues().makeMap();
	/** Wrappers made by {@link #wrapWithoutPriority}, same rules as {@link #wrapCache}. */
	private final Map<IInventoryAccess, IInventoryAccess> wrapCacheNoPriority = new MapMaker().weakKeys().weakValues().makeMap();
	/** This filter without its priority, what {@link #wrapWithoutPriority} wraps with. */
	private final IFilter noPriorityView = new IFilter() {
		@Override
		public ItemPredicate getItemPred() {
			return BlockFilter.this.getItemPred();
		}

		@Override
		public boolean isKeepLast() {
			return BlockFilter.this.isKeepLast();
		}

		@Override
		public Priority getPriority() {
			return Priority.NORMAL;
		}
	};
	private boolean wrapDirty = true;
	/**
	 * Bumped by any change to any BlockFilter (created, loaded, edited, removed). Connectors check
	 * it to rescan right away after a configuration change instead of waiting for their heartbeat.
	 */
	private static volatile int configEpoch;

	public BlockFilter(BlockPos pos) {
		this.pos = pos;
		side = Direction.DOWN;
		priority = Priority.NORMAL;
		connected = new HashSet<>();
		connected.add(pos);
		filter.addListener(__ -> markFilterDirty());
		bumpConfigEpoch();
	}

	public static int getConfigEpoch() {
		return configEpoch;
	}

	static void bumpConfigEpoch() {
		configEpoch++;
	}

	public Set<BlockPos> getConnectedBlocks() {
		return connected;
	}

	public BlockPos getMainPos() {
		return pos;
	}

	public Direction getSide() {
		return side;
	}

	public CompoundTag serializeNBT(HolderLookup.Provider provider) {
		CompoundTag tag = new CompoundTag();
		ListTag conn = new ListTag();
		connected.forEach(e -> {
			CompoundTag t = new CompoundTag();
			t.putInt("x", e.getX() - pos.getX());
			t.putInt("y", e.getY() - pos.getY());
			t.putInt("z", e.getZ() - pos.getZ());
			conn.add(t);
		});
		tag.put("connected", conn);
		tag.putBoolean("skip", skip);
		tag.putString("side", side.getSerializedName());
		if (!filter.getItem(0).isEmpty())
			tag.put("filter", filter.getItem(0).save(provider));
		tag.putInt("priority", priority.ordinal());
		tag.putBoolean("keepLast", keepLast);
		return tag;
	}

	public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
		ListTag conn = tag.getList("connected", Tag.TAG_COMPOUND);
		for (int i = 0;i<conn.size();i++) {
			CompoundTag t = conn.getCompound(i);
			int x = t.getInt("x") + pos.getX();
			int y = t.getInt("y") + pos.getY();
			int z = t.getInt("z") + pos.getZ();
			connected.add(new BlockPos(x, y, z));
		}
		skip = tag.getBoolean("skip");
		side = Direction.byName(tag.getString("side"));
		// LegacyData: filters converted from the 1.20.1 saved data hold a 1.20.1 item stack
		filter.setItem(0, LegacyData.parseItemStack(provider, tag.getCompound("filter")));
		priority = Priority.VALUES[Math.abs(tag.getInt("priority")) % Priority.VALUES.length];
		keepLast = tag.getBoolean("keepLast");
		multiblockFilled = true;
	}

	public void dropContents(LevelAccessor level, BlockPos pos2) {
		if (level instanceof Level lvl) {
			filter.removeAllItems().forEach(f -> Block.popResource(lvl, pos2, f));
		}
	}

	public IInventoryAccess wrap(Level level, IInventoryAccess acc) {
		return wrap(level, acc, true);
	}

	/**
	 * Like {@link #wrap(Level, IInventoryAccess)} but the result carries the item filter and keep-last
	 * of this filter and NOT its priority. A cable connector uses it for the filter on the block it
	 * points at when the connector has a filter of its own: the connector's priority wins then, and
	 * priorities would otherwise add up when both wrap the same inventory.
	 */
	public IInventoryAccess wrapWithoutPriority(Level level, IInventoryAccess acc) {
		return wrap(level, acc, false);
	}

	private IInventoryAccess wrap(Level level, IInventoryAccess acc, boolean withPriority) {
		ItemStack filter = this.filter.getItem(0);
		if (filterNeedsUpdate || !itemFilter.configMatch(filter)) {
			if (filter.getItem() instanceof IItemFilter f) {
				itemFilter = f.createFilter(new BlockFaceReference(level, pos, side), filter);
			} else
				itemFilter = ItemFilter.TRUE;
			filterNeedsUpdate = false;
			wrapDirty = true;
		}
		if (wrapDirty) {
			wrapCache.clear();
			wrapCacheNoPriority.clear();
			wrapDirty = false;
		}
		Map<IInventoryAccess, IInventoryAccess> cache = withPriority ? wrapCache : wrapCacheNoPriority;
		IInventoryAccess result = cache.get(acc);
		if (result != null)return result;
		IFilter as = withPriority ? this : noPriorityView;
		if (acc instanceof PlatformFilteredInventoryAccess f) {
			MultiFilter mf = new MultiFilter(f.getFilter(), as);
			result = new PlatformFilteredInventoryAccess(f.getActualInventory(), mf);
		} else {
			result = new PlatformFilteredInventoryAccess(acc, as);
		}
		cache.put(acc, result);
		return result;
	}

	public boolean skip() {
		return skip;
	}

	public static BlockFilter findBlockFilterAt(Level level, BlockPos pos) {
		return BlockPos.betweenClosedStream(new AABB(pos).inflate(8)).
				map(p -> getFilterAt(level, p)).
				filter(p -> p != null && p.getConnectedBlocks().contains(pos)).findFirst().
				orElseGet(() -> {
					if (level.getBlockState(pos).is(StorageTags.INV_CONFIG_SKIP))return null;
					return getOrCreateFilterAt(level, pos);
				});
	}

	public static BlockFilter getFilterAt(Level level, BlockPos pos) {
		return PlatformInventoryAccess.getBlockFilterAt(level, pos, false);
	}

	public static BlockFilter getOrCreateFilterAt(Level level, BlockPos pos) {
		var f = PlatformInventoryAccess.getBlockFilterAt(level, pos, true);
		if (f != null)f.fillMultiblock(level);
		return f;
	}

	@Override
	public Priority getPriority() {
		return priority;
	}

	public void setPriority(Priority priority) {
		this.priority = priority;
		bumpConfigEpoch();
	}

	@Override
	public ItemPredicate getItemPred() {
		return itemFilter;
	}

	public void setSkip(boolean skip) {
		this.skip = skip;
		bumpConfigEpoch();
	}

	public void setSide(Direction side) {
		this.side = side;
		bumpConfigEpoch();
	}

	public void addConnected(Level level, BlockPos pos) {
		if (!this.pos.equals(pos))
			PlatformInventoryAccess.removeBlockFilterAt(level, pos);
		connected.add(pos.immutable());
		bumpConfigEpoch();
	}

	@Override
	public boolean isKeepLast() {
		return keepLast;
	}

	public void setKeepLast(boolean keepLast) {
		this.keepLast = keepLast;
		bumpConfigEpoch();
	}

	public void markFilterDirty() {
		filterNeedsUpdate = true;
		wrapDirty = true;
		bumpConfigEpoch();
	}

	private void fillMultiblock(Level level) {
		if (multiblockFilled)return;
		MultiblockInventoryAPI.EVENT.invoker().detectMultiblocks(level, pos, level.getBlockState(pos), p -> addConnected(level, p));
		multiblockFilled = true;
	}

	@Override
	public String toString() {
		return "BlockFilter@" + pos;
	}
}
