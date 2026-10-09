package com.obscura.storage.util;

import java.util.function.Consumer;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;

/**
 * Forge counterparts of the Fabric Transfer API helpers this mod uses (lookup, iteration, insert,
 * extract, move) on {@link IItemHandler}.
 *
 * <p>Merged network storages and exact delegates ({@link Delegating}) are walked part by part, so
 * nothing goes through the global slot numbers of a {@link MergedStorage} and block filters are
 * read without copying stacks. Server thread only.
 */
public final class ItemHandlers {
	private ItemHandlers() {
	}

	/** A handler that forwards everything unchanged to another one (proxies, cable connectors, link views). */
	public interface Delegating {
		@Nullable
		IItemHandler getDelegate();
	}

	/** Sees one slot: the live stack (do not modify) and the amount the handler shows of it. */
	@FunctionalInterface
	public interface StackVisitor {
		/** @return false to stop */
		boolean visit(ItemStack stack, int count);
	}

	/**
	 * The item handler at {@code pos} seen from {@code side}: the block entity's capability, otherwise
	 * a vanilla container there (composter, container entities like minecart chests).
	 */
	@Nullable
	public static IItemHandler find(Level level, BlockPos pos, @Nullable Direction side) {
		BlockEntity be = level.getBlockEntity(pos);
		if(be != null) {
			IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
			if(h != null)return h;
		}
		Container inv = HopperBlockEntity.getContainerAt(level, pos);
		if(inv == null)return null;
		if(inv instanceof WorldlyContainer w && side != null)return new SidedInvWrapper(w, side);
		return new InvWrapper(inv);
	}

	/** The capability of {@code be} only (no container fallback). */
	@Nullable
	public static IItemHandler ofBlockEntity(BlockEntity be, @Nullable Direction side) {
		return be.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
	}

	/** Visits every slot of {@code h}; merged storages part by part. */
	public static boolean forEach(IItemHandler h, StackVisitor v) {
		if(h == null)return true;
		if(h instanceof MergedStorage m) {
			if(!m.enter())return true;
			try {
				for (int i = 0; i < m.parts.size(); i++) {
					if(!forEach(m.parts.get(i), v))return false;
				}
			} finally {
				m.exit();
			}
			return true;
		}
		if(h instanceof Delegating d) {
			return forEach(d.getDelegate(), v);
		}
		if(h instanceof LabelledStorage l) {
			// A frame label only limits inserting: everything is read as it is
			return forEach(l.getBacking(), v);
		}
		if(h instanceof FilteredStorage f) {
			IItemHandler b = f.getBacking();
			int n = b.getSlots();
			for (int i = 0; i < n; i++) {
				ItemStack s = b.getStackInSlot(i);
				if(!v.visit(s, f.visibleCount(s)))return false;
			}
			return true;
		}
		int n = h.getSlots();
		for (int i = 0; i < n; i++) {
			ItemStack s = h.getStackInSlot(i);
			if(!v.visit(s, s.getCount()))return false;
		}
		return true;
	}

	/** Amount of {@code key} in {@code h}, stops counting at {@code limit}. */
	public static long count(IItemHandler h, ItemKey key, long limit) {
		long[] total = new long[1];
		forEach(h, (s, c) -> {
			if(c > 0 && key.matches(s)) {
				total[0] += c;
				return total[0] < limit;
			}
			return true;
		});
		return total[0];
	}

	/**
	 * Inserts {@code stack} (any count up to its max stack size), existing stacks first, part by part.
	 *
	 * @return what did not fit
	 */
	public static ItemStack insert(IItemHandler h, ItemStack stack, boolean simulate) {
		if(h == null || stack.isEmpty())return stack;
		if(h instanceof MergedStorage m) {
			if(!m.enter())return stack;
			try {
				for (int i = 0; i < m.parts.size() && !stack.isEmpty(); i++) {
					stack = insert(m.parts.get(i), stack, simulate);
				}
			} finally {
				m.exit();
			}
			return stack;
		}
		if(h instanceof Delegating d) {
			return insert(d.getDelegate(), stack, simulate);
		}
		if(h instanceof LabelledStorage l) {
			return l.accepts(stack) ? insert(l.getBacking(), stack, simulate) : stack;
		}
		return ItemHandlerHelper.insertItemStacked(h, stack, simulate);
	}

	/**
	 * Inserts {@code amount} items like {@code template} in max-stack-size chunks.
	 *
	 * @return the amount inserted
	 */
	public static long insert(IItemHandler h, ItemStack template, long amount) {
		if(h == null || template.isEmpty() || amount <= 0)return 0;
		int max = Math.max(1, template.getMaxStackSize());
		long inserted = 0;
		while(inserted < amount) {
			int chunk = (int) Math.min(amount - inserted, max);
			ItemStack rem = insert(h, template.copyWithCount(chunk), false);
			int ins = chunk - rem.getCount();
			inserted += ins;
			if(ins < chunk)break;
		}
		return inserted;
	}

	/**
	 * Extracts up to {@code max} items matching {@code key}, parts in order (higher priority first).
	 *
	 * @return the amount extracted
	 */
	public static long extract(IItemHandler h, ItemKey key, long max, boolean simulate) {
		if(h == null || max <= 0 || key.isBlank())return 0;
		if(h instanceof MergedStorage m) {
			if(!m.enter())return 0;
			long got = 0;
			try {
				for (int i = 0; i < m.parts.size() && got < max; i++) {
					got += extract(m.parts.get(i), key, max - got, simulate);
				}
			} finally {
				m.exit();
			}
			return got;
		}
		if(h instanceof Delegating d) {
			return extract(d.getDelegate(), key, max, simulate);
		}
		if(h instanceof LabelledStorage l) {
			return extract(l.getBacking(), key, max, simulate);
		}
		long got = 0;
		int n = h.getSlots();
		for (int i = 0; i < n && got < max; i++) {
			ItemStack s = h.getStackInSlot(i);
			if(s.isEmpty() || !key.matches(s))continue;
			while(got < max) {
				ItemStack ex = h.extractItem(i, (int) Math.min(max - got, Integer.MAX_VALUE), simulate);
				if(ex.isEmpty() || !key.matches(ex))break;
				got += ex.getCount();
				// A simulation doesn't change the slot, asking again would count it twice
				if(simulate)break;
			}
		}
		return got;
	}

	/**
	 * Moves up to {@code max} items that pass {@code filter} from {@code from} to {@code to}, like the
	 * Fabric {@code StorageUtil.move}: every candidate stack is simulated first and only the amount the
	 * target accepts is extracted. An item the target refused after all is put back, or handed to
	 * {@code overflow} when it can't be.
	 *
	 * @return the amount moved
	 */
	public static int move(IItemHandler from, IItemHandler to, Predicate<ItemStack> filter, int max, Consumer<ItemStack> overflow) {
		if(from == null || to == null || max <= 0)return 0;
		return move0(from, from, to, filter, max, overflow);
	}

	private static int move0(IItemHandler root, IItemHandler from, IItemHandler to, Predicate<ItemStack> filter, int max, Consumer<ItemStack> overflow) {
		if(from == null)return 0;
		if(from instanceof MergedStorage m) {
			if(!m.enter())return 0;
			int moved = 0;
			try {
				for (int i = 0; i < m.parts.size() && moved < max; i++) {
					moved += move0(root, m.parts.get(i), to, filter, max - moved, overflow);
				}
			} finally {
				m.exit();
			}
			return moved;
		}
		if(from instanceof Delegating d) {
			return move0(root, d.getDelegate(), to, filter, max, overflow);
		}
		if(from instanceof LabelledStorage l) {
			// Taking out is not labelled, and a refused item goes back where it came from
			return move0(root, l.getBacking(), to, filter, max, overflow);
		}
		int moved = 0;
		int n = from.getSlots();
		for (int i = 0; i < n && moved < max; i++) {
			ItemStack s = from.getStackInSlot(i);
			if(s.isEmpty() || !filter.test(s))continue;
			ItemStack sim = from.extractItem(i, max - moved, true);
			if(sim.isEmpty())continue;
			ItemStack rem = insert(to, sim, true);
			int can = sim.getCount() - rem.getCount();
			if(can <= 0)continue;
			ItemStack ex = from.extractItem(i, can, false);
			if(ex.isEmpty())continue;
			ItemStack left = insert(to, ex, false);
			moved += ex.getCount() - left.getCount();
			if(!left.isEmpty()) {
				left = from.insertItem(i, left, false);
				if(!left.isEmpty())left = insert(root, left, false);
				if(!left.isEmpty())overflow.accept(left);
			}
		}
		return moved;
	}
}
