package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantments;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.components.ConditionFilterComponent;
import com.obscura.storage.inventory.filter.ConditionFilter;
import com.obscura.storage.util.FilterCondition;

/** Pure logic of the Condition Filter: states, masks, the match rule and the trait checks. */
@DisplayName("Condition Filter")
public class ConditionFilterTest {
	private static final int DAMAGED = FilterCondition.DAMAGED.bit();
	private static final int ENCHANTED = FilterCondition.ENCHANTED.bit();
	private static final int RENAMED = FilterCondition.RENAMED.bit();
	private static final int FOOD = FilterCondition.FOOD.bit();

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@DisplayName("Clicking cycles Ignore, Required, Excluded and back")
	void triStateCycling() {
		int s = ConditionFilterComponent.STATE_IGNORE;
		s = ConditionFilterComponent.nextState(s);
		assertEquals(ConditionFilterComponent.STATE_REQUIRED, s);
		s = ConditionFilterComponent.nextState(s);
		assertEquals(ConditionFilterComponent.STATE_EXCLUDED, s);
		s = ConditionFilterComponent.nextState(s);
		assertEquals(ConditionFilterComponent.STATE_IGNORE, s);
	}

	@Test
	@DisplayName("Setting a state moves the bit between the masks and reads back")
	void maskEncodingAndDecoding() {
		ConditionFilterComponent c = ConditionFilterComponent.EMPTY;
		assertTrue(c.isEmpty());
		c = c.withState(FilterCondition.DAMAGED.ordinal(), ConditionFilterComponent.STATE_REQUIRED);
		c = c.withState(FilterCondition.FOOD.ordinal(), ConditionFilterComponent.STATE_EXCLUDED);
		assertEquals(DAMAGED, c.required());
		assertEquals(FOOD, c.excluded());
		assertEquals(ConditionFilterComponent.STATE_REQUIRED, c.getState(FilterCondition.DAMAGED.ordinal()));
		assertEquals(ConditionFilterComponent.STATE_EXCLUDED, c.getState(FilterCondition.FOOD.ordinal()));
		assertEquals(ConditionFilterComponent.STATE_IGNORE, c.getState(FilterCondition.FUEL.ordinal()));

		// switching required -> excluded moves the bit, it never sits in both masks
		c = c.withState(FilterCondition.DAMAGED.ordinal(), ConditionFilterComponent.STATE_EXCLUDED);
		assertEquals(0, c.required());
		assertEquals(DAMAGED | FOOD, c.excluded());
		c = c.withState(FilterCondition.DAMAGED.ordinal(), ConditionFilterComponent.STATE_IGNORE);
		c = c.withState(FilterCondition.FOOD.ordinal(), ConditionFilterComponent.STATE_IGNORE);
		assertTrue(c.isEmpty());
	}

	@Test
	@DisplayName("Out of range indexes and states are ignored")
	void invalidInputIsIgnored() {
		ConditionFilterComponent c = new ConditionFilterComponent(DAMAGED, 0);
		assertEquals(c, c.withState(-1, ConditionFilterComponent.STATE_REQUIRED));
		assertEquals(c, c.withState(FilterCondition.COUNT, ConditionFilterComponent.STATE_REQUIRED));
		assertEquals(c, c.withState(0, 3));
		assertEquals(c, c.withState(0, -1));
		assertEquals(ConditionFilterComponent.STATE_IGNORE, c.getState(-1));
		assertEquals(ConditionFilterComponent.STATE_IGNORE, c.getState(99));
	}

	@Test
	@DisplayName("Menu button ids encode condition and state, invalid ids are rejected")
	void buttonIds() {
		for (int cond = 0; cond < FilterCondition.COUNT; cond++) {
			for (int st = 0; st < ConditionFilterComponent.STATES; st++) {
				int id = ConditionFilterComponent.encodeButton(cond, st);
				assertEquals(cond, ConditionFilterComponent.buttonCondition(id));
				assertEquals(st, ConditionFilterComponent.buttonState(id));
			}
		}
		int max = FilterCondition.COUNT * ConditionFilterComponent.STATES;
		assertEquals(-1, ConditionFilterComponent.buttonCondition(-1));
		assertEquals(-1, ConditionFilterComponent.buttonCondition(max));
		assertEquals(-1, ConditionFilterComponent.buttonState(max + 5));
		assertEquals(-1, ConditionFilterComponent.buttonState(Integer.MIN_VALUE));
	}

	@Test
	@DisplayName("Masks are sanitised: unknown bits dropped, Required wins over Excluded")
	void sanitising() {
		ConditionFilterComponent c = new ConditionFilterComponent(0xFFFF_FFFF, 0xFFFF_FFFF);
		assertEquals(ConditionFilterComponent.ALL_MASK, c.required());
		assertEquals(0, c.excluded());
		c = new ConditionFilterComponent(DAMAGED, DAMAGED | FOOD);
		assertEquals(DAMAGED, c.required());
		assertEquals(FOOD, c.excluded());
	}

	@Test
	@DisplayName("Codec round trips the masks and missing fields mean nothing set")
	void codecRoundTrip() {
		ConditionFilterComponent c = new ConditionFilterComponent(DAMAGED | ENCHANTED, RENAMED | FOOD);
		var tag = ConditionFilterComponent.CODEC.encodeStart(NbtOps.INSTANCE, c).getOrThrow();
		assertEquals(c, ConditionFilterComponent.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow());
		var empty = ConditionFilterComponent.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
		assertTrue(empty.isEmpty());
	}

	@Test
	@DisplayName("Nothing set matches nothing, whatever the traits")
	void emptyMatchesNothing() {
		ConditionFilterComponent c = ConditionFilterComponent.EMPTY;
		assertFalse(c.matches(0));
		assertFalse(c.matches(ConditionFilterComponent.ALL_MASK));
	}

	@Test
	@DisplayName("Every Required trait must be present, every Excluded trait absent")
	void requiredAndExcludedRule() {
		ConditionFilterComponent req = new ConditionFilterComponent(DAMAGED | ENCHANTED, 0);
		assertTrue(req.matches(DAMAGED | ENCHANTED));
		assertTrue(req.matches(DAMAGED | ENCHANTED | FOOD));
		assertFalse(req.matches(DAMAGED));
		assertFalse(req.matches(0));

		ConditionFilterComponent exc = new ConditionFilterComponent(0, RENAMED | FOOD);
		assertTrue(exc.matches(0));
		assertTrue(exc.matches(DAMAGED));
		assertFalse(exc.matches(RENAMED));
		assertFalse(exc.matches(FOOD | DAMAGED));

		ConditionFilterComponent both = new ConditionFilterComponent(DAMAGED, RENAMED);
		assertTrue(both.matches(DAMAGED));
		assertTrue(both.matches(DAMAGED | ENCHANTED));
		assertFalse(both.matches(DAMAGED | RENAMED));
		assertFalse(both.matches(RENAMED));
		assertFalse(both.matches(0));
	}

	private static int traits(ItemStack s, int wanted) {
		return ConditionFilter.traitsOf(s, wanted, Set.of(), Set.of());
	}

	@Test
	@DisplayName("Trait checks on real item stacks")
	void traitsOfStacks() {
		ItemStack sword = new ItemStack(Items.IRON_SWORD);
		assertEquals(0, traits(sword, DAMAGED), "undamaged");
		sword.setDamageValue(10);
		assertEquals(DAMAGED, traits(sword, DAMAGED));
		assertEquals(0, traits(new ItemStack(Items.STONE), DAMAGED), "not damageable");

		ItemEnchantments.Mutable m = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		var ench = VanillaRegistries.createLookup().lookupOrThrow(Registries.ENCHANTMENT);
		m.set(ench.getOrThrow(Enchantments.MENDING), 1);
		ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
		assertEquals(0, traits(book, ENCHANTED), "book without enchantments");
		book.set(DataComponents.STORED_ENCHANTMENTS, m.toImmutable());
		assertEquals(ENCHANTED, traits(book, ENCHANTED), "stored enchantments count");
		ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
		assertEquals(0, traits(pick, ENCHANTED));
		pick.set(DataComponents.ENCHANTMENTS, m.toImmutable());
		assertEquals(ENCHANTED, traits(pick, ENCHANTED));

		ItemStack named = new ItemStack(Items.STICK);
		assertEquals(0, traits(named, RENAMED));
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Wand"));
		assertEquals(RENAMED, traits(named, RENAMED));

		int stackable = FilterCondition.STACKABLE.bit();
		assertEquals(stackable, traits(new ItemStack(Items.STONE), stackable));
		assertEquals(0, traits(new ItemStack(Items.IRON_SWORD), stackable));

		assertEquals(FOOD, traits(new ItemStack(Items.BREAD), FOOD));
		assertEquals(0, traits(new ItemStack(Items.COAL), FOOD));

		int block = FilterCondition.BLOCK.bit();
		assertEquals(block, traits(new ItemStack(Items.GRASS_BLOCK), block));
		assertEquals(0, traits(new ItemStack(Items.BREAD), block));

		int contents = FilterCondition.HAS_CONTENTS.bit();
		ItemStack box = new ItemStack(Items.SHULKER_BOX);
		assertEquals(0, traits(box, contents), "empty shulker box");
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT))));
		assertEquals(contents, traits(box, contents));
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(ItemStack.EMPTY)));
		assertEquals(0, traits(box, contents), "only empty stacks");
		ItemStack bundle = new ItemStack(Items.BUNDLE);
		assertEquals(0, traits(bundle, contents));
		bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStack(Items.DIRT))));
		assertEquals(contents, traits(bundle, contents));

		// only the wanted bits are computed
		assertEquals(0, traits(sword, 0));
		assertNotEquals(0, traits(sword, ConditionFilterComponent.ALL_MASK));
	}

	@Test
	@DisplayName("Fuel and smeltable use the precomputed item sets")
	void fuelAndSmeltableSets() {
		int fuel = FilterCondition.FUEL.bit();
		int smelt = FilterCondition.SMELTABLE.bit();
		Set<Item> fuelSet = Set.of(Items.COAL);
		Set<Item> smeltSet = Set.of(Items.IRON_ORE);
		assertEquals(fuel, ConditionFilter.traitsOf(new ItemStack(Items.COAL), fuel | smelt, fuelSet, smeltSet));
		assertEquals(smelt, ConditionFilter.traitsOf(new ItemStack(Items.IRON_ORE), fuel | smelt, fuelSet, smeltSet));
		assertEquals(0, ConditionFilter.traitsOf(new ItemStack(Items.DIRT), fuel | smelt, fuelSet, smeltSet));
	}
}
