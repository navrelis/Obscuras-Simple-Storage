package com.obscura.storage.tidy;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.util.FilteredStorage;
import com.obscura.storage.util.IProxy;
import com.obscura.storage.util.LabelledStorage;
import com.obscura.storage.util.MergedStorage;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/**
 * Tidy's slot level access on Fabric (Transfer API). Same class and method names as the Forge twin.
 *
 * <p>A network member is a {@code Storage<ItemVariant>}: the block's own inventory, behind the block
 * filters ({@link FilteredStorage}) and Frame Label ({@link LabelledStorage}) the network applies.
 *
 * <p>A move runs in two outer transactions: the first simulates the extract and the insert (and is
 * always rolled back), the second does exactly the amount the simulation allowed and only commits when
 * every item that left the source arrived in the destination. Anything else rolls back, so a move can
 * never void or duplicate items. Extraction goes through the network member's own Storage (its filter
 * views: block filters, keep-last), insertion through the same gates the member applies (block filter,
 * Frame Label) and then slot by slot into the inventory itself, partial stacks first.
 */
public final class TidyIO {
	private TidyIO() {}

	/** Item keys of this loader: ItemVariant is already an immutable item + NBT key. */
	public static Object2IntMap<Object> newKeyMap() {
		Object2IntOpenHashMap<Object> m = new Object2IntOpenHashMap<>();
		m.defaultReturnValue(-1);
		return m;
	}

	/** A key that may be kept (the variant is immutable). */
	public static Object freeze(Object key) {
		return key;
	}

	/** One item of {@code key}. */
	public static ItemStack prototype(Object key) {
		return ((ItemVariant) key).toStack();
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
				a = f.get();
			} else if (a instanceof LabelledStorage l) {
				if (out != null)out.labels.add(l.getLabel());
				a = l.get();
			} else {
				break;
			}
		}
		if (!(a instanceof Storage<?>) || a instanceof IProxy || a instanceof MergedStorage)return null;
		return a;
	}

	@SuppressWarnings("unchecked")
	private static Storage<ItemVariant> raw(Object part) {
		return (Storage<ItemVariant>) unwrap(part, null);
	}

	/**
	 * Reads every slot of the inventory behind the network member {@code part}.
	 *
	 * @return the slot count, -1 when the inventory is gone
	 */
	public static int snapshot(Object part, SlotSink sink) {
		Storage<ItemVariant> s = raw(part);
		if (s == null)return -1;
		int i = 0;
		for (StorageView<ItemVariant> v : s) {
			int limit = (int) Math.min(Integer.MAX_VALUE, Math.max(0, v.getCapacity()));
			if (v.isResourceBlank() || v.getAmount() <= 0) {
				sink.slot(i, null, 0, limit);
			} else {
				sink.slot(i, v.getResource(), (int) Math.min(Integer.MAX_VALUE, v.getAmount()), limit);
			}
			i++;
		}
		return i;
	}

	private static StorageView<ItemVariant> viewAt(Storage<ItemVariant> s, int index) {
		if (index < 0)return null;
		int i = 0;
		for (StorageView<ItemVariant> v : s) {
			if (i++ == index)return v;
		}
		return null;
	}

	private static List<StorageView<ItemVariant>> views(Storage<ItemVariant> s) {
		List<StorageView<ItemVariant>> out = new ArrayList<>();
		for (StorageView<ItemVariant> v : s)out.add(v);
		return out;
	}

	/**
	 * The storage items go into when the network inserts them into member {@code dst}: the gates of its
	 * block filters and Frame Label applied, then the inventory itself. Null when a gate refuses or the
	 * member is not a plain inventory.
	 */
	private static Storage<ItemVariant> target(Object dst, ItemStack proto) {
		Object a = dst;
		for (int depth = 0; depth < 16 && a != null; depth++) {
			if (a instanceof FilteredStorage f) {
				if (!f.getFilter().testStack(proto))return null;
				a = f.get();
			} else if (a instanceof LabelledStorage l) {
				if (!l.getLabel().test(proto))return null;
				a = l.get();
			} else {
				break;
			}
		}
		return raw(a);
	}

	/**
	 * Inserts into {@code to}: into slot {@code dstSlot} only, or (-1) partial stacks first, then
	 * empty slots. Storages without slot views get a plain insert.
	 */
	@SuppressWarnings("unchecked")
	private static long insert(Storage<ItemVariant> to, ItemVariant res, long amount, int dstSlot, TransactionContext tx, int[] filled) {
		if (dstSlot >= 0) {
			StorageView<ItemVariant> v = viewAt(to, dstSlot);
			if (!(v instanceof Storage<?> st))return 0;
			boolean blank = v.isResourceBlank();
			long n = ((Storage<ItemVariant>) st).insert(res, amount, tx);
			if (blank && n > 0)filled[0]++;
			return n;
		}
		List<StorageView<ItemVariant>> vs = views(to);
		long done = 0;
		boolean slotted = false;
		for (StorageView<ItemVariant> v : vs) {
			if (!(v instanceof Storage<?> st))continue;
			slotted = true;
			if (v.isResourceBlank() || !v.getResource().equals(res))continue;
			done += ((Storage<ItemVariant>) st).insert(res, amount - done, tx);
			if (done >= amount)return done;
		}
		for (StorageView<ItemVariant> v : vs) {
			if (!(v instanceof Storage<?> st) || !v.isResourceBlank())continue;
			long n = ((Storage<ItemVariant>) st).insert(res, amount - done, tx);
			if (n > 0) {
				filled[0]++;
				done += n;
				if (done >= amount)return done;
			}
		}
		if (!slotted)done += to.insert(res, amount - done, tx);
		return done;
	}

	/**
	 * Moves up to {@code amount} items from slot {@code srcSlot} of network member {@code srcPart} into
	 * member {@code dstPart} (slot {@code dstSlot}, or -1 for partial stacks first, then empty slots). The
	 * source slot must still hold {@code expect}'s item and NBT. Simulated first, then done atomically.
	 *
	 * @param overflow unused on Fabric (a transaction never leaves a remainder)
	 */
	@SuppressWarnings("unchecked")
	public static MoveResult move(TidyMember src, Object srcPart, int srcSlot, ItemStack expect, int amount,
			TidyMember dst, Object dstPart, int dstSlot, Object overflow) {
		if (amount <= 0)return MoveResult.NONE;
		Storage<ItemVariant> srcRaw = raw(srcPart);
		if (srcRaw == null || raw(dstPart) == null)return MoveResult.NONE;
		if (!(srcPart instanceof Storage<?> st))return MoveResult.NONE;
		Storage<ItemVariant> s = (Storage<ItemVariant>) st;
		StorageView<ItemVariant> view = viewAt(s, srcSlot);
		if (view == null || view.isResourceBlank() || view.getAmount() <= 0)return MoveResult.NONE;
		ItemVariant res = view.getResource();
		if (!res.matches(expect))return MoveResult.NONE;
		Storage<ItemVariant> to = target(dstPart, expect);
		if (to == null)return MoveResult.NONE;
		long want = Math.min(amount, view.getAmount());
		long can;
		try (Transaction sim = Transaction.openOuter()) {
			long ex = view.extract(res, want, sim);
			if (ex <= 0)return MoveResult.NONE;
			can = Math.min(ex, insert(to, res, ex, dstSlot, sim, new int[1]));
			// closed without commit: rolled back
		}
		if (can <= 0)return MoveResult.NONE;
		int[] filled = new int[1];
		try (Transaction tx = Transaction.openOuter()) {
			long ex = view.extract(res, can, tx);
			if (ex != can)return MoveResult.NONE;
			long in = insert(to, res, ex, dstSlot, tx, filled);
			if (in != ex)return MoveResult.NONE;// everything rolls back, nothing left the source
			tx.commit();
		}
		StorageView<ItemVariant> after = viewAt(srcRaw, srcSlot);
		boolean emptied = after == null || after.isResourceBlank() || after.getAmount() <= 0;
		return new MoveResult((int) can, filled[0], emptied);
	}
}
