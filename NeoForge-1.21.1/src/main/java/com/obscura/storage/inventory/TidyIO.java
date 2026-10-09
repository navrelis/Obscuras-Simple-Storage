package com.obscura.storage.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackLinkedSet;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

import com.obscura.storage.StorageMod;
import com.obscura.storage.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.obscura.storage.tidy.MoveResult;
import com.obscura.storage.tidy.SlotSink;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenCustomHashMap;

/**
 * Tidy's slot level access on NeoForge (IItemHandler). Same class and method names as the Fabric twin.
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

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static final Hash.Strategy<Object> KEY = (Hash.Strategy) ItemStackLinkedSet.TYPE_AND_TAG;

	/** Item keys of this loader: stacks compared by item and components. */
	public static Object2IntMap<Object> newKeyMap() {
		Object2IntOpenCustomHashMap<Object> m = new Object2IntOpenCustomHashMap<>(KEY);
		m.defaultReturnValue(-1);
		return m;
	}

	/** A key that may be kept: a copy of one item, the slot's own stack may change later. */
	public static Object freeze(Object key) {
		return ((ItemStack) key).copyWithCount(1);
	}

	/** One item of {@code key}. */
	public static ItemStack prototype(Object key) {
		return ((ItemStack) key).copyWithCount(1);
	}

	@SuppressWarnings("deprecation")
	private static IItemHandler raw(BlockInventoryAccess base) {
		IItemHandler h = base.get();
		return h == null || h instanceof IProxy ? null : h;
	}

	@SuppressWarnings("deprecation")
	private static IItemHandler handler(IInventoryAccess a) {
		Object h = a.get();
		return h instanceof IItemHandler ih ? ih : null;
	}

	/**
	 * Reads every slot of the inventory behind {@code base}.
	 *
	 * @return the slot count, -1 when the inventory is gone
	 */
	public static int snapshot(BlockInventoryAccess base, SlotSink sink) {
		IItemHandler h = raw(base);
		if (h == null)return -1;
		int n = h.getSlots();
		for (int i = 0; i < n; i++) {
			ItemStack s = h.getStackInSlot(i);
			int limit = Math.max(0, h.getSlotLimit(i));
			if (s.isEmpty())sink.slot(i, null, 0, limit);
			else sink.slot(i, s, s.getCount(), limit);
		}
		return n;
	}

	/** Slots of the inventory behind {@code base} that hold nothing, -1 when it is gone. */
	public static int freeSlots(BlockInventoryAccess base) {
		IItemHandler h = raw(base);
		if (h == null)return -1;
		int n = 0;
		for (int i = 0; i < h.getSlots(); i++) {
			if (h.getStackInSlot(i).isEmpty())n++;
		}
		return n;
	}

	/**
	 * Moves up to {@code amount} items from slot {@code srcSlot} of network member {@code src} into member
	 * {@code dst} (slot {@code dstSlot}, or -1 for partial stacks first, then empty slots). The source slot
	 * must still hold {@code expect}'s item and components.
	 *
	 * @param overflow the network, the last place a remainder can go
	 */
	public static MoveResult move(IInventoryAccess src, BlockInventoryAccess srcBase, int srcSlot, ItemStack expect, int amount,
			IInventoryAccess dst, BlockInventoryAccess dstBase, int dstSlot, IInventoryAccess overflow) {
		if (amount <= 0)return MoveResult.NONE;
		IItemHandler srcRaw = raw(srcBase), dstRaw = raw(dstBase);
		IItemHandler sh = handler(src), dh = handler(dst);
		if (srcRaw == null || dstRaw == null || sh == null || dh == null)return MoveResult.NONE;
		if (srcSlot < 0 || srcSlot >= sh.getSlots() || dstSlot >= dh.getSlots())return MoveResult.NONE;
		ItemStack cur = sh.getStackInSlot(srcSlot);
		if (cur.isEmpty() || !ItemStack.isSameItemSameComponents(cur, expect))return MoveResult.NONE;
		int want = Math.min(amount, cur.getCount());
		ItemStack sim = sh.extractItem(srcSlot, want, true);
		if (sim.isEmpty() || !ItemStack.isSameItemSameComponents(sim, expect))return MoveResult.NONE;

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
					boolean partial = !in.isEmpty() && ItemStack.isSameItemSameComponents(in, expect);
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
			StorageMod.LOGGER.debug("Tidy: {} took less than simulated, returning {}", dstBase.getPos(), left);
			left = putBack(left, sh, srcRaw, srcSlot, dh, overflow);
			if (!left.isEmpty())drop(srcBase, left);
		}
		boolean emptied = srcRaw.getStackInSlot(srcSlot).isEmpty();
		return new MoveResult(moved, filled, emptied);
	}

	/** Returns a remainder: source slot, source inventory, destination, network. What is left could not go anywhere. */
	private static ItemStack putBack(ItemStack left, IItemHandler sh, IItemHandler srcRaw, int srcSlot, IItemHandler dh, IInventoryAccess overflow) {
		left = sh.insertItem(srcSlot, left, false);
		if (!left.isEmpty())left = srcRaw.insertItem(srcSlot, left, false);
		for (int i = 0; i < srcRaw.getSlots() && !left.isEmpty(); i++)left = srcRaw.insertItem(i, left, false);
		for (int i = 0; i < dh.getSlots() && !left.isEmpty(); i++)left = dh.insertItem(i, left, false);
		if (!left.isEmpty() && overflow != null)left = overflow.pushStack(left);
		return left;
	}

	/** Last resort, never reached with well-behaved handlers: the items land next to the source block. */
	private static void drop(BlockInventoryAccess srcBase, ItemStack stack) {
		Level l = srcBase.getLevel();
		BlockPos p = srcBase.getPos();
		StorageMod.LOGGER.warn("Tidy: no inventory took back {} from {}, dropping it there", stack, p);
		if (l != null && p != null)Containers.dropItemStack(l, p.getX() + .5, p.getY() + 1, p.getZ() + .5, stack);
	}
}
