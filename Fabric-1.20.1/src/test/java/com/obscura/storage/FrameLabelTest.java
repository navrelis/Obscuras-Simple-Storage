package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.util.FilteredStorage;
import com.obscura.storage.util.FrameLabel;
import com.obscura.storage.util.FrameLabels;
import com.obscura.storage.util.IProxy;
import com.obscura.storage.util.ItemPredicate;
import com.obscura.storage.util.LabelledStorage;
import com.obscura.storage.util.MergedStorage;
import com.obscura.storage.util.Priority;

/** Frame Labels: the label rule and how a labelled inventory takes part in a network. */
@DisplayName("Frame Labels")
public class FrameLabelTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static FrameLabel label(Item... items) {
		return new FrameLabel(Set.of(items), List.of(), s -> null);
	}

	/** A filter item standing for "anything that is a diamond or an emerald". */
	private static final ItemPredicate GEMS = v -> v.isOf(Items.DIAMOND) || v.isOf(Items.EMERALD);

	private static int count(Container c, Item item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			if (c.getItem(i).is(item))n += c.getItem(i).getCount();
		}
		return n;
	}

	private static long insert(Storage<ItemVariant> s, Item item, long amount) {
		try (Transaction t = Transaction.openOuter()) {
			long n = s.insert(ItemVariant.of(item), amount, t);
			t.commit();
			return n;
		}
	}

	private static long extract(Storage<ItemVariant> s, Item item, long amount) {
		try (Transaction t = Transaction.openOuter()) {
			long n = s.extract(ItemVariant.of(item), amount, t);
			t.commit();
			return n;
		}
	}

	@Test
	@DisplayName("The label is the union of the framed item types, NBT ignored")
	void unionByItemType() {
		FrameLabel l = label(Items.IRON_INGOT, Items.GOLD_INGOT);
		assertTrue(l.test(new ItemStack(Items.IRON_INGOT)));
		assertTrue(l.test(new ItemStack(Items.GOLD_INGOT, 64)));
		ItemStack named = new ItemStack(Items.IRON_INGOT);
		named.setHoverName(Component.literal("Special"));
		assertTrue(l.test(named), "a named iron ingot is still iron");
		assertFalse(l.test(new ItemStack(Items.DIRT)));
		assertFalse(l.test(ItemStack.EMPTY));
		assertFalse(l.isEmpty());
		assertTrue(label().isEmpty());
		assertEquals(Priority.HIGHEST, FrameLabel.PRIORITY, "labelled inventories are filled first");
	}

	@Test
	@DisplayName("A framed filter item labels by its filter")
	void filterItems() {
		// Stands in for a filter item (the mod's items are not registered in unit tests)
		ItemStack filterStack = new ItemStack(Items.PAPER);
		FrameLabel l = new FrameLabel(Set.of(), List.of(filterStack), s -> GEMS);
		assertFalse(FrameLabel.isFilterItem(new ItemStack(Items.DIAMOND)));
		assertTrue(l.hasFilters());
		assertTrue(l.test(new ItemStack(Items.DIAMOND)));
		assertTrue(l.test(new ItemStack(Items.EMERALD)));
		assertFalse(l.test(new ItemStack(Items.PAPER)), "the filter item itself is not what it labels");
		assertFalse(l.test(new ItemStack(Items.IRON_INGOT)));
		FrameLabel both = new FrameLabel(Set.of(Items.IRON_INGOT), List.of(filterStack), s -> GEMS);
		assertTrue(both.test(new ItemStack(Items.IRON_INGOT)), "items and filters add up");
		assertTrue(both.test(new ItemStack(Items.DIAMOND)));
	}

	@Test
	@DisplayName("Same frame contents are recognised, in any order, so the network is not rebuilt")
	void sameContent() {
		ItemStack f1 = new ItemStack(Items.PAPER);
		ItemStack f2 = new ItemStack(Items.BOOK);
		FrameLabel l = new FrameLabel(Set.of(Items.IRON_INGOT, Items.GOLD_INGOT), List.of(f1, f2), s -> null);
		assertTrue(l.sameContent(Set.of(Items.GOLD_INGOT, Items.IRON_INGOT), List.of(f2.copy(), f1.copy())));
		assertFalse(l.sameContent(Set.of(Items.IRON_INGOT), List.of(f1, f2)), "an item less");
		assertFalse(l.sameContent(Set.of(Items.IRON_INGOT, Items.GOLD_INGOT), List.of(f1)), "a filter less");
		ItemStack renamed = f1.copy();
		renamed.setHoverName(Component.literal("x"));
		assertFalse(l.sameContent(Set.of(Items.IRON_INGOT, Items.GOLD_INGOT), List.of(renamed, f2)), "a filter with other settings");
	}

	@Test
	@DisplayName("Frame contents are compared like the label matches")
	void sameLabelItem() throws Exception {
		var m = FrameLabels.class.getDeclaredMethod("sameLabelItem", ItemStack.class, ItemStack.class);
		m.setAccessible(true);
		assertEquals(true, m.invoke(null, ItemStack.EMPTY, ItemStack.EMPTY));
		assertEquals(false, m.invoke(null, ItemStack.EMPTY, new ItemStack(Items.IRON_INGOT)));
		assertEquals(true, m.invoke(null, new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_INGOT, 1)));
		assertEquals(false, m.invoke(null, new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT)));
	}

	@Test
	@DisplayName("A labelled inventory only takes its items, extraction and listing are unchanged")
	void networkInsertAndExtract() {
		SimpleContainer labelled = new SimpleContainer(27);
		labelled.setItem(0, new ItemStack(Items.COBBLESTONE, 3));// put in by hand
		SimpleContainer a = new SimpleContainer(27);
		a.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
		Storage<ItemVariant> backing = InventoryStorage.of(labelled, null);
		LabelledStorage lab = new LabelledStorage(backing, label(Items.IRON_INGOT));
		assertSame(backing, IProxy.resolve(lab), "a labelled member stands for its inventory");
		MergedStorage net = new MergedStorage();
		// The connector adds the labelled inventory first (FrameLabel.PRIORITY)
		net.add(lab);
		net.add(InventoryStorage.of(a, null));

		assertEquals(20, insert(net, Items.IRON_INGOT, 20));
		assertEquals(20, count(labelled, Items.IRON_INGOT), "iron goes into the labelled inventory first");
		assertEquals(5, count(a, Items.IRON_INGOT));

		assertEquals(10, insert(net, Items.DIRT, 10));
		assertEquals(0, count(labelled, Items.DIRT), "dirt never goes into the labelled inventory");
		assertEquals(10, count(a, Items.DIRT));
		assertEquals(0, insert(lab, Items.DIRT, 4), "the labelled storage itself refuses dirt");

		// Listing and extraction are unchanged: the hand-placed cobblestone is seen and comes out
		long seen = 0;
		for (StorageView<ItemVariant> v : lab) {
			if (!v.isResourceBlank() && v.getResource().isOf(Items.COBBLESTONE))seen += v.getAmount();
		}
		assertEquals(3, seen, "cobblestone listed");
		assertEquals(3, extract(net, Items.COBBLESTONE, 64));
		assertEquals(0, count(labelled, Items.COBBLESTONE));
	}

	@Test
	@DisplayName("The Network Doctor counts labelled members, also behind a cable connector's own filter")
	void doctorCount() {
		Storage<ItemVariant> plain = InventoryStorage.of(new SimpleContainer(9), null);
		LabelledStorage lab = new LabelledStorage(InventoryStorage.of(new SimpleContainer(9), null), label(Items.IRON_INGOT));
		LabelledStorage lab2 = new LabelledStorage(InventoryStorage.of(new SimpleContainer(9), null), label(Items.GOLD_INGOT));
		MergedStorage net = new MergedStorage();
		net.add(plain);
		net.add(lab);
		net.add(new FilteredStorage(lab2, v -> true, false));
		assertEquals(2, net.countLabelled());
	}
}
