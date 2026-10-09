package com.obscura.storage.tidy;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;

/**
 * The Tidy planner, on pure data: inventories are slot arrays of item key ids, the rules (filters,
 * labels, priorities) come from {@link Rules}. No game objects, so it is unit tested on its own.
 *
 * <h2>Algorithm</h2>
 * Inventories are added one by one ({@link #addInventory}, the job spreads that over ticks). Every
 * non-empty slot is appended to the holder list of its key, so a key's slots are grouped by inventory
 * and sorted by slot index without any sorting. {@link #step} then plans one key after the other, in
 * the order the keys were first seen, until its work budget is used up:
 * <ol>
 * <li><b>Home.</b> A Frame-Labelled inventory for the key; else one whose Configurator filter is an
 * allow-list containing it (both: highest priority, then the most held, then the lowest position key);
 * else the inventory holding the most of it (ties: higher priority, then lower position key). The home
 * must accept the key.</li>
 * <li><b>Merge</b> the partial stacks of the key inside every inventory that may give and take it:
 * the emptiest partial goes into the fullest one, so every merge move frees a slot or fills one.</li>
 * <li><b>Strays</b> from the other inventories go into the home, smallest stacks first (most slots freed
 * per item moved), until the home is full. The home fills its partial stacks first, then empty slots.
 * Leftovers stay where they are ({@link #noRoom()} counts them). An item never moves from a higher
 * priority inventory into a lower priority home, unless the home is labelled or filtered for it.</li>
 * </ol>
 * The planner keeps its own model of the inventories up to date while it plans (slots emptied by a move
 * can take a later key, a home's empty slots run out), so the moves stay valid in plan order. They are
 * still re-validated against the live slots when they run.
 *
 * <h2>Cost</h2>
 * Memory: three ints per indexed slot plus one long per non-empty slot. Time: O(slots) to index, and per
 * key O(h log h) for its h holder slots plus one rule check per labelled and per filtered inventory.
 * {@link #step} charges that work against its budget, so a tick never plans more than about one key past
 * the budget. Planning stops once {@code moveCap} moves are planned.
 */
public final class TidyPlanner {
	/** What the planner needs to know about inventories and keys. Inventory and key ids are dense, from 0. */
	public interface Rules {
		/** Priority sorting value of the inventory, higher is preferred. */
		int priority(int inv);

		/** Stable tie breaker (the block position), lower wins. */
		long posKey(int inv);

		/** Whether the network may insert the key into the inventory (filters, labels). */
		boolean canInsert(int inv, int key);

		/** Whether the network may take the key out of the inventory (filters; false for keep-last). */
		boolean canExtract(int inv, int key);

		/** Whether a Frame Label on the inventory names the key. */
		boolean labelled(int inv, int key);

		/** Whether the inventory's Configurator filter is an allow-list containing the key. */
		boolean filtered(int inv, int key);

		/** Max stack size of the key. */
		int maxStack(int key);
	}

	/**
	 * One stack move. {@code dstSlot} is a slot of the destination for a merge, or -1 for a move into a
	 * home (partial stacks first, then empty slots).
	 */
	public record Move(int srcInv, int srcSlot, int key, int amount, int dstInv, int dstSlot) {
		public boolean isMerge() {
			return dstSlot >= 0;
		}
	}

	private final Rules rules;
	private final int moveCap;
	private final List<int[]> keys = new ArrayList<>();
	private final List<int[]> counts = new ArrayList<>();
	private final List<int[]> limits = new ArrayList<>();
	/** Free slots per inventory in the model. */
	private final IntArrayList empty = new IntArrayList();
	/** Slot limit of an empty slot per inventory (the first empty slot seen, 64 when none). */
	private final IntArrayList emptyLimit = new IntArrayList();
	private final IntArrayList labelledInvs = new IntArrayList();
	private final IntArrayList filteredInvs = new IntArrayList();
	/** Per key: (inventory << 32 | slot) of every slot holding it, in inventory then slot order. */
	private final List<LongArrayList> holders = new ArrayList<>();
	private final List<Move> moves = new ArrayList<>();
	/** The home picked per planned key, -1 for none. */
	private final Int2IntOpenHashMap homes = new Int2IntOpenHashMap();
	private int nextKey;
	private long noRoom;
	private int freed, slotsIndexed;
	private boolean started, done, truncated;

	/**
	 * @param moveCap stop planning once this many moves are planned (more could not run in time anyway)
	 */
	public TidyPlanner(Rules rules, int moveCap) {
		this.rules = rules;
		this.moveCap = Math.max(1, moveCap);
	}

	/**
	 * Adds the next inventory (its id is the number of inventories added before).
	 *
	 * @param slotKeys key id per slot, -1 for an empty slot
	 * @param slotCounts item count per slot
	 * @param slotLimits slot limit per slot (the item's max stack size is applied on top)
	 * @param anyLabel whether a Frame Label is on it (it is then asked {@link Rules#labelled})
	 * @param anyAllowFilter whether an allow-list Configurator filter is on it ({@link Rules#filtered})
	 * @return the inventory id
	 */
	public int addInventory(int[] slotKeys, int[] slotCounts, int[] slotLimits, boolean anyLabel, boolean anyAllowFilter) {
		if (started)throw new IllegalStateException("planning already started");
		int inv = keys.size();
		keys.add(slotKeys);
		counts.add(slotCounts);
		limits.add(slotLimits);
		int e = 0, el = -1;
		for (int s = 0; s < slotKeys.length; s++) {
			int k = slotKeys[s];
			if (k < 0 || slotCounts[s] <= 0) {
				slotKeys[s] = -1;
				slotCounts[s] = 0;
				e++;
				if (el < 0)el = slotLimits[s];
				continue;
			}
			while (holders.size() <= k)holders.add(null);
			LongArrayList h = holders.get(k);
			if (h == null)holders.set(k, h = new LongArrayList(4));
			h.add(((long) inv << 32) | s);
		}
		empty.add(e);
		emptyLimit.add(el > 0 ? el : 64);
		if (anyLabel)labelledInvs.add(inv);
		if (anyAllowFilter)filteredInvs.add(inv);
		slotsIndexed += slotKeys.length;
		return inv;
	}

	public int inventoryCount() {
		return keys.size();
	}

	public int slotsIndexed() {
		return slotsIndexed;
	}

	/**
	 * Plans keys until {@code budget} work units are used (about one unit per slot or rule check).
	 *
	 * @return whether planning is complete
	 */
	public boolean step(int budget) {
		started = true;
		if (done)return true;
		int work = 0;
		while (work < budget) {
			if (nextKey >= holders.size() || moves.size() >= moveCap) {
				truncated = nextKey < holders.size();
				done = true;
				return true;
			}
			work += planKey(nextKey++);
		}
		return false;
	}

	/** Plans everything at once (tests). */
	public TidyPlanner planAll() {
		while (!step(Integer.MAX_VALUE));
		return this;
	}

	public boolean isDone() {
		return done;
	}

	/** Planning stopped at the move cap with keys left over. */
	public boolean isTruncated() {
		return truncated;
	}

	public List<Move> moves() {
		return moves;
	}

	/** Items that stayed outside their home because it was full. */
	public long noRoom() {
		return noRoom;
	}

	/** Slots the moves free when they all run as planned (emptied slots minus the home slots they take). */
	public int predictedFreed() {
		return freed;
	}

	/** Progress of planning, 0 to 1. */
	public float progress() {
		return holders.isEmpty() ? 1 : Math.min(1f, nextKey / (float) holders.size());
	}

	/** The home picked for a key once it was planned (tests and diagnostics); -1 when it has none or is not planned yet. */
	public int homeOf(int key) {
		return homes.getOrDefault(key, -1);
	}

	// ------------------------------------------------------------------ per key

	private int capOf(int inv, int slot, int maxStack) {
		return Math.min(limits.get(inv)[slot], maxStack);
	}

	private static int inv(long e) {
		return (int) (e >>> 32);
	}

	private static int slot(long e) {
		return (int) e;
	}

	/** Items of the key per inventory, from its live holder slots. */
	private Int2IntOpenHashMap held(LongArrayList h) {
		Int2IntOpenHashMap m = new Int2IntOpenHashMap();
		for (int i = 0; i < h.size(); i++) {
			long e = h.getLong(i);
			int c = counts.get(inv(e))[slot(e)];
			if (c > 0)m.addTo(inv(e), c);
		}
		return m;
	}

	/** {home, tier}: tier 1 labelled, 2 filtered, 3 most held; {-1, 0} when there is no home. */
	private int[] pickHome(int key, Int2IntOpenHashMap held) {
		int best = -1;
		for (int i = 0; i < labelledInvs.size(); i++) {
			int inv = labelledInvs.getInt(i);
			if (rules.labelled(inv, key) && rules.canInsert(inv, key) && betterTier12(inv, best, held))best = inv;
		}
		if (best >= 0)return new int[] {best, 1};
		for (int i = 0; i < filteredInvs.size(); i++) {
			int inv = filteredInvs.getInt(i);
			if (rules.filtered(inv, key) && rules.canInsert(inv, key) && betterTier12(inv, best, held))best = inv;
		}
		if (best >= 0)return new int[] {best, 2};
		for (var it = held.int2IntEntrySet().fastIterator(); it.hasNext();) {
			var e = it.next();
			int inv = e.getIntKey();
			if (!rules.canInsert(inv, key))continue;
			if (best < 0 || better(e.getIntValue(), inv, held.get(best), best, false))best = inv;
		}
		return best >= 0 ? new int[] {best, 3} : new int[] {-1, 0};
	}

	/** Labelled or filtered candidates: priority, then most held, then position. */
	private boolean betterTier12(int inv, int best, Int2IntOpenHashMap held) {
		if (best < 0)return true;
		return better(held.get(inv), inv, held.get(best), best, true);
	}

	private boolean better(int heldA, int a, int heldB, int b, boolean priorityFirst) {
		int pa = rules.priority(a), pb = rules.priority(b);
		if (priorityFirst) {
			if (pa != pb)return pa > pb;
			if (heldA != heldB)return heldA > heldB;
		} else {
			if (heldA != heldB)return heldA > heldB;
			if (pa != pb)return pa > pb;
		}
		long ka = rules.posKey(a), kb = rules.posKey(b);
		if (ka != kb)return ka < kb;
		return a < b;
	}

	/** @return the work done */
	private int planKey(int key) {
		LongArrayList h = holders.get(key);
		if (h == null || h.isEmpty())return 1;
		int work = h.size() + labelledInvs.size() + filteredInvs.size();
		int maxStack = Math.max(1, rules.maxStack(key));
		Int2IntOpenHashMap held = held(h);
		int[] home = pickHome(key, held);
		homes.put(key, home[0]);

		// 2. merge partial stacks inside each inventory (holders are grouped by inventory)
		if (maxStack > 1) {
			int i = 0;
			while (i < h.size()) {
				int inv = inv(h.getLong(i));
				int j = i;
				while (j < h.size() && inv(h.getLong(j)) == inv)j++;
				if (j - i >= 2 && rules.canExtract(inv, key) && rules.canInsert(inv, key)) {
					work += (j - i) * 2;
					merge(key, inv, h, i, j, maxStack);
					if (moves.size() >= moveCap)return work;
				}
				i = j;
			}
		}

		// 3. strays into the home
		int hm = home[0];
		if (hm < 0)return work;
		boolean labelledOrFiltered = home[1] <= 2;
		int homePrio = rules.priority(hm);
		LongArrayList src = new LongArrayList();
		int partialRoom = 0;
		for (int i = 0; i < h.size(); i++) {
			long e = h.getLong(i);
			int inv = inv(e), s = slot(e);
			int c = counts.get(inv)[s];
			if (c <= 0)continue;
			if (inv == hm) {
				partialRoom += Math.max(0, capOf(inv, s, maxStack) - c);
				continue;
			}
			if (!rules.canExtract(inv, key))continue;
			if (!labelledOrFiltered && rules.priority(inv) > homePrio)continue;// never down the priorities
			src.add(e);
		}
		if (src.isEmpty())return work;
		work += src.size();
		// Smallest stacks first, then lower priority, then position and slot: most slots freed per item
		long[] order = src.toLongArray();
		LongArrays.quickSort(order, (x, y) -> {
			int cx = counts.get(inv(x))[slot(x)], cy = counts.get(inv(y))[slot(y)];
			if (cx != cy)return Integer.compare(cx, cy);
			int px = rules.priority(inv(x)), py = rules.priority(inv(y));
			if (px != py)return Integer.compare(px, py);
			int c = Long.compare(rules.posKey(inv(x)), rules.posKey(inv(y)));
			if (c != 0)return c;
			c = Integer.compare(inv(x), inv(y));
			return c != 0 ? c : Integer.compare(slot(x), slot(y));
		});
		int ecap = Math.min(emptyLimit.getInt(hm), maxStack);
		int openRoom = 0;
		for (long e : order) {
			int inv = inv(e), s = slot(e);
			int c = counts.get(inv)[s];
			long room = (long) partialRoom + openRoom + (long) empty.getInt(hm) * ecap;
			if (room <= 0 || moves.size() >= moveCap) {
				if (room <= 0)noRoom += c;
				continue;
			}
			int a = (int) Math.min(c, room);
			moves.add(new Move(inv, s, key, a, hm, -1));
			// the home fills partial stacks first, then the slot opened last, then new slots
			int rest = a;
			int p = Math.min(rest, partialRoom);
			partialRoom -= p;
			rest -= p;
			p = Math.min(rest, openRoom);
			openRoom -= p;
			rest -= p;
			while (rest > 0 && empty.getInt(hm) > 0) {
				empty.set(hm, empty.getInt(hm) - 1);
				freed--;
				int take = Math.min(rest, ecap);
				openRoom = ecap - take;
				rest -= take;
			}
			take(inv, s, a);
			if (a < c)noRoom += c - a;
		}
		return work;
	}

	/** Two pointers over the inventory's partial stacks of the key: the emptiest goes into the fullest. */
	private void merge(int key, int inv, LongArrayList h, int from, int to, int maxStack) {
		int[] c = counts.get(inv);
		IntArrayList partial = new IntArrayList();
		for (int i = from; i < to; i++) {
			int s = slot(h.getLong(i));
			if (c[s] > 0 && c[s] < capOf(inv, s, maxStack))partial.add(s);
		}
		if (partial.size() < 2)return;
		int[] b = partial.toIntArray();
		// fullest first, then slot order
		IntArrays.quickSort(b, (x, y) -> c[x] != c[y] ? Integer.compare(c[y], c[x]) : Integer.compare(x, y));
		int lo = 0, hi = b.length - 1;
		while (lo < hi && moves.size() < moveCap) {
			int dst = b[lo], src = b[hi];
			int room = capOf(inv, dst, maxStack) - c[dst];
			if (room <= 0) {
				lo++;
				continue;
			}
			int a = Math.min(room, c[src]);
			moves.add(new Move(inv, src, key, a, inv, dst));
			c[dst] += a;
			take(inv, src, a);
			if (c[src] <= 0)hi--;
			if (c[dst] >= capOf(inv, dst, maxStack))lo++;
		}
	}

	/** Takes {@code a} items out of a slot in the model; an emptied slot is free for later keys. */
	private void take(int inv, int slot, int a) {
		int[] c = counts.get(inv);
		c[slot] -= a;
		if (c[slot] <= 0) {
			c[slot] = 0;
			keys.get(inv)[slot] = -1;
			empty.set(inv, empty.getInt(inv) + 1);
			freed++;
		}
	}
}
