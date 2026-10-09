package com.obscura.storage.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.obscura.storage.StorageMod;

/**
 * Moves files that older versions wrote under the old mod id (config files, saved data) to their new
 * names, and copies key bindings saved under the old names. Every step runs at most once per file: it
 * only acts while the old file exists and the new one does not, and it never overwrites or deletes
 * anything. The old file is kept as {@code <name>.migrated}.
 */
public final class LegacyFiles {
	/** Appended to an old file after its content was copied to the new name. */
	public static final String MIGRATED_SUFFIX = ".migrated";

	// Own logger: the loaders' StorageMod classes have side effects in their static initializers.
	private static final Logger LOGGER = LogManager.getLogger(StorageMod.modid);

	private LegacyFiles() {}

	/**
	 * Copies {@code oldFile} to {@code newFile} when the old file exists and the new one does not, then
	 * renames the old file to {@code <old name>.migrated}. The copy goes through a temporary file, so a
	 * crash never leaves a half written new file behind.
	 *
	 * @return whether the file was migrated
	 */
	public static boolean migrate(Path oldFile, Path newFile) {
		if (!Files.isRegularFile(oldFile) || Files.exists(newFile))return false;
		try {
			Path parent = newFile.toAbsolutePath().getParent();
			if (parent != null)Files.createDirectories(parent);
			Path tmp = newFile.resolveSibling(newFile.getFileName() + ".tmp");
			Files.copy(oldFile, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			try {
				Files.move(tmp, newFile, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, newFile);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not move {} to {}, the old file stays in use until this works", oldFile, newFile, e);
			return false;
		}
		try {
			Files.move(oldFile, oldFile.resolveSibling(oldFile.getFileName() + MIGRATED_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			// The new file exists, so the old one is never read again; nothing is lost.
			LOGGER.warn("Copied {} to {} but could not rename the old file", oldFile, newFile, e);
		}
		LOGGER.info("Migrated {} to {}", oldFile, newFile);
		return true;
	}

	/**
	 * Key bindings saved in options.txt under the old names
	 * ({@code key_key.toms_storage.open_terminal:key.keyboard.b}) are copied to the new names, unless
	 * options.txt already has a value for the new name. The old lines are kept; the game drops unknown
	 * keys the next time it saves the options.
	 *
	 * @return the migrated lines, or {@code null} when nothing had to change
	 */
	public static List<String> migrateKeyBindings(List<String> lines) {
		String oldPrefix = "key_key." + LegacyIds.OLD_NAMESPACE + ".";
		String newPrefix = "key_key." + StorageMod.modid + ".";
		Set<String> present = new HashSet<>();
		for (String l : lines) {
			int c = l.indexOf(':');
			if (c > 0 && l.startsWith(newPrefix))present.add(l.substring(0, c));
		}
		List<String> out = null;
		for (String l : lines) {
			if (!l.startsWith(oldPrefix))continue;
			String moved = newPrefix + l.substring(oldPrefix.length());
			int c = moved.indexOf(':');
			if (c <= newPrefix.length())continue;// no key name or no value
			if (!present.add(moved.substring(0, c)))continue;
			if (out == null)out = new ArrayList<>(lines);
			out.add(moved);
		}
		return out;
	}

	/**
	 * Applies {@link #migrateKeyBindings} to an options.txt file. Must run before the game reads the
	 * key bindings (Fabric: client entrypoint; NeoForge: mod construction, the options are read again
	 * after mod loading).
	 *
	 * @return whether the file was changed
	 */
	public static boolean migrateKeyBindings(Path optionsFile) {
		if (!Files.isRegularFile(optionsFile))return false;
		try {
			List<String> out = migrateKeyBindings(Files.readAllLines(optionsFile, StandardCharsets.UTF_8));
			if (out == null)return false;
			Path tmp = optionsFile.resolveSibling(optionsFile.getFileName() + ".tmp");
			Files.write(tmp, out, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, optionsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, optionsFile, StandardCopyOption.REPLACE_EXISTING);
			}
			LOGGER.info("Copied the key bindings of an older version in {}", optionsFile);
			return true;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not migrate the key bindings in {}", optionsFile, e);
			return false;
		}
	}
}
