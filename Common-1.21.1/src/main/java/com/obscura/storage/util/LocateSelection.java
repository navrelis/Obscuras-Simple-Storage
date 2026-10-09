package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

import net.minecraft.core.BlockPos;

/**
 * Which of the located inventories the Item Locator sends to the client: only the player's dimension,
 * nearest first, capped, each inventory once. Pure data, so it needs no running game.
 */
public final class LocateSelection {
	/** Positions to highlight (nearest first, at most the cap) and the counts before the cap. */
	public static record Result(List<BlockPos> positions, int inDimension, int otherDimensions) {}

	private LocateSelection() {}

	/**
	 * Splits {@code located} into the inventories in {@code dimension} and the others, and keeps the
	 * {@code cap} positions nearest to {@code origin}. An entry given twice counts once.
	 *
	 * @param pos the block of an entry
	 * @param dim the dimension of an entry, compared with {@code dimension} by equals
	 */
	public static <T> Result select(Collection<T> located, Function<T, BlockPos> pos, Function<T, ?> dim, Object dimension, BlockPos origin, int cap) {
		List<BlockPos> here = new ArrayList<>();
		int other = 0;
		for (T l : new LinkedHashSet<>(located)) {
			if (dim.apply(l).equals(dimension))here.add(pos.apply(l));
			else other++;
		}
		int inDim = here.size();
		here.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
		if (here.size() > cap)here = new ArrayList<>(here.subList(0, cap));
		return new Result(here, inDim, other);
	}
}
