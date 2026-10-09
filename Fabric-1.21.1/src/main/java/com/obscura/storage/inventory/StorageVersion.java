package com.obscura.storage.inventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;

/**
 * Version of a storage that is handed out as a NEW wrapper object on every lookup.
 *
 * <p>Fabric only allows comparing {@link Storage#getVersion()} values taken from the same
 * instance, and a vanilla double chest ({@code CombinedSlottedStorage}) or any sided inventory
 * ({@code SidedInventoryStorageImpl}) is a fresh {@link CombinedStorage} per lookup, so its own
 * version is useless. Its parts, or the underlying views of its parts, are stable objects though.
 * If every one of those reports a real version, the combination of (part identity, part version)
 * is a valid version for the whole wrapper: same parts with the same versions means same contents.
 *
 * <p>Note: Fabric's own vanilla wrappers ({@code InventoryStorageImpl}, its slot wrappers) do NOT
 * implement {@code getVersion()} and inherit the default, which returns a new value on every call.
 * They are detected here (without calling it) and reported as not versionable, so the tracker falls
 * back to its allocation-free content comparison for them.
 */
final class StorageVersion {
	/** Nested CombinedStorages are flattened this deep (double chest: wrapper -> half -> slot). */
	private static final int MAX_DEPTH = 2;

	private static final ClassValue<Boolean> OVERRIDES_VERSION = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			try {
				return type.getMethod("getVersion").getDeclaringClass() != Storage.class;
			} catch (NoSuchMethodException e) {
				return false;
			}
		}
	};

	private final Object[] parts;
	private final long[] versions;

	private StorageVersion(Object[] parts, long[] versions) {
		this.parts = parts;
		this.versions = versions;
	}

	/** Whether instances of {@code type} report a real version instead of Fabric's always-new default. */
	static boolean hasRealVersion(Class<?> type) {
		return OVERRIDES_VERSION.get(type);
	}

	/**
	 * The derived version of {@code h}, or null when it has none: not a CombinedStorage, or one of
	 * its (flattened) parts does not report a real version. Must not be called inside a transaction.
	 */
	static StorageVersion of(Storage<?> h) {
		if (!(h instanceof CombinedStorage<?, ?> cs) || hasRealVersion(h.getClass()))return null;
		// Vanilla wrappers fail on their first part: find that out before allocating anything.
		if (!firstLeafVersioned(cs, 0))return null;
		List<Object> parts = new ArrayList<>();
		List<Storage<?>> versioned = new ArrayList<>();
		if (!collect(cs, parts, versioned, 0))return null;
		long[] v = new long[versioned.size()];
		for (int i = 0; i < v.length; i++)v[i] = versioned.get(i).getVersion();
		return new StorageVersion(parts.toArray(), v);
	}

	private static boolean firstLeafVersioned(CombinedStorage<?, ?> cs, int depth) {
		List<?> list = cs.parts;
		if (list == null)return false;
		if (list.isEmpty())return true;
		Object p = list.get(0);
		Object u = p instanceof StorageView<?> sv ? sv.getUnderlyingView() : p;
		if (u instanceof Storage<?> s && hasRealVersion(s.getClass()))return true;
		return depth < MAX_DEPTH && p instanceof CombinedStorage<?, ?> inner && !hasRealVersion(p.getClass()) && firstLeafVersioned(inner, depth + 1);
	}

	private static boolean collect(CombinedStorage<?, ?> cs, List<Object> parts, List<Storage<?>> versioned, int depth) {
		List<?> list = cs.parts;
		if (list == null)return false;
		for (int i = 0; i < list.size(); i++) {
			Object p = list.get(i);
			// A per-lookup slot wrapper (sided inventories) is a different object every time; its
			// underlying view is the stable one, and versions may only be read from that same object.
			Object u = p instanceof StorageView<?> sv ? sv.getUnderlyingView() : p;
			if (u instanceof Storage<?> s && hasRealVersion(s.getClass())) {
				parts.add(u);
				versioned.add(s);
			} else if (depth < MAX_DEPTH && p instanceof CombinedStorage<?, ?> inner && !hasRealVersion(p.getClass())) {
				if (!collect(inner, parts, versioned, depth + 1))return false;
			} else {
				return false;
			}
		}
		return true;
	}

	/** Same parts (by identity, in order) with the same versions. */
	boolean sameAs(StorageVersion o) {
		if (o == null || o.parts.length != parts.length)return false;
		for (int i = 0; i < parts.length; i++) {
			if (parts[i] != o.parts[i])return false;
		}
		return Arrays.equals(versions, o.versions);
	}
}
