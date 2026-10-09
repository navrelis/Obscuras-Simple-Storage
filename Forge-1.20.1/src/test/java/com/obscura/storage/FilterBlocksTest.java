package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.util.DestroyedCounter;
import com.obscura.storage.util.EjectorMode;
import com.obscura.storage.util.FilterAmount;
import com.obscura.storage.util.FilterBlockLogic;
import com.obscura.storage.util.FilterBlockStatus;

/**
 * Pure logic of the Ejector, Disposal and Exporter: batch sizes, the cooldown, the redstone/pause
 * gating, the destroyed counter and the placement rule. The ghost filter's match rule needs item
 * stacks, which the Forge unit tests cannot build (no item registry); it is covered by the in-world
 * GameTests (and by the Fabric unit tests of the same shared class).
 */
@DisplayName("Filter blocks (Ejector, Disposal, Exporter)")
public class FilterBlocksTest {

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
	@DisplayName("Idle check: item totals up to 4 ticks old for a block that waits for the cooldown, fresh ones for Pulse")
	void idleMaxAge() {
		assertEquals(4, FilterBlockLogic.IDLE_MAX_AGE, "the age the other readers of a network's item totals accept");
		assertEquals(4, FilterBlockLogic.idleMaxAge(true));
		assertEquals(1, FilterBlockLogic.idleMaxAge(false), "without the cooldown the totals are read afresh");
		// What decides it for the three blocks: only the Ejector's Pulse mode does not wait
		assertEquals(4, FilterBlockLogic.idleMaxAge(FilterBlockLogic.ejectorWaitsForCooldown(EjectorMode.CONTINUOUS)));
		assertEquals(1, FilterBlockLogic.idleMaxAge(FilterBlockLogic.ejectorWaitsForCooldown(EjectorMode.PULSE)), "a pulse right after an item arrived must still give its batch");
	}

	@Test
	@DisplayName("Slot walk: 256 stocked slots after the first that gave items, the rest is left to the next operation")
	void slotVisitLimit() {
		assertEquals(256, FilterBlockLogic.MAX_SLOT_VISITS, "the limit of a search on 1.21.1");
		assertTrue(FilterBlockLogic.gathersFrom(1), "the slot right after the first hit");
		assertTrue(FilterBlockLogic.gathersFrom(FilterBlockLogic.MAX_SLOT_VISITS), "the last slot inside the limit");
		assertFalse(FilterBlockLogic.gathersFrom(FilterBlockLogic.MAX_SLOT_VISITS + 1), "the first slot beyond it");
		assertFalse(FilterBlockLogic.gathersFrom(Integer.MAX_VALUE));
		// Counted like the item access counts: one more for every stocked slot it comes to
		int looked = 0;
		for (int visits = 0; FilterBlockLogic.gathersFrom(++visits);)looked++;
		assertEquals(256, looked, "slots looked at after the first hit");
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
		ResourceLocation cobble = new ResourceLocation("cobblestone");
		ResourceLocation dirt = new ResourceLocation("dirt");
		c.add(cobble, 64);
		c.add(dirt, 3);
		c.add(cobble, 6);
		c.add(dirt, 0);
		c.add(dirt, -4);
		assertEquals(73, c.getTotal());
		assertEquals(70, c.get(cobble));
		assertEquals(3, c.get(dirt));
		assertEquals(0, c.get(new ResourceLocation("stone")));
		assertEquals(0, c.get(null));
		assertEquals(List.of(new DestroyedCounter.Entry(cobble, 70), new DestroyedCounter.Entry(dirt, 3)), c.top(3));
		assertEquals(List.of(new DestroyedCounter.Entry(cobble, 70)), c.top(1));

		// The 257th item only adds to the total
		for (int i = 0; i < 300; i++)c.add(new ResourceLocation("test", "item_" + i), 2);
		assertEquals(DestroyedCounter.MAX_ITEMS, c.trackedItems());
		assertEquals(73 + 600, c.getTotal());
		assertEquals(2, c.get(new ResourceLocation("test", "item_253")), "the 256th tracked item");
		assertEquals(0, c.get(new ResourceLocation("test", "item_254")), "beyond the limit");
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
		ResourceLocation cobble = new ResourceLocation("cobblestone");
		ResourceLocation dirt = new ResourceLocation("dirt");
		c.add(cobble, 5_000_000_000L);
		c.add(dirt, 70_000);
		CompoundTag tag = new CompoundTag();
		c.save(tag);
		assertEquals(Tag.TAG_LONG, tag.getTagType("Destroyed"));
		assertEquals(5_000_070_000L, tag.getLong("Destroyed"));
		ListTag items = tag.getList("DestroyedItems", Tag.TAG_COMPOUND);
		assertEquals(2, items.size());
		assertEquals(Tag.TAG_STRING, items.getCompound(0).getTagType("id"));
		assertEquals("minecraft:cobblestone", items.getCompound(0).getString("id"));
		assertEquals(Tag.TAG_LONG, items.getCompound(0).getTagType("n"));
		assertEquals(5_000_000_000L, items.getCompound(0).getLong("n"));

		DestroyedCounter d = new DestroyedCounter();
		d.add(new ResourceLocation("stone"), 9);
		d.load(tag);
		assertEquals(5_000_070_000L, d.getTotal());
		assertEquals(5_000_000_000L, d.get(cobble));
		assertEquals(70_000, d.get(dirt));
		assertEquals(0, d.get(new ResourceLocation("stone")), "loading replaces what was there");
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
				assertEquals(toClicked.getOpposite(), FilterBlockLogic.frontFor(toClicked, true, false, none, dropper), "back faces a network block");
				assertEquals(toClicked.getOpposite(), FilterBlockLogic.frontFor(toClicked, true, true, EnumSet.allOf(Direction.class), dropper), "a connector has a block entity too: still the back");
				// 2. Any other block with a block entity: the front points at it, network neighbours or not
				assertEquals(toClicked, FilterBlockLogic.frontFor(toClicked, false, true, none, dropper), "front points at an inventory");
				assertEquals(toClicked, FilterBlockLogic.frontFor(toClicked, false, true, EnumSet.complementOf(EnumSet.of(toClicked)), dropper), "a clicked inventory comes before network neighbours");
				// 4. Nothing of that: like a dropper
				assertEquals(dropper, FilterBlockLogic.frontFor(toClicked, false, false, none, dropper), "like a dropper");
				// 3. One network neighbour: the back faces it
				for (Direction n : Direction.values()) {
					if (n == toClicked)continue;
					assertEquals(n.getOpposite(), FilterBlockLogic.frontFor(toClicked, false, false, EnumSet.of(n), dropper), "back faces the network neighbour");
				}
				// Several: the one opposite the clicked block wins
				assertEquals(toClicked, FilterBlockLogic.frontFor(toClicked, false, false, EnumSet.complementOf(EnumSet.of(toClicked)), dropper), "the neighbour opposite the clicked block wins");
			}
		}
		// Several without the opposite one: down, up, north, south, west, east
		Direction d = Direction.SOUTH;
		assertEquals(Direction.UP, FilterBlockLogic.frontFor(Direction.NORTH, false, false, EnumSet.of(Direction.DOWN, Direction.UP, Direction.WEST, Direction.EAST), d), "down first");
		assertEquals(Direction.DOWN, FilterBlockLogic.frontFor(Direction.NORTH, false, false, EnumSet.of(Direction.UP, Direction.WEST, Direction.EAST), d), "then up");
		assertEquals(Direction.SOUTH, FilterBlockLogic.frontFor(Direction.DOWN, false, false, EnumSet.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST), d), "then north");
		assertEquals(Direction.NORTH, FilterBlockLogic.frontFor(Direction.DOWN, false, false, EnumSet.of(Direction.SOUTH, Direction.WEST, Direction.EAST), d), "then south");
		assertEquals(Direction.EAST, FilterBlockLogic.frontFor(Direction.DOWN, false, false, EnumSet.of(Direction.WEST, Direction.EAST), d), "then west");
		assertEquals(Direction.WEST, FilterBlockLogic.frontFor(Direction.DOWN, false, false, EnumSet.of(Direction.EAST), d), "then east");
	}
}
