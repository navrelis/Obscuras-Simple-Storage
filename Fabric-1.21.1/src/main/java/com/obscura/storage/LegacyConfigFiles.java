package com.obscura.storage;

import java.nio.file.Path;

import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.migration.LegacyIds;

/**
 * Moves the Cloth config file that versions before v5.0.0 wrote under the old mod id
 * ({@code config/toms_storage.json}) to the new name ({@code config/obscuras_storage.json}) before
 * AutoConfig reads it. The JSON keys never changed, so the old content is used as is and
 * {@link Config#migrate} applies the usual {@code configVersion} steps. See {@link LegacyFiles#migrate}
 * for the rules (once, never overwrites, old file kept as {@code *.migrated}).
 */
public final class LegacyConfigFiles {
	private LegacyConfigFiles() {}

	public static String oldFileName() {
		return LegacyIds.OLD_NAMESPACE + ".json";
	}

	/** Same name AutoConfig derives from {@code @Config(name = StorageMod.modid)}. */
	public static String fileName() {
		return StorageMod.modid + ".json";
	}

	/** @return whether an old file was moved */
	public static boolean migrate(Path configDir) {
		return LegacyFiles.migrate(configDir.resolve(oldFileName()), configDir.resolve(fileName()));
	}
}
