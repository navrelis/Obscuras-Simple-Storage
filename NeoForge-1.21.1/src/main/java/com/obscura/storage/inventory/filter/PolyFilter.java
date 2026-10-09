package com.obscura.storage.inventory.filter;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.util.BlockFaceReference;

public class PolyFilter implements ItemFilter {
	private BlockFaceReference face;
	/**
	 * Item + components of every stack in the watched inventory (StoredItemStack equality ignores the
	 * count). Rebuilt on the server thread only (updateState) and published as an immutable set: test()
	 * runs concurrently from the off-thread tracker processing and must never see it half-filled.
	 */
	private volatile Set<StoredItemStack> filterSet = Set.of();
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
			IItemHandler ih = face.level().getCapability(Capabilities.ItemHandler.BLOCK, face.pos(), face.from());
			if(ih != null) {
				Set<StoredItemStack> set = new HashSet<>();
				for (int i = 0; i < ih.getSlots(); i++) {
					ItemStack s = ih.getStackInSlot(i);
					if (!s.isEmpty())set.add(new StoredItemStack(s));
				}
				// Only publish (and bump the version) when the contents really changed.
				if (inventoryMissing || !set.equals(filterSet)) {
					filterSet = Set.copyOf(set);
					inventoryMissing = false;
					stateVersion = ItemPredicate.nextStateVersion();
				}
			} else {
				filterSet = Set.of();
				if (!inventoryMissing) {
					stateVersion = ItemPredicate.nextStateVersion();
					StorageMod.LOGGER.debug("PolyFilter: No inventory found at {} facing {}", face.pos(), face.from());
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
		if (inventoryMissing) return false; // fail-closed: block all when the source inventory is missing
		return filterSet.contains(stack);
	}

	@Override
	public boolean configMatch(ItemStack stack) {
		return stack.getItem() == Content.polyItemFilter.get();
	}
}
