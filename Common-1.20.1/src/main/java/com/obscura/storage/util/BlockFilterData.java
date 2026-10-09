package com.obscura.storage.util;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import com.obscura.storage.StorageMod;

import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.migration.LegacySavedData;
import com.obscura.storage.platform.SavedDataFactory;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public class BlockFilterData extends SavedData {
	/**
	 * Saved data name, stored as {@code <world>/data/obscuras_storage_block_filters.dat}. The Inventory
	 * Configurator settings of every block (also vanilla and other mods' blocks) live here, not in the
	 * block entities, on both loaders.
	 */
	public static final String ID = "obscuras_storage_block_filters";
	/** Name used before v5.0.0, see {@link #migrateLegacyFile}. */
	public static final String LEGACY_ID = LegacyIds.OLD_NAMESPACE + "_block_filters";
	private static final LegacySavedData LEGACY = new LegacySavedData(LEGACY_ID, ID);
	private static final String LIST_TAG = "filters";
	private static final SavedDataFactory<BlockFilterData> FACTORY = new SavedDataFactory<>(BlockFilterData::new, BlockFilterData::new, ID);

	private final Map<FilterKey, BlockFilter> filters = new HashMap<>();
	/** Every position a filter covers (not only its own block), per dimension, so a lookup does not scan the filters. */
	private final Map<ResourceKey<Level>, Long2ObjectMap<BlockFilter>> coverage = new HashMap<>();

	private BlockFilterData() {
	}

	private BlockFilterData(CompoundTag tag) {
		load(tag);
	}

	public static BlockFilterData get(Level level) {
		if (!(level instanceof ServerLevel serverLevel)) {
			throw new IllegalStateException("BlockFilterData can only be accessed on server levels");
		}
		DimensionDataStorage storage = serverLevel.getServer().overworld().getDataStorage();
		return LEGACY.read(serverLevel.getServer(), storage, BlockFilterData::load);
	}

	/** Reads (or creates) the block filters kept in {@code storage}. */
	public static BlockFilterData load(DimensionDataStorage storage) {
		return FACTORY.get(storage);
	}

	/**
	 * Block filters saved by a version before v5.0.0 live in {@code toms_storage_block_filters.dat}. The
	 * file content never changed (dimension, position and the filter settings; item stacks inside are
	 * read by id, which resolves the old ids), so it is moved to the new name once.
	 *
	 * @param dataDir the overworld's saved data directory ({@code <world>/data})
	 * @return whether an old file was moved
	 */
	public static boolean migrateLegacyFile(Path dataDir) {
		return LEGACY.migrate(dataDir);
	}

	public BlockFilter getFilterAt(Level level, BlockPos pos, boolean make) {
		FilterKey key = FilterKey.of(level, pos);
		BlockFilter filter = filters.get(key);
		if (filter == null && make) {
			filter = new BlockFilter(pos.immutable(), this::setDirty);
			filters.put(key, filter);
			filter.attach(index(level.dimension()));
			setDirty();
		}
		return filter;
	}

	/** The filter that covers {@code pos}, whichever block of its group is the main one. */
	public BlockFilter findContaining(Level level, BlockPos pos) {
		Long2ObjectMap<BlockFilter> idx = coverage.get(level.dimension());
		return idx == null ? null : idx.get(pos.asLong());
	}

	private Long2ObjectMap<BlockFilter> index(ResourceKey<Level> dim) {
		return coverage.computeIfAbsent(dim, d -> new Long2ObjectOpenHashMap<>());
	}

	/**
	 * The block at {@code pos} is gone (broken by a player or found missing by a scan): a filter whose main
	 * block it was is removed and its filter item dropped, a filter that only covered it just stops covering it.
	 */
	public void onBlockRemoved(Level level, BlockPos pos) {
		Long2ObjectMap<BlockFilter> idx = coverage.get(level.dimension());
		if (idx == null || idx.isEmpty()) {
			return;
		}
		BlockFilter filter = idx.get(pos.asLong());
		if (filter == null) {
			return;
		}
		if (filter.getMainPos().equals(pos)) {
			removeFilterAt(level, pos);
		} else {
			filter.getConnectedBlocks().remove(pos);
		}
	}

	public boolean removeFilterAt(Level level, BlockPos pos) {
		FilterKey key = FilterKey.of(level, pos);
		BlockFilter removed = filters.remove(key);
		if (removed != null) {
			removed.detach();
			removed.dropContents(level, pos);
			setDirty();
			return true;
		}
		return false;
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		ListTag list = new ListTag();
		filters.forEach((k, v) -> {
			CompoundTag t = new CompoundTag();
			t.putString("dim", k.dim.location().toString());
			t.putInt("x", k.pos.getX());
			t.putInt("y", k.pos.getY());
			t.putInt("z", k.pos.getZ());
			t.put("data", v.save());
			list.add(t);
		});
		tag.put(LIST_TAG, list);
		return tag;
	}

	private void load(CompoundTag tag) {
		filters.clear();
		coverage.clear();
		int pruned = 0;
		ListTag list = tag.getList(LIST_TAG, Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag t = list.getCompound(i);
			ResourceLocation dimId = new ResourceLocation(t.getString("dim"));
			ResourceKey<Level> dim = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimId);
			BlockPos pos = new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"));
			BlockFilter filter = new BlockFilter(pos, this::setDirty);
			if (t.contains("data", Tag.TAG_COMPOUND)) {
				filter.load(t.getCompound("data"));
			}
			// Older versions created a filter for every inventory a connector saw. Those hold nothing but defaults, drop them.
			if (filter.isDefault()) {
				pruned++;
				continue;
			}
			filters.put(new FilterKey(dim, pos), filter);
			filter.attach(index(dim));
		}
		if (pruned > 0) {
			StorageMod.LOGGER.info("Removed {} block filters that only held default settings", pruned);
			setDirty();
		}
	}

	private static class FilterKey {
		private final ResourceKey<Level> dim;
		private final BlockPos pos;

		private FilterKey(ResourceKey<Level> dim, BlockPos pos) {
			this.dim = dim;
			this.pos = pos;
		}

		private static FilterKey of(Level level, BlockPos pos) {
			return new FilterKey(level.dimension(), pos.immutable());
		}

		@Override
		public int hashCode() {
			return Objects.hash(dim, pos);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof FilterKey o)) return false;
			return Objects.equals(dim, o.dim) && Objects.equals(pos, o.pos);
		}
	}
}