package com.obscura.storage.inventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.EmptyItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;

public class PlatformMultiInventoryAccess extends MultiInventoryAccess implements IItemHandler {
	/** Start slot of each non-empty member, ascending. */
	private int[] offsets = new int[0];
	/** Index into {@link #connected} of the member at the same position in {@link #offsets}. */
	private int[] members = new int[0];
	private int invSize, offsetsSize;
	// Per thread: a network can be read from a worker thread (terminal search) while the server
	// thread uses it, and a plain flag set by one would make the other see an empty inventory.
	private final ThreadLocal<Boolean> calling = ThreadLocal.withInitial(() -> Boolean.FALSE);

	// ---- Platform hooks used by MultiInventoryAccess: inventory identity on IItemHandler ----

	/**
	 * One slot of a container. Interned ({@link #slotRef}), so the same slot is always the same object
	 * and identity sets (see MultiInventoryAccess#overlaps) can hold and compare them. NeoForge has no
	 * per-slot view object like Fabric's Transfer API, this stands in for it.
	 */
	static final class SlotRef {
		final Container container;
		final int slot;

		private SlotRef(Container container, int slot) {
			this.container = container;
			this.slot = slot;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)return true;
			return obj instanceof SlotRef o && o.container == container && o.slot == slot;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(container) * 31 + slot;
		}
	}

	private static final Interner<SlotRef> SLOT_REFS = Interners.newWeakInterner();

	/** The canonical {@link SlotRef} of {@code slot} of {@code container}. */
	static SlotRef slotRef(Container container, int slot) {
		return SLOT_REFS.intern(new SlotRef(container, slot));
	}

	/**
	 * The container and face a {@link SidedInvWrapper} exposes: {@code slots[i]} is the container slot
	 * behind the wrapper's slot {@code i}.
	 */
	static record Face(WorldlyContainer container, int[] slots) {}

	/**
	 * The face behind {@code h}, the handler of the block at {@code pos} seen from {@code side}, or null
	 * when it is not a sided wrapper (plain container, chest, other mods).
	 *
	 * <p>{@code SidedInvWrapper} hides the container and face it wraps (its fields are protected and
	 * NeoForge classes cannot be access-transformed), so the container is taken from the block entity
	 * and confirmed through {@code equals}, which compares exactly container and face. A wrapper that
	 * does not match (a container made per lookup, like the composter's) is treated as a plain handler.
	 */
	static Face faceOf(IItemHandler h, Level level, BlockPos pos, Direction side) {
		if (!(h instanceof SidedInvWrapper) || level == null || pos == null || side == null)return null;
		if (!(level.getBlockEntity(pos) instanceof WorldlyContainer wc))return null;
		if (!h.equals(new SidedInvWrapper(wc, side)))return null;
		return new Face(wc, wc.getSlotsForFace(side));
	}

	/**
	 * The underlying slots a plain (unfiltered, unproxied) access to a sided block exposes, or null for
	 * any other access. Only sided inventories can partly overlap: everything else is either the same
	 * inventory (deduplicated in addDistinct) or a different one. The slots are interned
	 * {@link SlotRef}s, compared by identity; {@link #isSlotEmpty} reads them. The change tracker maps
	 * them back to its own slots, see {@link InventoryChangeTracker#streamWrappedStacks(java.util.Set)}.
	 */
	@SuppressWarnings("deprecation")
	static List<Object> sidedSlots(IInventoryAccess a) {
		if (!(a instanceof PlatformInventoryAccess.BlockInventoryAccess))return null;
		if (a.getRootHandler(Collections.newSetFromMap(new IdentityHashMap<>())) != a)return null;
		Face f = ((PlatformInventoryAccess.BlockInventoryAccess) a).face();
		if (f == null)return null;
		List<Object> views = new ArrayList<>(f.slots().length);
		for (int s : f.slots())views.add(slotRef(f.container(), s));
		return views;
	}

	/** Whether a slot returned by {@link #sidedSlots} is empty. */
	static boolean isSlotEmpty(Object slot) {
		SlotRef r = (SlotRef) slot;
		return r.container.getItem(r.slot).isEmpty();
	}

	private static final Interner<HandlerKey> HANDLERS = Interners.newWeakInterner();

	/** Canonical key for handlers that are equal ({@code equals}), e.g. two wrappers of one container and face. */
	private static record HandlerKey(IItemHandler h) {
		@Override
		public boolean equals(Object obj) {
			if (this == obj)return true;
			return obj instanceof HandlerKey o && (o.h == h || h.equals(o.h));
		}

		@Override
		public int hashCode() {
			return h.hashCode();
		}
	}

	/**
	 * Stable identity of the physical inventory a plain access points at, or null when it has none (or
	 * when the access wraps or filters its target, in which case two accesses over the same inventory
	 * are legitimately different and must both be kept).
	 *
	 * <p>A capability lookup hands out a new wrapper per {@code BlockCapabilityCache}, so two
	 * connectors touching one chest see two different handler objects. The key is therefore:
	 * <ul>
	 * <li>for an {@link InvWrapper} (vanilla non-sided containers), the container itself; for a double
	 * chest, whose container is a new {@link CompoundContainer} per lookup, its first half (the same
	 * block entity from either half, so both halves collapse into one);</li>
	 * <li>for a {@link SidedInvWrapper}, the set of container slots the face exposes: the face decides
	 * which slots are visible, so only faces showing exactly the same slots collapse (all four sides of
	 * a furnace). Faces that share only some slots (furnace bottom and side) both stay, each keeps its
	 * own insert/extract rules, and the shared slots are counted once, see MultiInventoryAccess#overlaps;</li>
	 * <li>otherwise the handler, canonicalised through {@code equals}, which merges handlers that are
	 * the same object or equal.</li>
	 * </ul>
	 */
	@SuppressWarnings("deprecation")
	static Object storageIdentity(IInventoryAccess a) {
		if (!(a instanceof PlatformInventoryAccess.BlockInventoryAccess))return null;
		Object h = a.get();
		if (!(h instanceof IItemHandler ih))return null;
		return identityOf(ih, ((PlatformInventoryAccess.BlockInventoryAccess) a).face());
	}

	static Object identityOf(IItemHandler h, Face f) {
		if (h instanceof InvWrapper w) {
			Container c = w.getInv();
			if (c instanceof CompoundContainer cc)return cc.container1;
			return c;
		}
		if (f != null) {
			List<Integer> slots = new ArrayList<>(f.slots().length);
			for (int s : f.slots())slots.add(s);
			// Interned: the dedup set compares keys by identity.
			return SlotSetKey.INTERNER.intern(new SlotSetKey(slots, p -> slotRef(f.container(), (Integer) p)));
		}
		return HANDLERS.intern(new HandlerKey(h));
	}

	@Deprecated
	@Override
	public IItemHandler get() {
		return this;
	}

	private int findInventory(int slot) {
		int arrayIndex = Arrays.binarySearch(offsets, 0, offsetsSize, slot);
		if (arrayIndex < 0) {
			arrayIndex = -arrayIndex - 2;
		}
		return arrayIndex;
	}

	@Override
	public boolean isItemValid(int slot, ItemStack stack) {
		if(calling.get())return false;
		if(slot < 0 || slot >= invSize)return false;
		calling.set(true);
		try {
			int arrayIndex = findInventory(slot);
			int invSlot = slot - offsets[arrayIndex];
			return getHandler(arrayIndex, invSlot).isItemValid(invSlot, stack);
		} finally {
			calling.set(false);
		}
	}

	@Override
	public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
		if(calling.get())return stack;
		if(slot < 0 || slot >= invSize)return stack;
		calling.set(true);
		try {
			int arrayIndex = findInventory(slot);
			int invSlot = slot - offsets[arrayIndex];
			return getHandler(arrayIndex, invSlot).insertItem(invSlot, stack, simulate);
		} finally {
			calling.set(false);
		}
	}

	@Override
	public ItemStack getStackInSlot(int slot) {
		if(calling.get())return ItemStack.EMPTY;
		if(slot < 0 || slot >= invSize)return ItemStack.EMPTY;
		calling.set(true);
		try {
			int arrayIndex = findInventory(slot);
			int invSlot = slot - offsets[arrayIndex];
			return getHandler(arrayIndex, invSlot).getStackInSlot(invSlot);
		} finally {
			calling.set(false);
		}
	}

	@Override
	public int getSlots() {
		return invSize;
	}

	@Override
	public int getSlotLimit(int slot) {
		if(calling.get())return 0;
		if(slot < 0 || slot >= invSize)return 0;
		calling.set(true);
		try {
			int arrayIndex = findInventory(slot);
			int invSlot = slot - offsets[arrayIndex];
			return getHandler(arrayIndex, invSlot).getSlotLimit(invSlot);
		} finally {
			calling.set(false);
		}
	}

	@Override
	public ItemStack extractItem(int slot, int amount, boolean simulate) {
		if(calling.get())return ItemStack.EMPTY;
		if(slot < 0 || slot >= invSize)return ItemStack.EMPTY;
		calling.set(true);
		try {
			int arrayIndex = findInventory(slot);
			int invSlot = slot - offsets[arrayIndex];
			return getHandler(arrayIndex, invSlot).extractItem(invSlot, amount, simulate);
		} finally {
			calling.set(false);
		}
	}

	@Override
	public void clear() {
		super.clear();
		invSize = 0;
		offsetsSize = 0;
	}

	@Override
	public void refresh() {
		int n = connected.size();
		if(offsets.length != n) {
			offsets = new int[n];
			members = new int[n];
		}
		invSize = 0;
		int k = 0;
		for (int i = 0; i < n; i++) {
			IItemHandler ih = memberHandler(i);
			int s = ih.getSlots();
			if (s > 0) {
				// Indexing connected with the compacted position picked the wrong
				// member as soon as an empty one was skipped.
				offsets[k] = invSize;
				members[k] = i;
				k++;
				invSize += s;
			}
		}
		offsetsSize = k;
	}

	private IItemHandler memberHandler(int connectedIndex) {
		if (connectedIndex >= connected.size())return EmptyItemHandler.INSTANCE;
		IItemHandler h = connected.get(connectedIndex).getPlatformHandler();
		return h != null ? h : EmptyItemHandler.INSTANCE;
	}

	private IItemHandler getHandler(int arrayIndex, int invSlot) {
		if (arrayIndex < 0 || arrayIndex >= offsetsSize)return EmptyItemHandler.INSTANCE;
		IItemHandler h = memberHandler(members[arrayIndex]);
		if (invSlot < h.getSlots())
			return h;
		return EmptyItemHandler.INSTANCE;
	}
}
