package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.obscura.storage.util.SearchModes;

/** Terminal search options and how they are packed into the terminal settings int. */
@DisplayName("Terminal search modes")
public class SearchModesTest {
	/** Settings bits used by other options: control mode 0-1, reverse 2, sort 3-4 and 12, crafting pull 8, ghost 9, tall 10. */
	private static final int OTHER_BITS = 0b11 | (1 << 2) | (0b11 << 3) | (1 << 12) | (1 << 8) | (1 << 9) | (1 << 10);

	@Test
	@DisplayName("Every search type survives packing, next to any other setting")
	void roundTrip() {
		for (int t = 0; t <= SearchModes.ALL; t++) {
			assertEquals(t, SearchModes.unpack(SearchModes.pack(t)), "type " + t);
			assertEquals(t, SearchModes.unpack(SearchModes.pack(t) | OTHER_BITS), "type " + t + " with other settings");
			assertEquals(0, SearchModes.pack(t) & OTHER_BITS, "type " + t + " overlaps another setting");
		}
		// the settings int travels as a menu data slot, which is a short
		assertTrue(SearchModes.pack(SearchModes.ALL) < (1 << 15));
	}

	@Test
	@DisplayName("Settings saved before the popup keep their options and have smart search on")
	void legacySettings() {
		for (int old = 0; old < 8; old++) {
			int saved = (old << 5) | OTHER_BITS;
			int t = SearchModes.unpack(saved);
			assertEquals(old, t);
			assertTrue(SearchModes.isSmartSearch(t));
		}
		assertFalse(SearchModes.isSmartSearch(SearchModes.NO_SMART));
	}

	@Test
	@DisplayName("Every search button tooltip exists in en_us")
	void tooltipKeys() throws IOException {
		JsonObject en;
		try (Reader r = Files.newBufferedReader(Paths.get("..", "Common-1.20.1", "src", "main", "resources", "assets", "obscuras_storage", "lang", "en_us.json"), StandardCharsets.UTF_8)) {
			en = JsonParser.parseReader(r).getAsJsonObject();
		}
		for (int t = -1; t <= SearchModes.ALL; t++) {
			assertTrue(en.has(SearchModes.tooltipKey(t)), SearchModes.tooltipKey(t));
		}
		for (String k : new String[] {"opt.search_auto", "opt.search_keep", "opt.search_sync", "opt.search_smart", "recipe_book",
				"terminal_stat.slots", "terminal_stat.free", "terminal_stat.unique", "terminal_stat.alot"}) {
			assertTrue(en.has("tooltip.obscuras_storage." + k), k);
		}
	}
}
