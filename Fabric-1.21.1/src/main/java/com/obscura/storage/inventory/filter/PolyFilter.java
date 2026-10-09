package com.obscura.storage.inventory.filter;

import java.util.HashSet;
import java.util.Set;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.Content;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.util.BlockFaceReference;
import com.obscura.storage.util.Util;

public class PolyFilter implements ItemFilter {
	private BlockFaceReference face;
	/**
	 * Rebuilt on the server thread only (updateState) and published as an immutable set: test()
	 * runs concurrently from the off-thread tracker processing and must never see it half-filled.
	 */
	private volatile Set<ItemVariant> filterSet = Set.of();
	private long lastCheck;
	private volatile boolean inventoryMissing = false;
	private volatile long stateVersion = ItemPredicate.nextStateVersion();

	public PolyFilter(BlockFaceReference face) {
		this.face = face;
	}

	@Override
	public void updateState() {
		long time = face.level().getGameTime();
		if(time - lastCheck >= 10) {
			lastCheck = time;
			Storage<ItemVariant> st = ItemStorage.SIDED.find(face.level(), face.pos(), face.from());
			if(st != null) {
				Set<ItemVariant> set = new HashSet<>();
				Util.stream(st.iterator()).filter(s -> !s.isResourceBlank()).forEach(s -> set.add(s.getResource()));
				// Only publish (and bump the version) when the contents really changed: trackers drop
				// their per-slot caches whenever the version moves.
				if (inventoryMissing || !set.equals(filterSet)) {
					filterSet = Set.copyOf(set);
					inventoryMissing = false;
					stateVersion = ItemPredicate.nextStateVersion();
				}
			} else {
				filterSet = Set.of();
				if (!inventoryMissing) {
					stateVersion = ItemPredicate.nextStateVersion();
					// Log once when inventory first goes missing
					com.obscura.storage.StorageMod.LOGGER.debug("PolyFilter: No inventory found at {} facing {}", face.pos(), face.from());
				}
				inventoryMissing = true;
			}
		}
	}

	@Override
	public long stateVersion() {
		return stateVersion;
	}

	@Override
	public boolean test(StoredItemStack stack) {
		if (inventoryMissing) return false; // fail-closed: block all when source inventory is missing
		return filterSet.contains(ItemVariant.of(stack.getStack()));
	}

	@Override
	public boolean configMatch(ItemStack stack) {
		return stack.getItem() == Content.polyItemFilter.get();
	}
}
