package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.tile.AbstractFilterBlockEntity;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;

/**
 * The item access of an Ejector, Disposal or Exporter (Fabric twin): what stands on one side of the
 * block, behind it (the source) or in front (the Exporter's target).
 *
 * <p>A cable, connector or interface there leads to the Inventory Connector of its network. The cables
 * are walked like the Basic Inventory Hopper walks them, but the connector found is kept until
 * {@link NetworkChanges} reports a change, the connector is unloaded or a slow heartbeat is due.
 * Anything else is a single inventory, looked up for every attempt (one block lookup).
 *
 * <p>Changes are read from item totals ({@link StorageSnapshot}): the ones a network shares with its
 * terminals and level emitters, or this side's own for a single inventory. {@link #markSeen()} and
 * {@link #changedFor} let a block that found nothing to do wait without searching slots.
 *
 * <p>One operation gathers its items from as many slots as it takes, but after the first slot that
 * gave it items it looks at no more than {@link FilterBlockLogic#MAX_SLOT_VISITS} further stocked
 * slots: the batch is then smaller and the next operation goes on from there. The search before that
 * first slot is not bounded; it only runs when something the filter matches changed, and ends with
 * the block going idle.
 *
 * <p>Nothing is taken from a block that is not loaded: a network only counts while its connector is
 * loaded, and a member of it that stands in an unloaded chunk of this block's dimension is passed
 * over. A connector drops such a member with its next scan only, and until then the member's block
 * entity may already be saved: what was taken out of it would be back after a reload. No chunk is
 * ever loaded to find out.
 *
 * <p>Every operation is one transaction: either the items arrive or nothing happened. Server thread only.
 */
public final class FilterBlockAccess {
	/** Ticks after which the cables are walked again although nothing reported a change. */
	private static final int HEARTBEAT = 100;
	/** The same while the cables lead to no connector (one may have been loaded since). */
	private static final int RETRY = 20;
	/** {@link #move}: the target has no room for the item. */
	private static final long NO_ROOM = -1;

	private final Consumer<ItemStack> overflow;
	private Level level;
	private BlockPos self, pos;
	private Direction side;
	/** The connector the cables at {@link #pos} lead to, as of {@link #lookupTime}. */
	private InventoryConnectorBlockEntity connector;
	private boolean lookedUp;
	private long lookupTime, lookupGeneration;
	/** What {@link #resolve()} found, null when nothing. */
	private Storage<ItemVariant> storage;
	/** Item totals of a single inventory; a network has its own. */
	private StorageSnapshot own;
	private StorageSnapshot seen;
	private long seenVersion;
	private int seenSlots;
	private boolean refused;
	private final List<ItemVariant> changed = new ArrayList<>();

	/** @param overflow gets items that could not be put back where they came from (never called while inventories behave) */
	public FilterBlockAccess(Consumer<ItemStack> overflow) {
		this.overflow = overflow;
	}

	/**
	 * Points this side at {@code pos}.
	 *
	 * @param self the block that asks
	 * @param side the face of {@code pos} that touches the block
	 */
	public void bind(Level level, BlockPos self, BlockPos pos, Direction side) {
		this.level = level;
		this.self = self;
		this.pos = pos;
		this.side = side;
		storage = null;
		own = null;
		seen = null;
		invalidate();
	}

	/** What stands at the position may be another block now: the cables are walked again for the next attempt. */
	public void invalidate() {
		lookedUp = false;
		connector = null;
	}

	/**
	 * Finds the storage of this side for the attempt that follows.
	 *
	 * @return whether there is one
	 */
	public boolean resolve() {
		storage = null;
		if (level == null || !level.isLoaded(pos))return false;
		if (level.getBlockState(pos).getBlock() instanceof IInventoryCable) {
			long now = level.getGameTime(), generation = NetworkChanges.get();
			long age = now - lookupTime;
			if (!lookedUp || generation != lookupGeneration || age < 0 || (connector != null ? connector.isRemoved() || age >= HEARTBEAT : age >= RETRY)) {
				lookedUp = true;
				lookupTime = now;
				lookupGeneration = generation;
				connector = AbstractFilterBlockEntity.findConnector(level, self, pos);
			}
			// Asked every time, the connector found earlier may stand in a chunk that was unloaded since
			if (connector != null && level.isLoaded(connector.getBlockPos()))storage = connector.getNetworkStorage();
			return storage != null;
		}
		invalidate();
		Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, side);
		if (s == null) {
			Container inv = HopperBlockEntity.getContainerAt(level, pos);
			if (inv != null)s = InventoryStorage.of(inv, side);
		}
		storage = s;
		return s != null;
	}

	/** The Network Doctor timing window of the resolved storage's network, null when it is none. */
	public NetworkStats stats() {
		return NetworkDoctor.statsOf(storage);
	}

	private StorageSnapshot snapshot() {
		if (storage instanceof MergedStorage m)return m.getSnapshot();
		if (own == null)own = new StorageSnapshot();
		return own;
	}

	/** Remembers the contents as they are right now, read afresh, for {@link #changed()} and {@link #changedFor}. */
	public void markSeen() {
		StorageSnapshot s = snapshot();
		s.invalidate();
		s.refresh(storage, level.getGameTime(), 1);
		seen = s;
		seenVersion = s.getVersion();
		seenSlots = s.getSlotCount();
	}

	/**
	 * Whether the contents or the number of slots changed since {@link #markSeen()}. The slot count
	 * matters for a target: a box put into an empty Shulker Dock adds 27 empty slots.
	 *
	 * @param maxAge how old the item totals may be, in ticks. A change they do not show yet is not
	 *        lost: the call that reads them again reports it.
	 */
	public boolean changed(int maxAge) {
		StorageSnapshot s = snapshot();
		if (s != seen)return true;
		s.refresh(storage, level.getGameTime(), maxAge);
		return s.getVersion() != seenVersion || s.getSlotCount() != seenSlots;
	}

	/**
	 * Whether the amount of an item {@code filter} matches changed since {@link #markSeen()}. Other
	 * items coming and going are read from the change log and do not count, so a busy network does not
	 * wake a block that has nothing to do with them.
	 *
	 * @param maxAge like {@link #changed}
	 */
	public boolean changedFor(GhostFilter filter, int maxAge) {
		StorageSnapshot s = snapshot();
		if (s != seen)return true;
		s.refresh(storage, level.getGameTime(), maxAge);
		long v = s.getVersion();
		if (v == seenVersion)return false;
		if (!s.canDiffSince(seenVersion))return true;
		changed.clear();
		s.collectChangedSince(seenVersion, changed);
		boolean hit = false;
		for (int i = 0; i < changed.size() && !hit; i++)hit = matches(filter, changed.get(i));
		changed.clear();
		if (!hit)seenVersion = v;
		return hit;
	}

	private static boolean matches(GhostFilter filter, ItemVariant resource) {
		if (filter.testPlain(resource.getItem(), resource.getNbt()))return true;
		List<ItemPredicate> filters = filter.filterEntries();
		for (int i = 0; i < filters.size(); i++) {
			if (filters.get(i).test(resource))return true;
		}
		return false;
	}

	/**
	 * Whether {@code part}, a member of the network {@code owner}, stands in a chunk that is not
	 * loaded and must be left alone (see the class comment). Only known for a member whose block the
	 * network remembers and that is in the dimension of this block; any other member is walked.
	 *
	 * @param dim the dimension the members of {@code owner} are in
	 */
	private boolean unloaded(MergedStorage owner, Storage<ItemVariant> part, ResourceKey<Level> dim) {
		BlockPos p = owner.getPosition(part);
		return p != null && dim == level.dimension() && !level.isLoaded(p);
	}

	/**
	 * The stocked slots of the resolved storage in its own order (a network: its inventories by
	 * priority), one inventory after the other so that each is known by the block it stands for.
	 * Members in unloaded chunks are left out, see {@link #unloaded}; for that a network reached over
	 * an Inventory Link is walked member by member too.
	 */
	private final class Views {
		private final MergedStorage merged = storage instanceof MergedStorage m ? m : null;
		private Iterator<? extends StorageView<ItemVariant>> current = merged == null ? storage.iterator() : null;
		private int nextPart;
		private Storage<ItemVariant> part;
		/** The inventories of the linked network {@link #part} hands on, null when it is any other member. */
		private MergedStorage linked;
		private ResourceKey<Level> linkedDimension;
		private int nextLinked;
		private StorageView<ItemVariant> view;
		/** Whether {@link #part} lies in the target: 0 not asked yet, 1 no, 2 yes. */
		private int partExcluded;

		/** @return the next stocked slot, null at the end */
		StorageView<ItemVariant> next() {
			while (true) {
				while (current != null && current.hasNext()) {
					StorageView<ItemVariant> v = current.next();
					if (v.isResourceBlank() || v.getAmount() <= 0)continue;
					return view = v;
				}
				current = null;
				if (linked != null) {
					if (nextLinked < linked.parts.size()) {
						Storage<ItemVariant> p = linked.parts.get(nextLinked++);
						if (p != InfoHandler.INSTANCE && !unloaded(linked, p, linkedDimension))current = p.iterator();
						continue;
					}
					linked = null;
				}
				if (merged == null || nextPart >= merged.parts.size())return view = null;
				part = merged.parts.get(nextPart++);
				partExcluded = 0;
				if (part == InfoHandler.INSTANCE || unloaded(merged, part, level.dimension()))continue;
				if (part instanceof InventoryConnectorBlockEntity.LocalView v && v.get() instanceof MergedStorage m) {
					// The same slots in the same order as the view's own iterator gives them
					linked = m;
					linkedDimension = TargetExclusion.dimensionOf(part, level.dimension());
					nextLinked = 0;
				} else {
					current = part.iterator();
				}
			}
		}

		/** Whether the slot {@link #next()} returned lies in the target. */
		boolean excluded(TargetExclusion exclusion) {
			if (part != null) {
				if (partExcluded == 0)partExcluded = exclusion.excludes(merged, part) ? 2 : 1;
				if (partExcluded == 2)return true;
			}
			return exclusion.excludes(view);
		}
	}

	/**
	 * Takes one batch out of the storage: matching items of ONE type (same item and NBT), a single
	 * item or up to one stack, gathered from as many slots as it takes, in slot order, within
	 * {@link FilterBlockLogic#MAX_SLOT_VISITS} stocked slots after the first that gave any.
	 *
	 * @return the batch as one stack, EMPTY when nothing matching can be taken
	 */
	public ItemStack extractBatch(GhostFilter filter, FilterAmount amount) {
		ItemVariant type = null;
		long got = 0, max = 1;
		// Stocked slots looked at since the first one that gave items
		int visits = 0;
		try (Transaction tx = Transaction.openOuter()) {
			Views views = new Views();
			for (StorageView<ItemVariant> view; (view = views.next()) != null;) {
				if (type != null && !FilterBlockLogic.gathersFrom(++visits))break;
				ItemVariant resource = view.getResource();
				long want;
				if (type == null) {
					if (!matches(filter, resource))continue;
					want = FilterBlockLogic.batchSize(amount, resource.getItem().getMaxStackSize());
				} else {
					if (!type.equals(resource))continue;
					want = max - got;
				}
				long ex = view.extract(resource, want, tx);
				if (ex <= 0)continue;
				if (type == null) {
					type = resource;
					max = want;
				}
				got += ex;
				if (got >= max)break;
			}
			if (got > 0)tx.commit();
		}
		return type == null ? ItemStack.EMPTY : type.toStack((int) got);
	}

	/** Returns {@code stack} to the storage. Whatever does not fit goes to the overflow, never lost. */
	public void giveBack(ItemStack stack) {
		if (stack.isEmpty())return;
		long inserted = 0;
		if (storage != null) {
			try (Transaction tx = Transaction.openOuter()) {
				inserted = storage.insert(ItemVariant.of(stack), stack.getCount(), tx);
				tx.commit();
			}
		}
		if (inserted < stack.getCount())overflow.accept(stack.copyWithCount((int) (stack.getCount() - inserted)));
	}

	/**
	 * Moves matching items of ONE type from this storage into {@code target}: a single item or up to
	 * one stack, from and into as many slots as it takes, within {@link FilterBlockLogic#MAX_SLOT_VISITS}
	 * stocked slots after the first that gave any. Slots that lie in the target are never taken from,
	 * see {@link TargetExclusion}. An item type the target has no room for is passed over, the next
	 * matching one is tried.
	 *
	 * @return the number of items that arrived in the target
	 */
	public int moveTo(FilterBlockAccess target, GhostFilter filter, FilterAmount amount) {
		refused = false;
		TargetExclusion exclusion = null;
		ItemVariant type = null;
		// Item types the target took nothing of in this attempt, not offered to it again
		List<ItemVariant> full = null;
		long moved = 0, max = 1;
		// Stocked slots looked at since the first one that gave items
		int visits = 0;
		try (Transaction tx = Transaction.openOuter()) {
			Views views = new Views();
			for (StorageView<ItemVariant> view; (view = views.next()) != null;) {
				if (type != null && !FilterBlockLogic.gathersFrom(++visits))break;
				ItemVariant resource = view.getResource();
				if (type != null) {
					if (!type.equals(resource))continue;
				} else if (!matches(filter, resource) || full != null && full.contains(resource)) {
					continue;
				}
				// Worked out for the first candidate only: most attempts of an idle network find none
				if (exclusion == null) {
					exclusion = exclusion(target);
					if (exclusion.isAll())break;
				}
				if (views.excluded(exclusion))continue;
				long want = type != null ? max - moved : FilterBlockLogic.batchSize(amount, resource.getItem().getMaxStackSize());
				long n = move(view, resource, Math.min(want, view.getAmount()), target.storage, tx);
				if (n > 0) {
					if (type == null) {
						type = resource;
						max = want;
					}
					moved += n;
					if (moved >= max)break;
				} else if (n == NO_ROOM) {
					refused = true;
					// The target takes no more of the item this operation moves: done
					if (type != null)break;
					if (full == null)full = new ArrayList<>();
					full.add(resource);
				}
			}
			if (moved > 0)tx.commit();
		}
		return (int) moved;
	}

	/**
	 * Moves up to {@code want} items out of one slot: what the slot gives is offered to the target,
	 * and only what the target took is taken out of the slot.
	 *
	 * @return the number of items moved; {@link #NO_ROOM} when the target took none (0: the slot gave none)
	 */
	private static long move(StorageView<ItemVariant> from, ItemVariant resource, long want, Storage<ItemVariant> to, Transaction tx) {
		long available;
		try (Transaction simulation = tx.openNested()) {
			available = from.extract(resource, want, simulation);
		}
		if (available <= 0)return 0;
		try (Transaction step = tx.openNested()) {
			long accepted = to.insert(resource, available, step);
			if (accepted <= 0)return NO_ROOM;
			if (from.extract(resource, accepted, step) != accepted)return 0;
			step.commit();
			return accepted;
		}
	}

	/** Whether the last {@link #moveTo} found matching items the target had no room for. */
	public boolean wasRefused() {
		return refused;
	}

	/** Which slots of this resolved storage lie in the resolved {@code target}. */
	public TargetExclusion exclusion(FilterBlockAccess target) {
		return TargetExclusion.of(storage, target.storage, target.level, target.pos);
	}

	/** For tests and diagnostics: the stocked slots of this storage {inside the target, outside it}. */
	public int[] countSlots(TargetExclusion exclusion) {
		int[] out = new int[2];
		if (storage == null)return out;
		Views views = new Views();
		while (views.next() != null)out[exclusion.isAll() || views.excluded(exclusion) ? 0 : 1]++;
		return out;
	}
}
