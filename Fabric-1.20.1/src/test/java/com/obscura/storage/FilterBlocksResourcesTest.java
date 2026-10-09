package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

/**
 * The resource files of the Ejector, Disposal and Exporter, read as plain strict JSON: the shared
 * assets and data (Common-1.20.1) and this loader's mineable tag. Six facing variants per block that
 * resolve to models with existing textures, the GUI sheets and button icons the screens draw, loot
 * tables, recipes and advancements in the 1.20.1 formats, tags and the language keys the code asks for.
 */
@DisplayName("Filter blocks resources (Ejector, Disposal, Exporter)")
public class FilterBlocksResourcesTest {
	private static final Path RES = Paths.get("..", "Common-1.20.1", "src", "main", "resources");
	private static final Path LOADER_DATA = Paths.get("src", "main", "resources", "data");
	private static final Path ASSETS = RES.resolve("assets").resolve("obscuras_storage");
	private static final Path DATA = RES.resolve("data");
	private static final String NS = "obscuras_storage:";
	private static final List<String> IDS = List.of("ejector", "disposal", "exporter");
	private static final List<String> FACINGS = List.of("down", "up", "north", "south", "west", "east");
	/** The 16x16 button icons the three screens draw (textures/gui/sprites/icons). */
	private static final List<String> ICONS = List.of("match_tag_off", "match_tag_on", "ejector_mode_continuous", "ejector_mode_pulse",
			"amount_item", "amount_stack", "disposal_running", "disposal_paused", "counter_reset");

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

	@Test
	@DisplayName("Blockstates: exactly the six facing variants, each its own orientation of an existing model")
	void blockstateVariants() throws IOException {
		for (String id : IDS) {
			JsonObject variants = parseStrict(ASSETS.resolve("blockstates/" + id + ".json")).getAsJsonObject("variants");
			assertNotNull(variants, id + ": no variants");
			assertEquals(6, variants.size(), id + " variants: " + variants.keySet());
			Set<String> looks = new HashSet<>();
			for (String f : FACINGS) {
				String key = "facing=" + f;
				assertTrue(variants.has(key), id + ": missing variant " + key);
				JsonObject v = variants.getAsJsonObject(key);
				String model = v.get("model").getAsString();
				assertTrue(model.startsWith(NS + "block/"), id + " " + key + ": model " + model);
				checkModel(model, new ArrayList<>());
				int x = v.has("x") ? v.get("x").getAsInt() : 0, y = v.has("y") ? v.get("y").getAsInt() : 0;
				assertTrue(x % 90 == 0 && y % 90 == 0, id + " " + key + ": rotation " + x + "/" + y);
				looks.add(model + " x" + x + " y" + y);
			}
			assertEquals(6, looks.size(), id + ": two facings look the same: " + looks);
		}
	}

	@Test
	@DisplayName("Item models resolve")
	void itemModels() throws IOException {
		for (String id : IDS)checkModel(NS + "item/" + id, new ArrayList<>());
	}

	/** Follows the parents up to a vanilla model; every own texture must exist as a file. */
	private static void checkModel(String model, List<String> chain) throws IOException {
		assertFalse(chain.contains(model), "parent loop " + chain);
		chain.add(model);
		if (model.startsWith("minecraft:") || !model.contains(":"))return;
		assertTrue(model.startsWith(NS), "unexpected parent " + model);
		JsonObject m = parseStrict(ASSETS.resolve("models/" + model.substring(NS.length()) + ".json"));
		if (m.has("textures")) {
			for (var e : m.getAsJsonObject("textures").entrySet()) {
				String t = e.getValue().getAsString();
				if (t.startsWith("#") || t.startsWith("minecraft:") || !t.contains(":"))continue;
				assertTrue(t.startsWith(NS), model + ": texture namespace " + t);
				Path png = ASSETS.resolve("textures/" + t.substring(NS.length()) + ".png");
				assertTrue(Files.isRegularFile(png), model + ": missing texture " + png);
			}
		}
		if (m.has("parent"))checkModel(m.get("parent").getAsString(), chain);
		else assertTrue(m.has("elements"), model + " has neither parent nor elements");
	}

	private static BufferedImage image(Path png) throws IOException {
		assertTrue(Files.isRegularFile(png), "missing " + png);
		BufferedImage img = ImageIO.read(png.toFile());
		assertNotNull(img, png + " is not an image");
		return img;
	}

	@Test
	@DisplayName("GUI: one 256x256 sheet per block and the 16x16 button icons, as plain texture files")
	void guiTextures() throws IOException {
		for (String id : IDS) {
			BufferedImage img = image(ASSETS.resolve("textures/gui/" + id + ".png"));
			assertEquals("256x256", img.getWidth() + "x" + img.getHeight(), "gui/" + id + ".png");
		}
		for (String icon : ICONS) {
			BufferedImage img = image(ASSETS.resolve("textures/gui/sprites/icons/" + icon + ".png"));
			assertEquals("16x16", img.getWidth() + "x" + img.getHeight(), "icons/" + icon + ".png");
		}
	}

	@Test
	@DisplayName("Loot tables, recipes and advancements in the 1.20.1 formats")
	void dataFiles() throws IOException {
		for (String id : IDS) {
			JsonObject loot = parseStrict(DATA.resolve("obscuras_storage/loot_tables/blocks/" + id + ".json"));
			assertEquals("minecraft:block", loot.get("type").getAsString(), id + " loot type");
			assertTrue(loot.toString().contains("\"name\":\"" + NS + id + "\""), id + ": the loot table must drop the block");

			JsonObject recipe = parseStrict(DATA.resolve("obscuras_storage/recipes/" + id + ".json"));
			assertTrue(recipe.get("type").getAsString().startsWith("minecraft:crafting_"), id + " recipe type");
			assertEquals(NS + id, recipe.getAsJsonObject("result").get("item").getAsString(), id + " recipe result (1.20.1: result.item)");
			// Vanilla tags and items only, so the same file serves both loaders
			assertFalse(recipe.toString().contains("\"c:") || recipe.toString().contains("\"forge:"), id + ": loader specific tag in the shared recipe");

			Path adv = advancement(id);
			JsonObject a = parseStrict(adv);
			assertEquals(NS + id, a.getAsJsonObject("rewards").getAsJsonArray("recipes").get(0).getAsString(), adv + " reward");
			assertTrue(a.toString().contains("\"recipe\":\"" + NS + id + "\""), adv + ": has_the_recipe criterion");
			JsonObject cable = a.getAsJsonObject("criteria").getAsJsonObject("has_inventory_cable");
			assertEquals("minecraft:inventory_changed", cable.get("trigger").getAsString(), adv + ": unlocked by an item");
			assertTrue(cable.getAsJsonObject("conditions").getAsJsonArray("items").get(0).getAsJsonObject().get("items").isJsonArray(), adv + ": 1.20.1 item predicate, items is a list");
			JsonArray any = a.getAsJsonArray("requirements").get(0).getAsJsonArray();
			assertEquals(2, any.size(), adv + ": either criterion unlocks the recipe");
		}
		// No leftover 1.21 folder names
		assertFalse(Files.exists(DATA.resolve("obscuras_storage/recipe")), "1.21 recipe folder");
		assertFalse(Files.exists(DATA.resolve("obscuras_storage/loot_table")), "1.21 loot table folder");
		assertFalse(Files.exists(DATA.resolve("obscuras_storage/advancement")), "1.21 advancement folder");
		assertFalse(Files.exists(DATA.resolve("obscuras_storage/tags/block")), "1.21 block tag folder");
	}

	private static Path advancement(String id) throws IOException {
		Path dir = DATA.resolve("obscuras_storage/advancements/recipes");
		try (Stream<Path> s = Files.walk(dir)) {
			List<Path> found = s.filter(p -> p.getFileName().toString().equals(id + ".json")).toList();
			assertEquals(1, found.size(), "recipe advancement of " + id + " under " + dir + ": " + found);
			return found.get(0);
		}
	}

	private static List<String> tagValues(Path p) throws IOException {
		JsonObject tag = parseStrict(p);
		assertFalse(tag.get("replace").getAsBoolean(), p + " must not replace other packs' values");
		List<String> out = new ArrayList<>();
		for (JsonElement e : tag.getAsJsonArray("values"))out.add(e.isJsonObject() ? e.getAsJsonObject().get("id").getAsString() : e.getAsString());
		return out;
	}

	@Test
	@DisplayName("Tags: mined with an axe, skipped by the Inventory Configurator, never a trim")
	void tags() throws IOException {
		List<String> axe = tagValues(LOADER_DATA.resolve("minecraft/tags/blocks/mineable/axe.json"));
		List<String> configuratorSkip = tagValues(DATA.resolve("obscuras_storage/tags/blocks/inventory_configurator_skip.json"));
		List<String> trims = tagValues(DATA.resolve("obscuras_storage/tags/blocks/trims.json"));
		List<String> connectorSkip = tagValues(DATA.resolve("obscuras_storage/tags/blocks/inventory_connector_skip.json"));
		for (String id : IDS) {
			assertTrue(axe.contains(NS + id), id + " is not mineable with an axe");
			assertTrue(configuratorSkip.contains(NS + id), id + " must be skipped by the Inventory Configurator (no inventory, no block filter)");
			assertFalse(trims.contains(NS + id), id + " must not be a trim");
			assertFalse(connectorSkip.contains(NS + id), id + " needs no connector skip tag, it exposes no inventory");
		}
		assertEquals(new HashSet<>(axe).size(), axe.size(), "a block is listed twice in the axe tag");
		// Appended after the Shulker Dock, the older entries keep their places
		int dock = axe.indexOf(NS + "shulker_dock");
		assertEquals(List.of(NS + "ejector", NS + "disposal", NS + "exporter"), axe.subList(dock + 1, axe.size()), "the three blocks follow the Shulker Dock at the end of the axe tag");
	}

	private static final Pattern BAD_PLACEHOLDER = Pattern.compile("%(?!(\\d+\\$)?s|%)");

	@Test
	@DisplayName("Language: every key the three blocks, their screens, the config and the Doctor ask for")
	void langKeys() throws IOException {
		JsonObject lang = parseStrict(ASSETS.resolve("lang/en_us.json"));
		List<String> keys = new ArrayList<>();
		for (String id : IDS) {
			keys.add("block.obscuras_storage." + id);
			keys.add("menu.obscuras_storage." + id);
			keys.add("tooltip.obscuras_storage." + id);
		}
		for (String k : List.of("disposal.warning", "disposal.running", "disposal.paused", "disposal.reset", "disposal.destroyed",
				"ejector.mode_continuous", "ejector.mode_pulse", "filter_block.exact_off", "filter_block.exact_on",
				"filter_block.amount_item", "filter_block.amount_stack", "filter_block.status", "filter_block.status.running",
				"filter_block.status.paused", "filter_block.status.stopped_by_redstone", "filter_block.status.needs_redstone",
				"filter_block.status.no_source", "filter_block.status.no_target", "filter_block.status.no_filter")) {
			keys.add("tooltip.obscuras_storage." + k);
		}
		for (String k : List.of("filter_exact", "filter_amount", "ejector_mode", "disposal_pause", "disposal_reset")) {
			keys.add("narrator.obscuras_storage." + k);
		}
		keys.add("config.obscuras_storage.filter_block_cooldown");
		keys.add("text.autoconfig.obscuras_storage.option.filterBlockCooldown");
		keys.add("text.autoconfig.obscuras_storage.option.filterBlockCooldown.@Tooltip");
		keys.add("chat.obscuras_storage.doctor.count.filter_blocks");
		keys.add("chat.obscuras_storage.doctor.count.filter_blocks.one");
		for (String k : keys) {
			assertTrue(lang.has(k), "missing lang key " + k);
			String v = lang.get(k).getAsString();
			assertFalse(v.isBlank(), "empty lang value " + k);
			assertFalse(BAD_PLACEHOLDER.matcher(v).find(), k + ": only %s and %n$s placeholders: " + v);
			assertFalse(v.contains("Tom"), k + ": the old mod name: " + v);
		}
		assertEquals("Ejector", lang.get("block.obscuras_storage.ejector").getAsString());
		assertEquals("Disposal", lang.get("block.obscuras_storage.disposal").getAsString());
		assertEquals("Exporter", lang.get("block.obscuras_storage.exporter").getAsString());
		// The item tooltip of the Disposal warns, and says that the count does not survive breaking the block
		String disposal = lang.get("tooltip.obscuras_storage.disposal").getAsString();
		assertTrue(disposal.toLowerCase().contains("forever"), disposal);
		assertTrue(disposal.contains("count is lost"), disposal);
		assertTrue(lang.get("tooltip.obscuras_storage.disposal.reset").getAsString().contains("Shift"), "the reset tooltip must name Shift");
		assertTrue(lang.get("chat.obscuras_storage.doctor.footnote").getAsString().contains("Ejectors, Disposals and Exporters"), "the Doctor footnote names what it counts");
		// 1.20.1 has no Jade plugin
		assertFalse(lang.has("config.jade.plugin_obscuras_storage.filter_block"), "Jade key without a Jade plugin");
	}
}
