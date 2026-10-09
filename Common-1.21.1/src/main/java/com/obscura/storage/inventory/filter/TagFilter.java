package com.obscura.storage.inventory.filter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.util.AbstractTagFilter;

public class TagFilter extends AbstractTagFilter implements ItemFilter {
	/**
	 * Bumped whenever tags are (re)loaded ({@code CommonLifecycleEvents.TAGS_LOADED}): tag contents
	 * change in place on /reload, so this is the only way to notice that a resolved set is stale.
	 */
	private static volatile int tagEpoch;

	/**
	 * Resolved on the server thread only (constructor/updateState) and published as an immutable
	 * set: test() runs concurrently from the off-thread tracker processing and must never see a
	 * half-filled set.
	 */
	private volatile Set<Item> resolvedItems;
	/** What {@link #resolvedItems} was built from; a rebuild is only needed when either differs. */
	private List<TagKey<Item>> resolvedTags;
	private int resolvedEpoch;
	private volatile long stateVersion;

	public TagFilter(ItemStack stack) {
		super(stack);
		resolve();
	}

	/** Invalidates every TagFilter's resolved item set. Called when tags are loaded or reloaded. */
	public static void onTagsReloaded() {
		tagEpoch++;
	}

	@Override
	public boolean configMatch(ItemStack stack) {
		return stack == this.stack;
	}

	@Override
	public void updateState() {
		// Called every tick by every filtered inventory: only resolve the tags again when the tag
		// list was edited or the tags themselves were reloaded.
		if (resolvedEpoch != tagEpoch || !tags.equals(resolvedTags))resolve();
	}

	private void resolve() {
		resolvedEpoch = tagEpoch;
		resolvedTags = List.copyOf(tags);
		Set<Item> set = new HashSet<>();
		for (int i = 0; i < resolvedTags.size(); i++) {
			BuiltInRegistries.ITEM.getTagOrEmpty(resolvedTags.get(i)).forEach(holder -> set.add(holder.value()));
		}
		resolvedItems = Set.copyOf(set);
		stateVersion = ItemPredicate.nextStateVersion();
	}

	@Override
	protected void onConfigChanged() {
		stateVersion = ItemPredicate.nextStateVersion();
	}

	@Override
	public long stateVersion() {
		return stateVersion;
	}

	@Override
	public boolean test(StoredItemStack stack) {
		return test0(stack) == allowList;
	}

	private boolean test0(StoredItemStack stack) {
		return resolvedItems.contains(stack.getStack().getItem());
	}
}
