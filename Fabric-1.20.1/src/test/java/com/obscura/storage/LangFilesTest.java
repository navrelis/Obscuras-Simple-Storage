package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import com.obscura.storage.migration.LegacyIds;

/** The language files are read by vanilla's own (limited) formatter. */
@DisplayName("Language files")
public class LangFilesTest {
	private static final Path LANG_DIR = Paths.get("..", "Common-1.20.1", "src", "main", "resources", "assets", "obscuras_storage", "lang");
	/** Every % in a string must be %s, %n$s or %%, vanilla's formatter knows nothing else (no %d). */
	private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z%]");

	private static List<Path> files() throws IOException {
		List<Path> out = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(LANG_DIR, "*.json")) {
			for (Path p : ds)out.add(p);
		}
		assertFalse(out.isEmpty(), "no language files found in " + LANG_DIR.toAbsolutePath());
		return out;
	}

	private static JsonObject parseStrict(Path p) throws IOException {
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			JsonReader jr = new JsonReader(r);
			jr.setLenient(false);
			JsonElement e = new Gson().getAdapter(JsonElement.class).read(jr);
			assertTrue(e.isJsonObject(), p.getFileName() + " is not an object");
			return e.getAsJsonObject();
		}
	}

	@Test
	@DisplayName("Every language file is valid JSON with string values only")
	void validJson() throws IOException {
		for (Path p : files()) {
			JsonObject o = parseStrict(p);
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				assertTrue(e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString(), p.getFileName() + " " + e.getKey());
			}
		}
	}

	@Test
	@DisplayName("Only %s, %n$s and %% placeholders are used")
	void placeholders() throws IOException {
		for (Path p : files()) {
			JsonObject o = parseStrict(p);
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				String v = e.getValue().getAsString();
				Matcher m = PLACEHOLDER.matcher(v);
				while (m.find()) {
					String ph = m.group();
					assertTrue(ph.endsWith("s") || ph.equals("%%"), p.getFileName() + " " + e.getKey() + " uses " + ph);
				}
				// a % that is not part of a valid placeholder
				String rest = PLACEHOLDER.matcher(v).replaceAll("");
				assertFalse(rest.contains("%"), p.getFileName() + " " + e.getKey() + " has a stray %");
			}
		}
	}

	@Test
	@DisplayName("The link tooltip keys used by the Inventory Cable Connector exist")
	void linkTooltipKeys() throws IOException {
		JsonObject en = parseStrict(LANG_DIR.resolve("en_us.json"));
		for (String k : new String[] {"", ".beacon", ".beacon0", ".beacon1", ".beacon2"}) {
			assertTrue(en.has("tooltip.obscuras_storage.inventory_cable_connector_link" + k), k);
		}
		assertEquals(1, en.get("tooltip.obscuras_storage.inventory_cable_connector_link.beacon1").getAsString().split("%s", -1).length - 1);
	}

	@Test
	@DisplayName("The Inventory Link label uses the 1.21.1 key in every language, the old key is gone")
	void beaconLabelKey() throws IOException {
		String key = "label.obscuras_storage.inventory_connector.beacon_level";
		assertTrue(parseStrict(LANG_DIR.resolve("en_us.json")).has(key));
		for (Path p : files()) {
			JsonObject o = parseStrict(p);
			assertFalse(o.has("ts.inventory_connector.beacon_level"), p.getFileName() + " still has the old key");
			if (o.has(key))assertTrue(o.get(key).getAsString().contains("%s"), p.getFileName() + " label has no level");
		}
	}

	@Test
	@DisplayName("Screen titles, block and item names use the 1.21.1 keys in every language, the old ts.* keys are gone")
	void menuTitleKeys() throws IOException {
		JsonObject en = parseStrict(LANG_DIR.resolve("en_us.json"));
		for (String k : new String[] {"storage_terminal", "crafting_terminal", "level_emitter", "inventory_connector", "filing_cabinet"}) {
			assertTrue(en.has("menu.obscuras_storage." + k), k);
		}
		for (Path p : files()) {
			for (String k : parseStrict(p).keySet()) {
				assertFalse(k.startsWith("ts."), p.getFileName() + " still has the old key " + k);
				// the 1.20.1-only duplicates block.<mod>.ts.<name> were folded into block.<mod>.<name>
				assertFalse(k.contains(".ts."), p.getFileName() + " still has the old key " + k);
			}
		}
	}

	/** The original mod's name and author, in any spelling (also the Chinese transliteration of the name). */
	private static final Pattern OLD_BRAND = Pattern.compile("(?i)\\btom(s|'s|\\u2019s|5454)?\\b|\\u6c64\\u59c6");

	@Test
	@DisplayName("No language file names the original mod or its author, every key uses the current mod id")
	void brandName() throws IOException {
		for (Path p : files()) {
			for (Map.Entry<String, JsonElement> e : parseStrict(p).entrySet()) {
				String v = e.getValue().getAsString();
				assertFalse(OLD_BRAND.matcher(v).find(), p.getFileName() + " " + e.getKey() + ": " + v);
				assertFalse(OLD_BRAND.matcher(e.getKey()).find(), p.getFileName() + " " + e.getKey());
				assertFalse(e.getKey().contains(LegacyIds.OLD_NAMESPACE), p.getFileName() + " " + e.getKey());
			}
		}
	}
}
