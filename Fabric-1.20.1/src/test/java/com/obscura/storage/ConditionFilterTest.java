package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.util.ConditionFilter;
import com.obscura.storage.util.ConditionFilterState;
import com.obscura.storage.util.FilterCondition;

/** Pure logic of the Condition Filter: states, masks, the match rule and the trait checks. */
@DisplayName("Condition Filter")
public class ConditionFilterTest {
	private static final int DAMAGED = FilterCondition.DAMAGED.bit();
	private static final int ENCHANTED = FilterCondition.ENCHANTED.bit();
	private static final int RENAMED = FilterCondition.RENAMED.bit();
	private static final int FOOD = FilterCondition.FOOD.bit();
	/** Fuel and smeltable read server side recipe data, the checks below leave them out. */
	private static final int WITHOUT_RECIPE_DATA = ConditionFilterState.ALL_MASK & ~FilterCondition.FUEL.bit() & ~FilterCondition.SMELTABLE.bit();

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@DisplayName("Clicking cycles Ignore, Required, Excluded and back")
	void triStateCycling() {
		int s = ConditionFilterState.STATE_IGNORE;
		s = ConditionFilterState.nextState(s);
		assertEquals(ConditionFilterState.STATE_REQUIRED, s);
		s = ConditionFilterState.nextState(s);
		assertEquals(ConditionFilterState.STATE_EXCLUDED, s);
		s = ConditionFilterState.nextState(s);
		assertEquals(ConditionFilterState.STATE_IGNORE, s);
	}

	@Test
	@DisplayName("Setting a state moves the bit between the masks and reads back")
	void maskEncodingAndDecoding() {
		ConditionFilterState c = ConditionFilterState.EMPTY;
		assertTrue(c.isEmpty());
		c = c.withState(FilterCondition.DAMAGED.ordinal(), ConditionFilterState.STATE_REQUIRED);
		c = c.withState(FilterCondition.FOOD.ordinal(), ConditionFilterState.STATE_EXCLUDED);
		assertEquals(DAMAGED, c.required());
		assertEquals(FOOD, c.excluded());
		assertEquals(DAMAGED | FOOD, c.activeMask());
		assertEquals(ConditionFilterState.STATE_REQUIRED, c.getState(FilterCondition.DAMAGED.ordinal()));
		assertEquals(ConditionFilterState.STATE_EXCLUDED, c.getState(FilterCondition.FOOD.ordinal()));
		assertEquals(ConditionFilterState.STATE_IGNORE, c.getState(FilterCondition.FUEL.ordinal()));

		// switching required -> excluded moves the bit, it never sits in both masks
		c = c.withState(FilterCondition.DAMAGED.ordinal(), ConditionFilterState.STATE_EXCLUDED);
		assertEquals(0, c.required());
		assertEquals(DAMAGED | FOOD, c.excluded());
		c = c.withState(FilterCondition.DAMAGED.ordinal(), ConditionFilterState.STATE_IGNORE);
		c = c.withState(FilterCondition.FOOD.ordinal(), ConditionFilterState.STATE_IGNORE);
		assertTrue(c.isEmpty());
	}

	@Test
	@DisplayName("Out of range indexes and states are ignored")
	void invalidInputIsIgnored() {
		ConditionFilterState c = new ConditionFilterState(DAMAGED, 0);
		assertSame(c, c.withState(-1, ConditionFilterState.STATE_REQUIRED));
		assertSame(c, c.withState(FilterCondition.COUNT, ConditionFilterState.STATE_REQUIRED));
		assertSame(c, c.withState(0, 3));
		assertSame(c, c.withState(0, -1));
		assertEquals(ConditionFilterState.STATE_IGNORE, c.getState(-1));
		assertEquals(ConditionFilterState.STATE_IGNORE, c.getState(99));
	}

	@Test
	@DisplayName("Menu button ids encode condition and state, invalid ids are rejected")
	void buttonIds() {
		for (int cond = 0; cond < FilterCondition.COUNT; cond++) {
			for (int st = 0; st < ConditionFilterState.STATES; st++) {
				int id = ConditionFilterState.encodeButton(cond, st);
				assertEquals(cond, ConditionFilterState.buttonCondition(id));
				assertEquals(st, ConditionFilterState.buttonState(id));
			}
		}
		int max = FilterCondition.COUNT * ConditionFilterState.STATES;
		assertEquals(-1, ConditionFilterState.buttonCondition(-1));
		assertEquals(-1, ConditionFilterState.buttonCondition(max));
		assertEquals(-1, ConditionFilterState.buttonState(max + 5));
		assertEquals(-1, ConditionFilterState.buttonState(Integer.MIN_VALUE));
	}

	@Test
	@DisplayName("Masks are sanitised: unknown bits dropped, Required wins over Excluded")
	void sanitising() {
		ConditionFilterState c = new ConditionFilterState(0xFFFF_FFFF, 0xFFFF_FFFF);
		assertEquals(ConditionFilterState.ALL_MASK, c.required());
		assertEquals(0, c.excluded());
		c = new ConditionFilterState(DAMAGED, DAMAGED | FOOD);
		assertEquals(DAMAGED, c.required());
		assertEquals(FOOD, c.excluded());
	}

	@Test
	@DisplayName("The state is stored in the stack NBT and read back")
	void nbtRoundTrip() {
		ConditionFilterState c = new ConditionFilterState(DAMAGED | ENCHANTED, RENAMED | FOOD);
		ItemStack stack = new ItemStack(Items.PAPER);
		c.write(stack);
		CompoundTag tag = stack.getTag();
		assertEquals(DAMAGED | ENCHANTED, tag.getInt("Required"));
		assertEquals(RENAMED | FOOD, tag.getInt("Excluded"));
		assertEquals(c, ConditionFilterState.read(stack));
		assertEquals(c, ConditionFilterState.read(ItemStack.of(stack.save(new CompoundTag()))));
	}

	@Test
	@DisplayName("A missing or empty tag reads as an empty filter and an empty state removes the keys")
	void emptyState() {
		assertEquals(ConditionFilterState.EMPTY, ConditionFilterState.read(new ItemStack(Items.PAPER)));

		ItemStack stack = new ItemStack(Items.PAPER);
		new ConditionFilterState(DAMAGED, 0).write(stack);
		ConditionFilterState.EMPTY.write(stack);
		assertNull(stack.getTag(), "the tag goes away with its last key");

		// other tags stay
		stack.getOrCreateTag().putString("other", "x");
		new ConditionFilterState(0, FOOD).write(stack);
		ConditionFilterState.EMPTY.write(stack);
		assertEquals("x", stack.getTag().getString("other"));
		assertFalse(stack.getTag().contains("Required"));
		assertFalse(stack.getTag().contains("Excluded"));

		// broken data is sanitised on read
		ItemStack broken = new ItemStack(Items.PAPER);
		broken.getOrCreateTag().putInt("Required", -1);
		broken.getOrCreateTag().putInt("Excluded", -1);
		ConditionFilterState s = ConditionFilterState.read(broken);
		assertEquals(ConditionFilterState.ALL_MASK, s.required());
		assertEquals(0, s.excluded());
	}

	@Test
	@DisplayName("Nothing set matches nothing, whatever the traits")
	void emptyMatchesNothing() {
		ConditionFilterState c = ConditionFilterState.EMPTY;
		assertFalse(c.matches(0));
		assertFalse(c.matches(ConditionFilterState.ALL_MASK));
	}

	@Test
	@DisplayName("Every Required trait must be present, every Excluded trait absent")
	void requiredAndExcludedRule() {
		ConditionFilterState req = new ConditionFilterState(DAMAGED | ENCHANTED, 0);
		assertTrue(req.matches(DAMAGED | ENCHANTED));
		assertTrue(req.matches(DAMAGED | ENCHANTED | FOOD));
		assertFalse(req.matches(DAMAGED));
		assertFalse(req.matches(0));

		ConditionFilterState exc = new ConditionFilterState(0, RENAMED | FOOD);
		assertTrue(exc.matches(0));
		assertTrue(exc.matches(DAMAGED));
		assertFalse(exc.matches(RENAMED));
		assertFalse(exc.matches(FOOD | DAMAGED));

		ConditionFilterState both = new ConditionFilterState(DAMAGED, RENAMED);
		assertTrue(both.matches(DAMAGED));
		assertTrue(both.matches(DAMAGED | ENCHANTED));
		assertFalse(both.matches(DAMAGED | RENAMED));
		assertFalse(both.matches(RENAMED));
		assertFalse(both.matches(0));
	}

	private static int traits(ItemStack s, int wanted) {
		return ConditionFilter.traitsOf(s, wanted);
	}

	@Test
	@DisplayName("Trait checks on real item stacks")
	void traitsOfStacks() {
		ItemStack sword = new ItemStack(Items.IRON_SWORD);
		assertEquals(0, traits(sword, DAMAGED), "undamaged");
		sword.setDamageValue(10);
		assertEquals(DAMAGED, traits(sword, DAMAGED));
		assertEquals(0, traits(new ItemStack(Items.STONE), DAMAGED), "not damageable");

		ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
		assertEquals(0, traits(book, ENCHANTED), "book without enchantments");
		EnchantedBookItem.addEnchantment(book, new EnchantmentInstance(Enchantments.MENDING, 1));
		assertEquals(ENCHANTED, traits(book, ENCHANTED), "stored enchantments count");
		ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
		assertEquals(0, traits(pick, ENCHANTED));
		pick.enchant(Enchantments.MENDING, 1);
		assertEquals(ENCHANTED, traits(pick, ENCHANTED));

		ItemStack named = new ItemStack(Items.STICK);
		assertEquals(0, traits(named, RENAMED));
		named.setHoverName(Component.literal("Wand"));
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
		ListTag items = new ListTag();
		CompoundTag dirt = new CompoundTag();
		new ItemStack(Items.DIRT).save(dirt);
		dirt.putByte("Slot", (byte) 0);
		items.add(dirt);
		box.getOrCreateTagElement("BlockEntityTag").put("Items", items);
		assertEquals(contents, traits(box, contents));
		box.getOrCreateTagElement("BlockEntityTag").put("Items", new ListTag());
		assertEquals(0, traits(box, contents), "no items in the list");
		ItemStack bundle = new ItemStack(Items.BUNDLE);
		assertEquals(0, traits(bundle, contents));
		bundle.getOrCreateTag().put("Items", items);
		assertEquals(contents, traits(bundle, contents));

		// only the wanted bits are computed
		assertEquals(0, traits(sword, 0));
		assertNotEquals(0, traits(sword, WITHOUT_RECIPE_DATA));
	}
}
