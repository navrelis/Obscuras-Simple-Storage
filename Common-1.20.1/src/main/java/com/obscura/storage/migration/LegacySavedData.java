package com.obscura.storage.migration;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.function.Function;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A saved data file of the overworld that versions before v5.0.0 wrote under the old mod id
 * ({@code <world>/data/toms_storage_<name>.dat}). The content never changed, so the file is moved to
 * the new name ({@code obscuras_storage_<name>.dat}) once, before the data is first read in a world.
 * See {@link LegacyFiles#migrate} for the rules (once, never overwrites, old file kept as
 * {@code *.migrated}).
 */
public final class LegacySavedData {
	private final String legacyId;
	private final String id;
	/** The data storage whose legacy file was already looked for (the running server's overworld). */
	private volatile WeakReference<DimensionDataStorage> checked = new WeakReference<>(null);

	/**
	 * @param legacyId the saved data name used before v5.0.0
	 * @param id the current saved data name
	 */
	public LegacySavedData(String legacyId, String id) {
		this.legacyId = legacyId;
		this.id = id;
	}

	public String legacyId() {
		return legacyId;
	}

	public String id() {
		return id;
	}

	/**
	 * Reads the saved data from the overworld's {@code storage}, looking for the old file first, once per
	 * storage (world). Cheap after the first call. Data read from a moved file is marked dirty, so the
	 * next save writes it in the current format (ids of item stacks inside included).
	 */
	public <T extends SavedData> T read(MinecraftServer server, DimensionDataStorage storage, Function<DimensionDataStorage, T> loader) {
		boolean moved = false;
		if (checked.get() != storage) {
			synchronized (this) {
				if (checked.get() != storage) {
					// The overworld keeps its saved data in <world>/data
					Path root = server.getWorldPath(LevelResource.ROOT);
					if (root != null)moved = migrate(root.resolve("data"));
					checked = new WeakReference<>(storage);
				}
			}
		}
		T data = loader.apply(storage);
		if (moved)data.setDirty();
		return data;
	}

	/**
	 * @param dataDir the overworld's saved data directory ({@code <world>/data})
	 * @return whether an old file was moved
	 */
	public boolean migrate(Path dataDir) {
		return LegacyFiles.migrate(dataDir.resolve(legacyId + ".dat"), dataDir.resolve(id + ".dat"));
	}
}
