package com.obscura.storage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

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

	private LegacyConfigFiles() {}

	public static String oldFileName(ModConfig.Type type) {
		return LegacyIds.OLD_NAMESPACE + "-" + type.extension() + ".toml";
	}

	/** Same name NeoForge picks for {@code registerConfig(type, spec)}. */
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
	 * Called from the mod constructor, before the configs are registered (and so before NeoForge reads
	 * them): {@code config/}, {@code defaultconfigs/} and the per-world server config overrides that can
	 * be found without a running server ({@code saves/<world>/serverconfig/} on a client, the
	 * {@code level-name} world of server.properties on a dedicated server).
	 */
	public static void migrateAtStartup() {
		try {
			migrateDir(FMLPaths.CONFIGDIR.get());
			migrateDir(FMLPaths.GAMEDIR.get().resolve(FMLConfig.defaultConfigPath()));
			Path gameDir = FMLPaths.GAMEDIR.get();
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
	 * Safety net for a world whose server config override was not found at startup (a world opened
	 * from another directory, a dedicated server started with --world). NeoForge has already read the
	 * SERVER configs when this runs (ServerAboutToStartEvent), so after moving the file the SERVER
	 * configs are read again. Only happens on the first start of such a world.
	 *
	 * <p>Unlike on Forge 1.20.1 no generated default file has to be moved aside first: NeoForge reads
	 * a world's {@code serverconfig/<file>} only as an override and, when it does not exist, loads (and
	 * creates from the defaults) {@code config/<file>} instead, so the new name never exists in the
	 * world directory at this point and {@link LegacyFiles#migrate} is not blocked. Verified on a
	 * dedicated server started with {@code --world}, with and without an existing
	 * {@code config/obscuras_storage-server.toml}.
	 */
	public static void migrateWorld(MinecraftServer server) {
		try {
			Path dir = server.getWorldPath(new LevelResource(SERVERCONFIG));
			ModConfig.Type type = ModConfig.Type.SERVER;
			if (LegacyFiles.migrate(dir.resolve(oldFileName(type)), dir.resolve(fileName(type)))) {
				ConfigTracker.INSTANCE.unloadConfigs(type);
				ConfigTracker.INSTANCE.loadConfigs(type, FMLPaths.CONFIGDIR.get(), dir);
				StorageMod.LOGGER.info("Reloaded the server configs after moving the world's server config file");
			}
		} catch (RuntimeException e) {
			StorageMod.LOGGER.warn("Could not migrate the server config file of the world", e);
		}
	}
}
