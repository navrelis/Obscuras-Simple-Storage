package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedSlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.IProxy;
import com.obscura.storage.inventory.InventoryChangeTracker;
import com.obscura.storage.inventory.MultiInventoryAccess;
import com.obscura.storage.inventory.PlatformInventoryAccess;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.util.WorldStates;

/**
 * Regression test for the double-chest terminal refresh storm.
 *
 * <p>This exercises PRODUCTION classes ({@link InventoryChangeTracker}, {@link WorldStates},
 * {@link MultiInventoryAccess}) against REAL Fabric Transfer API objects. The only thing modelled
 * is what {@code ItemStorage.SIDED} does for a vanilla double chest, which was read out of
 * {@code fabric-transfer-api-v1} {@code ItemStorage.java}:
 *
 * <pre>
 * SlottedStorage&lt;ItemVariant&gt; first  = InventoryStorage.of(accessor.fabric_getFirst(),  direction);
 * SlottedStorage&lt;ItemVariant&gt; second = InventoryStorage.of(accessor.fabric_getSecond(), direction);
 * return new CombinedSlottedStorage&lt;&gt;(List.of(first, second));   // NEW OBJECT EVERY LOOKUP
 * </pre>
 *
 * <p>{@code CombinedStorage} does not override {@link Storage#getVersion()}, so it inherits the
 * default implementation backed by a global counter that increments on every call. The two facts
 * together mean a double chest hands the mod a new {@code Storage} identity with a brand-new
 * version on every single tick.
 *
 * <p>{@code InventoryStorage.of} is itself cached per {@code Container}, so {@code parts.get(0)}
 * and {@code parts.get(1)} ARE stable across re-creation — that stability is what the fix relies
 * on.
 */
@DisplayName("Double chest tracker stability")
public class DoubleChestTrackerTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * A stand-in for {@code Level} that only needs {@code getGameTime()}. The production trackers
	 * call nothing else on it. Using a hand written subclass avoids pulling Mockito in.
	 */
	static final class TickClock {
		private long time;

		long tick() {
			return ++time;
		}

		long time() {
			return time;
		}
	}

	/**
	 * The production trackers only ever call {@code level.getGameTime()} on the Level, and a real
	 * Level cannot be constructed outside a running server, so stub it.
	 */
	static Level fakeLevel(TickClock clock) {
		Level level = Mockito.mock(Level.class, Mockito.withSettings().defaultAnswer(Mockito.RETURNS_DEFAULTS));
		Mockito.when(level.getGameTime()).thenAnswer(inv -> clock.time());
		return level;
	}

	/**
	 * Mirrors {@code PlatformInventoryAccess.BlockInventoryAccess} for one double chest: hands out
	 * a FRESH {@code CombinedSlottedStorage} on every {@link #get()} exactly like
	 * {@code ItemStorage.SIDED} does. Everything else - crucially {@code tracker()} - is the
	 * unmodified production implementation, so these tests exercise the real tracker-ownership path.
	 */
	static final class DoubleChestAccess extends PlatformInventoryAccess.BlockInventoryAccess {
		private final SimpleContainer left, right;
		final AtomicLong lookups = new AtomicLong();

		DoubleChestAccess(SimpleContainer left, SimpleContainer right) {
			this.left = left;
			this.right = right;
		}

		@SuppressWarnings("unchecked")
		@Override
		public Storage<ItemVariant> get() {
			lookups.incrementAndGet();
			SlottedStorage<ItemVariant> a = InventoryStorage.of(left, null);
			SlottedStorage<ItemVariant> b = InventoryStorage.of(right, null);
			return (Storage<ItemVariant>) (Storage<?>) new CombinedSlottedStorage<>(List.of(a, b));
		}

		@Override
		public int getFreeSlotCount() {
			int em = 0;
			for (var slot : get()) {
				if (slot.isResourceBlank())em++;
			}
			return em;
		}

		@Override
		public int getSlotCount() {
			int c = 0;
			for (var ignored : get())c++;
			return c;
		}

		@Override
		public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
			return this;
		}
	}

	/** A WorldlyContainer with every slot reachable from every side, like a furnace's shape. */
	static final class SidedTestContainer extends SimpleContainer implements WorldlyContainer {
		private final int[] slots;

		SidedTestContainer(int size) {
			super(size);
			slots = IntStream.range(0, size).toArray();
		}

		@Override
		public int[] getSlotsForFace(Direction side) {
			return slots;
		}

		@Override
		public boolean canPlaceItemThroughFace(int index, ItemStack stack, Direction dir) {
			return true;
		}

		@Override
		public boolean canTakeItemThroughFace(int index, ItemStack stack, Direction dir) {
			return true;
		}
	}

	/**
	 * One sided block inventory (furnace / hopper / dropper / dispenser / brewing stand shape).
	 *
	 * <p>{@code InventoryStorage.of(worldlyContainer, direction)} returns a FRESH
	 * {@code SidedInventoryStorageImpl} on every call - it is a {@code CombinedStorage} SUBCLASS
	 * whose {@code parts} are newly allocated {@code SidedInventorySlotWrapper}s too, so neither the
	 * wrapper nor its parts are usable as a stable key. The connector always queries with a
	 * direction, so this is the shape every furnace on a network produces.
	 */
	static final class SidedBlockAccess extends PlatformInventoryAccess.BlockInventoryAccess {
		private final SidedTestContainer container;

		SidedBlockAccess(SidedTestContainer container) {
			this.container = container;
		}

		@SuppressWarnings("unchecked")
		@Override
		public Storage<ItemVariant> get() {
			return (Storage<ItemVariant>) (Storage<?>) InventoryStorage.of(container, Direction.UP);
		}

		@Override
		public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
			return this;
		}
	}

	/** Stands in for a filtered/proxied access: a distinct object resolving to the same root. */
	static final class WrappingAccess implements IInventoryAccess {
		private final IInventoryAccess inner;

		WrappingAccess(IInventoryAccess inner) {
			this.inner = inner;
		}

		@Override
		public Storage<ItemVariant> get() {
			return inner.getPlatformHandler();
		}

		@Override
		public IInventoryChangeTracker tracker() {
			return inner.tracker();
		}

		@Override
		public int getFreeSlotCount() {
			return inner.getFreeSlotCount();
		}

		@Override
		public int getSlotCount() {
			return inner.getSlotCount();
		}

		@Override
		public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
			return inner.getRootHandler(dejaVu);
		}
	}

	private static SimpleContainer filled(int size, net.minecraft.world.item.Item item) {
		SimpleContainer c = new SimpleContainer(size);
		for (int i = 0; i < size; i++)c.setItem(i, new ItemStack(item, 8));
		return c;
	}

	/** The tracker the access actually delegates to, through the production {@code tracker()}. */
	private static IInventoryChangeTracker resolved(IInventoryAccess acc) throws Exception {
		IInventoryChangeTracker t = acc.tracker();
		var f = IInventoryChangeTracker.Delegate.class.getDeclaredField("delegate");
		f.setAccessible(true);
		return (IInventoryChangeTracker) f.get(t);
	}

	@Test
	@DisplayName("A double chest keeps ONE tracker across Storage re-creation")
	void trackerIdentityIsStable() throws Exception {
		WorldStates.clearWorldStates();
		DoubleChestAccess acc = new DoubleChestAccess(filled(27, Items.STONE), filled(27, Items.DIRT));

		IInventoryChangeTracker first = resolved(acc);
		for (int i = 0; i < 50; i++) {
			assertSame(first, resolved(acc),
					"one physical double chest must map to ONE InventoryChangeTracker even though "
							+ "ItemStorage.SIDED returns a new CombinedSlottedStorage on every lookup");
		}
	}

	@Test
	@DisplayName("A sided inventory (furnace/hopper) keeps ONE tracker across wrapper re-creation")
	void sidedInventoryTrackerIdentityIsStable() throws Exception {
		WorldStates.clearWorldStates();
		SidedTestContainer c = new SidedTestContainer(27);
		for (int i = 0; i < 27; i++)c.setItem(i, new ItemStack(Items.STONE, 8));
		SidedBlockAccess acc = new SidedBlockAccess(c);

		IInventoryChangeTracker first = resolved(acc);
		for (int i = 0; i < 50; i++) {
			assertSame(first, resolved(acc),
					"InventoryStorage.of(WorldlyContainer, dir) returns a fresh SidedInventoryStorageImpl "
							+ "per lookup - a CombinedStorage SUBCLASS whose parts are fresh too - so the "
							+ "tracker must be owned by the access, not keyed on any wrapper");
		}
	}

	@Test
	@DisplayName("An idle sided inventory reports a change only on the first tick")
	void idleSidedInventoryDoesNotChangeEveryTick() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = fakeLevel(clock);
		SidedTestContainer c = new SidedTestContainer(27);
		for (int i = 0; i < 27; i++)c.setItem(i, new ItemStack(Items.STONE, 8));
		SidedBlockAccess acc = new SidedBlockAccess(c);

		List<Long> stamps = new ArrayList<>();
		for (int t = 0; t < 100; t++) {
			clock.tick();
			stamps.add(acc.tracker().getChangeTracker(level));
		}

		long distinct = stamps.stream().distinct().count();
		assertEquals(1, distinct,
				"A furnace/hopper sitting idle must not report a change every tick. It changed " + distinct
						+ " times over 100 ticks, which is the same packet storm the double chest had.");
	}

	@Test
	@DisplayName("A sided inventory still detects a real insertion")
	void sidedInventoryInsertionIsStillDetected() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = fakeLevel(clock);
		SidedTestContainer c = new SidedTestContainer(27);
		for (int i = 0; i < 26; i++)c.setItem(i, new ItemStack(Items.STONE, 8));
		SidedBlockAccess acc = new SidedBlockAccess(c);

		clock.tick();
		acc.tracker().getChangeTracker(level);
		clock.tick();
		long idle = acc.tracker().getChangeTracker(level);

		c.setItem(26, new ItemStack(Items.DIAMOND, 5));
		clock.tick();
		assertNotEquals(idle, acc.tracker().getChangeTracker(level),
				"an actual content change must still bump the change stamp for a sided inventory");
	}

	@Test
	@DisplayName("A filtered/wrapping access over the same block is never deduplicated away")
	void wrappedAccessIsNotDroppedByDedup() {
		WorldStates.clearWorldStates();
		SimpleContainer left = filled(27, Items.STONE);
		SimpleContainer right = filled(27, Items.DIRT);
		// The review's real scenario: two Inventory Connectors touching one chest each build their
		// OWN BlockInventoryAccess (invAccesses is per-connector), so the roots are distinct objects
		// and the pre-existing allRoots check does not collapse them. Connector B wraps its access in
		// a filter. Only the storage-identity dedup could drop it - and it must not.
		DoubleChestAccess plainA = new DoubleChestAccess(left, right);
		DoubleChestAccess plainB = new DoubleChestAccess(left, right);
		WrappingAccess filteredB = new WrappingAccess(plainB);

		TestMulti multi = new TestMulti(List.of(plainA, filteredB));
		assertEquals(2, multi.getConnected().size(),
				"a filtered access carries the user's item filter, keep-last and priority config; "
						+ "deduping it against the plain access over the same block would silently "
						+ "expose that inventory unfiltered");
		assertTrue(multi.getConnected().contains(filteredB), "the filtered access must survive");
	}

	@Test
	@DisplayName("Two plain accesses over the same block are still deduplicated")
	void plainDuplicateIsStillDeduplicated() {
		WorldStates.clearWorldStates();
		SimpleContainer left = filled(27, Items.STONE);
		SimpleContainer right = filled(27, Items.DIRT);
		// Same physical chest reached by two connectors, neither filtered: still exactly one entry,
		// otherwise its contents are counted twice in the terminal.
		TestMulti multi = new TestMulti(List.of(
				new DoubleChestAccess(left, right), new DoubleChestAccess(left, right)));
		assertEquals(1, multi.getConnected().size(),
				"two plain accesses over one physical inventory must still collapse to one");
	}

	@Test
	@DisplayName("An idle double chest reports a change only on the first tick")
	void idleDoubleChestDoesNotChangeEveryTick() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = fakeLevel(clock);
		DoubleChestAccess acc = new DoubleChestAccess(filled(27, Items.STONE), filled(27, Items.DIRT));

		List<Long> stamps = new ArrayList<>();
		for (int t = 0; t < 100; t++) {
			clock.tick();
			stamps.add(acc.tracker().getChangeTracker(level));
		}

		long distinct = stamps.stream().distinct().count();
		assertEquals(1, distinct,
				"With nothing moving, the change stamp must be constant. It changed " + distinct
						+ " times over 100 ticks, which makes StorageTerminalBlockEntity.changeCount++ "
						+ "and therefore a sync packet fire every tick.");
	}

	@Test
	@DisplayName("A real insertion is still detected")
	void insertionIsStillDetected() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = fakeLevel(clock);
		SimpleContainer left = filled(27, Items.STONE);
		DoubleChestAccess acc = new DoubleChestAccess(left, filled(26, Items.DIRT));

		clock.tick();
		acc.tracker().getChangeTracker(level);
		clock.tick();
		long idle = acc.tracker().getChangeTracker(level);

		left.setItem(3, new ItemStack(Items.DIAMOND, 5));
		clock.tick();
		assertNotEquals(idle, acc.tracker().getChangeTracker(level),
				"an actual content change must still bump the change stamp");
	}

	@Test
	@DisplayName("Ten idle double chests keep the network change stamp stable")
	void tenDoubleChestsAreStableThroughMultiChangeTracker() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = fakeLevel(clock);

		List<IInventoryAccess> chests = new ArrayList<>();
		for (int i = 0; i < 10; i++)
			chests.add(new DoubleChestAccess(filled(27, Items.STONE), filled(27, Items.DIRT)));

		TestMulti multi = new TestMulti(chests);

		List<Long> stamps = new ArrayList<>();
		for (int t = 0; t < 100; t++) {
			clock.tick();
			stamps.add(multi.tracker().getChangeTracker(level));
		}

		long distinct = stamps.stream().distinct().count();
		assertEquals(1, distinct,
				"MultiChangeTracker must report a stable stamp for 10 idle double chests; it changed "
						+ distinct + " times over 100 ticks");
	}

	@Test
	@DisplayName("Both halves of one double chest are streamed once, not twice")
	void bothHalvesDoNotDoubleCount() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = fakeLevel(clock);

		SimpleContainer left = filled(27, Items.STONE);
		SimpleContainer right = filled(27, Items.DIRT);
		// The connector scan registers BOTH halves of a double chest as separate BlockFaces, and
		// each of them resolves to a storage covering ALL 54 slots.
		DoubleChestAccess a = new DoubleChestAccess(left, right);
		DoubleChestAccess b = new DoubleChestAccess(left, right);

		TestMulti multi = new TestMulti(List.of(a, b));
		clock.tick();
		multi.tracker().getChangeTracker(level);

		long stone = multi.tracker().streamWrappedStacks(false)
				.filter(s -> s.getStack().is(Items.STONE)).mapToLong(StoredItemStack::getQuantity).sum();
		assertEquals(27 * 8, stone,
				"the 27 stone stacks of one physical double chest must be counted once, not twice");
	}

	/** Minimal concrete MultiInventoryAccess so the production MultiChangeTracker can be driven. */
	static final class TestMulti extends MultiInventoryAccess {
		TestMulti(List<? extends IInventoryAccess> invs) {
			// Same registration/dedup path MultiInventoryAccess.build uses after the connector scan.
			Set<IInventoryAccess> roots = Collections.newSetFromMap(new IdentityHashMap<>());
			Set<Object> storages = Collections.newSetFromMap(new IdentityHashMap<>());
			Set<IProxy> dejaVu = Collections.newSetFromMap(new IdentityHashMap<>());
			roots.add(this);
			for (IInventoryAccess a : invs)addDistinct(a, roots, storages, dejaVu);
		}

		/**
		 * NOT {@code Storage.empty()}: that is a JVM-wide singleton, and MultiInventoryAccess's
		 * constructor registers {@code getPlatformHandler()} in {@code WorldStates.trackers}, so
		 * every instance would fight over one map entry and make the suite order-fragile. Lazily
		 * created on first use (not a field initializer): the super-constructor calls
		 * {@code getPlatformHandler()} -> {@code get()} before this class's field initializers run,
		 * so {@code self} must still be assignable at that point.
		 */
		private Storage<ItemVariant> self;

		@Override
		public Storage<ItemVariant> get() {
			if (self == null) self = new CombinedStorage<>(List.<Storage<ItemVariant>>of());
			return self;
		}
	}
}
