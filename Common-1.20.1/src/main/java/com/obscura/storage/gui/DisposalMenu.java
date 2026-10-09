package com.obscura.storage.gui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;

import com.obscura.storage.Content;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.tile.DisposalBlockEntity;
import com.obscura.storage.util.DataSlots;
import com.obscura.storage.util.DestroyedCounter;
import com.obscura.storage.util.FilterBlockStatus;
import com.obscura.storage.util.GhostFilter;

/**
 * Disposal menu. The destroyed counts are longs, more than a data slot carries (16 bit on the wire), so
 * they go to the open screen as a data packet: once after opening and then at most once per second,
 * only when something the screen shows changed.
 */
public class DisposalMenu extends AbstractFilterBlockMenu<DisposalBlockEntity> {
	/** Client messages: byte pause state; reset the counters (sent only with Shift held); send the counts again. */
	public static final String MSG_PAUSED = "paused", MSG_RESET = "reset", MSG_SYNC = "sync";
	/**
	 * Server message with the counts: {@value #STATS_TOTAL} long, {@value #STATS_SLOTS} long array (per
	 * ghost slot, -1 for an empty slot or a filter item) and {@value #STATS_TOP} list of {@code {id, n}}.
	 */
	public static final String STATS_KEY = "disposalStats", STATS_TOTAL = "t", STATS_SLOTS = "s", STATS_TOP = "top", STATS_ID = "id", STATS_COUNT = "n";
	/** Items listed with their count in the GUI. */
	public static final int TOP_ITEMS = 3;
	private static final int SYNC_TICKS = 20;

	public boolean paused = true;
	public FilterBlockStatus status = FilterBlockStatus.PAUSED;
	private CompoundTag lastStats;
	private long nextSync = Long.MIN_VALUE;

	public DisposalMenu(int wid, Inventory pinv) {
		this(wid, pinv, null);
	}

	public DisposalMenu(int wid, Inventory pinv, DisposalBlockEntity te) {
		super(Content.disposalMenu.get(), wid, pinv, te, 8);
		addDataSlot(DataSlots.create(v -> paused = v > 0, () -> te == null || te.isPaused() ? 1 : 0));
		addDataSlot(DataSlots.create(v -> status = FilterBlockStatus.byId(v), () -> te != null ? te.getStatus().ordinal() : 0));
	}

	@Override
	protected void handle(CompoundTag tag) {
		if(tag.contains(MSG_PAUSED, Tag.TAG_BYTE))te.setPaused(tag.getBoolean(MSG_PAUSED));
		if(tag.getBoolean(MSG_RESET))te.resetDestroyed();
		// The screen was covered (a recipe viewer) and may have missed a packet
		if(tag.getBoolean(MSG_SYNC))lastStats = null;
	}

	@Override
	public void broadcastChanges() {
		super.broadcastChanges();
		if(te == null || !(pinv.player instanceof ServerPlayer player))return;
		// Looked at once per second at most, whatever the client asks for
		long now = player.level().getGameTime();
		if(now < nextSync)return;
		nextSync = now + SYNC_TICKS;
		CompoundTag stats = stats();
		if(stats.equals(lastStats))return;
		lastStats = stats;
		CompoundTag tag = new CompoundTag();
		tag.put(STATS_KEY, stats);
		NetworkHandler.sendTo(player, tag);
	}

	/** What the screen shows of the counters: the total, the count of every plain ghost entry and the top items. */
	private CompoundTag stats() {
		DestroyedCounter d = te.getDestroyed();
		GhostFilter f = te.getFilter();
		CompoundTag tag = new CompoundTag();
		tag.putLong(STATS_TOTAL, d.getTotal());
		long[] slots = new long[GhostFilter.SLOTS];
		for(int i = 0;i<slots.length;i++) {
			slots[i] = f.isPlainEntry(i) ? d.get(BuiltInRegistries.ITEM.getKey(f.getStack(i).getItem())) : -1;
		}
		tag.putLongArray(STATS_SLOTS, slots);
		ListTag top = new ListTag();
		for(DestroyedCounter.Entry e : d.top(TOP_ITEMS)) {
			CompoundTag c = new CompoundTag();
			c.putString(STATS_ID, e.id().toString());
			c.putLong(STATS_COUNT, e.count());
			top.add(c);
		}
		tag.put(STATS_TOP, top);
		return tag;
	}
}
