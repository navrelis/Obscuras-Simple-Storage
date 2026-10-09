package com.obscura.storage.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counter that goes up whenever something happens that can change what an inventory network is made of:
 * a cable, trim or connector changes, a block next to one changes, or a block filter is edited.
 *
 * <p>Connectors compare it with the value they last scanned at, so they only rebuild on a real change
 * (plus a slow heartbeat for changes nobody reports, like a chest that got a neighbour deep inside a network).
 * One counter for all dimensions on purpose: it is only a hint, a spurious rescan is cheap.
 */
public class NetworkChanges {
	private static final AtomicLong GENERATION = new AtomicLong();

	public static long get() {
		return GENERATION.get();
	}

	public static void bump() {
		GENERATION.incrementAndGet();
	}
}
