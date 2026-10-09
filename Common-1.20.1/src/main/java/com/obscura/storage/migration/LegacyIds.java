package com.obscura.storage.migration;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import com.obscura.storage.StorageMod;

/**
 * The ids this mod used before v5.0.0. Everything the mod registered, saved or synced used to live in
 * the {@value #OLD_NAMESPACE} namespace; since v5.0.0 it lives in {@link StorageMod#modid}. The paths
 * never changed (also the {@code ts.*} ids of the painted blocks and the id of the removed Filtered
 * Connector, which still converts itself), so an old id is always the new id with the old namespace.
 *
 * <p>Worlds written by an older version are loaded through registry aliases (Forge: remapped missing
 * mappings plus registry aliases; Fabric: the {@code MappedRegistryMixin}), so every old id that is read
 * from a chunk, an item stack or a block entity resolves to the new entry. The few strings that are not
 * resolved by a by-name registry lookup on every loader (block states and tag names inside NBT, saved
 * data and config file names, key binding names) are moved by the classes in this package.
 */
public final class LegacyIds {
	/** Namespace (mod id) used up to v4.x. Only ever read, never written. */
	public static final String OLD_NAMESPACE = "toms_storage";

	private LegacyIds() {}

	/** {@code toms_storage:<path>} */
	public static ResourceLocation old(String path) {
		return new ResourceLocation(OLD_NAMESPACE, path);
	}

	/** Whether {@code id} is in the old namespace. Null safe. */
	public static boolean isOld(ResourceLocation id) {
		return id != null && OLD_NAMESPACE.equals(id.getNamespace());
	}

	/** The new id of an old one: same path, new namespace. Any other id is returned unchanged. */
	public static ResourceLocation toCurrent(ResourceLocation id) {
		return isOld(id) ? new ResourceLocation(StorageMod.modid, id.getPath()) : id;
	}

	/**
	 * {@code "toms_storage:<path>"} as the string {@code "obscuras_storage:<path>"}. Any other string
	 * (also one that is no valid id) is returned unchanged.
	 */
	public static String toCurrent(String id) {
		if (id == null || !id.startsWith(OLD_NAMESPACE + ":"))return id;
		return StorageMod.modid + id.substring(OLD_NAMESPACE.length());
	}

	/**
	 * Rewrites, in place, every string value in {@code tag} (also in nested compounds and lists) that
	 * is an id in the old namespace ({@code "toms_storage:trim"}) to the new namespace. Keys and every
	 * other string are left alone. Used for the NBT of this mod's own items when a stack is loaded
	 * ({@code Item#verifyTagAfterLoad}): painted block states, tag names and the stacks inside filters.
	 *
	 * @return whether something changed
	 */
	public static boolean migrateStrings(CompoundTag tag) {
		if (tag == null)return false;
		boolean changed = false;
		for (String key : List.copyOf(tag.getAllKeys())) {
			Tag v = tag.get(key);
			if (v instanceof StringTag s) {
				String c = toCurrent(s.getAsString());
				if (!c.equals(s.getAsString())) {
					tag.putString(key, c);
					changed = true;
				}
			} else if (v instanceof CompoundTag c) {
				changed |= migrateStrings(c);
			} else if (v instanceof ListTag l) {
				changed |= migrateStrings(l);
			}
		}
		return changed;
	}

	private static boolean migrateStrings(ListTag list) {
		boolean changed = false;
		for (int i = 0; i < list.size(); i++) {
			Tag v = list.get(i);
			if (v instanceof StringTag s) {
				String c = toCurrent(s.getAsString());
				if (!c.equals(s.getAsString())) {
					list.set(i, StringTag.valueOf(c));
					changed = true;
				}
			} else if (v instanceof CompoundTag c) {
				changed |= migrateStrings(c);
			} else if (v instanceof ListTag l) {
				changed |= migrateStrings(l);
			}
		}
		return changed;
	}

	/**
	 * A block state as {@code NbtUtils.writeBlockState} stores it ({@code {Name:"toms_storage:trim"}}):
	 * the same state with the new block id, or {@code tag} itself when it names no block of the old
	 * namespace. The tag passed in is never changed.
	 */
	public static CompoundTag currentBlockState(CompoundTag tag) {
		if (tag == null || !tag.contains("Name", Tag.TAG_STRING))return tag;
		String name = tag.getString("Name");
		String current = toCurrent(name);
		if (current.equals(name))return tag;
		CompoundTag copy = tag.copy();
		copy.putString("Name", current);
		return copy;
	}
}
