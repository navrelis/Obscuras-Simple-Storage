package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.obscura.storage.doctor.DoctorExport;
import com.obscura.storage.doctor.DoctorFormat;
import com.obscura.storage.doctor.NetworkReport;
import com.obscura.storage.doctor.NetworkStats;

/** The Network Doctor's pure parts: rolling window, number formatting, export format. */
@DisplayName("Network Doctor")
public class DoctorTest {
	private static final long MS = 1_000_000L;

	@Test
	@DisplayName("Average and peak per tick, idle ticks included")
	void averageAndPeak() {
		NetworkStats s = new NetworkStats();
		s.addWork(0, 2 * MS);
		s.addWork(10, 1 * MS);
		s.addWork(10, 3 * MS);// same tick: the peak is the tick's sum
		assertEquals(20, s.windowTicks(19));
		assertEquals(6.0 / 20, s.avgMsPerTick(19), 1e-9);
		assertEquals(4.0, s.peakMs(19), 1e-9, "peak of the tick in progress");
		s.addWork(15, MS / 2);
		assertEquals(4.0, s.peakMs(19), 1e-9, "peak after the tick was folded into its bucket");
		assertEquals(4, s.samples(19));
	}

	@Test
	@DisplayName("The window covers 30 seconds and old seconds fall out")
	void windowExpiry() {
		NetworkStats s = new NetworkStats();
		s.addWork(0, 10 * MS);
		for (long t = 20; t < 1000; t += 5)s.addWork(t, MS);
		long now = 999;
		assertEquals(NetworkStats.WINDOW_TICKS, s.windowTicks(now), "a full window is 600 ticks");
		// ticks 400..999 are in the window: 120 samples of 1 ms
		assertEquals(120 * MS, s.totalNanos(now));
		assertEquals(120.0 / 600, s.avgMsPerTick(now), 1e-9);
		assertEquals(1.0, s.peakMs(now), 1e-9, "the 10 ms tick at 0 is out of the window");
		// Nothing new for a minute: everything is out
		assertEquals(0, s.totalNanos(now + 1200));
		assertEquals(0.0, s.peakMs(now + 1200), 1e-9);
	}

	@Test
	@DisplayName("Rates per minute and per second, and reset")
	void ratesAndReset() {
		NetworkStats s = new NetworkStats();
		s.addWork(0, 0);
		for (int i = 0; i < 5; i++)s.addRescan(100 + i * 50);
		s.addRebuild(300);
		s.addPackets(400, 30, 30 * 1024);
		long now = 599;
		assertEquals(600, s.windowTicks(now));
		assertEquals(10.0, s.rescansPerMinute(now), 1e-9);
		assertEquals(2.0, s.rebuildsPerMinute(now), 1e-9);
		assertEquals(1.0, s.packetsPerSecond(now), 1e-9);
		assertEquals(1024.0, s.bytesPerSecond(now), 1e-9);
		s.reset();
		assertEquals(0, s.windowTicks(now));
		// A network that just loaded: rates are over at least 10 s, not extrapolated from 5 ticks
		s.addRescan(1000);
		assertEquals(5, s.windowTicks(1004));
		assertEquals(6.0, s.rescansPerMinute(1004), 1e-9);
		assertEquals(0.0, s.avgMsPerTick(now), 1e-9);
		assertEquals(0.0, s.rescansPerMinute(now), 1e-9);
	}

	@Test
	@DisplayName("Log warnings: thresholds, minimum window and the 10 minute throttle")
	void warnings() {
		NetworkStats s = new NetworkStats();
		for (long t = 0; t < 100; t++)s.addWork(t, 6 * MS);
		assertFalse(s.shouldWarn(99, 5.0, 0, 10), "5 s of data is not enough for the ms warning");
		for (long t = 100; t < 300; t++)s.addWork(t, 6 * MS);
		assertTrue(s.shouldWarn(299, 5.0, 0, 10));
		assertFalse(s.shouldWarn(399, 5.0, 0, 10), "throttled");
		assertFalse(s.shouldWarn(399, 0, 0, 10_000), "both warnings off");
		NetworkStats big = new NetworkStats();
		assertTrue(big.shouldWarn(0, 0, 100, 101), "inventory warning needs no timing data");
		assertFalse(big.shouldWarn(NetworkStats.WARN_INTERVAL_TICKS - 1, 0, 100, 101));
		assertTrue(big.shouldWarn(NetworkStats.WARN_INTERVAL_TICKS, 0, 100, 101));
		assertFalse(new NetworkStats().shouldWarn(0, 0, 100, 100), "at the threshold is fine");
	}

	@Test
	@DisplayName("Numbers are formatted readably and independent of the locale")
	void formatting() {
		assertEquals("0", DoctorFormat.count(0));
		assertEquals("1,234", DoctorFormat.count(1234));
		assertEquals("1,234,567", DoctorFormat.count(1234567));
		assertEquals("0.42", DoctorFormat.ms(0.4199));
		assertEquals("2.41", DoctorFormat.ms(2.405001));
		assertEquals("14", DoctorFormat.rate(14.0));
		assertEquals("12.6", DoctorFormat.rate(12.56));
		assertEquals("74%", DoctorFormat.percent(5120, 6912));
		assertEquals("0%", DoctorFormat.percent(5, 0));
		assertEquals("7.7%", DoctorFormat.share(3.86 / 50));
		assertEquals("12.6", DoctorFormat.kilobytes(12.6 * 1024));
	}

	private static NetworkReport report(String dim, int x, double avg) {
		NetworkReport.Details d = new NetworkReport.Details(2, 1, 0, 40, 12, 1, 0, 0, 0, 0, 2, 0, 1, 0, 0, 0, false,
				List.of(new NetworkReport.Skipped(dim, x + 1, 64, 0, NetworkReport.Reasons.NOT_INVENTORY)), List.of(NetworkReport.Hints.RESCANS), 1, 3);
		return new NetworkReport(dim, x, 64, 0, 3, 81, 20, avg, avg * 3, 12, 1, 30, 1, 4, 2048, d);
	}

	@Test
	@DisplayName("Export: JSON with top and per-network details, CSV with one row per network")
	void export() {
		List<NetworkReport> reports = List.of(report("minecraft:overworld", 10, 1.25), report("mod:odd,dim", -5, 0.5));
		JsonObject json = JsonParser.parseString(DoctorExport.toJsonString(reports, "2026-10-01T12:00", 1)).getAsJsonObject();
		assertEquals(DoctorExport.FORMAT_VERSION, json.get("format").getAsInt());
		assertEquals(2, json.get("networkCount").getAsInt());
		assertEquals(1.75, json.get("totalAvgMsPerTick").getAsDouble(), 1e-9);
		JsonArray top = json.getAsJsonArray("top");
		assertEquals(1, top.size(), "top holds topCount networks");
		assertEquals(1, top.get(0).getAsJsonObject().get("rank").getAsInt());
		JsonArray nets = json.getAsJsonArray("networks");
		assertEquals(2, nets.size());
		JsonObject n = nets.get(0).getAsJsonObject();
		assertEquals(10, n.get("x").getAsInt());
		assertEquals(61, n.get("slotsUsed").getAsInt());
		assertEquals(1.25, n.getAsJsonObject("timing").get("avgMsPerTick").getAsDouble(), 1e-9);
		assertEquals(3, n.getAsJsonObject("duplicatesRemoved").get("sameStorage").getAsInt() + n.getAsJsonObject("duplicatesRemoved").get("reachedTwice").getAsInt());
		assertEquals(12, n.getAsJsonObject("blocks").get("cables").getAsInt());
		JsonObject attached = n.getAsJsonObject("attached");
		assertEquals(2, attached.get("terminals").getAsInt());
		assertEquals(3, attached.get("filterBlocks").getAsInt(), "Ejectors, Disposals and Exporters together");
		// Fields are only ever appended: the ones older exports had keep their names and order
		assertEquals(List.of("terminals", "craftingTerminals", "hoppers", "levelEmitters", "filterBlocks"), List.copyOf(attached.keySet()));
		assertEquals("not_inventory", n.getAsJsonArray("skipped").get(0).getAsJsonObject().get("reason").getAsString());
		assertEquals("rescans", n.getAsJsonArray("hints").get(0).getAsString());
		assertEquals(1, n.get("labelled").getAsInt(), "members labelled by glow frames");

		String csv = DoctorExport.toCsv(reports);
		String[] lines = csv.split("\n");
		assertEquals(3, lines.length);
		assertEquals(DoctorExport.CSV_HEADER, lines[0]);
		int columns = DoctorExport.CSV_HEADER.split(",").length;
		assertEquals(columns, lines[1].split(",").length);
		assertTrue(lines[1].startsWith("1,minecraft:overworld,10,64,0,3,81,61,1.2500,"), lines[1]);
		assertTrue(lines[2].startsWith("2,\"mod:odd,dim\",-5,"), "a comma in a value is quoted: " + lines[2]);
		assertTrue(csv.endsWith("\n"));
	}
}
