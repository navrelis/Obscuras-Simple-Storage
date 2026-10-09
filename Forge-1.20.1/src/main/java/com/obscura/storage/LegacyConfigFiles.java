package com.obscura.storage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.migration.LegacyIds;

/**
 * Moves the TOML config files that versions before v5.0.0 wrote under the old mod id
 * ({@code toms_storage-common.toml}, {@code toms_storage-server.toml}) to the new names
 * ({@code obscuras_storage-common.toml}, {@code obscuras_storage-server.toml}). The TOML keys never
 * changed, so the old content is used as is; {@link Config#migrate} then applies the usual
 * {@code configVersion} steps. See {@link LegacyFiles#migrate} for the rules (once, never overwrites,
 * old file kept as {@code *.migrated}).
 *
 * <p>The mod registers no CLIENT config, so there is no client file to move.
 */
public final class LegacyConfigFiles {
	/** The config types this mod registers. */
	private static final List<ModConfig.Type> TYPES = List.of(ModConfig.Type.COMMON, ModConfig.Type.SERVER);
	private static final String SERVERCONFIG = "serverconfig";
	/** Appended to a server config file Forge generated from the defaults before the old file was moved in. */
	public static final String GENERATED_SUFFIX = ".generated";

	private LegacyConfigFiles() {}

	public static String oldFileName(ModConfig.Type type) {
		return LegacyIds.OLD_NAMESPACE + "-" + type.extension() + ".toml";
	}

	/** Same name Forge picks for {@code registerConfig(type, spec)}. */
	public static String fileName(ModConfig.Type type) {
		return StorageMod.modid + "-" + type.extension() + ".toml";
	}

	/** Moves the old files of every config type found in {@code dir}. */
	public static boolean migrateDir(Path dir) {
		boolean any = false;
		for (ModConfig.Type type : TYPES) {
			any |= LegacyFiles.migrate(dir.resolve(oldFileName(type)), dir.resolve(fileName(type)));
		}
		return any;
	}

	/**
	 * Called from the mod constructor, before the configs are registered (and so before Forge reads
	 * them): {@code config/}, {@code defaultconfigs/} and the per-world server config overrides that can
	 * be found without a running server ({@code saves/<world>/serverconfig/} on a client, the
	 * {@code level-name} world of server.properties on a dedicated server).
	 */
	public static void migrateAtStartup() {
		try {
			migrateDir(FMLPaths.CONFIGDIR.get());
			Path gameDir = FMLPaths.GAMEDIR.get();
			migrateDir(gameDir.resolve(FMLConfig.defaultConfigPath()));
			if (FMLEnvironment.dist.isClient()) {
				Path saves = gameDir.resolve("saves");
				if (Files.isDirectory(saves)) {
					try (DirectoryStream<Path> worlds = Files.newDirectoryStream(saves, Files::isDirectory)) {
						for (Path world : worlds)migrateDir(world.resolve(SERVERCONFIG));
					}
				}
			} else {
				migrateDir(gameDir.resolve(dedicatedServerLevelName(gameDir)).resolve(SERVERCONFIG));
			}
		} catch (IOException | RuntimeException e) {
			StorageMod.LOGGER.warn("Could not look for config files of an older version", e);
		}
	}

	private static String dedicatedServerLevelName(Path gameDir) {
		Path props = gameDir.resolve("server.properties");
		if (Files.isRegularFile(props)) {
			Properties p = new Properties();
			try (Reader r = Files.newBufferedReader(props, StandardCharsets.UTF_8)) {
				p.load(r);
				String name = p.getProperty("level-name");
				if (name != null && !name.isBlank())return name;
			} catch (IOException | RuntimeException e) {
				StorageMod.LOGGER.debug("Could not read {}", props, e);
			}
		}
		return "world";
	}

	/**
	 * Safety net for a world whose server config override was not found at startup (a dedicated server
	 * started with --world or --universe, a world opened from another directory). Forge has already read
	 * the SERVER configs when this runs (ServerAboutToStartEvent) and, as the new file did not exist, has
	 * just written it from the defaults (or from defaultconfigs/). An old file that is still in place was
	 * never migrated (a migrated one is renamed to {@code *.migrated}), so that fresh file only holds
	 * defaults: it is kept as {@code obscuras_storage-server.toml.generated}, the old file is moved in and
	 * the SERVER configs are read again. Only happens on the first start of such a world.
	 */
	public static void migrateWorld(MinecraftServer server) {
		try {
			Path dir = server.getWorldPath(new LevelResource(SERVERCONFIG));
			if (migrateWorldDir(dir, () -> ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER, dir))) {
				ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.SERVER, dir);
				StorageMod.LOGGER.info("Reloaded the server configs after moving the world's server config file");
			}
		} catch (RuntimeException e) {
			StorageMod.LOGGER.warn("Could not migrate the server config file of the world", e);
		}
	}

	/**
	 * The file part of {@link #migrateWorld}: runs {@code unloadServerConfigs} when there is something to move
	 * (the caller loads them again).
	 *
	 * @return whether the old server config file of {@code dir} was moved in
	 */
	public static boolean migrateWorldDir(Path dir, Runnable unloadServerConfigs) {
		ModConfig.Type type = ModConfig.Type.SERVER;
		Path oldFile = dir.resolve(oldFileName(type));
		Path newFile = dir.resolve(fileName(type));
		if (!Files.isRegularFile(oldFile))return false;
		// Close the freshly generated file (Forge watches it) before it is moved
		unloadServerConfigs.run();
		if (Files.exists(newFile)) {
			Path generated = newFile.resolveSibling(newFile.getFileName() + GENERATED_SUFFIX);
			try {
				Files.move(newFile, generated, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				StorageMod.LOGGER.warn("Could not move the generated {} aside, the old server config stays unused", newFile, e);
				return true;// load the configs again, unchanged
			}
		}
		if (!LegacyFiles.migrate(oldFile, newFile)) {
			StorageMod.LOGGER.warn("Could not move {} to {}, the server config uses the defaults", oldFile, newFile);
		}
		return true;
	}
}
