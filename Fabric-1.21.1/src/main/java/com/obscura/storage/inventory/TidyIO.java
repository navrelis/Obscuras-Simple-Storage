package com.obscura.storage.inventory;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.obscura.storage.tidy.MoveResult;
import com.obscura.storage.tidy.SlotSink;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/**
 * Tidy's slot level access on Fabric (Transfer API). Same class and method names as the NeoForge twin.
 *
 * <p>A move runs in two outer transactions: the first simulates the extract and the insert (and is
 * always rolled back), the second does exactly the amount the simulation allowed and only commits when
 * every item that left the source arrived in the destination. Anything else rolls back, so a move can
 * never void or duplicate items. Extraction goes through the network member's own Storage (its filter
 * views: block filters, keep-last), insertion through the same gates the member applies (Configurator
 * filter, Frame Label) and then slot by slot into the inventory itself, partial stacks first.
 */
public final class TidyIO {
	private TidyIO() {}

	/** Item keys of this loader: ItemVariant is already an immutable item + components key. */
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

	@SuppressWarnings("deprecation")
	private static Storage<ItemVariant> raw(BlockInventoryAccess base) {
		Storage<ItemVariant> s = base.get();
		return s == null || s instanceof IProxy ? null : s;
	}

	/**
	 * Reads every slot of the inventory behind {@code base}.
	 *
	 * @return the slot count, -1 when the inventory is gone
	 */
	public static int snapshot(BlockInventoryAccess base, SlotSink sink) {
		base.invalidateTickCache();
		Storage<ItemVariant> s = raw(base);
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

	/** Slots of the inventory behind {@code base} that hold nothing, -1 when it is gone. */
	public static int freeSlots(BlockInventoryAccess base) {
		Storage<ItemVariant> s = raw(base);
		if (s == null)return -1;
		int n = 0;
		for (StorageView<ItemVariant> v : s) {
			if (v.isResourceBlank() || v.getAmount() <= 0)n++;
		}
		return n;
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
	 * The storage items of {@code res} go into when the network inserts them into member {@code dst}:
	 * the gates of its block filters and Frame Label applied, then the inventory itself. Null when a
	 * gate refuses or the member is not a plain inventory.
	 */
	private static Storage<ItemVariant> target(IInventoryAccess dst, ItemStack proto) {
		IInventoryAccess a = dst;
		while (true) {
			if (a instanceof PlatformFilteredInventoryAccess f) {
				if (!f.getFilter().getItemPred().test(proto))return null;
				a = f.getActualInventory();
			} else if (a instanceof PlatformLabelledInventoryAccess l) {
				if (!l.getLabel().test(proto))return null;
				a = l.getLabelledInventory();
			} else break;
		}
		return a instanceof BlockInventoryAccess b ? raw(b) : null;
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
	 * Moves up to {@code amount} items from slot {@code srcSlot} of network member {@code src} into member
	 * {@code dst} (slot {@code dstSlot}, or -1 for partial stacks first, then empty slots). The source slot
	 * must still hold {@code expect}'s item and components. Simulated first, then done atomically.
	 *
	 * @param overflow unused on Fabric (a transaction never leaves a remainder)
	 */
	@SuppressWarnings("deprecation")
	public static MoveResult move(IInventoryAccess src, BlockInventoryAccess srcBase, int srcSlot, ItemStack expect, int amount,
			IInventoryAccess dst, BlockInventoryAccess dstBase, int dstSlot, IInventoryAccess overflow) {
		if (amount <= 0)return MoveResult.NONE;
		srcBase.invalidateTickCache();
		dstBase.invalidateTickCache();
		if (raw(srcBase) == null || raw(dstBase) == null)return MoveResult.NONE;
		if (!(src.get() instanceof Storage<?> st))return MoveResult.NONE;
		@SuppressWarnings("unchecked")
		Storage<ItemVariant> s = (Storage<ItemVariant>) st;
		StorageView<ItemVariant> view = viewAt(s, srcSlot);
		if (view == null || view.isResourceBlank() || view.getAmount() <= 0)return MoveResult.NONE;
		ItemVariant res = view.getResource();
		if (!res.matches(expect))return MoveResult.NONE;
		Storage<ItemVariant> to = target(dst, expect);
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
		StorageView<ItemVariant> after = viewAt(raw(srcBase), srcSlot);
		boolean emptied = after == null || after.isResourceBlank() || after.getAmount() <= 0;
		return new MoveResult((int) can, filled[0], emptied);
	}
}
