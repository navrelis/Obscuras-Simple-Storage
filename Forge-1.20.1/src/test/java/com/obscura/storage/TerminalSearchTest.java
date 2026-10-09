package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.util.TerminalSearch;
import com.obscura.storage.util.TerminalSearch.Target;

/** The terminal search syntax: OR with |, AND with spaces, @mod, #tag, $data and regular expressions. */
@DisplayName("Terminal search")
public class TerminalSearchTest {
	private static final Item IRON_INGOT = new Item("Iron Ingot", "minecraft", List.of("Iron Ingot"), List.of("c:ingots", "c:ingots/iron"), null);
	private static final Item GOLD_INGOT = new Item("Gold Ingot", "minecraft", List.of("Gold Ingot"), List.of("c:ingots", "c:ingots/gold"), null);
	private static final Item OAK_LOG = new Item("Oak Log", "minecraft", List.of("Oak Log"), List.of("minecraft:logs", "minecraft:oak_logs"), null);
	private static final Item STEEL_PLATE = new Item("Steel Plate", "create", List.of("Steel Plate", "Stronger than iron"), List.of(), null);
	private static final Item SHARP_SWORD = new Item("Sharp Sword", "minecraft", List.of("Sharp Sword", "Sharpness V"), List.of("minecraft:swords"), "{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}]}");
	private static final Item DIAMOND = new Item("Diamond", "minecraft", List.of("Diamond"), List.of("c:gems"), null);
	private static final Item[] ALL = {IRON_INGOT, GOLD_INGOT, OAK_LOG, STEEL_PLATE, SHARP_SWORD, DIAMOND};

	private static final class Item implements Target {
		private final String name, mod, data;
		private final List<String> tooltip, tags;
		int tooltipReads;

		Item(String name, String mod, List<String> tooltip, List<String> tags, String data) {
			this.name = name;
			this.mod = mod;
			this.tooltip = tooltip;
			this.tags = tags;
			this.data = data;
		}

		@Override
		public String displayNameLower() {
			return name.toLowerCase(Locale.ROOT);
		}

		@Override
		public String modId() {
			return mod;
		}

		@Override
		public List<String> tooltipLines() {
			tooltipReads++;
			return tooltip;
		}

		@Override
		public List<String> tags() {
			return tags;
		}

		@Override
		public boolean hasData() {
			return data != null;
		}

		@Override
		public String data() {
			return data;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	private static List<Item> search(String query) {
		Predicate<Target> p = TerminalSearch.compile(query);
		List<Item> r = new ArrayList<>();
		for (Item i : ALL) {
			if (p.test(i))r.add(i);
		}
		return r;
	}

	@Test
	@DisplayName("An empty or blank query matches everything")
	void emptyQuery() {
		assertEquals(ALL.length, search("").size());
		assertEquals(ALL.length, search("   ").size());
		assertEquals(ALL.length, search(" | | ").size());
	}

	@Test
	@DisplayName("Plain text matches the name, case insensitive")
	void nameSearch() {
		assertEquals(List.of(IRON_INGOT, GOLD_INGOT), search("ingot"));
		assertEquals(List.of(IRON_INGOT, GOLD_INGOT), search("INGOT"));
		assertEquals(List.of(OAK_LOG), search("oak"));
		assertEquals(List.of(), search("emerald"));
	}

	@Test
	@DisplayName("Plain text also matches the tooltip")
	void tooltipSearch() {
		// "iron" is in the tooltip of the steel plate and in the name of the iron ingot
		assertEquals(List.of(IRON_INGOT, STEEL_PLATE), search("iron"));
		assertEquals(List.of(SHARP_SWORD), search("sharpness"));
	}

	@Test
	@DisplayName("Text is a regular expression, an invalid one is matched as plain text")
	void regex() {
		assertEquals(List.of(IRON_INGOT, GOLD_INGOT), search("^[ig].*ingot"));
		assertEquals(List.of(OAK_LOG), search("o.k"));
		assertEquals(List.of(DIAMOND), search("^dia"));
		// a pipe always splits, it is never part of a regex
		assertEquals(List.of(OAK_LOG, DIAMOND), search("^oak|^dia"));
		// "[" is no valid regex, it is searched for as text and matches nothing here
		assertEquals(List.of(), search("[ingot"));
		assertNotNull(TerminalSearch.buildPattern("["));
		assertTrue(TerminalSearch.buildPattern("[").matcher("a[b").find());
		assertFalse(TerminalSearch.buildPattern("[").matcher("ab").find());
	}

	@Test
	@DisplayName("A pipe is an OR between alternatives")
	void or() {
		assertEquals(List.of(OAK_LOG, DIAMOND), search("oak|diamond"));
		assertEquals(List.of(OAK_LOG, DIAMOND), search(" oak | diamond "));
		assertEquals(List.of(OAK_LOG, DIAMOND), search("oak||diamond"));
		assertEquals(List.of(OAK_LOG), search("oak|"));
		assertEquals(List.of(OAK_LOG), search("|oak"));
		assertEquals(List.of(IRON_INGOT, GOLD_INGOT, DIAMOND), search("ingot|diamond"));
	}

	@Test
	@DisplayName("Terms separated by spaces all have to match")
	void and() {
		assertEquals(List.of(IRON_INGOT), search("iron ingot"));
		assertEquals(List.of(IRON_INGOT), search("ingot iron"));
		assertEquals(List.of(IRON_INGOT), search("iron  ingot"));
		assertEquals(List.of(), search("iron gold"));
		assertEquals(List.of(STEEL_PLATE), search("iron plate"));
	}

	@Test
	@DisplayName("AND binds tighter than OR")
	void precedence() {
		assertEquals(List.of(GOLD_INGOT, DIAMOND), search("gold ingot|diamond"));
		assertEquals(List.of(IRON_INGOT, DIAMOND), search("diamond|iron ingot"));
		assertEquals(List.of(), search("gold oak|iron diamond"));
	}

	@Test
	@DisplayName("@ matches the mod id, also as part of it")
	void mod() {
		assertEquals(List.of(STEEL_PLATE), search("@create"));
		assertEquals(List.of(STEEL_PLATE), search("@CREA"));
		assertEquals(List.of(IRON_INGOT, GOLD_INGOT, OAK_LOG, SHARP_SWORD, DIAMOND), search("@minecraft"));
		assertEquals(List.of(IRON_INGOT, GOLD_INGOT), search("@minecraft ingot"));
		assertEquals(List.of(IRON_INGOT, STEEL_PLATE), search("@minecraft iron|@create"));
		assertEquals(List.of(), search("@nothing"));
	}

	@Test
	@DisplayName("# matches tags, items without tags are not found by it")
	void tag() {
		assertEquals(List.of(IRON_INGOT, GOLD_INGOT), search("#ingots"));
		assertEquals(List.of(IRON_INGOT), search("#ingots/iron"));
		assertEquals(List.of(OAK_LOG), search("#minecraft:logs"));
		assertEquals(List.of(IRON_INGOT, GOLD_INGOT, OAK_LOG), search("#ingots|#logs"));
		assertEquals(List.of(GOLD_INGOT), search("#ingots gold"));
		assertFalse(search("#ingots").contains(STEEL_PLATE));
		assertEquals(List.of(), search("#nothing"));
	}

	@Test
	@DisplayName("$ matches the extra data, items without any never match")
	void data() {
		assertEquals(List.of(SHARP_SWORD), search("$sharpness"));
		assertEquals(List.of(SHARP_SWORD), search("$lvl:5"));
		assertEquals(List.of(SHARP_SWORD), search("$enchant.*sharpness"));
		assertEquals(List.of(), search("$unbreaking"));
		// even a pattern that matches everything finds only items that have data
		assertEquals(List.of(SHARP_SWORD), search("$"));
		assertEquals(List.of(SHARP_SWORD), search("$.*"));
	}

	@Test
	@DisplayName("Prefixes work inside alternatives and next to plain terms")
	void mixed() {
		assertEquals(List.of(SHARP_SWORD), search("@minecraft $sharpness"));
		assertEquals(List.of(SHARP_SWORD), search("#swords $lvl sharp"));
		assertEquals(List.of(OAK_LOG, STEEL_PLATE), search("#logs|@create plate"));
		assertEquals(List.of(), search("@create #ingots"));
	}

	@Test
	@DisplayName("Only the prefix at the start of a term counts")
	void prefixOnlyAtTermStart() {
		// "@" inside a word is text, matching nothing here
		assertEquals(List.of(), search("iron@minecraft"));
		assertEquals(List.of(), search("a#b"));
	}

	@Test
	@DisplayName("An item that cannot be read does not match, the others still do")
	void unreadableItem() {
		Target broken = new Target() {
			public String displayNameLower() { throw new IllegalStateException("name"); }
			public String modId() { throw new IllegalStateException("mod"); }
			public List<String> tooltipLines() { throw new IllegalStateException("tooltip"); }
			public List<String> tags() { throw new IllegalStateException("tags"); }
			public boolean hasData() { throw new IllegalStateException("data"); }
			public String data() { throw new IllegalStateException("data"); }
		};
		for (String q : new String[] {"ingot", "@minecraft", "#logs", "$x", "a|b"}) {
			assertFalse(TerminalSearch.matches(q, broken), q);
		}
		// an empty query needs to read nothing
		assertTrue(TerminalSearch.matches("", broken));
		assertEquals(List.of(IRON_INGOT), search("iron ingot"));
	}

	@Test
	@DisplayName("The tooltip is only read when the name did not already match")
	void lazyTooltip() {
		for (Item i : ALL)i.tooltipReads = 0;
		search("ingot");
		assertEquals(0, IRON_INGOT.tooltipReads);
		assertEquals(0, GOLD_INGOT.tooltipReads);
		assertEquals(1, OAK_LOG.tooltipReads);
		for (Item i : ALL)i.tooltipReads = 0;
		search("@create");
		for (Item i : ALL)assertEquals(0, i.tooltipReads);
	}

	@Test
	@DisplayName("A compiled query can be reused for many items")
	void reusable() {
		Predicate<Target> p = TerminalSearch.compile("ingot|log");
		for (int round = 0; round < 3; round++) {
			assertTrue(p.test(IRON_INGOT));
			assertTrue(p.test(OAK_LOG));
			assertFalse(p.test(DIAMOND));
		}
	}

	@Test
	@DisplayName("Patterns are built case insensitive and never throw")
	void patterns() {
		assertTrue(TerminalSearch.buildPattern("IRON").matcher("iron").find());
		assertNotNull(TerminalSearch.buildPattern("("));
		assertNotNull(TerminalSearch.buildPattern("*"));
		assertNotNull(TerminalSearch.buildPattern("\\"));
	}
}
