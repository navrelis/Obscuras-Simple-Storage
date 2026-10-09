package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.obscura.storage.block.entity.BasicInventoryHopperBlockEntity;

/** Config migration from files written before configVersion 2. */
@DisplayName("Config migration")
public class ConfigMigrationTest {

	private static Config oldDefaults() {
		Config c = new Config();
		c.configVersion = 0;
		c.wirelessRange = 16;
		c.advWirelessRange = 64;
		c.wirelessTermBeaconLvlCrossDim = 4;
		c.invLinkBeaconLvlCrossDim = 2;
		c.basicHopperCooldown = 10;
		return c;
	}

	@Test
	@DisplayName("old default values move to the new defaults")
	void oldDefaultsAreReplaced() {
		Config c = oldDefaults();
		c.validatePostLoad();
		assertEquals(32, c.wirelessRange);
		assertEquals(128, c.advWirelessRange);
		assertEquals(3, c.wirelessTermBeaconLvlCrossDim);
		assertEquals(3, c.invLinkBeaconLvlCrossDim);
		assertEquals(8, c.basicHopperCooldown);
		assertEquals(2, c.configVersion);
	}

	@Test
	@DisplayName("values changed by the owner are kept")
	void customValuesAreKept() {
		Config c = oldDefaults();
		c.wirelessRange = 24;
		c.advWirelessRange = 200;
		c.wirelessTermBeaconLvlCrossDim = 2;
		c.invLinkBeaconLvlCrossDim = -1;
		c.basicHopperCooldown = 5;
		c.validatePostLoad();
		assertEquals(24, c.wirelessRange);
		assertEquals(200, c.advWirelessRange);
		assertEquals(2, c.wirelessTermBeaconLvlCrossDim);
		assertEquals(-1, c.invLinkBeaconLvlCrossDim);
		assertEquals(5, c.basicHopperCooldown);
		assertEquals(2, c.configVersion);
	}

	@Test
	@DisplayName("migration is idempotent and skipped for version 2")
	void currentVersionIsNotMigrated() {
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
	void missingVersionKeyMeansOldFile() {
		Gson gson = new GsonBuilder().create();
		Config c = gson.fromJson("{\"wirelessRange\":16,\"basicHopperCooldown\":10}", Config.class);
		c.validatePostLoad();
		assertEquals(32, c.wirelessRange);
		assertEquals(8, c.basicHopperCooldown);
		assertEquals(2, c.configVersion);
	}

	@Test
	@DisplayName("ranges are clamped to the new bounds")
	void boundsAreApplied() {
		Config c = new Config();
		c.configVersion = 2;
		c.wirelessRange = 1000;
		c.advWirelessRange = 5000;
		c.wirelessTermBeaconLvl = 9;
		c.invLinkBeaconLvlCrossDim = -7;
		c.validatePostLoad();
		assertEquals(256, c.wirelessRange);
		assertEquals(1024, c.advWirelessRange);
		assertEquals(4, c.wirelessTermBeaconLvl);
		assertEquals(-1, c.invLinkBeaconLvlCrossDim);
	}

	@Test
	@DisplayName("basic hopper moves exactly every basicHopperCooldown ticks")
	void hopperPeriodMatchesCooldown() {
		for (int cd : new int[] {1, 2, 8, 10, 200}) {
			int cooldown = BasicInventoryHopperBlockEntity.cooldownAfterMove(cd);
			int ticks = 0;
			// same pattern as updateServer(): while cooldown > 0 the tick is skipped, otherwise the hopper acts
			while (true) {
				ticks++;
				if (cooldown > 0) {
					cooldown--;
					continue;
				}
				break;
			}
			assertEquals(cd, ticks, "cd=" + cd);
		}
	}
}
