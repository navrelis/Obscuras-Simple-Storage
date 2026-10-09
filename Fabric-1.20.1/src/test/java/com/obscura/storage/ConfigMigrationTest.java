package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Config migration from files written before configVersion 2. */
@DisplayName("Config migration")
public class ConfigMigrationTest {

	private static Config oldDefaults() {
		Config c = new Config();
		c.configVersion = 0;
		c.wirelessRange = 16;
		c.advWirelessRange = 64;
		c.wirelessTermBeaconLvlCrossDim = 4;
		c.invLinkBeaconLvl = 1;
		c.invLinkBeaconLvlCrossDim = 2;
		return c;
	}

	@Test
	@DisplayName("old default values move to the new defaults")
	void oldDefaultsAreReplaced() throws Exception {
		Config c = oldDefaults();
		c.validatePostLoad();
		assertEquals(32, c.wirelessRange);
		assertEquals(128, c.advWirelessRange);
		assertEquals(3, c.wirelessTermBeaconLvlCrossDim);
		assertEquals(0, c.invLinkBeaconLvl);
		assertEquals(3, c.invLinkBeaconLvlCrossDim);
		assertEquals(2, c.configVersion);
		assertTrue(c.wasMigrated());
	}

	@Test
	@DisplayName("values changed by the owner are kept")
	void customValuesAreKept() throws Exception {
		Config c = oldDefaults();
		c.wirelessRange = 24;
		c.advWirelessRange = 200;
		c.wirelessTermBeaconLvlCrossDim = 2;
		c.invLinkBeaconLvl = 3;
		c.invLinkBeaconLvlCrossDim = -1;
		c.validatePostLoad();
		assertEquals(24, c.wirelessRange);
		assertEquals(200, c.advWirelessRange);
		assertEquals(2, c.wirelessTermBeaconLvlCrossDim);
		assertEquals(3, c.invLinkBeaconLvl);
		assertEquals(-1, c.invLinkBeaconLvlCrossDim);
		assertEquals(2, c.configVersion);
	}

	@Test
	@DisplayName("migration is idempotent and skipped for version 2")
	void currentVersionIsNotMigrated() throws Exception {
		Config c = oldDefaults();
		c.validatePostLoad();
		c.wirelessRange = 16;
		c.validatePostLoad();
		assertEquals(16, c.wirelessRange);

		Config fresh = new Config();
		fresh.validatePostLoad();
		assertEquals(32, fresh.wirelessRange);
		assertEquals(2, fresh.configVersion);
	}

	@Test
	@DisplayName("a file without configVersion is treated as version 0")
	void missingVersionKeyMeansOldFile() throws Exception {
		Gson gson = new GsonBuilder().create();
		Config c = gson.fromJson("{\"wirelessRange\":16,\"advWirelessRange\":64}", Config.class);
		c.validatePostLoad();
		assertEquals(32, c.wirelessRange);
		assertEquals(128, c.advWirelessRange);
		assertEquals(2, c.configVersion);
	}

	@Test
	@DisplayName("renamed legacy keys carry their value over, also for a current config version")
	void renamedKeysCarryOver() throws Exception {
		Gson gson = new GsonBuilder().create();
		Config c = gson.fromJson("{\"configVersion\":2,\"invRange\":24,\"wirelessTermBeaconLvlDim\":2,\"invLinkBeaconLvlDim\":-1}", Config.class);
		c.validatePostLoad();
		assertEquals(24, c.invConnectorScanRange);
		assertEquals(2, c.wirelessTermBeaconLvlCrossDim);
		assertEquals(-1, c.invLinkBeaconLvlCrossDim);
		assertNull(c.invRange, "the legacy key is cleared so it disappears from the file");
		assertNull(c.wirelessTermBeaconLvlDim);
		assertNull(c.invLinkBeaconLvlDim);
		assertTrue(c.wasMigrated());
	}

	@Test
	@DisplayName("a carried over legacy value still goes through the range check (clamped)")
	void carriedValueIsValidated() throws Exception {
		Gson gson = new GsonBuilder().create();
		Config c = gson.fromJson("{\"configVersion\":2,\"invRange\":1000}", Config.class);
		c.validatePostLoad();
		assertEquals(64, c.invConnectorScanRange);
	}

	@Test
	@DisplayName("a current config is not reported as migrated")
	void currentConfigIsNotMigrated() throws Exception {
		Config c = new Config();
		c.configVersion = 2;
		c.validatePostLoad();
		assertFalse(c.wasMigrated());
	}

	@Test
	@DisplayName("values out of bounds are clamped to the nearest bound, like the 1.21.1 config")
	void boundsAreApplied() throws Exception {
		Config c = new Config();
		c.configVersion = 2;
		c.wirelessRange = 1000;
		c.advWirelessRange = 5000;
		c.wirelessTermBeaconLvl = 9;
		c.invLinkBeaconLvlCrossDim = -7;
		c.invConnectorScanRange = 0;
		c.invConnectorMaxCables = 100000;
		c.basicHopperCooldown = -3;
		c.validatePostLoad();
		assertEquals(256, c.wirelessRange);
		assertEquals(1024, c.advWirelessRange);
		assertEquals(4, c.wirelessTermBeaconLvl);
		assertEquals(-1, c.invLinkBeaconLvlCrossDim);
		assertEquals(1, c.invConnectorScanRange, "the scan range goes down to 1");
		assertEquals(16384, c.invConnectorMaxCables);
		assertEquals(1, c.basicHopperCooldown);
	}

	@Test
	@DisplayName("values inside the bounds are kept")
	void valuesInsideBoundsAreKept() throws Exception {
		Config c = new Config();
		c.configVersion = 2;
		c.invConnectorScanRange = 1;
		c.invConnectorMaxCables = 16384;
		c.wirelessRange = 4;
		c.validatePostLoad();
		assertEquals(1, c.invConnectorScanRange);
		assertEquals(16384, c.invConnectorMaxCables);
		assertEquals(4, c.wirelessRange);
	}
}
