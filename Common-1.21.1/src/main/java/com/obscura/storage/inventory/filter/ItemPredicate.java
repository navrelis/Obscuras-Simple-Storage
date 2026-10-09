package com.obscura.storage.inventory.filter;

import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.item.ItemStack;

import com.obscura.storage.inventory.StoredItemStack;

public interface ItemPredicate {
	/** {@link #stateVersion()} of a predicate that cannot tell when its answers change. */
	long UNVERSIONED = Long.MIN_VALUE;

	boolean test(StoredItemStack stack);

	default boolean test(ItemStack stack) {
		return test(new StoredItemStack(stack, stack.getCount()));
	}

	default void updateState() {}

	/**
	 * A value that changes whenever {@link #test} may answer differently for the same stack, read
	 * after {@link #updateState()}. Callers that cache test() results per slot compare it and drop
	 * the cache when it differs. Values are taken from {@link #nextStateVersion()}, so a replaced
	 * predicate never reuses an older value, and sums of versions (see {@code MultiFilter}) only grow.
	 *
	 * <p>The default, {@link #UNVERSIONED}, disables such caching: test() is re-evaluated every time.
	 */
	default long stateVersion() {
		return UNVERSIONED;
	}

	/** A fresh, globally increasing, positive {@link #stateVersion()} value. */
	static long nextStateVersion() {
		return StateVersions.SEQ.incrementAndGet();
	}

	final class StateVersions {
		private static final AtomicLong SEQ = new AtomicLong();

		private StateVersions() {
		}
	}
}
