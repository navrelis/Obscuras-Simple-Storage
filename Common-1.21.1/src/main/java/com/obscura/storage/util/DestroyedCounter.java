package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * What a Disposal destroyed: the total and the count per item (registry id). At most
 * {@link #MAX_ITEMS} different items are tracked, anything beyond only adds to the total, so the saved
 * data stays small whatever is thrown at the block. Counts stop at {@link Long#MAX_VALUE}.
 */
public class DestroyedCounter {
	public static final int MAX_ITEMS = 256;
	public static final String TOTAL_TAG = "Destroyed", ITEMS_TAG = "DestroyedItems";
	private static final String ID_TAG = "id", COUNT_TAG = "n";

	/** One tracked item and how many of it were destroyed. */
	public static record Entry(ResourceLocation id, long count) {}

	private long total;
	private final Map<ResourceLocation, Long> items = new LinkedHashMap<>();

	public void add(ResourceLocation id, long count) {
		if (count <= 0)return;
		total = FilterBlockLogic.saturatedAdd(total, count);
		if (id == null)return;
		Long old = items.get(id);
		if (old != null)items.put(id, FilterBlockLogic.saturatedAdd(old, count));
		else if (items.size() < MAX_ITEMS)items.put(id, count);
	}

	public long getTotal() {
		return total;
	}

	/** Destroyed items of {@code id}, 0 when none or when the item is not tracked. */
	public long get(ResourceLocation id) {
		Long c = id != null ? items.get(id) : null;
		return c != null ? c : 0L;
	}

	public int trackedItems() {
		return items.size();
	}

	public boolean isEmpty() {
		return total == 0 && items.isEmpty();
	}

	public void reset() {
		total = 0;
		items.clear();
	}

	/** The {@code n} most destroyed items, most first; ties keep the order the items were first destroyed in. */
	public List<Entry> top(int n) {
		List<Entry> out = new ArrayList<>(items.size());
		for (Map.Entry<ResourceLocation, Long> e : items.entrySet())out.add(new Entry(e.getKey(), e.getValue()));
		out.sort((a, b) -> Long.compare(b.count(), a.count()));
		return out.size() > n ? new ArrayList<>(out.subList(0, Math.max(0, n))) : out;
	}

	/** Writes {@value #TOTAL_TAG} (long) and {@value #ITEMS_TAG} (list of {@code {id, n}}) into {@code tag}. */
	public void save(CompoundTag tag) {
		tag.putLong(TOTAL_TAG, total);
		ListTag list = new ListTag();
		for (Map.Entry<ResourceLocation, Long> e : items.entrySet()) {
			CompoundTag c = new CompoundTag();
			c.putString(ID_TAG, e.getKey().toString());
			c.putLong(COUNT_TAG, e.getValue());
			list.add(c);
		}
		tag.put(ITEMS_TAG, list);
	}

	/** Reads what {@link #save} wrote; missing keys read as 0, broken entries are skipped. */
	public void load(CompoundTag tag) {
		reset();
		total = Math.max(0, tag.getLong(TOTAL_TAG));
		ListTag list = tag.getList(ITEMS_TAG, Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size() && items.size() < MAX_ITEMS; i++) {
			CompoundTag c = list.getCompound(i);
			ResourceLocation id = ResourceLocation.tryParse(c.getString(ID_TAG));
			long n = c.getLong(COUNT_TAG);
			if (id != null && n > 0)items.merge(id, n, FilterBlockLogic::saturatedAdd);
		}
	}
}
