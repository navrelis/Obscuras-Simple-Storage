package com.obscura.storage.gametest;

import java.util.Collection;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.obscura.storage.StorageMod;
import com.obscura.storage.StorageTags;

/**
 * Every shared data file of the mod loaded on NeoForge: each recipe, advancement and loot table JSON
 * in the jar must show up in the server's loaded data (a file that fails to parse is only logged by
 * vanilla), and the block tags must resolve.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class DataLoadGameTests {
	private static final String EMPTY_STRUCTURE = GameTestUtil.EMPTY_STRUCTURE;

	private static int files(MinecraftServer server, String dir) {
		return server.getResourceManager().listResources(dir, rl -> rl.getNamespace().equals(StorageMod.modid) && rl.getPath().endsWith(".json")).size();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void allDataFilesLoad(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();

		int recipeFiles = files(server, "recipe");
		long recipes = server.getRecipeManager().getRecipes().stream().filter(r -> r.id().getNamespace().equals(StorageMod.modid)).count();
		h.assertTrue(recipeFiles > 0, "no recipe files found");
		h.assertValueEqual(recipes, (long) recipeFiles, "loaded recipes vs recipe files");

		int advFiles = files(server, "advancement");
		long adv = server.getAdvancements().getAllAdvancements().stream().filter(a -> a.id().getNamespace().equals(StorageMod.modid)).count();
		h.assertTrue(advFiles > 0, "no advancement files found");
		h.assertValueEqual(adv, (long) advFiles, "loaded advancements vs advancement files");

		int lootFiles = files(server, "loot_table");
		Collection<ResourceLocation> loot = server.reloadableRegistries().getKeys(Registries.LOOT_TABLE);
		long lootOwn = loot.stream().filter(l -> l.getNamespace().equals(StorageMod.modid)).count();
		h.assertTrue(lootFiles > 0, "no loot table files found");
		h.assertValueEqual(lootOwn, (long) lootFiles, "loaded loot tables vs loot table files");

		h.assertTrue(BuiltInRegistries.BLOCK.getTag(StorageTags.TRIMS).map(t -> t.size() > 0).orElse(false), "block tag obscuras_storage:trims is empty");
		h.assertTrue(BuiltInRegistries.BLOCK.getTag(StorageTags.INV_CONNECTOR_SKIP).isPresent(), "block tag obscuras_storage:inventory_connector_skip missing");
		h.succeed();
	}
}
