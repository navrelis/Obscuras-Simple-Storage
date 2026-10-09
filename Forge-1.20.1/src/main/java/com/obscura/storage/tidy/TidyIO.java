package com.obscura.storage.tidy;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.minecraftforge.items.IItemHandler;

import com.obscura.storage.StorageMod;
import com.obscura.storage.util.FilteredStorage;
import com.obscura.storage.util.IProxy;
import com.obscura.storage.util.ItemHandlers;
import com.obscura.storage.util.ItemKey;
import com.obscura.storage.util.LabelledStorage;
import com.obscura.storage.util.MergedStorage;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/**
 * Tidy's slot level access on Forge (IItemHandler). Same class and method names as the Fabric twin.
 *
 * <p>A network member is an {@code IItemHandler}: the block's own inventory, behind the block filters
 * ({@link FilteredStorage}) and Frame Label ({@link LabelledStorage}) the network applies.
 *
 * <p>IItemHandler has no transactions, so a move is: simulate the extract, simulate the insert slot by
 * slot (partial stacks first, then empty slots), then extract exactly what fits and insert it. Should a
 * handler take less than it promised, the rest goes back into the source slot, else into the source
 * inventory, else the destination, else the network; only when every one of them refuses (a broken
 * handler) is it dropped at the source block, with a warning. It is never voided. Extraction and
 * insertion go through the network member's own handler, so block filters, keep-last and Frame Labels
 * apply.
 */
public final class TidyIO {
	private TidyIO() {}

	/** Item keys of this loader: an item plus its NBT (immutable, see {@link ItemKey}). */
	public static Object2IntMap<Object> newKeyMap() {
		Object2IntOpenHashMap<Object> m = new Object2IntOpenHashMap<>();
		m.defaultReturnValue(-1);
		return m;
	}

	/** A key that may be kept (the key is immutable). */
	public static Object freeze(Object key) {
		return key;
	}

	/** One item of {@code key}. */
	public static ItemStack prototype(Object key) {
		return ((ItemKey) key).toStack();
	}

	/**
	 * Walks the wrapper chain of a network member down to the block's own inventory, collecting the block
	 * filters and Frame Labels on the way (into {@code out}, when given).
	 *
	 * @return the inventory, null when the chain does not end in a plain block inventory (a proxy, an
	 *         interface or anything leading into a network)
	 */
	public static Object unwrap(Object part, TidyMember.Chain out) {
		Object a = part;
		for (int depth = 0; depth < 16 && a != null; depth++) {
			if (a instanceof FilteredStorage f) {
				if (out != null)out.addFilter(f.getFilter(), f.isKeepLast(), f.getFilterPriority());
				a = f.getBacking();
			} else if (a instanceof LabelledStorage l) {
				if (out != null)out.labels.add(l.getLabel());
				a = l.getBacking();
			} else {
				break;
			}
		}
		if (!(a instanceof IItemHandler) || a instanceof IProxy || a instanceof ItemHandlers.Delegating || a instanceof MergedStorage)return null;
		return a;
	}

	private static IItemHandler raw(Object part) {
		return (IItemHandler) unwrap(part, null);
	}

	/**
	 * Reads every slot of the inventory behind the network member {@code part}.
	 *
	 * @return the slot count, -1 when the inventory is gone
	 */
	public static int snapshot(Object part, SlotSink sink) {
		IItemHandler h = raw(part);
		if (h == null)return -1;
		int n = h.getSlots();
		for (int i = 0; i < n; i++) {
			ItemStack s = h.getStackInSlot(i);
			int limit = Math.max(0, h.getSlotLimit(i));
			if (s.isEmpty())sink.slot(i, null, 0, limit);
			else sink.slot(i, ItemKey.of(s), s.getCount(), limit);
		}
		return n;
	}

	/**
	 * Moves up to {@code amount} items from slot {@code srcSlot} of network member {@code srcPart} into
	 * member {@code dstPart} (slot {@code dstSlot}, or -1 for partial stacks first, then empty slots). The
	 * source slot must still hold {@code expect}'s item and NBT.
	 *
	 * @param overflow the network, the last place a remainder can go
	 */
	public static MoveResult move(TidyMember src, Object srcPart, int srcSlot, ItemStack expect, int amount,
			TidyMember dst, Object dstPart, int dstSlot, Object overflow) {
		if (amount <= 0)return MoveResult.NONE;
		IItemHandler srcRaw = raw(srcPart), dstRaw = raw(dstPart);
		if (srcRaw == null || dstRaw == null)return MoveResult.NONE;
		IItemHandler sh = (IItemHandler) srcPart, dh = (IItemHandler) dstPart;
		if (srcSlot < 0 || srcSlot >= sh.getSlots() || dstSlot >= dh.getSlots())return MoveResult.NONE;
		ItemStack cur = sh.getStackInSlot(srcSlot);
		if (cur.isEmpty() || !ItemStack.isSameItemSameTags(cur, expect))return MoveResult.NONE;
		int want = Math.min(amount, cur.getCount());
		ItemStack sim = sh.extractItem(srcSlot, want, true);
		if (sim.isEmpty() || !ItemStack.isSameItemSameTags(sim, expect))return MoveResult.NONE;

		// Simulated insertion, slot by slot: where and how much
		int total = sim.getCount();
		int[] slots, amounts;
		int n = 0;
		if (dstSlot >= 0) {
			slots = new int[] {dstSlot};
			amounts = new int[1];
			int took = total - dh.insertItem(dstSlot, sim.copy(), true).getCount();
			if (took > 0)amounts[n++] = took;
		} else {
			int size = dh.getSlots();
			slots = new int[size];
			amounts = new int[size];
			int left = total;
			for (int pass = 0; pass < 2 && left > 0; pass++) {
				for (int i = 0; i < size && left > 0; i++) {
					ItemStack in = dh.getStackInSlot(i);
					boolean partial = !in.isEmpty() && ItemStack.isSameItemSameTags(in, expect);
					if (pass == 0 ? !partial : !in.isEmpty())continue;
					int took = left - dh.insertItem(i, sim.copyWithCount(left), true).getCount();
					if (took > 0) {
						slots[n] = i;
						amounts[n++] = took;
						left -= took;
					}
				}
			}
		}
		int planned = 0;
		for (int i = 0; i < n; i++)planned += amounts[i];
		planned = Math.min(planned, total);
		if (planned <= 0)return MoveResult.NONE;

		// Execute: exactly what fits
		ItemStack ex = sh.extractItem(srcSlot, planned, false);
		if (ex.isEmpty())return MoveResult.NONE;
		int moved = 0, filled = 0;
		ItemStack left = ex.copy();
		for (int i = 0; i < n && !left.isEmpty(); i++) {
			int c = Math.min(amounts[i], left.getCount());
			boolean wasEmpty = dh.getStackInSlot(slots[i]).isEmpty();
			ItemStack r = dh.insertItem(slots[i], left.copyWithCount(c), false);
			int in = c - r.getCount();
			if (in > 0) {
				left.shrink(in);
				moved += in;
				if (wasEmpty)filled++;
			}
		}
		if (!left.isEmpty()) {
			StorageMod.LOGGER.debug("Tidy: {} took less than simulated, returning {}", dst.pos, left);
			left = putBack(left, sh, srcRaw, srcSlot, dh, overflow);
			if (!left.isEmpty())drop(src, left);
		}
		boolean emptied = srcRaw.getStackInSlot(srcSlot).isEmpty();
		return new MoveResult(moved, filled, emptied);
	}

	/** Returns a remainder: source slot, source inventory, destination, network. What is left could not go anywhere. */
	private static ItemStack putBack(ItemStack left, IItemHandler sh, IItemHandler srcRaw, int srcSlot, IItemHandler dh, Object overflow) {
		left = sh.insertItem(srcSlot, left, false);
		if (!left.isEmpty())left = srcRaw.insertItem(srcSlot, left, false);
		for (int i = 0; i < srcRaw.getSlots() && !left.isEmpty(); i++)left = srcRaw.insertItem(i, left, false);
		for (int i = 0; i < dh.getSlots() && !left.isEmpty(); i++)left = dh.insertItem(i, left, false);
		if (!left.isEmpty() && overflow instanceof IItemHandler net)left = ItemHandlers.insert(net, left, false);
		return left;
	}

	/** Last resort, never reached with well-behaved handlers: the items land next to the source block. */
	private static void drop(TidyMember src, ItemStack stack) {
		Level l = src.level();
		BlockPos p = src.pos;
		StorageMod.LOGGER.warn("Tidy: no inventory took back {} from {}, dropping it there", stack, p);
		if (l != null && p != null)Containers.dropItemStack(l, p.getX() + .5, p.getY() + 1, p.getZ() + .5, stack);
	}
}
