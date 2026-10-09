package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.obscura.storage.util.HopperLogic;
import com.obscura.storage.util.HopperMode;

/** Pure logic of the Basic Inventory Hopper Pulse / Keep Stock modes. */
@DisplayName("Basic Inventory Hopper modes")
public class HopperModesTest {

	@Test
	@DisplayName("Configurator amount steps cycle 1, 2, 4, 8, 9, 16, 32, 64 and wrap")
	void amountCycling() {
		int a = 1;
		List<Integer> seen = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			a = HopperLogic.nextAmount(a);
			seen.add(a);
		}
		assertEquals(List.of(2, 4, 8, 9, 16, 32, 64, 1), seen);
		// Amounts set some other way are not steps, the next step above is used
		assertEquals(4, HopperLogic.nextAmount(3));
		assertEquals(16, HopperLogic.nextAmount(12));
		assertEquals(1, HopperLogic.nextAmount(64));
		assertEquals(1, HopperLogic.nextAmount(100));
	}

	@Test
	@DisplayName("Amount is clamped to 1..64")
	void amountClamp() {
		assertEquals(1, HopperLogic.clampAmount(0));
		assertEquals(1, HopperLogic.clampAmount(-5));
		assertEquals(9, HopperLogic.clampAmount(9));
		assertEquals(64, HopperLogic.clampAmount(64));
		assertEquals(64, HopperLogic.clampAmount(99));
	}

	@Test
	@DisplayName("Pulse mode moves on a rising redstone edge only")
	void risingEdge() {
		assertTrue(HopperLogic.isRisingEdge(false, true));
		assertFalse(HopperLogic.isRisingEdge(true, true));
		assertFalse(HopperLogic.isRisingEdge(true, false));
		assertFalse(HopperLogic.isRisingEdge(false, false));
	}

	@Test
	@DisplayName("Keep Stock only moves while the target holds fewer items than requested")
	void keepStock() {
		assertTrue(HopperLogic.needsStock(0, 1));
		assertTrue(HopperLogic.needsStock(7, 8));
		assertFalse(HopperLogic.needsStock(8, 8));
		assertFalse(HopperLogic.needsStock(20, 8));
		// counts above the int range do not overflow
		assertFalse(HopperLogic.needsStock(Long.MAX_VALUE, 64));
	}

	@Test
	@DisplayName("The mode button cycles Normal, Pulse, Keep Stock and wraps")
	void modeCycling() {
		HopperMode m = HopperMode.NORMAL;
		m = m.next();
		assertEquals(HopperMode.PULSE, m);
		m = m.next();
		assertEquals(HopperMode.KEEP_STOCK, m);
		m = m.next();
		assertEquals(HopperMode.NORMAL, m);
	}

	@Test
	@DisplayName("Saved mode ids: order is fixed, unknown ids load as Normal")
	void modeIds() {
		// the ordinal is saved in the world, the order must never change
		assertEquals(0, HopperMode.NORMAL.ordinal());
		assertEquals(1, HopperMode.PULSE.ordinal());
		assertEquals(2, HopperMode.KEEP_STOCK.ordinal());
		for (HopperMode m : HopperMode.values()) {
			assertEquals(m, HopperMode.byId(m.ordinal()));
		}
		assertEquals(HopperMode.NORMAL, HopperMode.byId(-1));
		assertEquals(HopperMode.NORMAL, HopperMode.byId(3));
		assertEquals(HopperMode.NORMAL, HopperMode.byId(255));
	}

	@Test
	@DisplayName("Only Pulse ignores the redstone level, Pulse and Keep Stock need a filter")
	void modeProperties() {
		assertTrue(HopperMode.NORMAL.isDisabledByRedstone());
		assertFalse(HopperMode.PULSE.isDisabledByRedstone());
		assertTrue(HopperMode.KEEP_STOCK.isDisabledByRedstone());
		assertFalse(HopperMode.NORMAL.needsFilter());
		assertTrue(HopperMode.PULSE.needsFilter());
		assertTrue(HopperMode.KEEP_STOCK.needsFilter());
	}

	@Test
	@DisplayName("Every mode has a translation in the English language file")
	void modeTranslations() throws IOException {
		JsonObject lang;
		try (InputStream in = HopperModesTest.class.getResourceAsStream("/assets/obscuras_storage/lang/en_us.json");
				Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			assertNotNull(in);
			lang = JsonParser.parseReader(r).getAsJsonObject();
		}
		for (HopperMode m : HopperMode.values()) {
			assertTrue(lang.has(m.getTranslationKey()), m.getTranslationKey());
		}
	}
}
