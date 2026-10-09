package com.obscura.storage.migration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import com.obscura.storage.StorageMod;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Inventory Configurator settings of a world upgraded from Minecraft 1.20.1.
 *
 * <p>The 1.20.1 builds keep every block's filter in one saved data file,
 * {@code <world>/data/obscuras_storage_block_filters.dat} (before v5.0.0
 * {@code toms_storage_block_filters.dat}): a list of {@code {dim, x, y, z, data}} where {@code data} is the
 * BlockFilter NBT (connected blocks, side, skip, priority, keep last and the filter item as a 1.20.1 item
 * stack). This build keeps the same BlockFilter NBT on the block entity itself (Fabric: a Cardinal Components
 * block component, NeoForge: a data attachment) and never reads that file, so without this every filter,
 * and the filter item in it, would be gone.
 *
 * <p>The file is read once when the server starts ({@link #load}), before any chunk is loaded. Whenever a
 * chunk is read from disk ({@link #inject}, called by the loader's chunk load hook before the chunk's block
 * entities are created), the filters of that chunk are written into the NBT of their block entities in the
 * loader's format; the block entities then load them like their own. The filter item is upgraded when the
 * filter is read (BlockFilter#deserializeNBT, {@link LegacyData}).
 *
 * <p>Applied entries leave the file when the server stopped ({@link #save}), after all chunks were saved:
 * the remaining ones are written back, and once none is left the file is renamed to
 * {@code <name>.migrated}. After a crash the file still has every entry; a block entity that already has a
 * filter (saved with the injected one) keeps it, so nothing is applied twice. Entries whose position has no
 * block entity stay in the file and are logged.
 */
public final class LegacyBlockFilters {
	/** The 1.20.1 file (BlockFilterData.ID of the 1.20.1 builds). */
	public static final String FILE_NAME = StorageMod.modid + "_block_filters.dat";
	/** The same file as versions before v5.0.0 named it. */
	public static final String LEGACY_FILE_NAME = LegacyIds.OLD_NAMESPACE + "_block_filters.dat";

	private static final Logger LOGGER = LogManager.getLogger(StorageMod.modid);
	private static final String LIST_TAG = "filters";
	private static final String BLOCK_ENTITIES = "block_entities";

	/** How a loader keeps a block filter in the NBT of a block entity. */
	public interface Target {
		/** Whether {@code blockEntity} (a block entity's NBT) already has a block filter. */
		boolean hasFilter(CompoundTag blockEntity);

		/** Stores {@code filter} (BlockFilter NBT) in {@code blockEntity} the way the loader reads it. */
		void putFilter(CompoundTag blockEntity, CompoundTag filter);
	}

	private record Entry(BlockPos pos, CompoundTag raw) {}

	private static final class State {
		final Path file;
		final Map<ResourceKey<Level>, Long2ObjectMap<List<Entry>>> pending = new HashMap<>();
		int remaining;
		boolean changed;

		State(Path file) {
			this.file = file;
		}
	}

	/** Null while no 1.20.1 filter is waiting (the usual case: one volatile read per chunk load). */
	private static volatile State state;

	private LegacyBlockFilters() {}

	/**
	 * Reads the 1.20.1 file from {@code dataDir} ({@code <world>/data}). Call when a server starts, before its
	 * worlds load.
	 *
	 * @return the number of filters waiting to be applied
	 */
	public static int load(Path dataDir) {
		synchronized (LegacyBlockFilters.class) {
			state = null;
			Path file = dataDir.resolve(FILE_NAME);
			if (!Files.isRegularFile(file))file = dataDir.resolve(LEGACY_FILE_NAME);
			if (!Files.isRegularFile(file))return 0;
			CompoundTag root;
			try {
				root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			} catch (IOException | RuntimeException e) {
				LOGGER.error("Could not read the 1.20.1 block filters in {}, they are not applied", file, e);
				return 0;
			}
			State s = new State(file);
			ListTag list = root.getCompound("data").getList(LIST_TAG, Tag.TAG_COMPOUND);
			for (int i = 0; i < list.size(); i++) {
				CompoundTag t = list.getCompound(i);
				ResourceLocation dimId = ResourceLocation.tryParse(t.getString("dim"));
				if (dimId == null || !t.contains("data", Tag.TAG_COMPOUND)) {
					LOGGER.warn("Skipping a broken 1.20.1 block filter entry: {}", t);
					continue;
				}
				ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimId);
				BlockPos pos = new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"));
				s.pending.computeIfAbsent(dim, d -> new Long2ObjectOpenHashMap<>())
				.computeIfAbsent(ChunkPos.asLong(pos), c -> new ArrayList<>()).add(new Entry(pos, t));
				s.remaining++;
			}
			if (s.remaining == 0) {
				markMigrated(file);
				return 0;
			}
			LOGGER.info("Found {} Inventory Configurator filters of Minecraft 1.20.1 in {}, they are moved to their blocks as the chunks load", s.remaining, file);
			state = s;
			return s.remaining;
		}
	}

	/**
	 * Writes the filters waiting for the chunk at {@code pos} of {@code dim} into the block entity entries of
	 * {@code chunk} (chunk NBT, list {@code block_entities}). Changes the given tags in place.
	 */
	public static void inject(ResourceKey<Level> dim, ChunkPos pos, CompoundTag chunk, Target target) {
		if (state == null || chunk == null)return;
		synchronized (LegacyBlockFilters.class) {
			State s = state;
			if (s == null)return;
			Long2ObjectMap<List<Entry>> byChunk = s.pending.get(dim);
			List<Entry> entries = byChunk == null ? null : byChunk.get(pos.toLong());
			if (entries == null)return;
			ListTag blockEntities = chunk.getList(BLOCK_ENTITIES, Tag.TAG_COMPOUND);
			for (Iterator<Entry> it = entries.iterator(); it.hasNext();) {
				Entry e = it.next();
				CompoundTag be = find(blockEntities, e.pos());
				if (be == null) {
					LOGGER.warn("1.20.1 block filter at {} in {}: no block entity there, kept in {}", e.pos(), dim.location(), s.file.getFileName());
					continue;
				}
				CompoundTag data = e.raw().getCompound("data");
				if (target.hasFilter(be)) {
					// Saved with this very filter before (crash before the file was updated), or configured anew
					LOGGER.info("1.20.1 block filter at {} in {}: the block already has a filter, the old one is not applied: {}", e.pos(), dim.location(), data);
				} else {
					target.putFilter(be, data.copy());
				}
				it.remove();
				s.remaining--;
				s.changed = true;
			}
			if (entries.isEmpty())byChunk.remove(pos.toLong());
		}
	}

	/**
	 * Updates the file after the server stopped: the entries that were applied are removed, a file without
	 * entries is renamed to {@code <name>.migrated}. Forgets the state.
	 */
	public static void save() {
		synchronized (LegacyBlockFilters.class) {
			State s = state;
			state = null;
			if (s == null || !s.changed)return;
			if (s.remaining <= 0) {
				markMigrated(s.file);
				return;
			}
			ListTag list = new ListTag();
			for (Long2ObjectMap<List<Entry>> byChunk : s.pending.values())
				for (List<Entry> entries : byChunk.values())
					for (Entry e : entries)list.add(e.raw());
			CompoundTag data = new CompoundTag();
			data.put(LIST_TAG, list);
			CompoundTag root = new CompoundTag();
			root.put("data", data);
			root.putInt("DataVersion", LegacyData.DATA_VERSION_1_20_1);
			try {
				Path tmp = s.file.resolveSibling(s.file.getFileName() + ".tmp");
				NbtIo.writeCompressed(root, tmp);
				try {
					Files.move(tmp, s.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, s.file, StandardCopyOption.REPLACE_EXISTING);
				}
				LOGGER.info("{} Inventory Configurator filters of Minecraft 1.20.1 are still waiting for their block in {}", s.remaining, s.file);
			} catch (IOException | RuntimeException e) {
				// The old file stays as it was: the entries are applied again, blocks that have a filter keep theirs
				LOGGER.error("Could not update {}", s.file, e);
			}
		}
	}

	/** Forgets the state without touching the file. */
	public static void clear() {
		synchronized (LegacyBlockFilters.class) {
			state = null;
		}
	}

	/** Filters that are waiting for their chunk. */
	public static int pending() {
		State s = state;
		return s == null ? 0 : s.remaining;
	}

	private static CompoundTag find(ListTag blockEntities, BlockPos pos) {
		for (int i = 0; i < blockEntities.size(); i++) {
			CompoundTag t = blockEntities.getCompound(i);
			if (t.getInt("x") == pos.getX() && t.getInt("y") == pos.getY() && t.getInt("z") == pos.getZ())return t;
		}
		return null;
	}

	private static void markMigrated(Path file) {
		try {
			Files.move(file, file.resolveSibling(file.getFileName() + LegacyFiles.MIGRATED_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
			LOGGER.info("All Inventory Configurator filters of Minecraft 1.20.1 were moved to their blocks, {} renamed to {}", file.getFileName(), file.getFileName() + LegacyFiles.MIGRATED_SUFFIX);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not rename {}", file, e);
		}
	}
}
