package com.obscura.storage;

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
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

import com.obscura.storage.migration.LegacyIds;

/** The shared language files of 1.21.1 (Common-1.21.1) are read by vanilla's own (limited) formatter. */
@DisplayName("Language files")
public class LangFilesTest {
	private static final Path LANG_DIR = Paths.get("..", "Common-1.21.1", "src", "main", "resources", "assets", "obscuras_storage", "lang");

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
			for (Map.Entry<String, JsonElement> e : parseStrict(p).entrySet()) {
				assertTrue(e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString(), p.getFileName() + " " + e.getKey());
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
				assertFalse(e.getKey().contains(LegacyIds.OLD_NAMESPACE), p.getFileName() + " " + e.getKey());
			}
		}
	}

	private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?s|%%");

	@Test
	@DisplayName("Placeholders are only %s, %n$s (and %%)")
	void placeholders() throws IOException {
		for (Path p : files()) {
			for (Map.Entry<String, JsonElement> e : parseStrict(p).entrySet()) {
				String v = e.getValue().getAsString();
				String rest = PLACEHOLDER.matcher(v).replaceAll("");
				assertFalse(rest.contains("%"), p.getFileName() + " " + e.getKey() + ": " + v);
			}
		}
	}
}
