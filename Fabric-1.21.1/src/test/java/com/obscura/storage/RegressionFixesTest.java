package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.obscura.storage.DoubleChestTrackerTest.TickClock;
import com.obscura.storage.block.entity.IInventoryConnector;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IProxy;
import com.obscura.storage.inventory.MultiInventoryAccess;
import com.obscura.storage.inventory.PlatformFilteredInventoryAccess;
import com.obscura.storage.inventory.PlatformInventoryAccess;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.inventory.filter.IFilter;
import com.obscura.storage.inventory.filter.ItemPredicate;
import com.obscura.storage.util.FilingCabinetContainer;
import com.obscura.storage.util.Priority;
import com.obscura.storage.util.WorldStates;

/** Regression tests for the v4.0.0 audit fixes that can be exercised without a running world. */
@DisplayName("v4.0.0 regression fixes")
public class RegressionFixesTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** A block inventory access whose block can be swapped out, like a chest being replaced. */
	static final class SwappableAccess extends PlatformInventoryAccess.BlockInventoryAccess {
		Container container;

		SwappableAccess(Container container) {
			this.container = container;
		}

		@SuppressWarnings("unchecked")
		@Override
		public Storage<ItemVariant> get() {
			if (container == null)return null;
			return (Storage<ItemVariant>) (Storage<?>) InventoryStorage.of(container, null);
		}

		@Override
		public IInventoryAccess getRootHandler(Set<IProxy> dejaVu) {
			return this;
		}
	}

	/** A connector exposing a fixed list of inventories, with no further linked connectors. */
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

	/** Minimal concrete MultiInventoryAccess, see DoubleChestTrackerTest.TestMulti for the lazy self. */
	static final class Multi extends MultiInventoryAccess {
		private Storage<ItemVariant> self;

		void rebuild(List<IInventoryAccess> invs) {
			build(new FakeConnector(invs), List.of());
		}

		@Override
		public Storage<ItemVariant> get() {
			if (self == null) self = new CombinedStorage<>(List.<Storage<ItemVariant>>of());
			return self;
		}
	}

	static final IFilter PASS_ALL = new IFilter() {

		@Override
		public ItemPredicate getItemPred() {
			return s -> true;
		}

		@Override
		public boolean isKeepLast() {
			return false;
		}

		@Override
		public Priority getPriority() {
			return Priority.NORMAL;
		}
	};

	private static SimpleContainer filled(int size, Item item) {
		SimpleContainer c = new SimpleContainer(size);
		for (int i = 0; i < size; i++)c.setItem(i, new ItemStack(item, 8));
		return c;
	}

	private static long count(Stream<StoredItemStack> str, Item item) {
		return str.filter(s -> s.getStack().is(item)).mapToLong(StoredItemStack::getQuantity).sum();
	}

	@Test
	@DisplayName("A2: growing the network after the tracker ran this tick does not break streaming")
	void rebuildGrowingInSameTickStreamsSafely() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = DoubleChestTrackerTest.fakeLevel(clock);
		Multi multi = new Multi();
		multi.rebuild(List.of(new SwappableAccess(filled(27, Items.STONE))));

		clock.tick();
		multi.tracker().getChangeTracker(level);
		long before = multi.tracker().getChangeTracker(level);
		assertEquals(27 * 8, count(multi.tracker().streamWrappedStacks(false), Items.STONE));

		// Same tick: the connector rescans and now sees far more inventories than the tracker's
		// per-index arrays were sized for.
		List<IInventoryAccess> grown = new ArrayList<>();
		for (int i = 0; i < 20; i++)grown.add(new SwappableAccess(filled(27, Items.STONE)));
		multi.rebuild(grown);

		assertDoesNotThrow(() -> multi.tracker().streamWrappedStacks(false).count(),
				"streaming right after a rebuild must not index past the tracker's cache arrays");
		assertDoesNotThrow(() -> multi.tracker().streamWrappedStacks(true).count());

		// The new members' own trackers are populated by the next getChangeTracker, which must run
		// even though the tracker already updated in this tick.
		assertNotEquals(before, multi.tracker().getChangeTracker(level),
				"a rebuild must be reported as a change even within the same tick");
		assertEquals(20 * 27 * 8, count(multi.tracker().streamWrappedStacks(false), Items.STONE));
		assertEquals(20 * 27 * 8, count(multi.tracker().streamWrappedStacks(true), Items.STONE));
	}

	@Test
	@DisplayName("A2: swapping the network members in the same tick does not stream stale contents")
	void rebuildSameSizeInSameTickDropsStaleCache() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = DoubleChestTrackerTest.fakeLevel(clock);
		Multi multi = new Multi();
		multi.rebuild(List.of(new SwappableAccess(filled(27, Items.STONE))));

		clock.tick();
		multi.tracker().getChangeTracker(level);
		multi.tracker().getChangeTracker(level);
		assertEquals(27 * 8, count(multi.tracker().streamWrappedStacks(false), Items.STONE));

		multi.rebuild(List.of(new SwappableAccess(filled(27, Items.DIRT))));
		assertEquals(0, count(multi.tracker().streamWrappedStacks(false), Items.STONE),
				"the removed inventory's cached stacks must not survive the rebuild");
		multi.tracker().getChangeTracker(level);
		assertEquals(0, count(multi.tracker().streamWrappedStacks(false), Items.STONE));
		assertEquals(27 * 8, count(multi.tracker().streamWrappedStacks(false), Items.DIRT),
				"the same-tick update after a rebuild must sync the new member");
	}

	@Test
	@DisplayName("A4: a filtered access picks up a chest placed after it was created")
	void filteredAccessSeesLaterPlacedChest() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = DoubleChestTrackerTest.fakeLevel(clock);
		SwappableAccess acc = new SwappableAccess(null);
		PlatformFilteredInventoryAccess filtered = new PlatformFilteredInventoryAccess(acc, PASS_ALL);

		clock.tick();
		filtered.tracker().getChangeTracker(level);
		assertEquals(0, filtered.tracker().streamWrappedStacks(false).count());

		acc.container = filled(27, Items.STONE);
		clock.tick();
		filtered.tracker().getChangeTracker(level);
		assertEquals(27 * 8, count(filtered.tracker().streamWrappedStacks(false), Items.STONE),
				"the filtered tracker must re-resolve the storage instead of keeping the empty one");
	}

	@Test
	@DisplayName("A4: replacing the chest behind a filtered access leaves no ghost items (sync path)")
	void filteredAccessReplacedChestLeavesNoGhosts() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = DoubleChestTrackerTest.fakeLevel(clock);
		SwappableAccess acc = new SwappableAccess(filled(27, Items.STONE));
		PlatformFilteredInventoryAccess filtered = new PlatformFilteredInventoryAccess(acc, PASS_ALL);

		clock.tick();
		long first = filtered.tracker().getChangeTracker(level);
		assertEquals(27 * 8, count(filtered.tracker().streamWrappedStacks(false), Items.STONE));

		acc.container = filled(27, Items.DIRT);
		clock.tick();
		assertNotEquals(first, filtered.tracker().getChangeTracker(level));
		assertEquals(0, count(filtered.tracker().streamWrappedStacks(false), Items.STONE),
				"slots of the removed chest must be dropped");
		assertEquals(27 * 8, count(filtered.tracker().streamWrappedStacks(false), Items.DIRT));

		acc.container = null;
		clock.tick();
		filtered.tracker().getChangeTracker(level);
		assertEquals(0, filtered.tracker().streamWrappedStacks(false).count(),
				"a broken chest must not stay visible");
	}

	@Test
	@DisplayName("A4: replacing a chest behind a filtered access leaves no ghost items (off-thread path)")
	void filteredAccessReplacedChestThroughMultiTracker() {
		WorldStates.clearWorldStates();
		TickClock clock = new TickClock();
		Level level = DoubleChestTrackerTest.fakeLevel(clock);
		// More than 8 members so MultiChangeTracker takes the parallel off-thread path.
		List<SwappableAccess> raw = new ArrayList<>();
		List<IInventoryAccess> accs = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			SwappableAccess a = new SwappableAccess(filled(27, Items.STONE));
			raw.add(a);
			accs.add(new PlatformFilteredInventoryAccess(a, PASS_ALL));
		}
		Multi multi = new Multi();
		multi.rebuild(accs);
		for (int i = 0; i < 3; i++) {
			clock.tick();
			multi.tracker().getChangeTracker(level);
		}
		assertEquals(10 * 27 * 8, count(multi.tracker().streamWrappedStacks(false), Items.STONE));

		raw.get(0).container = filled(27, Items.DIRT);
		clock.tick();
		multi.tracker().getChangeTracker(level);
		assertEquals(9 * 27 * 8, count(multi.tracker().streamWrappedStacks(false), Items.STONE));
		assertEquals(27 * 8, count(multi.tracker().streamWrappedStacks(false), Items.DIRT));
	}

	@Test
	@DisplayName("A17: the Filing Cabinet refuses shulker boxes and bundles")
	void filingCabinetRejectsContainerItems() {
		FilingCabinetContainer inv = new FilingCabinetContainer(8, null, p -> true);
		assertFalse(inv.canPlaceItem(0, new ItemStack(Items.SHULKER_BOX)), "shulker boxes nest inventories");
		assertFalse(inv.canPlaceItem(0, new ItemStack(Items.RED_SHULKER_BOX)));
		assertFalse(inv.canPlaceItem(0, new ItemStack(Items.BUNDLE)), "bundles nest inventories");
		assertFalse(inv.canPlaceItem(0, new ItemStack(Items.STONE)), "stackable items were never allowed");
		assertTrue(inv.canPlaceItem(0, new ItemStack(Items.WRITABLE_BOOK)));
		inv.setItem(0, new ItemStack(Items.WRITABLE_BOOK));
		assertFalse(inv.canPlaceItem(1, new ItemStack(Items.DIAMOND_SWORD)), "one item type per cabinet");
	}

	@Test
	@DisplayName("A16: every recipe is unlocked by a 1.21 advancement")
	void everyRecipeHasAnAdvancement() throws IOException {
		// Shared data lives in the Common project
		Path data = Path.of("../Common-1.21.1/src/main/resources/data/obscuras_storage");
		assertTrue(Files.isDirectory(data), "run from the Fabric-1.21.1 project directory (next to Common-1.21.1)");
		assertFalse(Files.exists(data.resolve("advancements")), "1.21 only loads advancement/ (singular)");

		Set<String> recipes = new HashSet<>();
		try (Stream<Path> s = Files.list(data.resolve("recipe"))) {
			s.forEach(p -> recipes.add("obscuras_storage:" + p.getFileName().toString().replace(".json", "")));
		}
		Set<String> rewarded = new HashSet<>();
		List<Path> advs;
		try (Stream<Path> s = Files.walk(data.resolve("advancement"))) {
			advs = s.filter(p -> p.toString().endsWith(".json")).toList();
		}
		for (Path p : advs) {
			JsonObject adv;
			try (Reader r = Files.newBufferedReader(p)) {
				adv = JsonParser.parseReader(r).getAsJsonObject();
			}
			JsonObject criteria = adv.getAsJsonObject("criteria");
			for (String name : criteria.keySet()) {
				JsonObject c = criteria.getAsJsonObject(name);
				assertTrue(c.has("trigger"), p + ": criterion " + name + " has no trigger");
				if (c.get("trigger").getAsString().equals("minecraft:inventory_changed")) {
					for (JsonElement e : c.getAsJsonObject("conditions").getAsJsonArray("items")) {
						assertTrue(e.getAsJsonObject().has("items"), p + ": 1.21 item predicates use \"items\"");
					}
				}
			}
			for (JsonElement req : adv.getAsJsonArray("requirements")) {
				for (JsonElement name : req.getAsJsonArray()) {
					assertTrue(criteria.has(name.getAsString()), p + ": undefined criterion " + name);
				}
			}
			for (JsonElement id : adv.getAsJsonObject("rewards").getAsJsonArray("recipes")) {
				assertTrue(recipes.contains(id.getAsString()), p + ": rewards unknown recipe " + id);
				rewarded.add(id.getAsString());
			}
		}
		Set<String> missing = new HashSet<>(recipes);
		missing.removeAll(rewarded);
		assertTrue(missing.isEmpty(), "recipes without an unlocking advancement: " + missing);
	}
}
