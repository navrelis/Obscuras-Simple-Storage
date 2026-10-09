package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.util.StorageSnapshot;

/** Item totals, version counter and change log of a {@link StorageSnapshot}. */
@DisplayName("Storage snapshot")
public class StorageSnapshotTest {
	private static final ItemVariant STONE = ItemVariant.of(Items.STONE);
	private static final ItemVariant DIRT = ItemVariant.of(Items.DIRT);
	private static final ItemVariant SAND = ItemVariant.of(Items.SAND);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Storage<ItemVariant> storage(SimpleContainer c) {
		return InventoryStorage.of(c, null);
	}

	private static SimpleContainer container() {
		SimpleContainer c = new SimpleContainer(9);
		c.setItem(0, new ItemStack(Items.STONE, 10));
		c.setItem(1, new ItemStack(Items.STONE, 5));
		c.setItem(2, new ItemStack(Items.DIRT, 3));
		return c;
	}

	private static Set<ItemVariant> changedSince(StorageSnapshot s, long since) {
		Set<ItemVariant> out = new HashSet<>();
		s.collectChangedSince(since, out);
		return out;
	}

	@Test
	@DisplayName("Totals add up the stacks of the same variant")
	void totals() {
		StorageSnapshot s = new StorageSnapshot();
		assertEquals(0, s.getVersion());
		assertTrue(s.refresh(storage(container()), 0, 1));
		assertEquals(15, s.getCount(STONE));
		assertEquals(3, s.getCount(DIRT));
		assertEquals(0, s.getCount(SAND));
		assertTrue(s.contains(STONE));
		assertFalse(s.contains(SAND));
		assertEquals(2, s.counts().size());
		assertEquals(1, s.getVersion());
	}

	@Test
	@DisplayName("An empty or missing storage is an empty snapshot and no change")
	void emptyStorage() {
		StorageSnapshot s = new StorageSnapshot();
		assertFalse(s.refresh(storage(new SimpleContainer(9)), 0, 1));
		assertEquals(0, s.getVersion());
		assertFalse(s.refresh(null, 1, 1));
		assertEquals(0, s.getVersion());
		assertTrue(s.counts().isEmpty());
	}

	@Test
	@DisplayName("The version only bumps when the totals really differ")
	void versionBumpsOnRealChangesOnly() {
		SimpleContainer c = container();
		StorageSnapshot s = new StorageSnapshot();
		long time = 0;
		s.refresh(storage(c), time++, 1);
		assertEquals(1, s.getVersion());

		// same contents
		assertFalse(s.refresh(storage(c), time++, 1));
		assertEquals(1, s.getVersion());

		// stacks moved around, totals the same
		c.setItem(0, new ItemStack(Items.STONE, 12));
		c.setItem(1, new ItemStack(Items.STONE, 3));
		assertFalse(s.refresh(storage(c), time++, 1));
		assertEquals(1, s.getVersion());

		// a count changed
		c.setItem(2, new ItemStack(Items.DIRT, 4));
		assertTrue(s.refresh(storage(c), time++, 1));
		assertEquals(2, s.getVersion());
		assertEquals(4, s.getCount(DIRT));

		// a new variant
		c.setItem(3, new ItemStack(Items.SAND, 1));
		assertTrue(s.refresh(storage(c), time++, 1));
		assertEquals(3, s.getVersion());

		// a variant gone
		c.setItem(3, ItemStack.EMPTY);
		assertTrue(s.refresh(storage(c), time++, 1));
		assertEquals(4, s.getVersion());
		assertFalse(s.contains(SAND));
		assertEquals(0, s.getCount(SAND));

		// everything gone
		c.clearContent();
		assertTrue(s.refresh(storage(c), time++, 1));
		assertEquals(5, s.getVersion());
		assertTrue(s.counts().isEmpty());
	}

	@Test
	@DisplayName("Slots and free slots are counted, a view reachable twice once")
	void slotCounts() {
		SimpleContainer c = container();
		StorageSnapshot s = new StorageSnapshot();
		s.refresh(storage(c), 0, 1);
		assertEquals(9, s.getSlotCount());
		assertEquals(6, s.getFreeSlotCount());

		StorageSnapshot dup = new StorageSnapshot();
		dup.refresh(new CombinedStorage<ItemVariant, Storage<ItemVariant>>(List.of(storage(c), storage(c))), 0, 1);
		assertEquals(9, dup.getSlotCount());
		assertEquals(6, dup.getFreeSlotCount());

		StorageSnapshot none = new StorageSnapshot();
		none.refresh(null, 0, 1);
		assertEquals(0, none.getSlotCount());
		assertEquals(0, none.getFreeSlotCount());
	}

	@Test
	@DisplayName("A view that is reachable twice is counted once")
	void identityDeDup() {
		SimpleContainer c = container();
		Storage<ItemVariant> single = storage(c);
		// InventoryStorage.of hands out the same slot views for the same container, like both halves of a double chest
		Storage<ItemVariant> twice = new CombinedStorage<ItemVariant, Storage<ItemVariant>>(List.of(single, storage(c)));

		StorageSnapshot once = new StorageSnapshot();
		once.refresh(single, 0, 1);
		StorageSnapshot dup = new StorageSnapshot();
		dup.refresh(twice, 0, 1);
		assertEquals(15, dup.getCount(STONE));
		assertEquals(3, dup.getCount(DIRT));
		assertEquals(once.counts(), dup.counts());

		// different containers are different views and are added up
		Storage<ItemVariant> two = new CombinedStorage<ItemVariant, Storage<ItemVariant>>(List.of(storage(container()), storage(container())));
		StorageSnapshot both = new StorageSnapshot();
		both.refresh(two, 0, 1);
		assertEquals(30, both.getCount(STONE));
		assertEquals(6, both.getCount(DIRT));
	}

	@Test
	@DisplayName("The contents are only read again after maxAge ticks, invalidate forces a read")
	void refreshInterval() {
		SimpleContainer c = container();
		StorageSnapshot s = new StorageSnapshot();
		assertTrue(s.refresh(storage(c), 100, 5));

		c.setItem(2, new ItemStack(Items.DIRT, 9));
		assertFalse(s.refresh(storage(c), 100, 5), "same tick");
		assertFalse(s.refresh(storage(c), 104, 5), "younger than maxAge");
		assertEquals(3, s.getCount(DIRT));

		assertTrue(s.refresh(storage(c), 105, 5), "maxAge reached");
		assertEquals(9, s.getCount(DIRT));

		c.setItem(2, new ItemStack(Items.DIRT, 1));
		assertFalse(s.refresh(storage(c), 106, 5));
		s.invalidate();
		assertTrue(s.refresh(storage(c), 106, 5), "invalidate skips the interval");
		assertEquals(1, s.getCount(DIRT));

		// a game time that went backwards (world reload) reads again
		c.setItem(2, new ItemStack(Items.DIRT, 2));
		assertTrue(s.refresh(storage(c), 3, 5));
		assertEquals(2, s.getCount(DIRT));

		// maxAge below 1 still means at most once per tick
		c.setItem(2, new ItemStack(Items.DIRT, 6));
		assertFalse(s.refresh(storage(c), 3, 0));
		assertTrue(s.refresh(storage(c), 4, 0));
	}

	@Test
	@DisplayName("The change log lists the variants that changed after a version")
	void changeLog() {
		SimpleContainer c = container();
		StorageSnapshot s = new StorageSnapshot();
		long time = 0;
		s.refresh(storage(c), time++, 1);
		assertTrue(s.canDiffSince(0));
		assertEquals(Set.of(STONE, DIRT), changedSince(s, 0));
		assertEquals(Set.of(), changedSince(s, 1));

		c.setItem(2, new ItemStack(Items.DIRT, 4));
		s.refresh(storage(c), time++, 1);
		assertEquals(2, s.getVersion());
		assertEquals(Set.of(DIRT), changedSince(s, 1));
		assertEquals(Set.of(STONE, DIRT), changedSince(s, 0));

		c.setItem(3, new ItemStack(Items.SAND, 2));
		c.setItem(0, new ItemStack(Items.STONE, 1));
		s.refresh(storage(c), time++, 1);
		assertEquals(3, s.getVersion());
		assertEquals(Set.of(SAND, STONE), changedSince(s, 2));

		// removal is logged too
		c.setItem(3, ItemStack.EMPTY);
		s.refresh(storage(c), time++, 1);
		assertEquals(Set.of(SAND), changedSince(s, 3));

		// an unchanged refresh adds nothing
		s.refresh(storage(c), time++, 1);
		assertEquals(Set.of(), changedSince(s, 4));
		assertEquals(4, s.getVersion());
	}

	@Test
	@DisplayName("A viewer from the future or from before the log can not be diffed")
	void canDiffBounds() {
		SimpleContainer c = container();
		StorageSnapshot s = new StorageSnapshot();
		s.refresh(storage(c), 0, 1);
		assertTrue(s.canDiffSince(0));
		assertTrue(s.canDiffSince(1));
		assertFalse(s.canDiffSince(2), "newer than the snapshot");
		assertFalse(s.canDiffSince(-1));
	}

	@Test
	@DisplayName("A long log is trimmed and old viewers fall back to a full sync")
	void trimFallback() {
		SimpleContainer c = new SimpleContainer(1);
		StorageSnapshot s = new StorageSnapshot();
		// one variant changes on every refresh, so the log gets one entry per version
		int changes = 1100;
		for (int i = 1; i <= changes; i++) {
			// the amount alternates between 2 and 1, so it differs from the previous one every time
			c.setItem(0, new ItemStack(Items.STONE, (i % 2) + 1));
			assertTrue(s.refresh(storage(c), i, 1), "refresh " + i);
		}
		assertEquals(changes, s.getVersion());

		assertFalse(s.canDiffSince(0), "the start of the log was dropped");
		assertFalse(s.canDiffSince(1));
		assertTrue(s.canDiffSince(changes - 1), "the newest entries are still there");
		assertTrue(s.canDiffSince(changes));
		assertEquals(Set.of(STONE), changedSince(s, changes - 1));
		assertEquals(Set.of(), changedSince(s, changes));

		// the totals are unaffected by trimming
		assertEquals(c.getItem(0).getCount(), s.getCount(STONE));
	}
}
