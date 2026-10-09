package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

/**
 * The Shulker Dock's resource files, read as plain strict JSON: the shared assets and data
 * (Common-1.20.1) and this loader's recipe and mineable tag. Every one of the 72 blockstate variants
 * points at an existing model with existing textures, and the data files are in the 1.20.1 formats
 * (plural folders, {@code result.item}, item lists in advancement conditions). The in-world GameTests
 * check the same against the real block.
 */
@DisplayName("Shulker Dock resources")
public class ShulkerDockResourcesTest {
	/** The iron ingot tag of this loader's recipe. */
	private static final String IRON_TAG = "forge:ingots/iron";
	private static final Path RES = Paths.get("..", "Common-1.20.1", "src", "main", "resources");
	private static final Path LOADER_DATA = Paths.get("src", "main", "resources", "data");
	private static final Path ASSETS = RES.resolve("assets").resolve("obscuras_storage");
	private static final Path DATA = RES.resolve("data");
	private static final List<String> BOXES = List.of("none", "shulker", "white", "orange", "magenta", "light_blue",
			"yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
	private static final Map<String, Integer> FACINGS = Map.of("north", 0, "east", 90, "south", 180, "west", 270);

	private static JsonObject parseStrict(Path p) throws IOException {
		assertTrue(Files.isRegularFile(p), "missing " + p);
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			JsonReader jr = new JsonReader(r);
			jr.setLenient(false);
			JsonElement e = new Gson().getAdapter(JsonElement.class).read(jr);
			assertTrue(e.isJsonObject(), p + " is not an object");
			return e.getAsJsonObject();
		}
	}

	private static String model(String box) {
		return "obscuras_storage:block/shulker_dock" + (box.equals("none") ? "" : box.equals("shulker") ? "_box" : "_box_" + box);
	}

	@Test
	@DisplayName("72 blockstate variants: 18 windows x 4 facings, right model and rotation")
	void blockstateVariants() throws IOException {
		JsonObject variants = parseStrict(ASSETS.resolve("blockstates/shulker_dock.json")).getAsJsonObject("variants");
		assertEquals(72, variants.size(), "variants");
		for (String box : BOXES) {
			for (var f : FACINGS.entrySet()) {
				String key = "box=" + box + ",facing=" + f.getKey();
				assertTrue(variants.has(key), "missing variant " + key);
				JsonObject v = variants.getAsJsonObject(key);
				assertEquals(model(box), v.get("model").getAsString(), key);
				assertEquals((int) f.getValue(), v.has("y") ? v.get("y").getAsInt() : 0, key + " rotation");
			}
		}
	}

	@Test
	@DisplayName("Every model resolves: parents, own textures exist, the window shows the vanilla shulker texture")
	void modelsResolve() throws IOException {
		Set<String> models = new HashSet<>();
		for (String box : BOXES)models.add(model(box));
		models.add("obscuras_storage:item/shulker_dock");
		for (String m : models)checkModel(m, new ArrayList<>());
		for (String box : BOXES) {
			if (box.equals("none"))continue;
			JsonObject m = parseStrict(ASSETS.resolve("models/" + model(box).substring("obscuras_storage:".length()) + ".json"));
			String inner = m.getAsJsonObject("textures").get("inner").getAsString();
			assertEquals(box.equals("shulker") ? "minecraft:block/shulker_box" : "minecraft:block/" + box + "_shulker_box", inner, box);
		}
	}

	private static void checkModel(String model, List<String> chain) throws IOException {
		assertFalse(chain.contains(model), "parent loop " + chain);
		chain.add(model);
		if (model.equals("minecraft:block/block"))return;
		assertTrue(model.startsWith("obscuras_storage:"), "unexpected parent " + model);
		JsonObject m = parseStrict(ASSETS.resolve("models/" + model.substring("obscuras_storage:".length()) + ".json"));
		if (m.has("textures")) {
			for (var e : m.getAsJsonObject("textures").entrySet()) {
				String t = e.getValue().getAsString();
				if (t.startsWith("#"))continue;
				if (t.startsWith("minecraft:")) {
					assertTrue(t.matches("minecraft:block/([a-z_]+_)?shulker_box"), "unexpected vanilla texture " + t);
				} else {
					assertTrue(t.startsWith("obscuras_storage:"), "texture namespace " + t);
					Path png = ASSETS.resolve("textures/" + t.substring("obscuras_storage:".length()) + ".png");
					assertTrue(Files.isRegularFile(png), "missing texture " + png);
				}
			}
		}
		if (m.has("parent"))checkModel(m.get("parent").getAsString(), chain);
		else assertTrue(m.has("elements"), model + " has neither parent nor elements");
	}

	@Test
	@DisplayName("Recipe, advancement, loot table and tags in the 1.20.1 formats")
	void dataFiles() throws IOException {
		JsonObject recipe = parseStrict(LOADER_DATA.resolve("obscuras_storage/recipes/shulker_dock.json"));
		assertEquals("minecraft:crafting_shapeless", recipe.get("type").getAsString());
		assertEquals("obscuras_storage:shulker_dock", recipe.getAsJsonObject("result").get("item").getAsString());
		JsonArray ing = recipe.getAsJsonArray("ingredients");
		assertEquals(4, ing.size(), "ingredients");
		assertEquals("[{\"item\":\"minecraft:barrel\"},{\"item\":\"obscuras_storage:inventory_cable\"},{\"tag\":\"" + IRON_TAG + "\"},{\"tag\":\"" + IRON_TAG + "\"}]", ing.toString());

		JsonObject adv = parseStrict(DATA.resolve("obscuras_storage/advancements/recipes/decorations/shulker_dock.json"));
		assertEquals("obscuras_storage:shulker_dock", adv.getAsJsonObject("rewards").getAsJsonArray("recipes").get(0).getAsString());
		assertEquals("obscuras_storage:dockable", adv.getAsJsonObject("criteria").getAsJsonObject("has_shulker_box").getAsJsonObject("conditions")
				.getAsJsonArray("items").get(0).getAsJsonObject().get("tag").getAsString(), "1.20.1 item predicate: tag");
		assertTrue(adv.getAsJsonObject("criteria").getAsJsonObject("has_inventory_cable").getAsJsonObject("conditions")
				.getAsJsonArray("items").get(0).getAsJsonObject().get("items").isJsonArray(), "1.20.1 item predicate: items is a list");

		JsonObject loot = parseStrict(DATA.resolve("obscuras_storage/loot_tables/blocks/shulker_dock.json"));
		assertTrue(loot.toString().contains("\"name\":\"obscuras_storage:shulker_dock\""), "loot drops the dock");

		JsonArray dockable = parseStrict(DATA.resolve("obscuras_storage/tags/items/dockable.json")).getAsJsonArray("values");
		assertEquals(17, dockable.size(), "dockable boxes");
		Set<String> ids = new HashSet<>();
		for (JsonElement e : dockable)ids.add(e.getAsString());
		assertTrue(ids.contains("minecraft:shulker_box"));
		for (String box : BOXES) {
			if (!box.equals("none") && !box.equals("shulker"))assertTrue(ids.contains("minecraft:" + box + "_shulker_box"), box);
		}

		JsonArray axe = parseStrict(LOADER_DATA.resolve("minecraft/tags/blocks/mineable/axe.json")).getAsJsonArray("values");
		assertTrue(axe.toString().contains("\"obscuras_storage:shulker_dock\""), "mineable with an axe");
		// No leftover 1.21 folder names
		assertFalse(Files.exists(DATA.resolve("obscuras_storage/recipe")), "1.21 recipe folder");
		assertFalse(Files.exists(DATA.resolve("obscuras_storage/loot_table")), "1.21 loot table folder");
		assertFalse(Files.exists(DATA.resolve("obscuras_storage/tags/item")), "1.21 item tag folder");
	}
}
