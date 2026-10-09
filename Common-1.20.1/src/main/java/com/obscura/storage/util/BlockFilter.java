package com.obscura.storage.util;

import java.util.AbstractSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import com.obscura.storage.StorageTags;
import com.obscura.storage.item.IItemFilter;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

public class BlockFilter {
	private final BlockPos pos;
	private final Runnable dirty;
	private Direction side;
	private final ConnectedSet connected = new ConnectedSet();
	/** Position to filter index of the dimension this filter lives in, while it is registered in a BlockFilterData. */
	private Long2ObjectMap<BlockFilter> index;
	/** Set while loading: the changes are the saved state, not edits. */
	private boolean quiet;
	public final SimpleContainer filter;
	private boolean skip;
	private boolean keepLast;
	private Priority priority;
	private ItemPredicate itemFilter;
	private boolean filterNeedsUpdate = true;
	/** Last result of {@link #wrap} and its input (loader storage types). */
	private Object cachedWrapped;
	private Object cachedInput;
	private boolean wrapDirty = true;

	public BlockFilter(BlockPos pos, Runnable dirty) {
		this.pos = pos;
		this.dirty = dirty;
		this.side = Direction.DOWN;
		this.priority = Priority.NORMAL;
		this.quiet = true;
		this.connected.add(pos);
		this.quiet = false;
		this.filter = new SimpleContainer(1);
		this.filter.addListener(c -> markFilterDirty());
		this.itemFilter = s -> true;
	}

	/** The filter that covers {@code pos}, without creating one. Used by connector scans, which run every few seconds for every inventory. */
	public static BlockFilter findExistingAt(Level level, BlockPos pos) {
		return BlockFilterData.get(level).findContaining(level, pos);
	}

	/** The filter that covers {@code pos}; when there is none, a new one is created (Inventory Configurator only). */
	public static BlockFilter findBlockFilterAt(Level level, BlockPos pos) {
		BlockFilter existing = findExistingAt(level, pos);
		if (existing != null) {
			return existing;
		}
		if (level.getBlockState(pos).is(StorageTags.INV_CONFIG_SKIP)) {
			return null;
		}
		return getOrCreateFilterAt(level, pos);
	}

	public static BlockFilter getFilterAt(Level level, BlockPos pos) {
		return BlockFilterData.get(level).getFilterAt(level, pos, false);
	}

	public static BlockFilter getOrCreateFilterAt(Level level, BlockPos pos) {
		return BlockFilterData.get(level).getFilterAt(level, pos, true);
	}

	public static void removeFilterAt(Level level, BlockPos pos) {
		BlockFilterData.get(level).removeFilterAt(level, pos);
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

	public Priority getPriority() {
		return priority;
	}

	public void setPriority(Priority priority) {
		this.priority = priority;
		markDirty();
	}

	public boolean skip() {
		return skip;
	}

	public void setSkip(boolean skip) {
		this.skip = skip;
		markDirty();
	}

	public boolean isKeepLast() {
		return keepLast;
	}

	public void setKeepLast(boolean keepLast) {
		this.keepLast = keepLast;
		this.wrapDirty = true;
		markDirty();
	}

	public void setSide(Direction side) {
		this.side = side;
		this.filterNeedsUpdate = true;
		this.wrapDirty = true;
		markDirty();
	}

	public void addConnected(Level level, BlockPos pos) {
		BlockPos p = pos.immutable();
		if (!this.pos.equals(p)) {
			removeFilterAt(level, p);
			// a block belongs to one filter only
			BlockFilter other = findExistingAt(level, p);
			if (other != null && other != this) {
				other.connected.remove(p);
			}
		}
		connected.add(p);
	}

	public void dropContents(LevelAccessor level, BlockPos pos2) {
		if (level instanceof Level lvl) {
			filter.removeAllItems().forEach(f -> Block.popResource(lvl, pos2, f));
		}
	}

	/**
	 * Builds the loader's filtered view of an inventory (Fabric: a {@code FilteredStorage} over a
	 * {@code Storage}, Forge: over an {@code IItemHandler}).
	 */
	@FunctionalInterface
	public interface Wrapper<S> {
		S wrap(S input, ItemPredicate filter, boolean keepLast);
	}

	/** The item test of the filter item in this filter, rebuilt when the filter item or the side changed. */
	public ItemPredicate getItemFilter(Level level) {
		ItemStack cfg = this.filter.getItem(0);
		if (filterNeedsUpdate || !itemFilter.configMatch(cfg)) {
			if (!cfg.isEmpty() && cfg.getItem() instanceof IItemFilter f) {
				itemFilter = f.createFilter(BlockFace.touching(level, pos, side), cfg);
			} else if (!cfg.isEmpty()) {
				itemFilter = ItemPredicate.sameItemSameTags(cfg.copy());
			} else {
				itemFilter = s -> true;
			}
			filterNeedsUpdate = false;
			wrapDirty = true;
		}
		return itemFilter;
	}

	/**
	 * A filtered view that is told its filter's priority (the loaders' {@code FilteredStorage}), so the
	 * Tidy button can tell how the network sorts the inventory without asking the filter again.
	 */
	public interface Prioritised {
		void setFilterPriority(Priority priority);
	}

	/**
	 * {@code input} behind this filter. The wrapper is cached and handed out again while neither the
	 * input nor the filter changed.
	 */
	@SuppressWarnings("unchecked")
	public <S> S wrap(Level level, S input, Wrapper<S> wrapper) {
		ItemPredicate pred = getItemFilter(level);

		if (!wrapDirty && cachedWrapped != null && cachedInput == input) {
			// The priority can change without the view being rebuilt
			if (cachedWrapped instanceof Prioritised p)p.setFilterPriority(priority);
			return (S) cachedWrapped;
		}

		S result = wrapper.wrap(input, pred, keepLast);
		if (result instanceof Prioritised p)p.setFilterPriority(priority);
		cachedWrapped = result;
		cachedInput = input;
		wrapDirty = false;
		return result;
	}

	public void markFilterDirty() {
		filterNeedsUpdate = true;
		wrapDirty = true;
		markDirty();
	}

	public void markDirty() {
		if (quiet) {
			return;
		}
		dirty.run();
		NetworkChanges.bump();
	}

	public CompoundTag save() {
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
		if (!filter.getItem(0).isEmpty()) {
			tag.put("filter", filter.getItem(0).save(new CompoundTag()));
		}
		tag.putInt("priority", priority.ordinal());
		tag.putBoolean("keepLast", keepLast);
		return tag;
	}

	public void load(CompoundTag tag) {
		quiet = true;
		connected.clear();
		connected.add(pos);
		ListTag conn = tag.getList("connected", Tag.TAG_COMPOUND);
		for (int i = 0; i < conn.size(); i++) {
			CompoundTag t = conn.getCompound(i);
			int x = t.getInt("x") + pos.getX();
			int y = t.getInt("y") + pos.getY();
			int z = t.getInt("z") + pos.getZ();
			connected.add(new BlockPos(x, y, z));
		}
		skip = tag.getBoolean("skip");
		Direction s = Direction.byName(tag.getString("side"));
		side = s != null ? s : Direction.DOWN;
		filter.setItem(0, ItemStack.of(tag.getCompound("filter")));
		priority = Priority.VALUES[Math.abs(tag.getInt("priority")) % Priority.VALUES.length];
		keepLast = tag.getBoolean("keepLast");
		filterNeedsUpdate = true;
		wrapDirty = true;
		quiet = false;
	}

	public boolean contains(BlockPos pos) {
		return connected.contains(pos);
	}

	/** Nothing changed since it was created: covers only its own block with the default settings and holds no item. */
	boolean isDefault() {
		return connected.size() == 1 && !skip && !keepLast && priority == Priority.NORMAL && side == Direction.DOWN && filter.getItem(0).isEmpty();
	}

	/** Registers every covered position in {@code index} and keeps it up to date from then on. */
	void attach(Long2ObjectMap<BlockFilter> index) {
		detach();
		this.index = index;
		for (BlockPos p : connected) {
			index.put(p.asLong(), this);
		}
	}

	void detach() {
		if (index != null) {
			for (BlockPos p : connected) {
				unindex(p);
			}
			index = null;
		}
	}

	private void unindex(BlockPos p) {
		if (index != null && index.get(p.asLong()) == this) {
			index.remove(p.asLong());
		}
	}

	/** The covered positions. Every change goes through here so the position index and the save flag stay right. */
	private class ConnectedSet extends AbstractSet<BlockPos> {
		private final Set<BlockPos> set = new HashSet<>();

		@Override
		public boolean add(BlockPos p) {
			if (set.add(p)) {
				if (index != null) {
					index.put(p.asLong(), BlockFilter.this);
				}
				markDirty();
				return true;
			}
			return false;
		}

		@Override
		public boolean remove(Object o) {
			if (set.remove(o)) {
				unindex((BlockPos) o);
				markDirty();
				return true;
			}
			return false;
		}

		@Override
		public void clear() {
			if (!set.isEmpty()) {
				for (BlockPos p : set) {
					unindex(p);
				}
				set.clear();
				markDirty();
			}
		}

		@Override
		public boolean contains(Object o) {
			return set.contains(o);
		}

		@Override
		public int size() {
			return set.size();
		}

		@Override
		public Iterator<BlockPos> iterator() {
			Iterator<BlockPos> it = set.iterator();
			return new Iterator<>() {
				private BlockPos last;

				@Override
				public boolean hasNext() {
					return it.hasNext();
				}

				@Override
				public BlockPos next() {
					last = it.next();
					return last;
				}

				@Override
				public void remove() {
					it.remove();
					unindex(last);
					markDirty();
				}
			};
		}
	}

	@Override
	public String toString() {
		return "BlockFilter@" + pos;
	}

	public static boolean removeAt(Level level, BlockPos pos) {
		return BlockFilterData.get(level).removeFilterAt(level, pos);
	}

	public static Iterable<BlockPos> bounds(BlockPos from, BlockPos to) {
		AABB bb = new AABB(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
		return () -> BlockPos.betweenClosedStream(bb).map(BlockPos::immutable).iterator();
	}
}