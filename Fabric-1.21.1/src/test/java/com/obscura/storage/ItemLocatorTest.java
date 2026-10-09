package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.obscura.storage.block.entity.IInventoryConnector;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IProxy;
import com.obscura.storage.inventory.InventoryChangeTracker;
import com.obscura.storage.inventory.InventoryImage;
import com.obscura.storage.inventory.InventoryImage.InvState;
import com.obscura.storage.inventory.MultiInventoryAccess;
import com.obscura.storage.inventory.PlatformInventoryAccess;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.util.ItemLocator;
import com.obscura.storage.util.ItemLocator.Location;
import com.obscura.storage.util.ItemLocator.SearchResult;
import com.obscura.storage.util.ItemLocator.Selection;

/** Server-side search, dedupe, sort and cap logic of the Item Locator. */
@DisplayName("Item Locator search")
public class ItemLocatorTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** A plain block inventory over a container, not bound to a world position. */
	static final class ContainerAccess extends PlatformInventoryAccess.BlockInventoryAccess {
		private final SimpleContainer container;

		ContainerAccess(SimpleContainer container) {
			this.container = container;
		}

		@SuppressWarnings("unchecked")
		@Override
		public Storage<ItemVariant> get() {
			return (Storage<ItemVariant>) (Storage<?>) InventoryStorage.of(container, null);
		}

		@Override
		public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
			return this;
		}
	}

	static final class FakeConnector implements IInventoryConnector {
		private final List<IInventoryAccess> inventories;

		FakeConnector(List<IInventoryAccess> inventories) {
			this.inventories = inventories;
		}

		@Override
		public IInventoryAccess getMergedHandler() {
			return null;
		}

		@Override
		public Collection<IInventoryAccess> getConnectedInventories() {
			return inventories;
		}

		@Override
		public Collection<IInventoryConnector> getConnectedConnectors() {
			return List.of();
		}

		@Override
		public boolean hasConnectedInventories() {
			return !inventories.isEmpty();
		}
	}

	static final class Multi extends MultiInventoryAccess {
		private Storage<ItemVariant> self;

		Multi(IInventoryAccess... invs) {
			build(new FakeConnector(List.of(invs)), List.of());
		}

		@Override
		public Storage<ItemVariant> get() {
			if (self == null) self = new CombinedStorage<>(List.<Storage<ItemVariant>>of());
			return self;
		}
	}

	/** An access in front of a whole other network, like a filtered link to a remote network. */
	static final class LinkAccess extends PlatformInventoryAccess.BlockInventoryAccess {
		private final Multi target;

		LinkAccess(Multi target) {
			this.target = target;
		}

		@Override
		public Storage<ItemVariant> get() {
			return target.get();
		}

		@Override
		public IInventoryChangeTracker tracker() {
			return target.tracker();
		}

		@Override
		public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
			return target;
		}
	}

	private static Level fakeLevel() {
		Level level = Mockito.mock(Level.class, Mockito.withSettings().defaultAnswer(Mockito.RETURNS_DEFAULTS));
		Mockito.when(level.getGameTime()).thenReturn(1L);
		return level;
	}

	private static ContainerAccess chest(ItemStack... stacks) {
		SimpleContainer c = new SimpleContainer(9);
		for (int i = 0; i < stacks.length; i++)c.setItem(i, stacks[i]);
		return new ContainerAccess(c);
	}

	private static StoredItemStack key(Item item) {
		return new StoredItemStack(new ItemStack(item), 1);
	}

	private static Location at(int x, ResourceKey<Level> dim) {
		return new Location(new BlockPos(x, 64, 0), dim);
	}

	@Test
	@DisplayName("Only inventories holding the item are reported, unpositioned ones are counted")
	void collectsMatchingInventories() {
		ContainerAccess a = chest(new ItemStack(Items.DIAMOND, 3));
		ContainerAccess b = chest(new ItemStack(Items.STONE, 64));
		ContainerAccess c = chest(new ItemStack(Items.DIAMOND, 1), new ItemStack(Items.STONE, 2));
		ContainerAccess d = chest(new ItemStack(Items.DIAMOND, 5));
		Multi net = new Multi(a, b, c, d);

		Map<IInventoryAccess, Location> pos = new IdentityHashMap<>();
		pos.put(a, at(1, Level.OVERWORLD));
		pos.put(b, at(2, Level.OVERWORLD));
		pos.put(c, at(3, Level.NETHER));
		// d has no known position

		SearchResult res = ItemLocator.collect(net, key(Items.DIAMOND), fakeLevel(), pos::get);
		assertEquals(List.of(at(1, Level.OVERWORLD), at(3, Level.NETHER)), res.located());
		assertEquals(1, res.unknown());

		SearchResult stone = ItemLocator.collect(net, key(Items.STONE), fakeLevel(), pos::get);
		assertEquals(List.of(at(2, Level.OVERWORLD), at(3, Level.NETHER)), stone.located());
		assertEquals(0, stone.unknown());
	}

	@Test
	@DisplayName("Components must match: a renamed or tagged item is a different entry")
	void matchesComponents() {
		ItemStack tagged = new ItemStack(Items.DIAMOND, 2);
		CompoundTag t = new CompoundTag();
		t.putString("k", "v");
		tagged.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
		ContainerAccess a = chest(new ItemStack(Items.DIAMOND, 3));
		ContainerAccess b = chest(tagged);
		Multi net = new Multi(a, b);
		Map<IInventoryAccess, Location> pos = new IdentityHashMap<>();
		pos.put(a, at(1, Level.OVERWORLD));
		pos.put(b, at(2, Level.OVERWORLD));

		SearchResult res = ItemLocator.collect(net, new StoredItemStack(tagged.copyWithCount(1), 1), fakeLevel(), pos::get);
		assertEquals(List.of(at(2, Level.OVERWORLD)), res.located());
	}

	@Test
	@DisplayName("Two accesses on the same block are reported once, linked networks are descended into")
	void dedupesAndFollowsLinks() {
		ContainerAccess a = chest(new ItemStack(Items.DIAMOND, 3));
		ContainerAccess a2 = chest(new ItemStack(Items.DIAMOND, 3));
		ContainerAccess remote = chest(new ItemStack(Items.DIAMOND, 7));
		Multi remoteNet = new Multi(remote, a);
		LinkAccess link = new LinkAccess(remoteNet);

		Map<IInventoryAccess, Location> pos = new IdentityHashMap<>();
		pos.put(a, at(1, Level.OVERWORLD));
		pos.put(a2, at(1, Level.OVERWORLD));// same block seen through another face
		pos.put(remote, at(9, Level.OVERWORLD));

		Level level = fakeLevel();
		SearchResult res = ItemLocator.collect(new Multi(a, a2), key(Items.DIAMOND), level, pos::get);
		assertEquals(List.of(at(1, Level.OVERWORLD)), res.located());

		SearchResult viaLink = ItemLocator.collect(link, key(Items.DIAMOND), level, pos::get);
		assertEquals(List.of(at(9, Level.OVERWORLD), at(1, Level.OVERWORLD)), viaLink.located());
		assertEquals(0, viaLink.unknown());
	}

	@Test
	@DisplayName("An access without a bound block has no location")
	void unboundAccessHasNoLocation() {
		assertNull(ItemLocator.locateBlock(chest(new ItemStack(Items.DIAMOND))));
	}

	@Test
	@DisplayName("Selection keeps the player's dimension, nearest first, capped")
	void selectsNearestInDimension() {
		List<Location> located = new ArrayList<>();
		for (int x = 100; x > 0; x--)located.add(new Location(new BlockPos(x, 64, 0), Level.OVERWORLD));
		located.add(new Location(new BlockPos(0, 64, 0), Level.NETHER));
		located.add(new Location(new BlockPos(1, 64, 0), Level.END));

		Selection sel = ItemLocator.select(located, Level.OVERWORLD, new BlockPos(0, 64, 0), ItemLocator.MAX_POSITIONS);
		assertEquals(100, sel.inDimension());
		assertEquals(2, sel.otherDimensions());
		assertEquals(ItemLocator.MAX_POSITIONS, sel.positions().size());
		for (int i = 0; i < sel.positions().size(); i++) {
			assertEquals(new BlockPos(i + 1, 64, 0), sel.positions().get(i));
		}

		Selection none = ItemLocator.select(located, Level.NETHER, new BlockPos(500, 64, 0), ItemLocator.MAX_POSITIONS);
		assertEquals(List.of(new BlockPos(0, 64, 0)), none.positions());
		assertEquals(1, none.inDimension());
		assertEquals(101, none.otherDimensions());
		assertTrue(ItemLocator.select(List.of(), Level.OVERWORLD, BlockPos.ZERO, 64).positions().isEmpty());
	}

	// ---- InventoryChangeTracker.countItems, which the locator relies on ----

	/** One tracker refresh at a new game time, through either the main-thread or the off-thread path. */
	@FunctionalInterface
	interface TrackerTick {
		void run(InventoryChangeTracker tr, Level level);
	}

	private static final TrackerTick MAIN_THREAD = (tr, level) -> tr.getChangeTracker(level);

	private static final TrackerTick OFF_THREAD = (tr, level) -> {
		InventoryImage im = tr.prepForOffThread(level);
		assertNotNull(im, "the storage version changed, so an off-thread image must be taken");
		InvState st = tr.processOffThread(im);
		tr.finishOffThreadProcess(level, st);
	};

	private static long realCount(SimpleContainer c, Item item) {
		long n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			if (c.getItem(i).is(item))n += c.getItem(i).getCount();
		}
		return n;
	}

	private static int trackedKeys(InventoryChangeTracker tr) throws ReflectiveOperationException {
		Field f = InventoryChangeTracker.class.getDeclaredField("itemCounts");
		f.setAccessible(true);
		return ((Map<?, ?>) f.get(tr)).size();
	}

	/** countItems and the streamed stacks must match the real container for every tracked item. */
	private static void assertMatches(InventoryChangeTracker tr, SimpleContainer c, String step) {
		for (Item item : List.of(Items.DIAMOND, Items.EMERALD)) {
			long real = realCount(c, item);
			assertEquals(real, tr.countItems(key(item)), step + ": countItems(" + item + ")");
			long streamed = tr.streamWrappedStacks(false).filter(s -> s.getStack().is(item)).mapToLong(StoredItemStack::getQuantity).sum();
			assertEquals(real, streamed, step + ": streamed " + item);
		}
	}

	private static void runCountScenario(TrackerTick tick) throws ReflectiveOperationException {
		long[] time = {0};
		Level level = Mockito.mock(Level.class, Mockito.withSettings().defaultAnswer(Mockito.RETURNS_DEFAULTS));
		Mockito.when(level.getGameTime()).thenAnswer(inv -> time[0]);
		SimpleContainer c = new SimpleContainer(9);
		@SuppressWarnings("unchecked")
		Storage<ItemVariant> storage = (Storage<ItemVariant>) (Storage<?>) InventoryStorage.of(c, null);
		InventoryChangeTracker tr = new InventoryChangeTracker(storage);

		c.setItem(0, new ItemStack(Items.DIAMOND, 10));
		time[0]++;
		tick.run(tr, level);
		assertMatches(tr, c, "10 diamonds");

		// Count-only change: this is the case the off-thread path used to get wrong
		c.setItem(0, new ItemStack(Items.DIAMOND, 3));
		time[0]++;
		tick.run(tr, level);
		assertMatches(tr, c, "count dropped to 3");

		c.setItem(1, new ItemStack(Items.DIAMOND, 4));
		time[0]++;
		tick.run(tr, level);
		assertMatches(tr, c, "second slot added");

		c.setItem(0, new ItemStack(Items.EMERALD, 5));
		time[0]++;
		tick.run(tr, level);
		assertMatches(tr, c, "item swapped in slot 0");

		c.setItem(1, new ItemStack(Items.DIAMOND, 64));
		time[0]++;
		tick.run(tr, level);
		assertMatches(tr, c, "count raised to 64");

		c.clearContent();
		time[0]++;
		tick.run(tr, level);
		assertMatches(tr, c, "emptied");
		assertEquals(0, trackedKeys(tr), "no 0-valued keys may be left behind");
	}

	@Test
	@DisplayName("Main-thread path: countItems follows count changes and ends at 0")
	void countItemsMainThread() throws ReflectiveOperationException {
		runCountScenario(MAIN_THREAD);
	}

	@Test
	@DisplayName("Off-thread path: countItems follows count-only changes and ends at 0")
	void countItemsOffThread() throws ReflectiveOperationException {
		runCountScenario(OFF_THREAD);
	}

	@Test
	@DisplayName("Off-thread processing does not mutate the tracked stacks before finish")
	void offThreadDoesNotMutateTrackedStacks() {
		long[] time = {0};
		Level level = Mockito.mock(Level.class, Mockito.withSettings().defaultAnswer(Mockito.RETURNS_DEFAULTS));
		Mockito.when(level.getGameTime()).thenAnswer(inv -> time[0]);
		SimpleContainer c = new SimpleContainer(3);
		@SuppressWarnings("unchecked")
		Storage<ItemVariant> storage = (Storage<ItemVariant>) (Storage<?>) InventoryStorage.of(c, null);
		InventoryChangeTracker tr = new InventoryChangeTracker(storage);

		c.setItem(0, new ItemStack(Items.DIAMOND, 10));
		time[0]++;
		OFF_THREAD.run(tr, level);
		StoredItemStack before = tr.streamWrappedStacks(false).findFirst().orElseThrow();

		c.setItem(0, new ItemStack(Items.DIAMOND, 3));
		time[0]++;
		InvState st = tr.processOffThread(tr.prepForOffThread(level));
		assertEquals(10, before.getQuantity(), "worker thread must not touch the shared stack");
		assertEquals(10, tr.countItems(key(Items.DIAMOND)));
		tr.finishOffThreadProcess(level, st);
		assertEquals(3, tr.countItems(key(Items.DIAMOND)));
	}
}
