package com.obscura.storage.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;

public class InventoryImage {
	private final long version;
	private List<FabricStack> stacks = new ArrayList<>();
	public static record FabricStack(StorageView<ItemVariant> view, StorageView<ItemVariant> uv, ItemVariant item, long count) {}

	public InventoryImage(long version) {
		this.version = version;
	}

	public long getVersion() {
		return version;
	}

	public void addStack(StorageView<ItemVariant> view) {
		stacks.add(new FabricStack(view, view.getUnderlyingView(), view.getResource(), view.getAmount()));
	}

	/**
	 * Result of the off-thread pass. {@code memo} holds the per-slot observation cache updates
	 * ({@link InventoryChangeTracker#NO_MEMO} = drop the entry); it can be non-empty even when
	 * nothing visible changed.
	 */
	public static record InvState(long change, long version, Map<StorageView<ItemVariant>, StoredItemStack> mod, Map<StorageView<ItemVariant>, SlotMemo> memo) {}

	/** What a slot held when it was last looked at, regardless of whether the filter accepted it. */
	public static record SlotMemo(ItemVariant variant, long amount) {}

	public List<FabricStack> getStacks() {
		return stacks;
	}
}
