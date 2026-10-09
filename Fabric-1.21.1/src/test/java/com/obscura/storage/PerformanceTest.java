package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.DoubleChestTrackerTest.DoubleChestAccess;
import com.obscura.storage.DoubleChestTrackerTest.SidedBlockAccess;
import com.obscura.storage.DoubleChestTrackerTest.SidedTestContainer;
import com.obscura.storage.DoubleChestTrackerTest.TickClock;
import com.obscura.storage.RegressionFixesTest.Multi;
import com.obscura.storage.RegressionFixesTest.SwappableAccess;
import com.obscura.storage.inventory.BlockFilter;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.IProxy;
import com.obscura.storage.inventory.InventoryChangeTracker;
import com.obscura.storage.inventory.InventoryImage;
import com.obscura.storage.inventory.InventoryImage.InvState;
import com.obscura.storage.inventory.InventorySlot;
import com.obscura.storage.inventory.PlatformFilteredInventoryAccess;
import com.obscura.storage.inventory.PlatformInventoryAccess;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.inventory.filter.IFilter;
import com.obscura.storage.inventory.filter.ItemPredicate;
import com.obscura.storage.util.Priority;
import com.obscura.storage.util.TerminalSyncManager;
import com.obscura.storage.util.WorldStates;

/** Regression tests for the v4.1 performance pass. None of them measure time except as a sanity bound. */
@DisplayName("v4.1 performance pass")
public class PerformanceTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static SimpleContainer filled(int size, Item item) {
		SimpleContainer c = new SimpleContainer(size);
		for (int i = 0; i < size; i++)c.setItem(i, new ItemStack(item, 8));
		return c;
	}

	private static long count(IInventoryChangeTracker tr, Item item) {
		return tr.streamWrappedStacks(false).filter(s -> s.getStack().is(item)).mapToLong(StoredItemStack::getQuantity).sum();
	}

	// ---- 1: terminal id map ----

	private static RegistryAccess registries() {
		return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	private static StoredItemStack distinct(int i, long count) {
		ItemStack st = new ItemStack(Items.STONE);
		st.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(i));
		return new StoredItemStack(st, count);
	}

	private static void deliver(TerminalSyncManager server, TerminalSyncManager client, int changeId, Map<StoredItemStack, StoredItemStack> items) {
		List<CompoundTag> packets = new ArrayList<>();
		server.updateTo(changeId, items, null, packets::add);
		for (CompoundTag t : packets)client.receiveUpdate(registries(), t);
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(Object o, String name) throws ReflectiveOperationException {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return (T) f.get(o);
	}

	private static boolean compact(TerminalSyncManager m) throws ReflectiveOperationException {
		Method c = TerminalSyncManager.class.getDeclaredMethod("compactIdMap");
		c.setAccessible(true);
		return (boolean) c.invoke(m);
	}

	@Test
	@DisplayName("1: 20k items sync round trip stays fast and keeps ids consistent")
	void terminalSyncRoundTrip() throws ReflectiveOperationException {
		TerminalSyncManager server = new TerminalSyncManager(registries());
		TerminalSyncManager client = new TerminalSyncManager(registries());
		int n = 20_000;
		Map<StoredItemStack, StoredItemStack> items = new HashMap<>();
		for (int i = 0; i < n; i++) {
			StoredItemStack s = distinct(i, i + 1);
			items.put(s, s);
		}

		long t0 = System.nanoTime();
		deliver(server, client, 1, items);
		// Removing half of it and compacting must stay linear as well.
		Map<StoredItemStack, StoredItemStack> half = new HashMap<>();
		for (int i = 0; i < n; i += 2) {
			StoredItemStack s = distinct(i, i + 1);
			half.put(s, s);
		}
		deliver(server, client, 2, half);
		assertTrue(compact(server), "stale ids of removed items must be compacted");
		long ms = (System.nanoTime() - t0) / 1_000_000;
		// Sanity bound only: the O(n^2) array map needed a multiple of this on slow machines.
		assertTrue(ms < 10_000, "20k item sync + removal + compaction took " + ms + " ms");

		assertFalse(compact(server), "nothing left to compact: no work must be done");
		assertEquals(n / 2, client.getAsList().size());
		for (int i = 0; i < n; i += 2)assertEquals(i + 1, client.getAmount(distinct(i, 0)));

		// Ids agree on both sides: what the client would send for a stack resolves to that stack.
		Map<StoredItemStack, Integer> clientIds = field(client, "idMap");
		Map<Integer, StoredItemStack> serverById = field(server, "idMap2");
		for (int i = 0; i < n; i += 2) {
			StoredItemStack key = distinct(i, 0);
			Integer id = clientIds.get(key);
			assertNotNull(id);
			assertTrue(key.equalItem(serverById.get(id)), "id " + id + " must resolve to the same item on the server");
		}

		// A removed item that comes back gets a (new) id both sides agree on.
		StoredItemStack back = distinct(1, 5);
		half.put(back, back);
		deliver(server, client, 3, half);
		assertEquals(5, client.getAmount(distinct(1, 0)));
		assertTrue(distinct(1, 0).equalItem(serverById.get(clientIds.get(distinct(1, 0)))));
		assertFalse(client.wasLastUpdateCountsOnly(), "an item appeared");

		// Count-only update is reported as such (the screen then skips its re-filter/re-sort).
		StoredItemStack more = distinct(1, 6);
		half.put(more, more);
		deliver(server, client, 4, half);
		assertTrue(client.wasLastUpdateCountsOnly());
		assertEquals(6, client.getAmount(distinct(1, 0)));
	}

	// ---- 2: rescans of per-lookup wrappers ----

	/** A slot with a real version, like a modded storage. Counts how often its contents are iterated. */
	static final class VersionedSlot extends SingleVariantStorage<ItemVariant> {
		long version = 1;
		int reads;

		VersionedSlot(Item item, long amount) {
			set(item, amount);
		}

		void set(Item item, long amount) {
			this.variant = ItemVariant.of(item);
			this.amount = amount;
			version++;
		}

		@Override
		protected ItemVariant getBlankVariant() {
			return ItemVariant.blank();
		}

		@Override
		protected long getCapacity(ItemVariant variant) {
			return 64;
		}

		@Override
		protected void onFinalCommit() {
			version++;
		}

		@Override
		public long getVersion() {
			return version;
		}

		@Override
		public Iterator<StorageView<ItemVariant>> iterator() {
			reads++;
			return super.iterator();
		}
	}

	/** Hands out a NEW CombinedStorage over the same versioned slots on every lookup, like ItemStorage.SIDED does. */
	static final class VersionedWrapperAccess extends PlatformInventoryAccess.BlockInventoryAccess {
		final List<VersionedSlot> slots;

		VersionedWrapperAccess(List<VersionedSlot> slots) {
			this.slots = slots;
		}

		@SuppressWarnings("unchecked")
		@Override
		public Storage<ItemVariant> get() {
			return (Storage<ItemVariant>) (Storage<?>) new CombinedStorage<>(new ArrayList<>(slots));
		}

		@Override
		public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
			return this;
		}

		int reads() {
			return slots.stream().mapToInt(s -> s.reads).sum();
		}
	}

	@Test
	@DisplayName("2: an idle per-lookup wrapper over versioned parts is not iterated after the first poll")
	void idleVersionedWrapperIsNotRescanned() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = DoubleChestTrackerTest.fakeLevel(clock);
		VersionedSlot a = new VersionedSlot(Items.STONE, 10), b = new VersionedSlot(Items.DIRT, 20);
		VersionedWrapperAccess acc = new VersionedWrapperAccess(List.of(a, b));

		clock.tick();
		long first = acc.tracker().getChangeTracker(level);
		assertTrue(acc.reads() > 0);
		int reads = acc.reads();
		for (int t = 0; t < 50; t++) {
			clock.tick();
			assertEquals(first, acc.tracker().getChangeTracker(level));
		}
		assertEquals(reads, acc.reads(), "an unchanged wrapper must not be iterated again");

		a.set(Items.DIAMOND, 3);
		clock.tick();
		assertNotEquals(first, acc.tracker().getChangeTracker(level), "a real change must still be detected");
		assertEquals(3, count(acc.tracker(), Items.DIAMOND));
		assertEquals(0, count(acc.tracker(), Items.STONE));
	}

	@Test
	@DisplayName("2: the same holds on the off-thread path of a large network")
	void idleVersionedWrappersOffThread() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = DoubleChestTrackerTest.fakeLevel(clock);
		List<VersionedWrapperAccess> accs = new ArrayList<>();
		for (int i = 0; i < 10; i++)accs.add(new VersionedWrapperAccess(List.of(new VersionedSlot(Items.STONE, 1), new VersionedSlot(Items.DIRT, 2))));
		Multi multi = new Multi();
		multi.rebuild(new ArrayList<>(accs));
		for (int i = 0; i < 3; i++) {
			clock.tick();
			multi.tracker().getChangeTracker(level);
		}
		long stamp = multi.tracker().getChangeTracker(level);
		int reads = accs.stream().mapToInt(VersionedWrapperAccess::reads).sum();
		for (int t = 0; t < 20; t++) {
			clock.tick();
			assertEquals(stamp, multi.tracker().getChangeTracker(level));
		}
		assertEquals(reads, accs.stream().mapToInt(VersionedWrapperAccess::reads).sum());

		accs.get(4).slots.get(1).set(Items.EMERALD, 7);
		clock.tick();
		assertNotEquals(stamp, multi.tracker().getChangeTracker(level));
		assertEquals(7, count(multi.tracker(), Items.EMERALD));
	}

	/**
	 * Counts the slow path: the filter is only asked about slots whose contents changed. Both the
	 * main-thread and the off-thread path end up in this overload, after building an ItemStack.
	 */
	static final class CountingTracker extends InventoryChangeTracker {
		int slowPath;

		CountingTracker(Storage<ItemVariant> h) {
			super(h);
		}

		@Override
		protected boolean checkFilter(StoredItemStack stack) {
			slowPath++;
			return super.checkFilter(stack);
		}
	}

	private static void idleVanillaScenario(PlatformInventoryAccess.BlockInventoryAccess acc, SimpleContainer toChange, int slots, boolean offThread) {
		TickClock clock = new TickClock();
		Level level = DoubleChestTrackerTest.fakeLevel(clock);
		@SuppressWarnings("deprecation")
		CountingTracker tr = new CountingTracker(acc.get());
		Runnable tick = () -> {
			clock.tick();
			tr.refresh(acc.get());// a new wrapper every tick, like the real lookup
			if (offThread) {
				InventoryImage im = tr.prepForOffThread(level);
				assertNotNull(im, "vanilla storages have no usable version, the contents are compared");
				InvState st = tr.processOffThread(im);
				tr.finishOffThreadProcess(level, st);
			} else tr.getChangeTracker(level);
		};
		tick.run();
		assertEquals(slots, tr.slowPath, "the first poll looks at every slot");
		long stamp = tr.getChangeTracker(level);
		for (int t = 0; t < 50; t++)tick.run();
		assertEquals(slots, tr.slowPath, "idle polls must not create an ItemStack or ask the filter for any slot");
		assertEquals(stamp, tr.getChangeTracker(level));

		toChange.setItem(2, new ItemStack(Items.DIAMOND, 5));
		tick.run();
		assertEquals(slots + 1, tr.slowPath, "only the changed slot takes the slow path");
		assertNotEquals(stamp, tr.getChangeTracker(level));
		assertEquals(5, count(tr, Items.DIAMOND));
	}

	@Test
	@DisplayName("2: an idle double chest is polled without allocations, changes are still seen")
	void idleDoubleChestTakesFastPath() {
		SimpleContainer left = filled(27, Items.STONE);
		idleVanillaScenario(new DoubleChestAccess(left, filled(27, Items.DIRT)), left, 54, false);
	}

	@Test
	@DisplayName("2: same for the off-thread path")
	void idleDoubleChestTakesFastPathOffThread() {
		SimpleContainer left = filled(27, Items.STONE);
		idleVanillaScenario(new DoubleChestAccess(left, filled(27, Items.DIRT)), left, 54, true);
	}

	@Test
	@DisplayName("2: an idle sided inventory (furnace shape) is polled without allocations")
	void idleSidedInventoryTakesFastPath() {
		SidedTestContainer c = new SidedTestContainer(9);
		for (int i = 0; i < 9; i++)c.setItem(i, new ItemStack(Items.COAL, 4));
		idleVanillaScenario(new SidedBlockAccess(c), c, 9, false);
	}

	/** A filter whose answer and keep-last can be switched, and that reports it through stateVersion. */
	static final class SwitchableFilter implements IFilter, ItemPredicate {
		Item allowed;
		boolean keepLast;
		long version = ItemPredicate.nextStateVersion();

		SwitchableFilter(Item allowed) {
			this.allowed = allowed;
		}

		void allow(Item item) {
			allowed = item;
			version = ItemPredicate.nextStateVersion();
		}

		@Override
		public boolean test(StoredItemStack stack) {
			return stack.getStack().is(allowed);
		}

		@Override
		public long stateVersion() {
			return version;
		}

		@Override
		public ItemPredicate getItemPred() {
			return this;
		}

		@Override
		public boolean isKeepLast() {
			return keepLast;
		}

		@Override
		public Priority getPriority() {
			return Priority.NORMAL;
		}
	}

	@Test
	@DisplayName("2: a filter change is picked up although the inventory itself did not change")
	void filterChangeRescansUnchangedInventory() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = DoubleChestTrackerTest.fakeLevel(clock);
		SimpleContainer c = new SimpleContainer(4);
		c.setItem(0, new ItemStack(Items.STONE, 10));
		c.setItem(1, new ItemStack(Items.DIRT, 20));
		SwitchableFilter filter = new SwitchableFilter(Items.STONE);
		PlatformFilteredInventoryAccess acc = new PlatformFilteredInventoryAccess(new SwappableAccess(c), filter);

		for (int i = 0; i < 3; i++) {
			clock.tick();
			acc.tracker().getChangeTracker(level);
		}
		assertEquals(10, count(acc.tracker(), Items.STONE));
		assertEquals(0, count(acc.tracker(), Items.DIRT));

		filter.allow(Items.DIRT);
		clock.tick();
		acc.tracker().getChangeTracker(level);
		assertEquals(0, count(acc.tracker(), Items.STONE));
		assertEquals(20, count(acc.tracker(), Items.DIRT));

		filter.keepLast = true;
		clock.tick();
		acc.tracker().getChangeTracker(level);
		assertEquals(19, count(acc.tracker(), Items.DIRT), "keep-last must apply to unchanged slots too");
	}

	/** A same-instance storage whose version may move without its contents changing. */
	@Test
	@DisplayName("6 (A15): an off-thread pass without changes records the version it read")
	void offThreadNoChangeRecordsCurrentVersion() {
		TickClock clock = new TickClock();
		Level level = DoubleChestTrackerTest.fakeLevel(clock);
		VersionedSlot slot = new VersionedSlot(Items.STONE, 5);
		InventoryChangeTracker tr = new InventoryChangeTracker(slot);

		clock.tick();
		InventoryImage im = tr.prepForOffThread(level);
		assertNotNull(im);
		tr.finishOffThreadProcess(level, tr.processOffThread(im));

		slot.version++;// version moved, contents did not
		clock.tick();
		im = tr.prepForOffThread(level);
		assertNotNull(im);
		tr.finishOffThreadProcess(level, tr.processOffThread(im));

		clock.tick();
		assertNull(tr.prepForOffThread(level), "the version read in the last pass must be the one compared against");
		int reads = slot.reads;
		assertEquals(5, tr.countItems(new StoredItemStack(new ItemStack(Items.STONE), 1)));
		assertEquals(reads, slot.reads);
	}

	// ---- 3: filter wrapper cache ----

	@Test
	@DisplayName("3: a block filter over several inventories keeps one wrapper per inventory")
	void blockFilterWrapsPerInput() {
		BlockFilter f = new BlockFilter(BlockPos.ZERO);
		IInventoryAccess a = new SwappableAccess(filled(3, Items.STONE));
		IInventoryAccess b = new SwappableAccess(filled(3, Items.DIRT));
		IInventoryAccess wa = f.wrap(null, a), wb = f.wrap(null, b);
		assertNotSame(wa, wb);
		for (int i = 0; i < 5; i++) {
			assertSame(wa, f.wrap(null, a), "alternating inputs must not rebuild the wrapper (and its tracker)");
			assertSame(wb, f.wrap(null, b));
		}
		// Nested wrapping (a filtered inventory behind a filtered connector) is cached as well.
		BlockFilter outer = new BlockFilter(BlockPos.ZERO.above());
		IInventoryAccess nested = outer.wrap(null, wa);
		assertSame(nested, outer.wrap(null, wa));

		f.markFilterDirty();
		assertNotSame(wa, f.wrap(null, a), "a filter change must rebuild the wrappers");
	}

	// ---- 4: rebuilding an unchanged network ----

	@Test
	@DisplayName("4: rebuilding a network with the same members does not report a change")
	void unchangedRebuildIsNoChange() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = DoubleChestTrackerTest.fakeLevel(clock);
		List<IInventoryAccess> invs = new ArrayList<>();
		for (int i = 0; i < 3; i++)invs.add(new SwappableAccess(filled(9, Items.STONE)));
		Multi multi = new Multi();
		multi.rebuild(new ArrayList<>(invs));
		clock.tick();
		multi.tracker().getChangeTracker(level);
		clock.tick();
		long stamp = multi.tracker().getChangeTracker(level);
		for (int i = 0; i < 10; i++) {
			multi.rebuild(new ArrayList<>(invs));
			clock.tick();
			assertEquals(stamp, multi.tracker().getChangeTracker(level), "a periodic rebuild must not make every terminal resync");
		}

		invs.add(new SwappableAccess(filled(9, Items.DIRT)));
		multi.rebuild(new ArrayList<>(invs));
		clock.tick();
		assertNotEquals(stamp, multi.tracker().getChangeTracker(level));
		assertEquals(9 * 8, count(multi.tracker(), Items.DIRT));
	}

	// ---- 7: A8 ----

	@Test
	@DisplayName("7 (A8): continuing a search in a network moves on to the next inventory")
	void findSlotAfterMovesToNextInventory() {
		WorldStates.clearWorldStates();
		SimpleContainer first = new SimpleContainer(3), second = new SimpleContainer(3);
		first.setItem(0, new ItemStack(Items.STONE, 1));
		second.setItem(0, new ItemStack(Items.DIAMOND, 1));
		Multi multi = new Multi();
		multi.rebuild(List.of(new SwappableAccess(first), new SwappableAccess(second)));
		ItemPredicate any = s -> true;

		InventorySlot s1 = multi.tracker().findSlotAfter(null, any, false, true);
		assertTrue(s1.getStack().is(Items.STONE));
		// The hopper got a slot it could not move and asks for the next one.
		InventorySlot s2 = multi.tracker().findSlotAfter(s1, any, false, true);
		assertNotNull(s2);
		assertTrue(s2.getStack().is(Items.DIAMOND), "must reach the second inventory instead of looping inside the first");
		// Past the last inventory, looping restarts at the first one...
		InventorySlot s3 = multi.tracker().findSlotAfter(s2, any, false, true);
		assertTrue(s3.getStack().is(Items.STONE));
		// ...and without looping the search ends.
		assertNull(multi.tracker().findSlotAfter(s2, any, false, false));
	}

	// ---- 8: A11 ----

	/** Brewing stand shape: top = ingredient, bottom = bottles + ingredient, sides = bottles + fuel. */
	static final class BrewingLike extends SimpleContainer implements WorldlyContainer {
		BrewingLike() {
			super(5);
		}

		@Override
		public int[] getSlotsForFace(Direction side) {
			return switch (side) {
			case UP -> new int[] {3};
			case DOWN -> new int[] {0, 1, 2, 3};
			default -> new int[] {0, 1, 2, 4};
			};
		}

		@Override
		public boolean canPlaceItemThroughFace(int index, ItemStack stack, Direction dir) {
			return true;
		}

		@Override
		public boolean canTakeItemThroughFace(int index, ItemStack stack, Direction dir) {
			// Like vanilla side rules differing per face: slot 0 can only be taken out from below
			return index != 0 || dir == Direction.DOWN;
		}
	}

	static final class FaceAccess extends PlatformInventoryAccess.BlockInventoryAccess {
		private final WorldlyContainer container;
		private final Direction side;

		FaceAccess(WorldlyContainer container, Direction side) {
			this.container = container;
			this.side = side;
		}

		@SuppressWarnings("unchecked")
		@Override
		public Storage<ItemVariant> get() {
			return (Storage<ItemVariant>) (Storage<?>) InventoryStorage.of(container, side);
		}

		// The production versions read a BlockApiCache, which these test accesses do not have
		@Override
		public int getSlotCount() {
			int c = 0;
			for (var ignored : get())c++;
			return c;
		}

		@Override
		public int getFreeSlotCount() {
			int c = 0;
			for (var s : get()) {
				if (s.isResourceBlank())c++;
			}
			return c;
		}

		@Override
		public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
			return this;
		}
	}

	@Test
	@DisplayName("8 (A11): two faces of a sided inventory exposing different slots are both kept")
	void sidedFacesWithDifferentSlotsAreKept() {
		WorldStates.clearWorldStates();
		BrewingLike stand = new BrewingLike();
		Multi multi = new Multi();
		multi.rebuild(List.of(new FaceAccess(stand, Direction.DOWN), new FaceAccess(stand, Direction.NORTH)));
		assertEquals(2, multi.getConnected().size(), "bottom and side both start with slot 0 but expose different slots");

		multi.rebuild(List.of(new FaceAccess(stand, Direction.NORTH), new FaceAccess(stand, Direction.SOUTH)));
		assertEquals(1, multi.getConnected().size(), "two faces exposing exactly the same slots are still one inventory");

		// And the double chest dedup is unchanged.
		SimpleContainer l = filled(27, Items.STONE), r = filled(27, Items.DIRT);
		multi.rebuild(List.of(new DoubleChestAccess(l, r), new DoubleChestAccess(l, r)));
		assertEquals(1, multi.getConnected().size());
	}

	@Test
	@DisplayName("8 (A11): overlapping faces count every slot exactly once and keep their own extract rules")
	void overlappingFacesCountEachSlotOnce() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = DoubleChestTrackerTest.fakeLevel(clock);
		BrewingLike stand = new BrewingLike();
		Item[] items = {Items.STONE, Items.DIRT, Items.SAND, Items.DIAMOND, Items.EMERALD};
		for (int i = 0; i < items.length; i++)stand.setItem(i, new ItemStack(items[i], i + 1));
		FaceAccess a = new FaceAccess(stand, Direction.DOWN);// {0,1,2,3}
		FaceAccess b = new FaceAccess(stand, Direction.NORTH);// {0,1,2,4}
		Multi multi = new Multi();
		multi.rebuild(List.of(a, b));
		assertEquals(2, multi.getConnected().size(), "both faces stay, each with its own insert/extract rules");

		clock.tick();
		multi.tracker().getChangeTracker(level);
		for (int i = 0; i < items.length; i++) {
			Item it = items[i];
			assertEquals(i + 1, count(multi.tracker(), it), "slot " + i + " must be streamed exactly once");
			assertEquals(i + 1, multi.tracker().streamWrappedStacks(true).filter(s -> s.getStack().is(it)).mapToLong(StoredItemStack::getQuantity).sum());
			assertEquals(i + 1, multi.tracker().countItems(new StoredItemStack(new ItemStack(it), 1)), "countItems for slot " + i);
		}
		assertEquals(5, multi.getSlotCount(), "4 + 4 slots, 3 of them shared");
		assertEquals(0, multi.getFreeSlotCount());

		// Slot 3 is only reachable from below, slot 4 only from the side
		assertEquals(4, a.pullMatchingStack(new ItemStack(Items.DIAMOND), 4).getCount());
		assertEquals(5, b.pullMatchingStack(new ItemStack(Items.EMERALD), 5).getCount());
		// The side face may not take slot 0, the network still can, through the bottom face
		assertTrue(b.pullMatchingStack(new ItemStack(Items.STONE), 1).isEmpty());
		assertEquals(1, multi.pullMatchingStack(new ItemStack(Items.STONE), 1).getCount());
		// And an insert into the network still works
		assertTrue(multi.pushStack(new ItemStack(Items.DIAMOND, 2)).isEmpty());

		clock.tick();
		multi.tracker().getChangeTracker(level);
		assertEquals(0, count(multi.tracker(), Items.STONE));
		assertEquals(2, count(multi.tracker(), Items.DIRT));
		assertEquals(3, count(multi.tracker(), Items.SAND));
		assertEquals(2, count(multi.tracker(), Items.DIAMOND));
		assertEquals(0, count(multi.tracker(), Items.EMERALD));
		assertEquals(2, multi.getFreeSlotCount(), "slots 0 and 4 are empty now, each counted once");
	}

	// ---- 9: client sort keys ----

	@Test
	@DisplayName("9: sort keys are cached and carried over to count updates")
	void sortKeysAreCached() {
		StoredItemStack s = new StoredItemStack(new ItemStack(Items.DIAMOND_SWORD), 1);
		String lower = s.getDisplayNameLower();
		assertEquals(s.getDisplayName().toLowerCase(), lower);
		assertSame(lower, s.getDisplayNameLower());
		assertEquals("minecraft", s.getModName());
		assertEquals("minecraft:diamond_sword", s.getRegistryName());
		StoredItemStack update = new StoredItemStack(s.getStack(), 5);
		update.copySortKeysFrom(s);
		assertSame(lower, update.getDisplayNameLower(), "a count update must not recompute the keys");
		List<StoredItemStack> list = new ArrayList<>(IntStream.range(0, 3).mapToObj(i -> new StoredItemStack(new ItemStack(i == 0 ? Items.STONE : i == 1 ? Items.APPLE : Items.DIRT), 1)).toList());
		list.sort(new StoredItemStack.ComparatorRegistryId(false));
		assertEquals(List.of("minecraft:apple", "minecraft:dirt", "minecraft:stone"), list.stream().map(StoredItemStack::getRegistryName).toList());
	}
}
