package com.obscura.storage.tidy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

/**
 * One Tidy run on one network. Not saved: a server restart simply ends it, every move already made is
 * a normal, complete transfer.
 *
 * <p>Per tick it does a bounded slice of work: reading inventories ({@link #INDEX_SLOTS_PER_TICK} slots),
 * then planning ({@link #PLAN_WORK_PER_TICK} planner units, see {@link TidyPlanner}), then at most
 * {@code tidyMovesPerTick} stack moves. The whole run stops after {@code tidyMaxSeconds}, the moves made
 * until then stay.
 */
public final class TidyJob {
	public enum Phase { INDEX, PLAN, EXECUTE, DONE }

	public enum End { FINISHED, STOPPED, TIMEOUT, NOTHING, GONE }

	/** Slots read per tick while indexing. */
	static final int INDEX_SLOTS_PER_TICK = 4096;
	/** Planner work units per tick. */
	static final int PLAN_WORK_PER_TICK = 4096;
	/** At most this many slots are indexed per run (memory cap); a bigger network is tidied in parts. */
	static final int MAX_SLOTS = 262_144;
	/** The progress the terminals show is updated this often (ticks). */
	static final int PUBLISH_INTERVAL = 5;

	final InventoryConnectorBlockEntity connector;
	final NetworkStats stats;
	final ServerLevel level;
	final UUID starter;
	final int startTick;
	final int movesPerTick;
	final int maxTicks;
	private final List<TidyMember> members;
	private final Set<Object> claimed;

	private Phase phase = Phase.INDEX;
	private End end;
	private int indexCursor;
	private final Object2IntMap<Object> keyIds = TidyIO.newKeyMap();
	private final List<ItemStack> protos = new ArrayList<>();
	/** Planner inventory id to member. */
	private final List<TidyMember> indexed = new ArrayList<>();
	private final TidyPlanner planner;
	private List<TidyPlanner.Move> moves = List.of();
	private int cursor;
	private int done, freed, maxMovesInTick, lastTick;
	private long movedItems;

	/** The network's members the last time they were compared, by identity, to find the current wrapper of a block. */
	private Object[] knownParts = new Object[0];
	private final Long2ObjectOpenHashMap<Object> livePartAt = new Long2ObjectOpenHashMap<>();

	private int pubDone, pubPlanned;
	private Phase pubPhase = Phase.INDEX;
	private int lastPublish = Integer.MIN_VALUE;

	TidyJob(InventoryConnectorBlockEntity connector, NetworkStats stats, ServerLevel level, UUID starter, int now,
			int movesPerTick, int maxSeconds, List<TidyMember> members, Set<Object> claimed) {
		this.connector = connector;
		this.stats = stats;
		this.level = level;
		this.starter = starter;
		this.startTick = now;
		this.movesPerTick = Math.max(1, movesPerTick);
		this.maxTicks = Math.max(1, maxSeconds) * 20;
		this.members = members;
		this.claimed = claimed;
		// More moves than this could never run before the time limit
		this.planner = new TidyPlanner(new Rules(), (int) Math.min(Integer.MAX_VALUE, (long) this.movesPerTick * maxTicks));
	}

	// ------------------------------------------------------------------ state for the manager and the menus

	public Phase phase() {
		return phase;
	}

	public End end() {
		return end;
	}

	/** Moves that moved something. */
	public int done() {
		return done;
	}

	/** Moves processed so far (made or skipped as stale), the progress. */
	public int processed() {
		return cursor;
	}

	public int planned() {
		return moves.size();
	}

	public int freed() {
		return freed;
	}

	public long movedItems() {
		return movedItems;
	}

	public long noRoom() {
		return planner.noRoom();
	}

	/** The most moves made in one tick so far. */
	public int maxMovesInTick() {
		return maxMovesInTick;
	}

	public int inventories() {
		return members.size();
	}

	public int indexedInventories() {
		return indexed.size();
	}

	public InventoryConnectorBlockEntity connector() {
		return connector;
	}

	public NetworkStats stats() {
		return stats;
	}

	int publishedDone() {
		return pubDone;
	}

	int publishedPlanned() {
		return pubPlanned;
	}

	boolean publishedRunning() {
		return pubPhase == Phase.EXECUTE;
	}

	boolean overlaps(Set<Object> positions) {
		Set<Object> a = positions.size() < claimed.size() ? positions : claimed;
		Set<Object> b = a == positions ? claimed : positions;
		for (Object o : a)if (b.contains(o))return true;
		return false;
	}

	void finish(End e) {
		if (phase == Phase.DONE)return;
		end = e;
		phase = Phase.DONE;
		publish(Integer.MAX_VALUE, true);
	}

	private void publish(int now, boolean force) {
		if (!force && pubPhase == phase && now - lastPublish < PUBLISH_INTERVAL)return;
		lastPublish = now;
		pubPhase = phase;
		pubDone = cursor;
		pubPlanned = moves.size();
	}

	// ------------------------------------------------------------------ work

	/** One tick of work; the manager times it for the Network Doctor. */
	void tick(int now) {
		if (phase == Phase.DONE)return;
		if (connector.isRemoved() || connector.getLevel() != level) {
			finish(End.GONE);
			return;
		}
		if (now - startTick >= maxTicks) {
			finish(End.TIMEOUT);
			return;
		}
		switch (phase) {
		case INDEX -> index();
		case PLAN -> plan();
		case EXECUTE -> execute();
		default -> {}
		}
		publish(now, false);
	}

	private void index() {
		refreshParts();
		int budget = INDEX_SLOTS_PER_TICK;
		IntArrayList k = new IntArrayList(), c = new IntArrayList(), l = new IntArrayList();
		while (budget > 0 && indexCursor < members.size()) {
			if (planner.slotsIndexed() >= MAX_SLOTS) {
				indexCursor = members.size();
				break;
			}
			TidyMember m = members.get(indexCursor++);
			Object part = currentPart(m);
			if (part == null || !m.isLive(level))continue;
			k.clear();
			c.clear();
			l.clear();
			int n = TidyIO.snapshot(part, (slot, key, count, limit) -> {
				k.add(key == null ? -1 : keyId(key));
				c.add(count);
				l.add(limit);
			});
			budget -= Math.max(1, n);
			if (n <= 0)continue;
			planner.addInventory(k.toIntArray(), c.toIntArray(), l.toIntArray(), m.hasLabel(), m.hasAllowFilter());
			indexed.add(m);
		}
		if (indexCursor >= members.size())phase = Phase.PLAN;
	}

	private int keyId(Object key) {
		int id = keyIds.getInt(key);
		if (id < 0) {
			Object frozen = TidyIO.freeze(key);
			id = protos.size();
			keyIds.put(frozen, id);
			protos.add(TidyIO.prototype(frozen));
		}
		return id;
	}

	private void plan() {
		if (!planner.step(PLAN_WORK_PER_TICK))return;
		moves = planner.moves();
		if (moves.isEmpty())finish(End.NOTHING);
		else phase = Phase.EXECUTE;
	}

	private void execute() {
		refreshParts();
		Object network = connector.getNetworkStorage();
		int made = 0, tries = 0;
		while (made < movesPerTick && tries < movesPerTick * 4 && cursor < moves.size()) {
			TidyPlanner.Move mv = moves.get(cursor++);
			tries++;
			TidyMember s = indexed.get(mv.srcInv()), d = indexed.get(mv.dstInv());
			// A member that left the network (block broken or replaced, set to skip, filter or label
			// changed) must not be moved into or out of any more: the move is stale
			Object sp = currentPart(s), dp = currentPart(d);
			if (sp == null || dp == null || !s.isLive(level) || !d.isLive(level))continue;
			MoveResult r = TidyIO.move(s, sp, mv.srcSlot(), protos.get(mv.key()), mv.amount(), d, dp, mv.dstSlot(), network);
			if (r.moved() <= 0)continue;
			made++;
			done++;
			movedItems += r.moved();
			freed += (r.sourceEmptied() ? 1 : 0) - r.slotsFilled();
		}
		lastTick = made;
		maxMovesInTick = Math.max(maxMovesInTick, made);
		if (cursor >= moves.size())finish(End.FINISHED);
	}

	/** Moves made in the last tick. */
	public int lastTickMoves() {
		return lastTick;
	}

	/**
	 * Keeps {@link #livePartAt} in step with the network: the member list is rebuilt every few ticks, with
	 * fresh wrappers for the same blocks. Tidy always moves through the wrapper the network lists now, so
	 * block filters and labels changed since planning apply, and a block that is no longer listed is left
	 * alone.
	 */
	private void refreshParts() {
		Collection<?> now = connector.getNetworkStorage().getStorages();
		boolean same = now.size() == knownParts.length;
		if (same) {
			int i = 0;
			for (Object a : now) {
				if (knownParts[i++] != a) {
					same = false;
					break;
				}
			}
		}
		if (same)return;
		knownParts = now.toArray();
		livePartAt.clear();
		for (Object p : knownParts) {
			BlockPos pos = connector.getNetworkStorage().positionOf(p);
			if (pos != null)livePartAt.putIfAbsent(pos.asLong(), p);
		}
	}

	/** The wrapper the network lists for the block of {@code m} right now, null when it is not listed. */
	private Object currentPart(TidyMember m) {
		return livePartAt.get(m.pos.asLong());
	}

	/** The planner's view of the indexed members and keys. */
	private final class Rules implements TidyPlanner.Rules {
		@Override
		public int priority(int inv) {
			return indexed.get(inv).priority;
		}

		@Override
		public long posKey(int inv) {
			return indexed.get(inv).pos.asLong();
		}

		@Override
		public boolean canInsert(int inv, int key) {
			return indexed.get(inv).canInsert(protos.get(key));
		}

		@Override
		public boolean canExtract(int inv, int key) {
			return indexed.get(inv).canExtract(protos.get(key));
		}

		@Override
		public boolean labelled(int inv, int key) {
			return indexed.get(inv).labelled(protos.get(key));
		}

		@Override
		public boolean filtered(int inv, int key) {
			return indexed.get(inv).filtered(protos.get(key));
		}

		@Override
		public int maxStack(int key) {
			return protos.get(key).getMaxStackSize();
		}
	}
}
