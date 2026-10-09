package com.obscura.storage.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import com.obscura.storage.util.ItemLocator;
import com.obscura.storage.util.ItemLocator.Location;

/**
 * Which slots of a source inventory physically belong to a target inventory. The Exporter must never
 * pull from the inventory it inserts into: when its target chest (or Shulker Dock) is also connected
 * to the source network, the items it just moved would be found again and moved forever.
 *
 * <p>A source member counts as the target when any of these is the same:
 * <ul>
 * <li>the root handler (the block behind proxies, filters and frame labels);</li>
 * <li>the physical storage, by the identity the network uses to remove duplicates (the other half of
 * a double chest, the same container reached through another face);</li>
 * <li>the block position of the storage (any other face of one block).</li>
 * </ul>
 * Built for one transfer attempt, nothing is cached: the members of a network and what a block face
 * resolves to can change at any time.
 */
public final class TargetExclusion {
	private static final TargetExclusion NONE = new TargetExclusion(List.of(), false);
	private static final TargetExclusion ALL = new TargetExclusion(List.of(), true);
	/** Networks nested in the target that are still resolved to their members. */
	private static final int MAX_DEPTH = 4;

	private final List<IInventoryAccess> excluded;
	private final boolean all;

	private TargetExclusion(List<IInventoryAccess> excluded, boolean all) {
		this.excluded = excluded;
		this.all = all;
	}

	public static TargetExclusion of(IInventoryAccess source, IInventoryAccess target) {
		if (source == null || target == null)return NONE;
		if (source == target)return ALL;
		Keys keys = new Keys();
		keys.addTarget(target, 0);
		if (source instanceof MultiInventoryAccess m) {
			if (keys.identity.contains(m))return ALL;
			List<IInventoryAccess> ex = null;
			for (IInventoryAccess a : m.getConnected()) {
				if (keys.matches(a)) {
					if (ex == null)ex = new ArrayList<>(2);
					ex.add(a);
				}
			}
			return ex == null ? NONE : new TargetExclusion(ex, false);
		}
		return keys.matches(source) ? ALL : NONE;
	}

	/** The whole source is the target: there is nothing to move. */
	public boolean isAll() {
		return all;
	}

	/** Source members that are the target, for tests and diagnostics. */
	public int excludedMembers() {
		return excluded.size();
	}

	/** Whether {@code slot}, handed out by the source's tracker, lies in the target. */
	public boolean excludes(InventorySlot slot) {
		if (all)return true;
		for (int i = 0; i < excluded.size(); i++) {
			if (excluded.get(i).tracker().owns(slot))return true;
		}
		return false;
	}

	private static final class Keys {
		/** Root handlers, networks and storage identities of the target, compared by identity. */
		final Set<Object> identity = Collections.newSetFromMap(new IdentityHashMap<>());
		final Set<Location> blocks = new HashSet<>();
		private final Set<IProxy> dejaVu = Collections.newSetFromMap(new IdentityHashMap<>());

		private IInventoryAccess rootOf(IInventoryAccess a) {
			dejaVu.clear();
			return a.getRootHandler(dejaVu);
		}

		void addTarget(IInventoryAccess a, int depth) {
			if (!identity.add(a))return;
			IInventoryAccess root = rootOf(a);
			identity.add(root);
			if (root instanceof MultiInventoryAccess m) {
				// The target is (or leads into) a network: every inventory of it is the target
				if (depth < MAX_DEPTH) {
					for (IInventoryAccess member : new ArrayList<>(m.getConnected()))addTarget(member, depth + 1);
				}
				return;
			}
			Object sid = PlatformMultiInventoryAccess.storageIdentity(root);
			if (sid != null)identity.add(sid);
			Location l = ItemLocator.locateBlock(a, root);
			if (l != null)blocks.add(l);
		}

		boolean matches(IInventoryAccess a) {
			if (identity.contains(a))return true;
			IInventoryAccess root = rootOf(a);
			if (identity.contains(root))return true;
			if (root instanceof MultiInventoryAccess)return false;
			Location l = ItemLocator.locateBlock(a, root);
			if (l != null && blocks.contains(l))return true;
			Object sid = PlatformMultiInventoryAccess.storageIdentity(root);
			return sid != null && identity.contains(sid);
		}
	}
}
