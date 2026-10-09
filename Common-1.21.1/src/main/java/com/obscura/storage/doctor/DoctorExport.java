package com.obscura.storage.doctor;

import java.util.List;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The file format of {@code doctor export}: a JSON document with the {@code top} list and every
 * network's details, and a CSV summary with one row per network. Plain values only, see
 * {@link NetworkReport}, so the format is unit tested without a world.
 */
public final class DoctorExport {
	/** Bumped when fields are renamed or removed; adding fields keeps the version. */
	public static final int FORMAT_VERSION = 1;
	public static final String CSV_HEADER = "rank,dimension,x,y,z,inventories,slots,slots_used,avg_ms,peak_ms,rescans_per_min,rebuilds_per_min,players_watching,packets_per_s,kb_per_s";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private DoctorExport() {}

	/**
	 * @param reports every loaded network, heaviest first (the order of {@code top})
	 * @param generatedAt when the export was made (ISO-8601 local date-time)
	 * @param topCount how many networks the {@code top} list holds
	 */
	public static JsonObject toJson(List<NetworkReport> reports, String generatedAt, int topCount) {
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT_VERSION);
		root.addProperty("generated", generatedAt);
		root.addProperty("windowSeconds", NetworkStats.WINDOW_SECONDS);
		root.addProperty("networkCount", reports.size());
		double total = 0;
		for (NetworkReport r : reports)total += r.avgMs();
		root.addProperty("totalAvgMsPerTick", round(total));
		JsonArray top = new JsonArray();
		for (int i = 0; i < reports.size() && i < topCount; i++) {
			NetworkReport r = reports.get(i);
			JsonObject o = position(r);
			o.addProperty("rank", i + 1);
			o.addProperty("inventories", r.inventories());
			o.addProperty("slots", r.slots());
			o.addProperty("avgMsPerTick", round(r.avgMs()));
			o.addProperty("peakMs", round(r.peakMs()));
			top.add(o);
		}
		root.add("top", top);
		JsonArray nets = new JsonArray();
		for (int i = 0; i < reports.size(); i++) {
			nets.add(network(reports.get(i), i + 1));
		}
		root.add("networks", nets);
		return root;
	}

	public static String toJsonString(List<NetworkReport> reports, String generatedAt, int topCount) {
		return GSON.toJson(toJson(reports, generatedAt, topCount));
	}

	private static JsonObject position(NetworkReport r) {
		JsonObject o = new JsonObject();
		o.addProperty("dimension", r.dimension());
		o.addProperty("x", r.x());
		o.addProperty("y", r.y());
		o.addProperty("z", r.z());
		return o;
	}

	private static JsonObject network(NetworkReport r, int rank) {
		JsonObject o = position(r);
		o.addProperty("rank", rank);
		o.addProperty("inventories", r.inventories());
		o.addProperty("slots", r.slots());
		o.addProperty("slotsUsed", r.slotsUsed());
		JsonObject t = new JsonObject();
		t.addProperty("windowSeconds", round(r.windowSeconds()));
		t.addProperty("avgMsPerTick", round(r.avgMs()));
		t.addProperty("peakMs", round(r.peakMs()));
		t.addProperty("rescansPerMin", round(r.rescansPerMin()));
		t.addProperty("rebuildsPerMin", round(r.rebuildsPerMin()));
		o.add("timing", t);
		JsonObject s = new JsonObject();
		s.addProperty("playersWatching", r.playersWatching());
		s.addProperty("packetsPerSec", round(r.packetsPerSec()));
		s.addProperty("bytesPerSec", round(r.bytesPerSec()));
		o.add("sync", s);
		NetworkReport.Details d = r.details();
		if (d != null) {
			JsonObject dup = new JsonObject();
			dup.addProperty("sameStorage", d.dupSameStorage());
			dup.addProperty("reachedTwice", d.dupReachedTwice());
			dup.addProperty("network", d.dupNetwork());
			o.add("duplicatesRemoved", dup);
			o.addProperty("uniqueItems", d.uniqueItems());
			o.addProperty("labelled", d.labelled());
			JsonObject b = new JsonObject();
			b.addProperty("cables", d.cables());
			b.addProperty("cableConnectors", d.cableConnectors());
			b.addProperty("interfaces", d.interfaces());
			b.addProperty("proxies", d.proxies());
			b.addProperty("links", d.links());
			b.addProperty("linkedConnectors", d.linkedConnectors());
			b.addProperty("cablesTruncated", d.cablesTruncated());
			o.add("blocks", b);
			JsonObject a = new JsonObject();
			a.addProperty("terminals", d.terminals());
			a.addProperty("craftingTerminals", d.craftingTerminals());
			a.addProperty("hoppers", d.hoppers());
			a.addProperty("levelEmitters", d.emitters());
			a.addProperty("filterBlocks", d.filterBlocks());
			o.add("attached", a);
			JsonObject c = new JsonObject();
			c.addProperty("maxInventories", d.cap());
			c.addProperty("overCap", d.overCap());
			o.add("cap", c);
			JsonArray sk = new JsonArray();
			for (NetworkReport.Skipped k : d.skipped()) {
				JsonObject e = new JsonObject();
				e.addProperty("dimension", k.dimension());
				e.addProperty("x", k.x());
				e.addProperty("y", k.y());
				e.addProperty("z", k.z());
				e.addProperty("reason", k.reason());
				sk.add(e);
			}
			o.add("skipped", sk);
			JsonArray h = new JsonArray();
			for (String hint : d.hints())h.add(hint);
			o.add("hints", h);
		}
		return o;
	}

	/** One header line, then one line per network in the given order. Lines end with \n. */
	public static String toCsv(List<NetworkReport> reports) {
		StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
		for (int i = 0; i < reports.size(); i++) {
			NetworkReport r = reports.get(i);
			sb.append(i + 1).append(',')
			.append(csv(r.dimension())).append(',')
			.append(r.x()).append(',').append(r.y()).append(',').append(r.z()).append(',')
			.append(r.inventories()).append(',')
			.append(r.slots()).append(',')
			.append(r.slotsUsed()).append(',')
			.append(num(r.avgMs())).append(',')
			.append(num(r.peakMs())).append(',')
			.append(num(r.rescansPerMin())).append(',')
			.append(num(r.rebuildsPerMin())).append(',')
			.append(r.playersWatching()).append(',')
			.append(num(r.packetsPerSec())).append(',')
			.append(num(r.bytesPerSec() / 1024.0)).append('\n');
		}
		return sb.toString();
	}

	private static String num(double d) {
		return String.format(Locale.ROOT, "%.4f", d);
	}

	private static double round(double d) {
		return Math.round(d * 10000) / 10000.0;
	}

	/** RFC 4180 quoting, only when needed. */
	static String csv(String s) {
		if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0)return s;
		return '"' + s.replace("\"", "\"\"") + '"';
	}
}
