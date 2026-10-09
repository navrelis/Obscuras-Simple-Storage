package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;

/**
 * Forge config migration: the same value moves as on Fabric, plus reading a server config written by a
 * version without a configVersion (same TOML keys, removed keys dropped).
 */
@DisplayName("Config migration (Forge)")
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
	void oldDefaultsAreReplaced() {
		Config c = oldDefaults();
		assertTrue(c.migrate());
		assertEquals(32, c.wirelessRange);
		assertEquals(128, c.advWirelessRange);
		assertEquals(3, c.wirelessTermBeaconLvlCrossDim);
		assertEquals(0, c.invLinkBeaconLvl);
		assertEquals(3, c.invLinkBeaconLvlCrossDim);
		assertEquals(2, c.configVersion);
	}

	@Test
	@DisplayName("values changed by the owner are kept")
	void customValuesAreKept() {
		Config c = oldDefaults();
		c.wirelessRange = 24;
		c.advWirelessRange = 200;
		c.wirelessTermBeaconLvlCrossDim = 2;
		c.invLinkBeaconLvl = 3;
		c.invLinkBeaconLvlCrossDim = -1;
		c.migrate();
		assertEquals(24, c.wirelessRange);
		assertEquals(200, c.advWirelessRange);
		assertEquals(2, c.wirelessTermBeaconLvlCrossDim);
		assertEquals(3, c.invLinkBeaconLvl);
		assertEquals(-1, c.invLinkBeaconLvlCrossDim);
		assertEquals(2, c.configVersion);
	}

	@Test
	@DisplayName("migration is idempotent and skipped for version 2")
	void currentVersionIsNotMigrated() {
		Config c = oldDefaults();
		c.migrate();
		c.wirelessRange = 16;
		assertFalse(c.migrate());
		assertEquals(16, c.wirelessRange);
	}

	@Test
	@DisplayName("a server config without configVersion is read, corrected and migrated")
	void unversionedServerConfig() {
		String toml = String.join("\n",
				"inventoryConnectorRange = 100",
				"onlyTrimsConnect = true",
				"invCableConnectorMaxScanSize = 4096",
				"wirelessReach = 16",
				"advWirelessRange = 64",
				"wirelessTermBeaconLvl = 1",
				"wirelessTermBeaconLvlDim = 4",
				"invLinkBeaconLvl = 1",
				"invLinkBeaconLvlDim = 2",
				"invDupScanSize = 100",
				"");
		CommentedConfig cfg = new TomlParser().parse(toml);
		Config.serverSpec.correct(cfg);
		Config.serverSpec.setConfig(cfg);
		Config c = new Config();
		c.readServerValues();
		assertEquals(0, c.configVersion, "the missing configVersion reads as 0");
		assertEquals(16, c.invConnectorScanRange, "out of range values fall back to the default");
		assertTrue(c.onlyTrims);
		assertEquals(4096, c.invConnectorMaxCables);
		assertTrue(c.migrateServerSpec());
		assertEquals(32, c.wirelessRange);
		assertEquals(128, c.advWirelessRange);
		assertEquals(3, c.wirelessTermBeaconLvlCrossDim);
		assertEquals(0, c.invLinkBeaconLvl);
		assertEquals(3, c.invLinkBeaconLvlCrossDim);
		// Written back into the config so the file gets the new values when saved
		assertEquals(32, (int) cfg.<Integer>get("wirelessReach"));
		assertEquals(2, (int) cfg.<Integer>get("configVersion"));
		assertFalse(cfg.contains("invDupScanSize"), "removed keys are dropped");
		assertTrue(TomlFormat.instance().createWriter().writeToString(cfg).contains("itemLocatorEnabled"), "new keys are added");
	}

	@Test
	@DisplayName("the scan range and cable limit bounds match the 1.21.1 config")
	void connectorBounds() {
		Config ok = readServer("inventoryConnectorRange = 1", "invCableConnectorMaxScanSize = 16384");
		assertEquals(1, ok.invConnectorScanRange, "a scan range of 1 is allowed");
		assertEquals(16384, ok.invConnectorMaxCables, "16384 cables is the upper limit");
		Config bad = readServer("inventoryConnectorRange = 0", "invCableConnectorMaxScanSize = 16385");
		assertEquals(16, bad.invConnectorScanRange, "out of range values are reset to the default");
		assertEquals(2048, bad.invConnectorMaxCables, "out of range values are reset to the default");
	}

	private static Config readServer(String... lines) {
		CommentedConfig cfg = new TomlParser().parse(String.join("\n", lines));
		Config.serverSpec.correct(cfg);
		Config.serverSpec.setConfig(cfg);
		Config c = new Config();
		c.readServerValues();
		return c;
	}
}
