package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.block.AbstractFilterBlock;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.inventory.filter.GhostFilter;
import com.obscura.storage.inventory.filter.ItemPredicate;
import com.obscura.storage.util.DestroyedCounter;
import com.obscura.storage.util.EjectorMode;
import com.obscura.storage.util.FilterAmount;
import com.obscura.storage.util.FilterBlockLogic;
import com.obscura.storage.util.FilterBlockStatus;

/**
 * Pure logic of the Ejector, Disposal and Exporter: the ghost filter's match rule, batch sizes, the
 * redstone/pause gating, the destroyed counter and the placement rule. Entries that are filter items
 * need the mod's registered items and are covered by the in-world GameTests.
 */
@DisplayName("Filter blocks (Ejector, Disposal, Exporter)")
public class FilterBlocksTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static GhostFilter filter(ItemStack... entries) {
		GhostFilter f = new GhostFilter(s -> null);
		for (int i = 0; i < entries.length; i++)f.setStack(i, entries[i]);
		f.updateState();
		return f;
	}

	private static ItemStack damaged(int damage) {
		ItemStack s = new ItemStack(Items.IRON_PICKAXE);
		s.set(DataComponents.DAMAGE, damage);
		return s;
	}

	private static ItemStack named(ItemStack s, String name) {
		s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return s;
	}

	@Test
	@DisplayName("An empty filter matches nothing, with Exact off and on")
	void emptyMatchesNothing() {
		GhostFilter f = filter();
		assertTrue(f.isEmpty());
		assertFalse(f.test(new ItemStack(Items.COBBLESTONE)));
		assertFalse(f.test(new StoredItemStack(new ItemStack(Items.COBBLESTONE), 64)));
		assertFalse(f.test(ItemStack.EMPTY));
		f.setExact(true);
		f.updateState();
		assertFalse(f.test(new ItemStack(Items.COBBLESTONE)));
		assertFalse(f.test(ItemStack.EMPTY));

		// Clearing the only entry makes it empty again
		f.setStack(4, new ItemStack(Items.COBBLESTONE));
		f.updateState();
		assertFalse(f.isEmpty());
		assertTrue(f.test(new ItemStack(Items.COBBLESTONE)));
		f.setStack(4, ItemStack.EMPTY);
		f.updateState();
		assertTrue(f.isEmpty());
		assertFalse(f.test(new ItemStack(Items.COBBLESTONE)));
	}

	@Test
	@DisplayName("Item match: only the item counts, durability, name and count are ignored")
	void itemMatch() {
		GhostFilter f = filter(new ItemStack(Items.IRON_PICKAXE));
		assertTrue(f.test(new ItemStack(Items.IRON_PICKAXE)));
		assertTrue(f.test(damaged(40)), "a damaged tool matches a pristine entry");
		assertTrue(f.test(named(new ItemStack(Items.IRON_PICKAXE), "Pick")), "a renamed tool matches");
		assertTrue(f.test(new StoredItemStack(damaged(3), 1)));
		assertFalse(f.test(new ItemStack(Items.IRON_SHOVEL)));
		assertFalse(f.test(new ItemStack(Items.COBBLESTONE, 64)));

		// The other way round: a damaged entry matches a pristine tool
		GhostFilter g = filter(damaged(100));
		assertTrue(g.test(new ItemStack(Items.IRON_PICKAXE)));
	}

	@Test
	@DisplayName("Exact match: the same item with the same components only")
	void exactMatch() {
		GhostFilter f = filter(new ItemStack(Items.IRON_PICKAXE));
		f.setExact(true);
		f.updateState();
		assertTrue(f.isExact());
		assertTrue(f.test(new ItemStack(Items.IRON_PICKAXE)));
		assertFalse(f.test(damaged(40)), "a damaged tool does not match a pristine entry with Exact on");
		assertFalse(f.test(named(new ItemStack(Items.IRON_PICKAXE), "Pick")));
		assertFalse(f.test(new ItemStack(Items.IRON_SHOVEL)));

		GhostFilter g = filter(damaged(40));
		g.setExact(true);
		g.updateState();
		assertTrue(g.test(damaged(40)));
		assertFalse(g.test(damaged(41)));
		assertFalse(g.test(new ItemStack(Items.IRON_PICKAXE)));

		// Back to item match
		g.setExact(false);
		g.updateState();
		assertTrue(g.test(new ItemStack(Items.IRON_PICKAXE)));
	}

	@Test
	@DisplayName("Mixed entries: any entry matching is enough, an entry holds a count of 1")
	void mixedEntries() {
		GhostFilter f = filter(new ItemStack(Items.COBBLESTONE, 64), ItemStack.EMPTY, damaged(7), named(new ItemStack(Items.PAPER), "Note"));
		assertEquals(1, f.getStack(0).getCount(), "an entry is a reference stack of 1");
		assertTrue(f.getStack(1).isEmpty());
		assertTrue(f.isPlainEntry(0));
		assertFalse(f.isPlainEntry(1), "an empty slot is no entry");
		assertFalse(f.isPlainEntry(-1));
		assertFalse(f.isPlainEntry(GhostFilter.SLOTS));
		assertTrue(f.getStack(GhostFilter.SLOTS).isEmpty(), "out of range reads as empty");

		assertTrue(f.test(new ItemStack(Items.COBBLESTONE)));
		assertTrue(f.test(new ItemStack(Items.IRON_PICKAXE)));
		assertTrue(f.test(new ItemStack(Items.PAPER)));
		assertFalse(f.test(new ItemStack(Items.STONE)));

		f.setExact(true);
		f.updateState();
		assertTrue(f.test(new ItemStack(Items.COBBLESTONE, 12)), "the count never matters");
		assertTrue(f.test(damaged(7)));
		assertFalse(f.test(new ItemStack(Items.IRON_PICKAXE)));
		assertTrue(f.test(named(new ItemStack(Items.PAPER), "Note")));
		assertFalse(f.test(new ItemStack(Items.PAPER)));
	}

	@Test
	@DisplayName("Edits only show after updateState and move the state version")
	void compiledState() {
		GhostFilter f = filter(new ItemStack(Items.COBBLESTONE));
		long v = f.stateVersion();
		assertNotEquals(ItemPredicate.UNVERSIONED, v);
		f.updateState();
		assertEquals(v, f.stateVersion(), "nothing was edited: nothing is rebuilt");

		f.setStack(0, new ItemStack(Items.STONE));
		assertTrue(f.test(new ItemStack(Items.COBBLESTONE)), "test() reads the compiled state, never builds it");
		f.updateState();
		assertFalse(f.test(new ItemStack(Items.COBBLESTONE)));
		assertTrue(f.test(new ItemStack(Items.STONE)));
		assertNotEquals(v, f.stateVersion());
	}

	@Test
	@DisplayName("Filter NBT: list of stacks with a byte Slot, read back into the same slots")
	void filterNbt() {
		HolderLookup.Provider reg = VanillaRegistries.createLookup();
		GhostFilter f = filter(new ItemStack(Items.COBBLESTONE), ItemStack.EMPTY, damaged(7));
		f.setStack(8, new ItemStack(Items.DIAMOND));
		ListTag list = f.save(reg);
		assertEquals(3, list.size(), "empty slots are not written");
		assertEquals(Tag.TAG_COMPOUND, list.getElementType());
		assertEquals(0, list.getCompound(0).getByte("Slot"));
		assertEquals(2, list.getCompound(1).getByte("Slot"));
		assertEquals(8, list.getCompound(2).getByte("Slot"));
		assertEquals("minecraft:diamond", list.getCompound(2).getString("id"));

		GhostFilter g = new GhostFilter(s -> null);
		g.setStack(5, new ItemStack(Items.STONE));
		g.load(list, reg);
		g.updateState();
		assertTrue(g.getStack(5).isEmpty(), "loading replaces what was there");
		assertTrue(g.getStack(0).is(Items.COBBLESTONE));
		assertTrue(g.getStack(1).isEmpty());
		assertTrue(ItemStack.isSameItemSameComponents(g.getStack(2), damaged(7)));
		assertTrue(g.getStack(8).is(Items.DIAMOND));
		assertTrue(g.test(new ItemStack(Items.DIAMOND)));

		// A missing list (a block entity that never saved one) is an empty filter
		g.load(new ListTag(), reg);
		g.updateState();
		assertTrue(g.isEmpty());
		assertFalse(g.test(new ItemStack(Items.DIAMOND)));
	}

	@Test
	@DisplayName("Batch size: 1 item, or one stack capped at 64")
	void batchSize() {
		assertEquals(1, FilterBlockLogic.batchSize(FilterAmount.ITEM, 64));
		assertEquals(1, FilterBlockLogic.batchSize(FilterAmount.ITEM, 1));
		assertEquals(64, FilterBlockLogic.batchSize(FilterAmount.STACK, 64));
		assertEquals(16, FilterBlockLogic.batchSize(FilterAmount.STACK, 16));
		assertEquals(1, FilterBlockLogic.batchSize(FilterAmount.STACK, 1));
		assertEquals(64, FilterBlockLogic.batchSize(FilterAmount.STACK, 99), "never more than 64 per operation");
		assertEquals(1, FilterBlockLogic.batchSize(FilterAmount.STACK, 0));
		assertEquals(16, FilterBlockLogic.batchSize(FilterAmount.STACK, new ItemStack(Items.ENDER_PEARL).getMaxStackSize()));
	}

	@Test
	@DisplayName("Cooldown: clamped to 1..200, the next attempt comes exactly that many ticks later")
	void cooldown() {
		assertEquals(1, FilterBlockLogic.clampCooldown(0));
		assertEquals(1, FilterBlockLogic.clampCooldown(-5));
		assertEquals(8, FilterBlockLogic.clampCooldown(8));
		assertEquals(200, FilterBlockLogic.clampCooldown(1000));
		assertEquals(7, FilterBlockLogic.cooldownAfterOperation(8));
		assertEquals(0, FilterBlockLogic.cooldownAfterOperation(1));
		assertEquals(0, FilterBlockLogic.cooldownAfterOperation(0));
		assertEquals(8, Config.defaults().filterBlockCooldown, "config default");
	}

	@Test
	@DisplayName("Gating: the Ejector needs redstone, a signal stops Disposal and Exporter, a Disposal starts paused")
	void gating() {
		assertFalse(FilterBlockLogic.ejectorMayRun(EjectorMode.CONTINUOUS, false, false));
		assertTrue(FilterBlockLogic.ejectorMayRun(EjectorMode.CONTINUOUS, true, false));
		assertFalse(FilterBlockLogic.ejectorMayRun(EjectorMode.CONTINUOUS, false, true), "Continuous ignores a pending pulse");
		assertFalse(FilterBlockLogic.ejectorMayRun(EjectorMode.PULSE, true, false), "a held signal is no pulse");
		assertTrue(FilterBlockLogic.ejectorMayRun(EjectorMode.PULSE, true, true));
		assertTrue(FilterBlockLogic.ejectorMayRun(EjectorMode.PULSE, false, true), "a short pulse still drops its batch");

		assertFalse(FilterBlockLogic.disposalMayRun(true, false));
		assertFalse(FilterBlockLogic.disposalMayRun(true, true));
		assertFalse(FilterBlockLogic.disposalMayRun(false, true));
		assertTrue(FilterBlockLogic.disposalMayRun(false, false));

		assertTrue(FilterBlockLogic.exporterMayRun(false));
		assertFalse(FilterBlockLogic.exporterMayRun(true));

		// The cooldown spaces Continuous batches; a pulse gives its batch on the next tick whatever the cooldown
		assertTrue(FilterBlockLogic.ejectorWaitsForCooldown(EjectorMode.CONTINUOUS));
		assertFalse(FilterBlockLogic.ejectorWaitsForCooldown(EjectorMode.PULSE));
	}

	@Test
	@DisplayName("Saved ordinals: unknown ids fall back to the defaults")
	void enumsById() {
		assertEquals(EjectorMode.CONTINUOUS, EjectorMode.byId(0));
		assertEquals(EjectorMode.PULSE, EjectorMode.byId(1));
		assertEquals(EjectorMode.CONTINUOUS, EjectorMode.byId(7));
		assertEquals(EjectorMode.CONTINUOUS, EjectorMode.byId(-1));
		assertEquals(FilterAmount.ITEM, FilterAmount.byId(0, FilterAmount.STACK));
		assertEquals(FilterAmount.STACK, FilterAmount.byId(1, FilterAmount.ITEM));
		assertEquals(FilterAmount.STACK, FilterAmount.byId(9, FilterAmount.STACK));
		assertEquals(FilterBlockStatus.RUNNING, FilterBlockStatus.byId(99));
		assertEquals("tooltip.obscuras_storage.filter_block.status.stopped_by_redstone", FilterBlockStatus.STOPPED_BY_REDSTONE.getTranslationKey());
	}

	@Test
	@DisplayName("Destroyed counter: total and per item, at most 256 items tracked, never overflows")
	void destroyedCounter() {
		DestroyedCounter c = new DestroyedCounter();
		assertTrue(c.isEmpty());
		ResourceLocation cobble = ResourceLocation.withDefaultNamespace("cobblestone");
		ResourceLocation dirt = ResourceLocation.withDefaultNamespace("dirt");
		c.add(cobble, 64);
		c.add(dirt, 3);
		c.add(cobble, 6);
		c.add(dirt, 0);
		c.add(dirt, -4);
		assertEquals(73, c.getTotal());
		assertEquals(70, c.get(cobble));
		assertEquals(3, c.get(dirt));
		assertEquals(0, c.get(ResourceLocation.withDefaultNamespace("stone")));
		assertEquals(0, c.get(null));
		assertEquals(List.of(new DestroyedCounter.Entry(cobble, 70), new DestroyedCounter.Entry(dirt, 3)), c.top(3));
		assertEquals(List.of(new DestroyedCounter.Entry(cobble, 70)), c.top(1));

		// The 257th item only adds to the total
		for (int i = 0; i < 300; i++)c.add(ResourceLocation.fromNamespaceAndPath("test", "item_" + i), 2);
		assertEquals(DestroyedCounter.MAX_ITEMS, c.trackedItems());
		assertEquals(73 + 600, c.getTotal());
		assertEquals(2, c.get(ResourceLocation.fromNamespaceAndPath("test", "item_253")), "the 256th tracked item");
		assertEquals(0, c.get(ResourceLocation.fromNamespaceAndPath("test", "item_254")), "beyond the limit");
		c.add(cobble, 1);
		assertEquals(71, c.get(cobble), "items already tracked keep counting");

		c.add(dirt, Long.MAX_VALUE);
		assertEquals(Long.MAX_VALUE, c.getTotal(), "stops at the maximum instead of overflowing");
		assertEquals(Long.MAX_VALUE, c.get(dirt));

		c.reset();
		assertTrue(c.isEmpty());
		assertEquals(0, c.getTotal());
		assertEquals(0, c.get(cobble));
		assertTrue(c.top(3).isEmpty());
	}

	@Test
	@DisplayName("Destroyed counter NBT: Destroyed (long) and DestroyedItems ({id, n}), values above 32 bit survive")
	void destroyedCounterNbt() {
		DestroyedCounter c = new DestroyedCounter();
		ResourceLocation cobble = ResourceLocation.withDefaultNamespace("cobblestone");
		ResourceLocation dirt = ResourceLocation.withDefaultNamespace("dirt");
		c.add(cobble, 5_000_000_000L);
		c.add(dirt, 70_000);
		CompoundTag tag = new CompoundTag();
		c.save(tag);
		assertEquals(Tag.TAG_LONG, tag.getTagType("Destroyed"));
		assertEquals(5_000_070_000L, tag.getLong("Destroyed"));
		ListTag items = tag.getList("DestroyedItems", Tag.TAG_COMPOUND);
		assertEquals(2, items.size());
		assertEquals("minecraft:cobblestone", items.getCompound(0).getString("id"));
		assertEquals(Tag.TAG_LONG, items.getCompound(0).getTagType("n"));
		assertEquals(5_000_000_000L, items.getCompound(0).getLong("n"));

		DestroyedCounter d = new DestroyedCounter();
		d.add(ResourceLocation.withDefaultNamespace("stone"), 9);
		d.load(tag);
		assertEquals(5_000_070_000L, d.getTotal());
		assertEquals(5_000_000_000L, d.get(cobble));
		assertEquals(70_000, d.get(dirt));
		assertEquals(0, d.get(ResourceLocation.withDefaultNamespace("stone")), "loading replaces what was there");
		assertEquals(c.top(3), d.top(3));

		// A block entity that never saved a counter
		d.load(new CompoundTag());
		assertTrue(d.isEmpty());
	}

	@Test
	@DisplayName("Placement: back to a network block, front to an inventory, back to a network neighbour, else like a dropper")
	void placementRule() {
		Set<Direction> none = EnumSet.noneOf(Direction.class);
		for (Direction toClicked : Direction.values()) {
			for (Direction dropper : Direction.values()) {
				// 1. A clicked network block: the back faces it, whatever else is around
				assertEquals(toClicked.getOpposite(), AbstractFilterBlock.frontFor(toClicked, true, false, none, dropper), "back faces a network block");
				assertEquals(toClicked.getOpposite(), AbstractFilterBlock.frontFor(toClicked, true, true, EnumSet.allOf(Direction.class), dropper), "a connector has a block entity too: still the back");
				// 2. Any other block with a block entity: the front points at it, network neighbours or not
				assertEquals(toClicked, AbstractFilterBlock.frontFor(toClicked, false, true, none, dropper), "front points at an inventory");
				assertEquals(toClicked, AbstractFilterBlock.frontFor(toClicked, false, true, EnumSet.complementOf(EnumSet.of(toClicked)), dropper), "a clicked inventory comes before network neighbours");
				// 4. Nothing of that: like a dropper
				assertEquals(dropper, AbstractFilterBlock.frontFor(toClicked, false, false, none, dropper), "like a dropper");
				// 3. One network neighbour: the back faces it
				for (Direction n : Direction.values()) {
					if (n == toClicked)continue;
					assertEquals(n.getOpposite(), AbstractFilterBlock.frontFor(toClicked, false, false, EnumSet.of(n), dropper), "back faces the network neighbour");
				}
				// Several: the one opposite the clicked block wins
				assertEquals(toClicked, AbstractFilterBlock.frontFor(toClicked, false, false, EnumSet.complementOf(EnumSet.of(toClicked)), dropper), "the neighbour opposite the clicked block wins");
			}
		}
		// Several without the opposite one: down, up, north, south, west, east
		Direction d = Direction.SOUTH;
		assertEquals(Direction.UP, AbstractFilterBlock.frontFor(Direction.NORTH, false, false, EnumSet.of(Direction.DOWN, Direction.UP, Direction.WEST, Direction.EAST), d), "down first");
		assertEquals(Direction.DOWN, AbstractFilterBlock.frontFor(Direction.NORTH, false, false, EnumSet.of(Direction.UP, Direction.WEST, Direction.EAST), d), "then up");
		assertEquals(Direction.SOUTH, AbstractFilterBlock.frontFor(Direction.DOWN, false, false, EnumSet.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST), d), "then north");
		assertEquals(Direction.NORTH, AbstractFilterBlock.frontFor(Direction.DOWN, false, false, EnumSet.of(Direction.SOUTH, Direction.WEST, Direction.EAST), d), "then south");
		assertEquals(Direction.EAST, AbstractFilterBlock.frontFor(Direction.DOWN, false, false, EnumSet.of(Direction.WEST, Direction.EAST), d), "then west");
		assertEquals(Direction.WEST, AbstractFilterBlock.frontFor(Direction.DOWN, false, false, EnumSet.of(Direction.EAST), d), "then east");
	}
}
