package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
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

import com.obscura.storage.RegressionFixesTest.FakeConnector;
import com.obscura.storage.RegressionFixesTest.SwappableAccess;
import com.obscura.storage.inventory.FrameLabel;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.PlatformLabelledInventoryAccess;
import com.obscura.storage.inventory.PlatformMultiInventoryAccess;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.inventory.filter.ItemFilter;
import com.obscura.storage.util.Priority.IPriority;

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
	private static final ItemFilter GEMS = new ItemFilter() {
		@Override
		public boolean test(StoredItemStack stack) {
			return stack.getStack().is(Items.DIAMOND) || stack.getStack().is(Items.EMERALD);
		}

		@Override
		public long stateVersion() {
			return 7L;
		}
	};

	private static int count(Container c, Item item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			if (c.getItem(i).is(item))n += c.getItem(i).getCount();
		}
		return n;
	}

	@Test
	@DisplayName("The label is the union of the framed item types, components ignored")
	void unionByItemType() {
		FrameLabel l = label(Items.IRON_INGOT, Items.GOLD_INGOT);
		assertTrue(l.test(new ItemStack(Items.IRON_INGOT)));
		assertTrue(l.test(new ItemStack(Items.GOLD_INGOT, 64)));
		ItemStack named = new ItemStack(Items.IRON_INGOT);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Special"));
		assertTrue(l.test(named), "a named iron ingot is still iron");
		assertTrue(l.test(new StoredItemStack(named, 3)), "same for the network's stack type");
		assertFalse(l.test(new ItemStack(Items.DIRT)));
		assertFalse(l.test(ItemStack.EMPTY));
		assertFalse(l.isEmpty());
	}

	@Test
	@DisplayName("A framed filter item labels by its filter")
	void filterItems() {
		// Stands in for a filter item (the mod's items are not registered in unit tests)
		ItemStack filterStack = new ItemStack(Items.PAPER);
		FrameLabel l = new FrameLabel(Set.of(), List.of(filterStack), s -> GEMS);
		assertFalse(FrameLabel.isFilterItem(new ItemStack(Items.DIAMOND)));
		assertTrue(l.test(new ItemStack(Items.DIAMOND)));
		assertTrue(l.test(new ItemStack(Items.EMERALD)));
		assertFalse(l.test(new ItemStack(Items.PAPER)), "the filter item itself is not what it labels");
		assertFalse(l.test(new ItemStack(Items.IRON_INGOT)));
		FrameLabel both = new FrameLabel(Set.of(Items.IRON_INGOT), List.of(filterStack), s -> GEMS);
		assertTrue(both.test(new ItemStack(Items.IRON_INGOT)), "items and filters add up");
		assertTrue(both.test(new ItemStack(Items.DIAMOND)));
		assertNotEquals(l.stateVersion(), both.stateVersion(), "every label has its own version");
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
		renamed.set(DataComponents.CUSTOM_NAME, Component.literal("x"));
		assertFalse(l.sameContent(Set.of(Items.IRON_INGOT, Items.GOLD_INGOT), List.of(renamed, f2)), "a filter with other settings");
	}

	@Test
	@DisplayName("Frame contents are compared like the label matches")
	void sameLabelItem() throws Exception {
		var m = com.obscura.storage.inventory.FrameLabels.class.getDeclaredMethod("sameLabelItem", ItemStack.class, ItemStack.class);
		m.setAccessible(true);
		assertEquals(true, m.invoke(null, ItemStack.EMPTY, ItemStack.EMPTY));
		assertEquals(false, m.invoke(null, ItemStack.EMPTY, new ItemStack(Items.IRON_INGOT)));
		assertEquals(true, m.invoke(null, new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_INGOT, 1)));
		assertEquals(false, m.invoke(null, new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT)));
	}

	@Test
	@DisplayName("A labelled inventory is filled first, only with its items, and extraction is unchanged")
	void networkInsertOrderAndFilter() {
		SimpleContainer labelled = new SimpleContainer(27);
		labelled.setItem(0, new ItemStack(Items.COBBLESTONE, 3));// put in by hand
		SimpleContainer a = new SimpleContainer(27), b = new SimpleContainer(27);
		a.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
		b.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
		IInventoryAccess lab = new PlatformLabelledInventoryAccess(new SwappableAccess(labelled), label(Items.IRON_INGOT));
		PlatformMultiInventoryAccess net = new PlatformMultiInventoryAccess();
		// The labelled one last: the priority, not the order it was found in, puts it first
		net.build(new FakeConnector(List.of(new SwappableAccess(a), new SwappableAccess(b), lab)), List.of());
		assertEquals(3, net.getInventoryCount());
		assertSame(lab, net.getConnected().iterator().next(), "the labelled inventory comes first");
		assertEquals(com.obscura.storage.util.Priority.HIGHEST, IPriority.get(lab));

		assertTrue(net.pushStack(new ItemStack(Items.IRON_INGOT, 20)).isEmpty());
		assertEquals(20, count(labelled, Items.IRON_INGOT), "iron goes into the labelled inventory first");
		assertEquals(10, count(a, Items.IRON_INGOT) + count(b, Items.IRON_INGOT));

		assertTrue(net.pushStack(new ItemStack(Items.DIRT, 10)).isEmpty());
		assertEquals(0, count(labelled, Items.DIRT), "dirt never goes into the labelled inventory");
		assertEquals(10, count(a, Items.DIRT) + count(b, Items.DIRT));

		// Inserting through the merged Transfer API storage (other mods' pipes) follows the label too
		try (Transaction t = Transaction.openOuter()) {
			assertEquals(8, net.insert(ItemVariant.of(Items.DIRT), 8, t));
			assertEquals(4, net.insert(ItemVariant.of(Items.IRON_INGOT), 4, t));
			t.commit();
		}
		assertEquals(0, count(labelled, Items.DIRT), "no dirt through the Storage view either");
		assertEquals(24, count(labelled, Items.IRON_INGOT), "iron through the Storage view");

		// Extraction and visibility are unchanged: the hand-placed cobblestone comes out
		ItemStack pulled = net.pullMatchingStack(new ItemStack(Items.COBBLESTONE), 64);
		assertEquals(3, pulled.getCount());
		assertEquals(0, count(labelled, Items.COBBLESTONE));
	}

	@Test
	@DisplayName("Double chest: labelled halves still count as one inventory, and the label wins over a plain half")
	void doubleChestDedupe() {
		SimpleContainer chest = new SimpleContainer(54);
		FrameLabel l = label(Items.IRON_INGOT);
		// The two halves: two accesses over the same container
		IInventoryAccess half1 = new PlatformLabelledInventoryAccess(new SwappableAccess(chest), l);
		IInventoryAccess half2 = new PlatformLabelledInventoryAccess(new SwappableAccess(chest), l);
		PlatformMultiInventoryAccess net = new PlatformMultiInventoryAccess();
		net.build(new FakeConnector(List.of(half1, half2)), List.of());
		assertEquals(1, net.getInventoryCount(), "both labelled halves are one inventory");

		IInventoryAccess plain = new SwappableAccess(chest);
		IInventoryAccess labelled = new PlatformLabelledInventoryAccess(new SwappableAccess(chest), l);
		net.build(new FakeConnector(List.of(plain, labelled)), List.of());
		assertEquals(1, net.getInventoryCount());
		assertSame(labelled, net.getConnected().iterator().next(), "the labelled access is the one kept");
		assertFalse(net.pushStack(new ItemStack(Items.DIRT, 1)).isEmpty(), "the kept access carries the label");
	}
}
