package com.obscura.storage.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Which networks an Inventory Link network reaches. Links are two-way and may form loops (A-B-A, A-B-C-A), so the
 * networks are walked once each, by identity, and the start network is never part of the result. A network only
 * ever merges the local inventories of the networks it reaches, never their whole (linked) storage, so a loop can't
 * make a network contain itself.
 */
public final class LinkGraph {
	private LinkGraph() {
	}

	/**
	 * Every network reachable from {@code start} over links, breadth first, without {@code start} and without duplicates.
	 *
	 * @param linked the networks directly linked to a network (may contain the network itself, nulls and duplicates)
	 */
	public static <N> List<N> reachable(N start, Function<N, ? extends Iterable<N>> linked) {
		Set<N> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		visited.add(start);
		List<N> out = new ArrayList<>();
		ArrayDeque<N> queue = new ArrayDeque<>();
		queue.add(start);
		while (!queue.isEmpty()) {
			N n = queue.poll();
			Iterable<N> next = linked.apply(n);
			if (next == null)continue;
			for (N o : next) {
				if (o == null || !visited.add(o))continue;
				out.add(o);
				queue.add(o);
			}
		}
		return out;
	}
}
