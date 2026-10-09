package com.obscura.storage.util;

import java.util.function.IntSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Comparator output of a block entity: the block reads {@link Source#getComparatorSignal} (see
 * {@link #at}), the block entity keeps one of these and calls {@link #tick} from its own throttled
 * tick to tell the neighbours when the value changed.
 */
public final class ComparatorOutput {
	private int last;

	/** Implemented by block entities whose block exposes an analog (comparator) output. */
	public interface Source {
		/** The current signal, 0 to 15. Must be cheap: cached, or at most a short list. */
		int getComparatorSignal();
	}

	/** The comparator signal of the block entity at {@code pos}, 0 when it has none. */
	public static int at(BlockGetter level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof Source s ? s.getComparatorSignal() : 0;
	}

	/**
	 * Re-reads the signal and updates the neighbours when it changed. Nothing is computed when no
	 * comparator could read this block, a comparator placed later reads the value itself.
	 */
	public void tick(Level level, BlockPos pos, IntSupplier signal) {
		if (hasListener(level, pos))update(level, pos, signal.getAsInt());
	}

	/** Updates the neighbours when {@code signal} differs from the last one sent. */
	public void update(Level level, BlockPos pos, int signal) {
		if (signal == last)return;
		last = signal;
		level.updateNeighbourForOutputSignal(pos, level.getBlockState(pos).getBlock());
	}

	/**
	 * Whether a comparator sits where {@link Level#updateNeighbourForOutputSignal} would update it:
	 * next to {@code pos}, or behind a solid block, in the four horizontal directions.
	 */
	public static boolean hasListener(Level level, BlockPos pos) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos p = pos.relative(d);
			if (!level.isLoaded(p))continue;
			BlockState s = level.getBlockState(p);
			if (s.getBlock() instanceof ComparatorBlock)return true;
			if (s.isRedstoneConductor(level, p)) {
				p = p.relative(d);
				if (level.isLoaded(p) && level.getBlockState(p).getBlock() instanceof ComparatorBlock)return true;
			}
		}
		return false;
	}
}
