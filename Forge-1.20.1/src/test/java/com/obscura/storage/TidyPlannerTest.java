package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.tidy.TidyPlanner;
import com.obscura.storage.tidy.TidyPlanner.Move;

/** The Tidy planner on pure data: home choice, merges, strays, priorities, exclusions, bounds. */
@DisplayName("Tidy planner")
public class TidyPlannerTest {
	static final int STONE = 0, DIRT = 1, IRON = 2, SWORD = 3;

	/** A made-up network: inventories of slots, plus the rules the planner asks for. */
	static final class Net implements TidyPlanner.Rules {
		final List<int[]> keys = new ArrayList<>(), counts = new ArrayList<>(), limits = new ArrayList<>();
		final List<Integer> prio = new ArrayList<>();
		final List<Long> pos = new ArrayList<>();
		final Map<Integer, Set<Integer>> labels = new HashMap<>(), allow = new HashMap<>(), noInsert = new HashMap<>(), noExtract = new HashMap<>();
		final Map<Integer, Integer> maxStack = new HashMap<>();

		/** An inventory of {@code size} slots; {@code contents} are (key, count) pairs filling the first slots. */
		int inv(int priority, long posKey, int size, int... contents) {
			int[] k = new int[size], c = new int[size], l = new int[size];
			java.util.Arrays.fill(k, -1);
			java.util.Arrays.fill(l, 64);
			for (int i = 0; i < contents.length / 2; i++) {
				k[i] = contents[i * 2];
				c[i] = contents[i * 2 + 1];
			}
			keys.add(k);
			counts.add(c);
			limits.add(l);
			prio.add(priority);
			pos.add(posKey);
			return keys.size() - 1;
		}

		Net label(int inv, int... ks) {
			for (int k : ks)labels.computeIfAbsent(inv, x -> new HashSet<>()).add(k);
			return this;
		}

		Net allow(int inv, int... ks) {
			for (int k : ks)allow.computeIfAbsent(inv, x -> new HashSet<>()).add(k);
			return this;
		}

		Net denyInsert(int inv, int... ks) {
			for (int k : ks)noInsert.computeIfAbsent(inv, x -> new HashSet<>()).add(k);
			return this;
		}

		Net denyExtract(int inv, int... ks) {
			for (int k : ks)noExtract.computeIfAbsent(inv, x -> new HashSet<>()).add(k);
			return this;
		}

		private static boolean in(Map<Integer, Set<Integer>> m, int inv, int key) {
			Set<Integer> s = m.get(inv);
			return s != null && s.contains(key);
		}

		@Override public int priority(int inv) { return prio.get(inv); }
		@Override public long posKey(int inv) { return pos.get(inv); }
		@Override public boolean canInsert(int inv, int key) { return !in(noInsert, inv, key) && (!labels.containsKey(inv) || in(labels, inv, key)) && (!allow.containsKey(inv) || in(allow, inv, key)); }
		@Override public boolean canExtract(int inv, int key) { return !in(noExtract, inv, key); }
		@Override public boolean labelled(int inv, int key) { return in(labels, inv, key); }
		@Override public boolean filtered(int inv, int key) { return in(allow, inv, key); }
		@Override public int maxStack(int key) { return maxStack.getOrDefault(key, 64); }

		TidyPlanner planner(int cap) {
			TidyPlanner p = new TidyPlanner(this, cap);
			for (int i = 0; i < keys.size(); i++) {
				p.addInventory(keys.get(i).clone(), counts.get(i).clone(), limits.get(i).clone(), labels.containsKey(i), allow.containsKey(i));
			}
			return p;
		}

		TidyPlanner plan() {
			return planner(1_000_000).planAll();
		}

		/**
		 * Runs the moves on these slot arrays, the way the game does (partial stacks first, then empty
		 * slots in slot order), and checks that each one fits completely.
		 */
		void apply(List<Move> moves) {
			for (Move m : moves) {
				int[] sk = keys.get(m.srcInv()), sc = counts.get(m.srcInv());
				assertEquals(m.key(), sk[m.srcSlot()], "source slot holds the planned item: " + m);
				assertTrue(sc[m.srcSlot()] >= m.amount(), "source slot holds enough: " + m);
				sc[m.srcSlot()] -= m.amount();
				if (sc[m.srcSlot()] == 0)sk[m.srcSlot()] = -1;
				int[] dk = keys.get(m.dstInv()), dc = counts.get(m.dstInv()), dl = limits.get(m.dstInv());
				int cap = maxStack(m.key());
				int left = m.amount();
				if (m.isMerge()) {
					int s = m.dstSlot();
					assertTrue(dk[s] == m.key() || dk[s] == -1, "merge target holds the item: " + m);
					int room = Math.min(dl[s], cap) - dc[s];
					assertTrue(room >= left, "merge fits: " + m);
					dk[s] = m.key();
					dc[s] += left;
					continue;
				}
				for (int pass = 0; pass < 2 && left > 0; pass++) {
					for (int s = 0; s < dk.length && left > 0; s++) {
						if (pass == 0 ? dk[s] != m.key() : dk[s] != -1)continue;
						int room = Math.min(dl[s], cap) - dc[s];
						int a = Math.min(room, left);
						if (a <= 0)continue;
						dk[s] = m.key();
						dc[s] += a;
						left -= a;
					}
				}
				assertEquals(0, left, "everything planned into the home fits: " + m);
			}
		}

		long total(int key) {
			long n = 0;
			for (int i = 0; i < keys.size(); i++) {
				for (int s = 0; s < keys.get(i).length; s++)if (keys.get(i)[s] == key)n += counts.get(i)[s];
			}
			return n;
		}

		long totalIn(int inv, int key) {
			long n = 0;
			for (int s = 0; s < keys.get(inv).length; s++)if (keys.get(inv)[s] == key)n += counts.get(inv)[s];
			return n;
		}

		int usedSlots() {
			int n = 0;
			for (int[] k : keys)for (int x : k)if (x >= 0)n++;
			return n;
		}

		int slotsOf(int inv, int key) {
			int n = 0;
			for (int x : keys.get(inv))if (x == key)n++;
			return n;
		}
	}

	@Test
	@DisplayName("Home: a labelled inventory, then an allow-list filter, then the one holding the most")
	void homeChoiceTiers() {
		Net n = new Net();
		int most = n.inv(0, 1, 9, STONE, 40);
		int filtered = n.inv(0, 2, 9, STONE, 5);
		int labelled = n.inv(0, 3, 9);
		n.allow(filtered, STONE).label(labelled, STONE);
		TidyPlanner p = n.plan();
		assertEquals(labelled, p.homeOf(STONE), "label first");
		n.apply(p.moves());
		assertEquals(45, n.totalIn(labelled, STONE));

		Net n2 = new Net();
		n2.inv(0, 1, 9, STONE, 40);
		int f2 = n2.inv(0, 2, 9, STONE, 5);
		n2.allow(f2, STONE);
		TidyPlanner p2 = n2.plan();
		assertEquals(f2, p2.homeOf(STONE), "then the allow-list filter");
		n2.apply(p2.moves());
		assertEquals(45, n2.totalIn(f2, STONE));

		Net n3 = new Net();
		int m3 = n3.inv(0, 2, 9, STONE, 40);
		n3.inv(0, 1, 9, STONE, 5);
		TidyPlanner p3 = n3.plan();
		assertEquals(m3, p3.homeOf(STONE), "else the one holding the most");
		n3.apply(p3.moves());
		assertEquals(45, n3.totalIn(m3, STONE));
	}

	@Test
	@DisplayName("Home ties: higher priority, then lower position")
	void homeTies() {
		Net n = new Net();
		n.inv(0, 1, 9, STONE, 20);
		int high = n.inv(1, 2, 9, STONE, 20);
		assertEquals(high, n.plan().homeOf(STONE), "same amount: higher priority");

		Net n2 = new Net();
		n2.inv(0, 5, 9, STONE, 20);
		int low = n2.inv(0, 2, 9, STONE, 20);
		assertEquals(low, n2.plan().homeOf(STONE), "same amount and priority: lower position");

		Net n3 = new Net();
		int a = n3.inv(0, 1, 9, STONE, 1);
		int b = n3.inv(1, 2, 9);
		n3.label(a, STONE).label(b, STONE);
		assertEquals(b, n3.plan().homeOf(STONE), "two labels: higher priority before more held");

		Net n4 = new Net();
		int c = n4.inv(0, 9, 9, STONE, 3);
		n4.inv(0, 1, 9, STONE, 1);
		n4.label(c, STONE).label(1, STONE);
		assertEquals(c, n4.plan().homeOf(STONE), "two labels, same priority: more held");
	}

	@Test
	@DisplayName("Partial stacks inside one inventory are merged, emptiest into fullest")
	void mergePartials() {
		Net n = new Net();
		int inv = n.inv(0, 1, 9, STONE, 10, STONE, 20, STONE, 30, DIRT, 64);
		TidyPlanner p = n.plan();
		assertEquals(2, p.moves().size(), "two merge moves");
		assertTrue(p.moves().stream().allMatch(Move::isMerge));
		assertEquals(2, p.predictedFreed());
		int before = n.usedSlots();
		n.apply(p.moves());
		assertEquals(before - 2, n.usedSlots());
		assertEquals(1, n.slotsOf(inv, STONE));
		assertEquals(60, n.totalIn(inv, STONE));
	}

	@Test
	@DisplayName("Strays go into the home until it is full, smallest first; leftovers stay")
	void straysUntilFull() {
		Net n = new Net();
		// Home: 60 stone plus one empty slot, the rest full of dirt -> room for 4 + 64 stone
		int home = n.inv(0, 1, 5, STONE, 60, DIRT, 64, DIRT, 64, DIRT, 64);
		int a = n.inv(0, 2, 3, STONE, 10);
		int b = n.inv(0, 3, 3, STONE, 20);
		int c = n.inv(0, 4, 3, STONE, 40);
		TidyPlanner p = n.plan();
		assertEquals(home, p.homeOf(STONE));
		assertEquals(2, p.noRoom(), "2 stone had no room");
		n.apply(p.moves());
		assertEquals(0, n.totalIn(a, STONE));
		assertEquals(0, n.totalIn(b, STONE));
		assertEquals(2, n.totalIn(c, STONE), "the leftover stays where it was");
		assertEquals(128, n.totalIn(home, STONE));
		assertEquals(130, n.total(STONE));
		// a and b freed a slot each, the home used its empty slot, c kept its slot
		assertEquals(1, p.predictedFreed());
	}

	@Test
	@DisplayName("Never down the priorities, unless the home is labelled or filtered for the item")
	void priorityRule() {
		Net n = new Net();
		int home = n.inv(0, 1, 9, STONE, 30);
		int high = n.inv(1, 2, 9, STONE, 10);
		TidyPlanner p = n.plan();
		assertEquals(home, p.homeOf(STONE));
		assertTrue(p.moves().isEmpty(), "nothing moves out of the higher priority inventory");

		Net n2 = new Net();
		int home2 = n2.inv(0, 1, 9, STONE, 30);
		int high2 = n2.inv(1, 2, 9, STONE, 10);
		n2.label(home2, STONE);
		n2.apply(n2.plan().moves());
		assertEquals(0, n2.totalIn(high2, STONE), "a labelled home takes it anyway");
		assertEquals(40, n2.totalIn(home2, STONE));

		Net n3 = new Net();
		int home3 = n3.inv(1, 1, 9, STONE, 30);
		int low3 = n3.inv(-1, 2, 9, STONE, 10);
		n3.apply(n3.plan().moves());
		assertEquals(0, n3.totalIn(low3, STONE), "up the priorities is fine");
		assertEquals(40, n3.totalIn(home3, STONE));
	}

	@Test
	@DisplayName("Exclusions: no extraction (keep-last, filters), no insertion (filters, labels)")
	void exclusions() {
		Net n = new Net();
		int most = n.inv(0, 1, 9, STONE, 50);
		int keep = n.inv(0, 2, 9, STONE, 10, STONE, 5);
		int other = n.inv(0, 3, 9, STONE, 20);
		n.denyExtract(keep, STONE);
		n.denyInsert(most, STONE);
		TidyPlanner p = n.plan();
		assertEquals(other, p.homeOf(STONE), "the inventory holding the most refuses stone, the next one is home");
		n.apply(p.moves());
		assertEquals(15, n.totalIn(keep, STONE), "nothing taken out of the keep-last inventory, nor merged there");
		assertEquals(2, n.slotsOf(keep, STONE));
		assertEquals(0, n.totalIn(most, STONE), "the inventory refusing stone still gives it away");
		assertEquals(70, n.totalIn(other, STONE), "home");
		assertFalse(p.moves().stream().anyMatch(m -> m.dstInv() == most), "nothing goes into it");

		Net n2 = new Net();
		int labelledIron = n2.inv(0, 1, 9, STONE, 50, IRON, 3);
		int plain = n2.inv(0, 2, 9, STONE, 5);
		n2.label(labelledIron, IRON);
		TidyPlanner p2 = n2.plan();
		assertEquals(plain, p2.homeOf(STONE), "a label for iron refuses stone, even when it holds the most");
		n2.apply(p2.moves());
		assertEquals(55, n2.totalIn(plain, STONE), "stray stone leaves the iron chest");
	}

	@Test
	@DisplayName("Unstackable items are never merged")
	void unstackables() {
		Net n = new Net();
		n.maxStack.put(SWORD, 1);
		int a = n.inv(0, 1, 9, SWORD, 1, SWORD, 1, SWORD, 1);
		int b = n.inv(0, 2, 9, SWORD, 1);
		TidyPlanner p = n.plan();
		assertTrue(p.moves().stream().noneMatch(Move::isMerge));
		n.apply(p.moves());
		assertEquals(4, n.totalIn(a, SWORD));
		assertEquals(0, n.totalIn(b, SWORD));
	}

	@Test
	@DisplayName("Random networks: every move fits, totals stay, predicted freed slots are exact")
	void conservation() {
		Random r = new Random(1234);
		for (int round = 0; round < 50; round++) {
			Net n = new Net();
			int invs = 2 + r.nextInt(8);
			int keys = 1 + r.nextInt(6);
			for (int i = 0; i < invs; i++) {
				int size = 3 + r.nextInt(27);
				int fill = r.nextInt(size + 1);
				int[] c = new int[fill * 2];
				for (int s = 0; s < fill; s++) {
					c[s * 2] = r.nextInt(keys);
					c[s * 2 + 1] = 1 + r.nextInt(64);
				}
				n.inv(r.nextInt(3) - 1, r.nextInt(1000), size, c);
			}
			long[] before = new long[keys];
			for (int k = 0; k < keys; k++)before[k] = n.total(k);
			int used = n.usedSlots();
			TidyPlanner p = n.plan();
			n.apply(p.moves());
			for (int k = 0; k < keys; k++)assertEquals(before[k], n.total(k), "round " + round + " key " + k);
			assertEquals(used - p.predictedFreed(), n.usedSlots(), "round " + round + ": predicted freed slots");
			assertTrue(p.predictedFreed() >= 0, "round " + round + ": tidying never takes more slots");
		}
	}

	@Test
	@DisplayName("Huge network: planned in bounded steps, the move cap holds")
	void bounded() {
		Random r = new Random(42);
		Net n = new Net();
		for (int i = 0; i < 5000; i++) {
			int[] c = new int[27 * 2];
			for (int s = 0; s < 27; s++) {
				c[s * 2] = r.nextInt(500);
				c[s * 2 + 1] = 1 + r.nextInt(64);
			}
			n.inv(0, i, 54, c);
		}
		int cap = 4800;
		TidyPlanner p = n.planner(cap);
		assertEquals(270_000, p.slotsIndexed());
		int steps = 0;
		long worst = 0;
		while (true) {
			long t0 = System.nanoTime();
			boolean done = p.step(4096);
			worst = Math.max(worst, System.nanoTime() - t0);
			steps++;
			if (done)break;
			assertTrue(steps < 10_000, "planning must end");
		}
		assertTrue(p.moves().size() <= cap, "move cap");
		assertTrue(p.isTruncated(), "a network this messy needs more moves than one run allows");
		assertTrue(steps > 1, "spread over several ticks");
		System.out.println("Tidy planner, 5000 inventories, 270k slots: " + steps + " steps, worst step " + worst / 1_000_000.0 + " ms");

		// Without the cap every key is planned, still in bounded steps
		TidyPlanner all = n.planner(Integer.MAX_VALUE);
		steps = 0;
		worst = 0;
		long sum = 0;
		while (true) {
			long t0 = System.nanoTime();
			boolean done = all.step(4096);
			long dt = System.nanoTime() - t0;
			sum += dt;
			worst = Math.max(worst, dt);
			steps++;
			if (done)break;
			assertTrue(steps < 10_000, "planning must end");
		}
		assertFalse(all.isTruncated());
		assertTrue(steps > 10, "spread over many ticks: " + steps);
		System.out.println("Tidy planner, 5000 inventories, no cap: " + steps + " steps, " + all.moves().size() + " moves, average step " + sum / steps / 1_000_000.0 + " ms, worst step " + worst / 1_000_000.0 + " ms");
	}
}
