package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.tile.AbstractFilterBlockEntity;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;

/**
 * The item access of an Ejector, Disposal or Exporter (Forge twin): what stands on one side of the
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
 * <p>Item handlers have no transactions: a move is simulated first, only what the target accepts is
 * taken out, and whatever a handler refuses after all goes back where it came from, into the storage,
 * or as a last resort to the overflow. Server thread only.
 */
public final class FilterBlockAccess {
	/** Ticks after which the cables are walked again although nothing reported a change. */
	private static final int HEARTBEAT = 100;
	/** The same while the cables lead to no connector (one may have been loaded since). */
	private static final int RETRY = 20;
	/** {@link Move#move}: the target has no room for the item. */
	private static final int NO_ROOM = -1;

	private final Consumer<ItemStack> overflow;
	private Level level;
	private BlockPos self, pos;
	private Direction side;
	/** The connector the cables at {@link #pos} lead to, as of {@link #lookupTime}. */
	private InventoryConnectorBlockEntity connector;
	private boolean lookedUp;
	private long lookupTime, lookupGeneration;
	/** What {@link #resolve()} found, null when nothing. */
	private IItemHandler storage;
	/** Item totals of a single inventory; a network has its own. */
	private StorageSnapshot own;
	private StorageSnapshot seen;
	private long seenVersion;
	private int seenSlots;
	private boolean refused;
	private final List<ItemKey> changed = new ArrayList<>();

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
		storage = ItemHandlers.find(level, pos, side);
		return storage != null;
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

	private static boolean matches(GhostFilter filter, ItemKey key) {
		if (filter.testPlain(key.getItem(), key.getTag()))return true;
		List<ItemPredicate> filters = filter.filterEntries();
		if (filters.isEmpty())return false;
		ItemStack stack = key.toStack();
		for (int i = 0; i < filters.size(); i++) {
			if (filters.get(i).test(stack))return true;
		}
		return false;
	}

	/** Same item, NBT and capabilities: what one stack can hold. */
	private static boolean sameType(ItemStack a, ItemStack b) {
		return ItemHandlerHelper.canItemStacksStack(a, b);
	}

	/** Sees the stocked slots of a storage. */
	private abstract static class SlotVisitor {
		/**
		 * @param owner the network the slot's inventory is a member of, null for a single inventory
		 * @param part that member
		 * @param dim the dimension the member is in
		 * @param h the handler holding the slot
		 * @param stack what the slot shows, not to be modified
		 * @return false to stop
		 */
		abstract boolean visit(MergedStorage owner, IItemHandler part, ResourceKey<Level> dim, IItemHandler h, int slot, ItemStack stack);
	}

	/**
	 * Whether {@code part}, a member of the network {@code owner}, stands in a chunk that is not
	 * loaded and must be left alone (see the class comment). Only known for a member whose block the
	 * network remembers and that is in the dimension of this block; any other member is walked.
	 *
	 * @param dim the dimension the members of {@code owner} are in
	 */
	private boolean unloaded(MergedStorage owner, IItemHandler part, ResourceKey<Level> dim) {
		BlockPos p = owner.getPosition(part);
		return p != null && dim == level.dimension() && !level.isLoaded(p);
	}

	/**
	 * Visits the stocked slots of {@code h} in its own order (a network: its inventories by priority),
	 * like {@link ItemHandlers} walks them: networks member by member, exact delegates and frame labels
	 * through to what they stand for. A block filter's view is a handler of its own, so only what it
	 * shows can be taken. Members in unloaded chunks are left out, see {@link #unloaded}.
	 *
	 * @return false when the visitor stopped the walk; every network entered on the way is left again
	 */
	private boolean walk(MergedStorage owner, IItemHandler part, ResourceKey<Level> dim, IItemHandler h, SlotVisitor v) {
		if (h == null)return true;
		if (h instanceof MergedStorage m) {
			if (!m.enter())return true;
			try {
				for (int i = 0; i < m.parts.size(); i++) {
					IItemHandler p = m.parts.get(i);
					if (p != InfoHandler.INSTANCE && !unloaded(m, p, dim) && !walk(m, p, dim, p, v))return false;
				}
			} finally {
				m.exit();
			}
			return true;
		}
		if (h instanceof ItemHandlers.Delegating d) {
			return walk(owner, part, TargetExclusion.dimensionOf(h, dim), d.getDelegate(), v);
		}
		if (h instanceof LabelledStorage l) {
			return walk(owner, part, dim, l.getBacking(), v);
		}
		int n = h.getSlots();
		for (int i = 0; i < n; i++) {
			ItemStack s = h.getStackInSlot(i);
			if (!s.isEmpty() && !v.visit(owner, part, dim, h, i, s))return false;
		}
		return true;
	}

	private void walk(SlotVisitor v) {
		walk(null, null, level.dimension(), storage, v);
	}

	/**
	 * Takes one batch out of the storage: matching items of ONE type (same item and NBT), a single
	 * item or up to one stack, gathered from as many slots as it takes, in slot order, within
	 * {@link FilterBlockLogic#MAX_SLOT_VISITS} stocked slots after the first that gave any.
	 *
	 * <p>Whatever comes out of a slot other than expected goes straight back, so nothing that does not
	 * match the filter leaves the storage.
	 *
	 * @return the batch as one stack, EMPTY when nothing matching can be taken
	 */
	public ItemStack extractBatch(GhostFilter filter, FilterAmount amount) {
		Batch b = new Batch(filter, amount);
		walk(b);
		b.returnStrays();
		return b.batch;
	}

	/**
	 * A visitor that takes items. Items that left a slot and could not be put back into it are
	 * returned to the storage once the walk is over, also when {@link #gathers()} ended it early.
	 */
	private abstract class Taking extends SlotVisitor {
		private List<ItemStack> strays;
		/** Stocked slots looked at since the first one that gave items. */
		private int visits;

		/**
		 * Counts one more stocked slot after the first that gave items.
		 *
		 * @return false when that slot is not looked at any more, see {@link FilterBlockLogic#MAX_SLOT_VISITS}
		 */
		boolean gathers() {
			return FilterBlockLogic.gathersFrom(++visits);
		}

		void putBack(IItemHandler h, int slot, ItemStack stack) {
			ItemStack rest = h.insertItem(slot, stack, false);
			if (rest.isEmpty())return;
			if (strays == null)strays = new ArrayList<>();
			strays.add(rest);
		}

		void returnStrays() {
			if (strays == null)return;
			for (ItemStack s : strays)giveBack(s);
		}
	}

	private final class Batch extends Taking {
		private final GhostFilter filter;
		private final FilterAmount amount;
		private ItemStack batch = ItemStack.EMPTY;
		private int max = 1;

		Batch(GhostFilter filter, FilterAmount amount) {
			this.filter = filter;
			this.amount = amount;
		}

		@Override
		boolean visit(MergedStorage owner, IItemHandler part, ResourceKey<Level> dim, IItemHandler h, int slot, ItemStack cur) {
			if (!batch.isEmpty() && !gathers())return false;
			int want;
			if (batch.isEmpty()) {
				if (!filter.test(cur))return true;
				want = FilterBlockLogic.batchSize(amount, cur.getMaxStackSize());
			} else {
				if (!sameType(batch, cur))return true;
				want = max - batch.getCount();
			}
			// A copy: a slot may hand out its live stack, which changes when it is extracted from
			ItemStack ref = cur.copyWithCount(1);
			ItemStack ex = h.extractItem(slot, want, false);
			if (ex.isEmpty())return true;
			if (!sameType(ex, ref)) {
				putBack(h, slot, ex);
				return true;
			}
			if (batch.isEmpty()) {
				batch = ex;
				max = want;
			} else {
				batch.grow(ex.getCount());
			}
			return batch.getCount() < max;
		}
	}

	/** Returns {@code stack} to the storage. Whatever does not fit goes to the overflow, never lost. */
	public void giveBack(ItemStack stack) {
		if (stack.isEmpty())return;
		ItemStack rest = ItemHandlers.insert(storage, stack, false);
		if (!rest.isEmpty())overflow.accept(rest);
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
		Move m = new Move(target, filter, amount);
		walk(m);
		m.returnStrays();
		return m.moved;
	}

	private final class Move extends Taking {
		private final FilterBlockAccess target;
		private final GhostFilter filter;
		private final FilterAmount amount;
		private TargetExclusion exclusion;
		private ItemStack type = ItemStack.EMPTY;
		/** Item types the target took nothing of in this attempt, not offered to it again. */
		private List<ItemStack> full;
		private int max = 1, moved;

		Move(FilterBlockAccess target, GhostFilter filter, FilterAmount amount) {
			this.target = target;
			this.filter = filter;
			this.amount = amount;
		}

		@Override
		boolean visit(MergedStorage owner, IItemHandler part, ResourceKey<Level> dim, IItemHandler h, int slot, ItemStack cur) {
			if (!type.isEmpty() && !gathers())return false;
			if (!type.isEmpty()) {
				if (!sameType(type, cur))return true;
			} else if (!filter.test(cur) || isFull(cur)) {
				return true;
			}
			// Worked out for the first candidate only: most attempts of an idle network find none
			if (exclusion == null) {
				exclusion = exclusion(target);
				if (exclusion.isAll())return false;
			}
			if (exclusion.excludes(owner, part, dim) || exclusion.excludes(cur))return true;
			int want = !type.isEmpty() ? max - moved : FilterBlockLogic.batchSize(amount, cur.getMaxStackSize());
			// A copy: a slot may hand out its live stack, which changes when items are moved out of it
			ItemStack ref = cur.copyWithCount(1);
			int n = move(h, slot, ref, Math.min(want, cur.getCount()));
			if (n > 0) {
				if (type.isEmpty()) {
					type = ref;
					max = want;
				}
				moved += n;
				return moved < max;
			}
			if (n == NO_ROOM) {
				refused = true;
				// The target takes no more of the item this operation moves: done
				if (!type.isEmpty())return false;
				if (full == null)full = new ArrayList<>();
				full.add(ref);
			}
			return true;
		}

		private boolean isFull(ItemStack stack) {
			if (full == null)return false;
			for (int i = 0; i < full.size(); i++) {
				if (sameType(full.get(i), stack))return true;
			}
			return false;
		}

		/**
		 * Moves up to {@code want} items out of one slot: what the slot would give is offered to the
		 * target in a simulation, and only what the target would take is taken out of the slot.
		 *
		 * @return the number of items that arrived; {@link #NO_ROOM} when the target would take none (0: the slot gave none)
		 */
		private int move(IItemHandler h, int slot, ItemStack ref, int want) {
			ItemStack sim = h.extractItem(slot, want, true);
			if (sim.isEmpty() || !sameType(sim, ref))return 0;
			int can = sim.getCount() - ItemHandlers.insert(target.storage, sim, true).getCount();
			if (can <= 0)return NO_ROOM;
			ItemStack ex = h.extractItem(slot, can, false);
			if (ex.isEmpty())return 0;
			if (!sameType(ex, ref)) {
				putBack(h, slot, ex);
				return 0;
			}
			int n = ex.getCount();
			ItemStack rest = ItemHandlers.insert(target.storage, ex, false);
			if (!rest.isEmpty()) {
				n -= rest.getCount();
				putBack(h, slot, rest);
			}
			return n;
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
		walk(new SlotVisitor() {
			@Override
			boolean visit(MergedStorage owner, IItemHandler part, ResourceKey<Level> dim, IItemHandler h, int slot, ItemStack stack) {
				out[exclusion.isAll() || exclusion.excludes(owner, part, dim) || exclusion.excludes(stack) ? 0 : 1]++;
				return true;
			}
		});
		return out;
	}
}
