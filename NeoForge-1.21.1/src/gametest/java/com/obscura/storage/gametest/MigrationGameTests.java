package com.obscura.storage.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import com.obscura.storage.Content;
import com.obscura.storage.LegacyConfigFiles;
import com.obscura.storage.StorageMod;
import com.obscura.storage.inventory.BlockFilter;
import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.platform.Platform;

/**
 * Worlds, configs and saved data written before v5.0.0 (mod id {@code toms_storage}) load in this
 * version: registry aliases, block entities, item stacks, the block filter attachment (also on vanilla
 * block entities through the chunk NBT), the Inventory Link saved data and the config files.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class MigrationGameTests {
	private static final String EMPTY_STRUCTURE = GameTestUtil.EMPTY_STRUCTURE;

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldRegistryIdsResolve(GameTestHelper h) {
		LegacyMigrationChecks.checkVanillaRegistries(h);
		h.assertValueEqual(LegacyMigrationChecks.checkRegistry(h, NeoForgeRegistries.ATTACHMENT_TYPES), 1, "attachment types");
		h.assertTrue(NeoForgeRegistries.ATTACHMENT_TYPES.get(LegacyIds.old(LegacyIds.BLOCK_FILTER_PATH)) == Platform.BLOCK_FILTER.get(), "old attachment id");
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldBlockStatesLoad(GameTestHelper h) {
		LegacyMigrationChecks.checkBlockStates(h);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldItemStacksLoad(GameTestHelper h) {
		LegacyMigrationChecks.checkItemStacks(h);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldBlockEntitiesLoad(GameTestHelper h) {
		BlockPos cabinet = new BlockPos(2, 2, 2);
		// The cabinet also carries a block filter: its attachment is saved as toms_storage:block_filter
		h.setBlock(cabinet, Content.filingCabinet.get());
		BlockFilter f = BlockFilter.getOrCreateFilterAt(h.getLevel(), h.absolutePos(cabinet));
		h.assertTrue(f != null, "no block filter at the cabinet");
		f.setKeepLast(true);
		f.filter.setItem(0, GameTestUtil.itemFilter(Items.DIAMOND));
		h.assertTrue(h.getBlockEntity(cabinet).saveWithFullMetadata(h.getLevel().registryAccess()).getCompound("neoforge:attachments").contains(LegacyIds.blockFilterKey()), "attachment not saved");
		LegacyMigrationChecks.checkBlockEntities(h, cabinet, new BlockPos(5, 2, 5));
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldBlockFilterOnVanillaChestLoads(GameTestHelper h) {
		BlockPos src = new BlockPos(2, 2, 2);
		h.setBlock(src, Blocks.CHEST);
		BlockPos abs = h.absolutePos(src);
		BlockFilter f = BlockFilter.findBlockFilterAt(h.getLevel(), abs);
		h.assertTrue(f != null, "no block filter at the chest");
		f.setSkip(true);
		f.filter.setItem(0, GameTestUtil.itemFilter(Items.DIAMOND));
		var reg = h.getLevel().registryAccess();
		LevelChunk chunk = h.getLevel().getChunkAt(abs);
		CompoundTag chunkTag = ChunkSerializer.write(h.getLevel(), chunk);
		NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, h.getLevel(), chunkTag));
		CompoundTag entry = blockEntityTag(chunkTag, abs);
		h.assertTrue(entry != null, "chest missing from the chunk NBT");

		// 1. an old NeoForge world: the attachment under the old key
		CompoundTag oldNeo = (CompoundTag) LegacyMigrationChecks.toLegacy(entry);
		oldNeo.remove("cardinal_components");
		LegacyMigrationChecks.assertLegacyOnly(h, oldNeo);
		assertFilterLoads(h, abs, oldNeo, reg);

		// 2. an old Fabric world: only the Cardinal Components entry, under the old key, through the chunk hook
		CompoundTag oldFabric = (CompoundTag) LegacyMigrationChecks.toLegacy(entry);
		oldFabric.remove("neoforge:attachments");
		LegacyMigrationChecks.assertLegacyOnly(h, oldFabric);
		CompoundTag fakeChunk = new CompoundTag();
		ListTag list = new ListTag();
		list.add(oldFabric);
		fakeChunk.put("block_entities", list);
		NeoForge.EVENT_BUS.post(new ChunkDataEvent.Load(chunk, fakeChunk, ChunkType.LEVELCHUNK));
		h.assertTrue(oldFabric.getCompound("neoforge:attachments").contains(LegacyIds.blockFilterKey(), Tag.TAG_COMPOUND), "the old Fabric filter was not turned into the attachment: " + oldFabric);
		assertFilterLoads(h, abs, oldFabric, reg);

		// 3. the same through the block entity hook (the mod's own block entities)
		CompoundTag oldFabricBe = (CompoundTag) LegacyMigrationChecks.toLegacy(entry);
		oldFabricBe.remove("neoforge:attachments");
		oldFabricBe.putString("id", LegacyIds.old("filing_cabinet").toString());
		BlockPos cab = new BlockPos(5, 2, 5);
		h.setBlock(cab, Content.filingCabinet.get());
		assertFilterLoads(h, h.absolutePos(cab), oldFabricBe, reg);
		h.succeed();
	}

	private static void assertFilterLoads(GameTestHelper h, BlockPos abs, CompoundTag tag, net.minecraft.core.HolderLookup.Provider reg) {
		BlockEntity loaded = BlockEntity.loadStatic(abs, h.getLevel().getBlockState(abs), tag, reg);
		h.assertTrue(loaded != null && loaded.hasData(Platform.BLOCK_FILTER), "old block filter not read from " + tag);
		BlockFilter g = loaded.getData(Platform.BLOCK_FILTER).getFilter();
		h.assertTrue(g.filter.getItem(0).is(Content.itemFilter.get()), "filter item lost");
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldSavedDataLoads(GameTestHelper h) {
		LegacyMigrationChecks.checkSavedData(h);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void oldConfigFilesMove(GameTestHelper h) {
		Path dir = null;
		try {
			dir = Files.createTempDirectory("obscura-config-migration");
			String server = "configVersion = 0\nwirelessReach = 16\n";
			String common = "blockedMods = [\"create\"]\n";
			Files.writeString(dir.resolve("toms_storage-server.toml"), server, StandardCharsets.UTF_8);
			Files.writeString(dir.resolve("toms_storage-common.toml"), common, StandardCharsets.UTF_8);
			h.assertValueEqual(LegacyConfigFiles.fileName(ModConfig.Type.SERVER), "obscuras_storage-server.toml", "server file name");
			h.assertTrue(LegacyConfigFiles.migrateDir(dir), "nothing migrated");
			h.assertValueEqual(Files.readString(dir.resolve("obscuras_storage-server.toml")), server, "server config content");
			h.assertValueEqual(Files.readString(dir.resolve("obscuras_storage-common.toml")), common, "common config content");
			h.assertTrue(Files.exists(dir.resolve("toms_storage-server.toml" + LegacyFiles.MIGRATED_SUFFIX)), "old server file not kept");
			h.assertFalse(Files.exists(dir.resolve("toms_storage-server.toml")), "old server file still active");
			// Runs once: an old file that shows up again never replaces the new one
			Files.writeString(dir.resolve("toms_storage-server.toml"), "wirelessReach = 99\n", StandardCharsets.UTF_8);
			h.assertFalse(LegacyConfigFiles.migrateDir(dir), "migrated over an existing file");
			h.assertValueEqual(Files.readString(dir.resolve("obscuras_storage-server.toml")), server, "the new file was overwritten");
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			LegacyMigrationChecks.delete(dir);
		}
		h.succeed();
	}

	private static CompoundTag blockEntityTag(CompoundTag chunkTag, BlockPos pos) {
		ListTag list = chunkTag.getList("block_entities", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag t = list.getCompound(i);
			if (t.getInt("x") == pos.getX() && t.getInt("y") == pos.getY() && t.getInt("z") == pos.getZ())return t;
		}
		return null;
	}
}
