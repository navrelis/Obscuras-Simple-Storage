package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;

import net.minecraftforge.items.IItemHandler;

import com.obscura.storage.StorageMod;
import com.obscura.storage.doctor.NetworkStats;

/**
 * The inventories of a network as one {@link IItemHandler} (Forge twin of the Fabric MergedStorage).
 *
 * <p>Slots are numbered part after part; the part offsets are recomputed by {@link #refresh()}
 * (after every network scan and before every snapshot read), a part that shrank in between just
 * reports its missing slots as empty. Bulk operations ({@link ItemHandlers}) walk the parts directly
 * instead of going through the global slot numbers.
 */
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;

public class MergedStorage implements IItemHandler {
	private final Set<IItemHandler> dupCheck = new HashSet<>();
	public final List<IItemHandler> parts = new ArrayList<>();
	/** Block position each part came from, when the builder knew it (Item Locator). Keyed by identity, cleared with the parts. */
	private final Map<IItemHandler, BlockPos> positions = new IdentityHashMap<>();
	/** offsets[i] = first slot of part i, offsets[parts.size()] = slot count. */
	private int[] offsets = new int[] {0};
	private boolean sizesDirty = true;
	private boolean calling;
	private static boolean warnedRecursion;
	private StorageSnapshot snapshot;
	/** Network Doctor timing window of the network this storage is the inventory of, null when it is not attributed to any. */
	private NetworkStats doctorStats;
	/** Parts {@link #add} rejected as duplicates since the last {@link #clear}, for the Network Doctor. */
	private int duplicatesSkipped;
	private long fillVersion = Long.MIN_VALUE;
	private int fillSlots = -1, fillSignal;
	/** Oldest network snapshot a comparator accepts, in ticks. */
	private static final int FILL_MAX_AGE = 4;

	public MergedStorage() {
	}

	public Collection<IItemHandler> getStorages() {
		return parts;
	}

	public void add(IItemHandler storage) {
		add(storage, null);
	}

	/**
	 * Adds a part and remembers the block it came from. When {@code storage} is itself a merged
	 * storage, its parts are added with the positions it knows.
	 */
	public void add(IItemHandler storage, @Nullable BlockPos pos) {
		if(storage == this || storage == null)return;
		if(storage instanceof MergedStorage str) {
			for (IItemHandler part : new ArrayList<>(str.parts)) {
				add(part, str.positions.get(part));
			}
			return;
		}
		if(dupCheck.add(storage)) {
			this.parts.add(storage);
			if(pos != null)positions.put(storage, pos.immutable());
			sizesDirty = true;
		} else {
			duplicatesSkipped++;
		}
	}

	/** {@link #getPosition} for loader neutral code (the Tidy button): {@code part} is a member of {@link #getStorages()}. */
	@Nullable
	public BlockPos positionOf(Object part) {
		return positions.get(part);
	}

	/** Read-only: the block {@code part} was added for, or null when unknown. */
	@Nullable
	public BlockPos getPosition(IItemHandler part) {
		return positions.get(part);
	}

	/**
	 * Item totals of everything merged here, shared by every reader of this network (terminals, level
	 * emitters). Readers refresh it with the age they can accept, see {@link StorageSnapshot#refresh}.
	 */
	public StorageSnapshot getSnapshot() {
		if(snapshot == null)snapshot = new StorageSnapshot();
		return snapshot;
	}

	/** This network's Network Doctor timing window, null when its work is not attributed to any. */
	public NetworkStats getDoctorStats() {
		return doctorStats;
	}

	public void setDoctorStats(NetworkStats doctorStats) {
		this.doctorStats = doctorStats;
	}

	/** Parts offered to {@link #add} that were already there since the last {@link #clear}. */
	public int getDuplicatesSkipped() {
		return duplicatesSkipped;
	}

	/** Members labelled by glow item frames (Frame Labels), also behind a cable connector's own filter. For the Network Doctor. */
	public int countLabelled() {
		int n = 0;
		for (IItemHandler part : parts) {
			IItemHandler a = part;
			for (int i = 0; i < 8 && a != null; i++) {
				if (a instanceof FrameLabel.Labelled) {
					n++;
					break;
				}
				a = a instanceof FilteredStorage f ? f.getBacking() : null;
			}
		}
		return n;
	}

	/**
	 * How full the whole network is as a comparator signal (see {@link RedstoneSignals#fill}): the
	 * stored items, each as a fraction of its max stack size, out of the slot count. The value is cached
	 * and only recomputed when the shared snapshot changed (which it re-reads at most every few ticks for
	 * all readers), so asking every tick costs next to nothing.
	 */
	public int getFillSignal(Level level) {
		StorageSnapshot s = getSnapshot();
		s.refresh(this, level.getGameTime(), FILL_MAX_AGE);
		if(s.getVersion() != fillVersion || s.getSlotCount() != fillSlots) {
			fillVersion = s.getVersion();
			fillSlots = s.getSlotCount();
			float sum = 0;
			boolean any = false;
			for (Object2LongMap.Entry<ItemKey> e : Object2LongMaps.fastIterable(s.counts())) {
				long n = e.getLongValue();
				if(n <= 0)continue;
				any = true;
				sum += RedstoneSignals.slotFill(n, e.getKey().getItem().getMaxStackSize());
			}
			fillSignal = RedstoneSignals.fill(sum, any, s.getSlotCount());
		}
		return fillSignal;
	}

	public void clear() {
		parts.clear();
		dupCheck.clear();
		positions.clear();
		duplicatesSkipped = 0;
		sizesDirty = true;
	}

	/** Recomputes the slot offsets of the parts. */
	public void refresh() {
		if(!enter())return;
		try {
			int[] off = new int[parts.size() + 1];
			int total = 0;
			for (int i = 0; i < parts.size(); i++) {
				off[i] = total;
				int s = parts.get(i).getSlots();
				if(s > 0)total += s;
			}
			off[parts.size()] = total;
			offsets = off;
			sizesDirty = false;
		} finally {
			exit();
		}
	}

	/** Recursion guard: false when this storage is already being accessed further up the stack (a network connected to itself). */
	boolean enter() {
		if(calling) {
			if(!warnedRecursion) {
				warnedRecursion = true;
				StorageMod.LOGGER.warn("Recursive inventory connector access detected (a network is connected to itself); it is skipped. Further occurrences are not logged.");
			}
			return false;
		}
		calling = true;
		return true;
	}

	void exit() {
		calling = false;
	}

	@Override
	public int getSlots() {
		if(sizesDirty)refresh();
		return offsets[offsets.length - 1];
	}

	/** Index of the part holding {@code slot}, or -1. */
	private int partOf(int slot) {
		if(sizesDirty)refresh();
		int[] off = offsets;
		int n = off.length - 1;
		if(slot < 0 || slot >= off[n])return -1;
		int i = Arrays.binarySearch(off, 0, n, slot);
		if(i < 0)i = -i - 2;
		// Parts without slots share their offset with the next part
		while(i + 1 < n && off[i + 1] == slot)i++;
		return i < parts.size() ? i : -1;
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		int p = partOf(slot);
		if(p < 0 || !enter())return ItemStack.EMPTY;
		try {
			IItemHandler h = parts.get(p);
			int local = slot - offsets[p];
			return local < h.getSlots() ? h.getStackInSlot(local) : ItemStack.EMPTY;
		} finally {
			exit();
		}
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		int p = partOf(slot);
		if(p < 0 || !enter())return stack;
		try {
			IItemHandler h = parts.get(p);
			int local = slot - offsets[p];
			return local < h.getSlots() ? h.insertItem(local, stack, simulate) : stack;
		} finally {
			exit();
		}
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		int p = partOf(slot);
		if(p < 0 || !enter())return ItemStack.EMPTY;
		try {
			IItemHandler h = parts.get(p);
			int local = slot - offsets[p];
			return local < h.getSlots() ? h.extractItem(local, amount, simulate) : ItemStack.EMPTY;
		} finally {
			exit();
		}
	}

	@Override
	public int getSlotLimit(int slot) {
		int p = partOf(slot);
		if(p < 0 || !enter())return 0;
		try {
			IItemHandler h = parts.get(p);
			int local = slot - offsets[p];
			return local < h.getSlots() ? h.getSlotLimit(local) : 0;
		} finally {
			exit();
		}
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		int p = partOf(slot);
		if(p < 0 || !enter())return false;
		try {
			IItemHandler h = parts.get(p);
			int local = slot - offsets[p];
			return local < h.getSlots() && h.isItemValid(local, stack);
		} finally {
			exit();
		}
	}
}
