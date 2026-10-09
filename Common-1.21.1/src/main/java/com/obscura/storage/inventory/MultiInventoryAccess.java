package com.obscura.storage.inventory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;

import com.obscura.storage.Config;
import com.obscura.storage.block.entity.IInventoryConnector;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.inventory.filter.ItemPredicate;
import com.obscura.storage.util.Priority;
import com.obscura.storage.util.Priority.IPriority;
import com.obscura.storage.util.RedstoneSignals;
import com.obscura.storage.util.WorldStates;

public abstract class MultiInventoryAccess implements IInventoryAccess {
	protected List<IInventoryAccess> connected = new ArrayList<>();
	protected MultiChangeTracker tracker = new MultiChangeTracker();
	/** Network Doctor timing window this network's work is booked to, null when not attributed to any. */
	private NetworkStats doctorStats;

	public MultiInventoryAccess() {
		WorldStates.trackers.put(getPlatformHandler(), tracker());
	}

	/**
	 * Rebuilds the member list from {@code self} and the connectors reachable from it.
	 *
	 * @return whether the members changed, see {@link #setConnected}
	 */
	public boolean build(IInventoryConnector self, Collection<IInventoryConnector> connectors) {
		return setConnected(collect(self, connectors));
	}

	/**
	 * The deduplicated, priority-ordered inventories of {@code self} and of every connector reachable
	 * from it, exactly as {@link #build} would register them, without changing this access.
	 */
	public List<IInventoryAccess> collect(IInventoryConnector self, Collection<IInventoryConnector> connectors) {
		return collect(self, connectors, Config.get().doctorMaxInventories, null);
	}

	/**
	 * {@link #collect(IInventoryConnector, Collection)} with an explicit inventory cap, optionally
	 * counting what was left out and why (for the Network Doctor).
	 *
	 * @param cap at most this many inventories, 0 (or less) = no cap. Members are taken in priority
	 *        order, so the cap leaves out the lowest priority inventories first.
	 * @param stats receives the counts of left out inventories, may be null
	 */
	public List<IInventoryAccess> collect(IInventoryConnector self, Collection<IInventoryConnector> connectors, int cap, CollectStats stats) {
		// BFS to resolve all transitive connectors
		Queue<IInventoryConnector> q = new ArrayDeque<>();
		q.add(self);
		q.addAll(connectors);
		Set<IInventoryConnector> all = Collections.newSetFromMap(new IdentityHashMap<>());
		while (!q.isEmpty()) {
			IInventoryConnector ic = q.poll();
			if (all.add(ic)) {
				q.addAll(ic.getConnectedConnectors());
			}
		}

		List<IInventoryAccess> out = new ArrayList<>();
		EnumMap<Priority, List<IInventoryAccess>> map = new EnumMap<>(Priority.class);
		for (IInventoryConnector c : all) {
			for (IInventoryAccess inv : c.getConnectedInventories()) {
				Priority p = IPriority.GETTER.apply(inv);
				map.computeIfAbsent(p, k -> new ArrayList<>()).add(inv);
			}
		}
		Set<IInventoryAccess> allRoots = Collections.newSetFromMap(new IdentityHashMap<>());
		allRoots.add(this);
		// Second key: the physical inventory behind the Storage wrapper. Both halves of a vanilla
		// double chest are scanned as separate BlockFaces but each resolves to a Storage covering
		// ALL slots of the chest, so without this they would be registered (and counted) twice.
		Set<Object> allStorages = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<IProxy> dejaVu = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int i = Priority.VALUES.length - 1; i >= 0; i--) {
			for (IInventoryAccess a : map.getOrDefault(Priority.VALUES[i], Collections.emptyList())) {
				int r = addDistinct(out, a, allRoots, allStorages, dejaVu, cap);
				if (stats != null)stats.count(r, a);
			}
		}
		return out;
	}

	/** Why {@link #addDistinct} left inventories out, filled by the Network Doctor's analysis. */
	public static final class CollectStats {
		/** Inventories offered by the connectors, before anything was left out. */
		public int offered;
		/** Its root is a storage network (a proxy or filter in front of a network block). */
		public int network;
		/** The same root handler was already registered (the same block reached twice). */
		public int sameRoot;
		/** The same physical storage was already registered (the other half of a double chest). */
		public int sameStorage;
		/** Left out because the cap was reached. */
		public final List<IInventoryAccess> overCap = new ArrayList<>();

		void count(int reason, IInventoryAccess a) {
			offered++;
			switch (reason) {
			case SKIP_NETWORK -> network++;
			case SKIP_SAME_ROOT -> sameRoot++;
			case SKIP_SAME_STORAGE -> sameStorage++;
			case SKIP_OVER_CAP -> overCap.add(a);
			default -> {}
			}
		}
	}

	/** This network's Network Doctor timing window, null when its work is not attributed to any. */
	public NetworkStats getDoctorStats() {
		return doctorStats;
	}

	public void setDoctorStats(NetworkStats doctorStats) {
		this.doctorStats = doctorStats;
	}

	/**
	 * Makes {@code list} the member list. Nothing happens when it holds exactly the current members
	 * in the same order: connectors rebuild periodically, and invalidating the tracker for an
	 * unchanged network makes it report a change, which makes every open terminal regroup its whole
	 * item list and resync.
	 *
	 * @return whether the members changed
	 */
	protected boolean setConnected(List<IInventoryAccess> list) {
		List<Set<Object>> ov = computeOverlaps(list);
		if (sameMembers(list) && ov.equals(overlaps))return false;
		connected.clear();
		connected.addAll(list);
		overlaps = ov;
		tracker.invalidate();
		refresh();
		return true;
	}

	/**
	 * Slots each member must leave out when counting, by index into {@link #connected} (shorter than
	 * it, or a null entry, when nothing is left out).
	 *
	 * <p>Two faces of one sided block (a brewing stand's bottom {0,1,2,3} and side {0,1,2,4}) are
	 * different inventories: both stay members, so inserting and extracting still goes through a
	 * face that allows it. But the slots they share must only be counted once, so the later member
	 * leaves out every slot an earlier member already covers.
	 *
	 * <p>The slots are opaque, loader-specific objects compared by identity (see
	 * {@link PlatformMultiInventoryAccess#sidedSlots}).
	 */
	private List<Set<Object>> overlaps = List.of();

	private static List<Set<Object>> computeOverlaps(List<IInventoryAccess> list) {
		List<Set<Object>> out = null;
		Set<Object> claimed = null;
		for (int i = 0; i < list.size(); i++) {
			List<Object> views = PlatformMultiInventoryAccess.sidedSlots(identityView(list.get(i)));
			if (views == null)continue;
			if (claimed == null)claimed = Collections.newSetFromMap(new IdentityHashMap<>());
			Set<Object> shared = null;
			for (Object v : views) {
				if (!claimed.add(v)) {
					if (shared == null)shared = Collections.newSetFromMap(new IdentityHashMap<>());
					shared.add(v);
				}
			}
			if (shared != null) {
				if (out == null)out = new ArrayList<>(Collections.nCopies(list.size(), null));
				out.set(i, shared);
			}
		}
		return out != null ? out : List.of();
	}

	private Set<Object> overlapOf(int i) {
		List<Set<Object>> ov = overlaps;
		return i < ov.size() ? ov.get(i) : null;
	}

	private boolean sameMembers(List<IInventoryAccess> list) {
		if (list.size() != connected.size())return false;
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i) != connected.get(i))return false;
		}
		return true;
	}

	/** Adds {@code a} to {@link #connected} unless an equivalent inventory is already registered. */
	protected void addDistinct(IInventoryAccess a, Set<IInventoryAccess> allRoots, Set<Object> allStorages, Set<IProxy> dejaVu) {
		addDistinct(connected, a, allRoots, allStorages, dejaVu, 0);
	}

	private static final int ADDED = 0, SKIP_NETWORK = 1, SKIP_SAME_ROOT = 2, SKIP_SAME_STORAGE = 3, SKIP_OVER_CAP = 4;

	/** @return {@link #ADDED}, or why {@code a} was left out */
	private static int addDistinct(List<IInventoryAccess> out, IInventoryAccess a, Set<IInventoryAccess> allRoots, Set<Object> allStorages, Set<IProxy> dejaVu, int cap) {
		IInventoryAccess root = a.getRootHandler(dejaVu);
		if (root instanceof MultiInventoryAccess)return SKIP_NETWORK;//skip
		if (!allRoots.add(root))return SKIP_SAME_ROOT;
		// Only dedupe accesses that ARE their own root. A filtered or proxied access resolves to the
		// same root as a plain one over the same block, but it carries the user's item filter,
		// keep-last and priority config - dropping it would silently expose that inventory
		// unfiltered. A frame label is the exception: it is derived from the physical inventory
		// (either half of a double chest yields the same label), so it takes part in the dedupe;
		// labelled accesses sort first, so the labelled one is the one kept.
		Object sid = identityView(a) == root ? PlatformMultiInventoryAccess.storageIdentity(root) : null;
		if (sid != null && !allStorages.add(sid))return SKIP_SAME_STORAGE;
		// Network Doctor inventory cap (doctorMaxInventories), off by default
		if (cap > 0 && out.size() >= cap)return SKIP_OVER_CAP;
		out.add(a);
		return ADDED;
	}

	/**
	 * The access that stands for {@code a}'s physical inventory in the identity rules (dedupe, shared
	 * sided slots): the inventory behind a frame label, else {@code a} itself.
	 */
	static IInventoryAccess identityView(IInventoryAccess a) {
		return a instanceof FrameLabel.Labelled l ? l.getLabelledInventory() : a;
	}

	/**
	 * The set of underlying slots a sided inventory exposes, compared by identity and ignoring order.
	 * The platform decides when an inventory is sided and what its underlying slots are, see
	 * {@link PlatformMultiInventoryAccess#storageIdentity}.
	 */
	static final class SlotSetKey {
		static final Interner<SlotSetKey> INTERNER = Interners.newWeakInterner();
		private final Set<Object> views = Collections.newSetFromMap(new IdentityHashMap<>());
		private final int hash;

		/**
		 * @param parts the inventory's parts
		 * @param underlying maps a part to the stable slot object it stands for
		 */
		SlotSetKey(List<?> parts, UnaryOperator<Object> underlying) {
			int h = 0;
			for (Object p : parts) {
				Object u = underlying.apply(p);
				if (views.add(u))h += System.identityHashCode(u);
			}
			hash = h;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)return true;
			if (!(obj instanceof SlotSetKey o) || o.hash != hash || o.views.size() != views.size())return false;
			return views.containsAll(o.views);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	@Override
	public ItemStack pullMatchingStack(ItemStack st, long max) {
		ItemStack res = ItemStack.EMPTY;
		int c = 0;
		for (int i = 0;i<connected.size();i++) {
			ItemStack p = connected.get(i).pullMatchingStack(st, max - c);
			if (p.isEmpty())continue;
			if (res.isEmpty())res = p;
			c += p.getCount();
			if (c >= max)break;
		}
		res.setCount(c);
		return res;
	}

	@Override
	public ItemStack pushStack(ItemStack stack) {
		for (int i = 0;i<connected.size();i++) {
			stack = connected.get(i).pushStack(stack);
			if (stack.isEmpty())return ItemStack.EMPTY;
		}
		return stack;
	}

	@Override
	public IInventoryChangeTracker tracker() {
		return tracker;
	}

	@Override
	public int getFreeSlotCount() {
		int c = 0;
		for (int i = 0;i<connected.size();i++) {
			c += connected.get(i).getFreeSlotCount();
			Set<Object> ov = overlapOf(i);
			if (ov != null) {
				// Shared slots are counted with the member that covers them first
				for (Object v : ov) {
					if (PlatformMultiInventoryAccess.isSlotEmpty(v))c--;
				}
			}
		}
		return c;
	}

	@Override
	public int getSlotCount() {
		int c = 0;
		for (int i = 0;i<connected.size();i++) {
			c += connected.get(i).getSlotCount();
			Set<Object> ov = overlapOf(i);
			if (ov != null)c -= ov.size();
		}
		return c;
	}

	public void clear() {
		// Already empty: the tracker was invalidated when it got empty, doing it again would only
		// report another (empty) change.
		if (connected.isEmpty())return;
		connected.clear();
		overlaps = List.of();
		tracker.invalidate();
	}

	public int getInventoryCount() {
		return connected.size();
	}

	private long fillTracker = Long.MIN_VALUE;
	private int fillSignal;

	/**
	 * How full the whole network is as a comparator signal (see {@link RedstoneSignals#fill}): the
	 * stored items, each as a fraction of its max stack size, out of {@link #getSlotCount}. The value
	 * is cached and only recomputed when the change tracker moved (the tracker itself scans at most
	 * once per tick), so reading it every tick costs no more than asking for the tracker.
	 */
	public int getFillSignal(Level level) {
		long t = tracker.getChangeTracker(level);
		if (t != fillTracker) {
			fillTracker = t;
			float[] sum = new float[1];
			boolean[] any = new boolean[1];
			tracker.streamWrappedStacks(false).forEach(s -> {
				if (s.getQuantity() <= 0)return;
				any[0] = true;
				sum[0] += RedstoneSignals.slotFill(s.getQuantity(), s.getStack().getMaxStackSize());
			});
			fillSignal = RedstoneSignals.fill(sum[0], any[0], getSlotCount());
		}
		return fillSignal;
	}

	protected void refresh() {
	}

	@Override
	public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
		return this;
	}

	private static class ItemList extends ArrayList<StoredItemStack> {
		private static final long serialVersionUID = 6690277901361998268L;
	}

	public static class TrackerInfo {
		private int id;
		private IMultiThreadedTracker<Object, Object> mt;
		private Object prep, result;
		private long tracker;

		@SuppressWarnings("unchecked")
		public TrackerInfo(int id, IMultiThreadedTracker<?, ?> mt, Object prep) {
			this.id = id;
			this.mt = (IMultiThreadedTracker<Object, Object>) mt;
			this.prep = prep;
		}

		public void run() {
			result = mt.processOffThread(prep);
		}

		public void finish(Level level) {
			tracker = mt.finishOffThreadProcess(level, result);
		}
	}

	protected class MultiChangeTracker implements IInventoryChangeTracker {
		private long lastUpdate, lastChange;
		private long[] trackers = new long[0];
		private ItemList[] items = new ItemList[0];
		private int lastConnectedSize = 0;

		/**
		 * Must be called whenever {@link #connected} is rebuilt: the per-index caches are only valid
		 * for the list they were filled from. Forces the next {@link #getChangeTracker} to resize and
		 * re-sync them even within the same tick, and drops the cached stacks right away for callers
		 * that stream before that.
		 */
		void invalidate() {
			lastUpdate = Long.MIN_VALUE;
			lastConnectedSize = -1;
			Arrays.fill(items, null);
		}

		private boolean multithreadProcessing(Level level) {
			int size = connected.size();
			List<TrackerInfo> infos = new ArrayList<>(size);
			boolean ch = false;
			for (int i = 0;i<connected.size();i++) {
				IInventoryChangeTracker tr = connected.get(i).tracker();
				if (tr instanceof IMultiThreadedTracker mt) {
					Object prep = mt.prepForOffThread(level);
					if (prep != null) {
						infos.add(new TrackerInfo(i, mt, prep));
						continue;
					}
				}
				long v = tr.getChangeTracker(level);
				if (v != trackers[i]) {
					ch |= true;
					items[i] = null;
					trackers[i] = v;
				}
			}
			(infos.size() > 8 ? infos.parallelStream() : infos.stream()).unordered().peek(TrackerInfo::run).toArray();
			for (TrackerInfo tr : infos) {
				tr.finish(level);

				int i = tr.id;
				long v = tr.tracker;
				if (v != trackers[i]) {
					ch |= true;
					items[i] = null;
					trackers[i] = v;
				}
			}
			return ch;
		}

		@Override
		public long getChangeTracker(Level level) {
			if (lastUpdate != level.getGameTime()) {
				// The change scan over every member is the main per-tick cost of a network: booked to
				// its Network Doctor window (two nanoTime reads, only when the scan actually runs).
				NetworkStats st = doctorStats;
				long t0 = NetworkDoctor.begin(st);
				try {
					return scanForChanges(level);
				} finally {
					NetworkDoctor.end(st, level.getGameTime(), t0);
				}
			}
			return lastChange;
		}

		/** The actual change scan of {@link #getChangeTracker}, once per tick. */
		private long scanForChanges(Level level) {
			int size = connected.size();
			if (size > trackers.length || size < trackers.length / 4) {
				// Exponential growth: round up to next power of 2, minimum 8
				int newCap = Math.max(size, 8);
				newCap = Integer.highestOneBit(newCap - 1) << 1;
				trackers = new long[newCap];
				items = new ItemList[newCap];
				for (int i = 0;i<size;i++) {
					IInventoryChangeTracker tr = connected.get(i).tracker();
					trackers[i] = tr.getChangeTracker(level);
				}
				lastConnectedSize = size;
				lastChange = System.nanoTime();
				return lastChange;
			} else if (size != lastConnectedSize) {
				// Size changed but within allocated range; reinitialize without reallocating
				for (int i = 0;i<trackers.length;i++) {
					trackers[i] = 0;
					items[i] = null;
				}
				for (int i = 0;i<size;i++) {
					IInventoryChangeTracker tr = connected.get(i).tracker();
					trackers[i] = tr.getChangeTracker(level);
				}
				lastConnectedSize = size;
				lastChange = System.nanoTime();
				return lastChange;
			}
			boolean ch = false;
			if (Config.get().runMultithreaded) {
				ch = multithreadProcessing(level);
			} else {
				for (int i = 0;i<connected.size();i++) {
					IInventoryAccess ia = connected.get(i);
					long v = ia.tracker().getChangeTracker(level);
					if (v != trackers[i]) {
						ch |= true;
						items[i] = null;
						trackers[i] = v;
					}
				}
			}
			if (ch)lastChange = System.nanoTime();
			lastUpdate = level.getGameTime();
			return lastChange;
		}

		@Override
		public Stream<StoredItemStack> streamWrappedStacks(boolean parallel) {
			ItemList[] cache = items;
			var str = IntStream.range(0, connected.size()).mapToObj(i -> {
				// connected can outgrow the cache between a rebuild and the next getChangeTracker
				if (i >= cache.length) {
					return memberStacks(i);
				}
				if (cache[i] == null) {
					cache[i] = memberStacks(i);
				}
				return cache[i];
			});
			if (parallel) {
				var list = str.toList();
				if (list.size() > 8)
					return list.parallelStream().flatMap(List::parallelStream).unordered();
				else
					return list.stream().flatMap(List::stream);
			}
			else return str.flatMap(List::stream);
		}

		/** Member {@code i}'s stacks, without the slots an earlier member already counts (see overlaps). */
		private ItemList memberStacks(int i) {
			IInventoryChangeTracker tr = connected.get(i).tracker();
			Set<Object> ov = overlapOf(i);
			return (ov != null ? tr.streamWrappedStacks(ov) : tr.streamWrappedStacks(false)).collect(Collectors.toCollection(ItemList::new));
		}

		@Override
		public long countItems(StoredItemStack filter) {
			long c = 0;
			for (int i = 0;i<connected.size();i++) {
				IInventoryAccess ia = connected.get(i);
				Set<Object> ov = overlapOf(i);
				c += ov != null ? ia.tracker().countItems(filter, ov) : ia.tracker().countItems(filter);
			}
			return c;
		}

		@Override
		public InventorySlot findSlot(ItemPredicate filter, boolean findEmpty) {
			for (int i = 0;i<connected.size();i++) {
				IInventoryAccess ia = connected.get(i);
				InventorySlot is = ia.tracker().findSlot(filter, findEmpty);
				if (is != null)return is;
			}
			return null;
		}

		@Override
		public InventorySlot findSlotDest(StoredItemStack forStack) {
			for (int i = 0;i<connected.size();i++) {
				IInventoryAccess ia = connected.get(i);
				InventorySlot is = ia.tracker().findSlotDest(forStack);
				if (is != null)return is;
			}
			return null;
		}

		/**
		 * Continues in the member that handed out {@code slot}, then in the members after it. Looping
		 * happens here, across the whole network, and never inside a member: a member told to loop
		 * restarts at its own first match, so a filtered hopper pulling from a network kept getting
		 * the same (unmovable) slot of the first inventory and never reached the others (A8).
		 */
		@Override
		public InventorySlot findSlotAfter(InventorySlot slot, ItemPredicate filter, boolean findEmpty, boolean loop) {
			if (slot == null)return findSlot(filter, findEmpty);
			for (int i = Math.max(ownerIndex(slot), 0);i<connected.size();i++) {
				IInventoryAccess ia = connected.get(i);
				// Members after the owner start from their beginning; passing the slot keeps the
				// shared "seen" set, so a slot reachable through two accesses is not offered twice.
				InventorySlot is = ia.tracker().findSlotAfter(slot, filter, findEmpty, false);
				if (is != null)return is;
			}
			return loop ? findSlot(filter, findEmpty) : null;
		}

		/** Same as {@link #findSlotAfter}: continue after the owner of {@code slot}, loop at this level only. */
		@Override
		public InventorySlot findSlotDestAfter(InventorySlot slot, StoredItemStack forStack, boolean loop) {
			if (slot == null)return findSlotDest(forStack);
			int owner = ownerIndex(slot);
			for (int i = Math.max(owner, 0);i<connected.size();i++) {
				IInventoryChangeTracker tr = connected.get(i).tracker();
				InventorySlot is;
				if (i <= owner || owner < 0 || slot.getView() != null)is = tr.findSlotDestAfter(slot, forStack, false);
				else is = tr.findSlotDest(forStack);// a view-less destination slot has no position to continue from
				if (is != null)return is;
			}
			return loop ? findSlotDest(forStack) : null;
		}

		/** Index of the member that handed out {@code slot}, or -1 (network rebuilt since, or unknown). */
		private int ownerIndex(InventorySlot slot) {
			for (int i = 0;i<connected.size();i++) {
				if (connected.get(i).tracker().owns(slot))return i;
			}
			return -1;
		}

		@Override
		public boolean owns(InventorySlot slot) {
			return ownerIndex(slot) >= 0;
		}
	}

	public Collection<IInventoryAccess> getConnected() {
		return connected;
	}
}
