package com.obscura.storage.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.inventory.BlockFilter;
import com.obscura.storage.platform.Platform;

/**
 * NeoForge only: an Inventory Configurator filter saved by the Fabric build (Cardinal Components NBT)
 * is read back, and a NeoForge save carries that NBT too, see FabricDataCompat.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class FabricCompatGameTests {
	private static final String EMPTY_STRUCTURE = GameTestUtil.EMPTY_STRUCTURE;

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 40)
	public void blockFilterSurvivesLoaderSwitch(GameTestHelper h) {
		BlockPos src = new BlockPos(2, 2, 2);
		BlockPos dst = new BlockPos(5, 2, 5);
		h.setBlock(src, Content.connector.get());
		BlockFilter f = BlockFilter.getOrCreateFilterAt(h.getLevel(), h.absolutePos(src));
		h.assertTrue(f != null, "no block filter at the connector");
		f.setKeepLast(true);
		f.setSkip(true);
		f.setSide(Direction.EAST);
		f.filter.setItem(0, GameTestUtil.itemFilter(Items.DIAMOND));

		var reg = h.getLevel().registryAccess();
		BlockEntity srcBe = h.getBlockEntity(src);
		CompoundTag tag = srcBe.saveWithFullMetadata(reg);
		// NeoForge -> Fabric: the save carries the CCA entry the Fabric build reads
		CompoundTag cca = tag.getCompound("cardinal_components").getCompound("obscuras_storage:block_filter");
		h.assertTrue(cca.contains("data", Tag.TAG_COMPOUND), "save lacks cardinal_components/obscuras_storage:block_filter/data: " + tag);

		// Fabric -> NeoForge: a save without NeoForge's attachment, like the Fabric build writes it
		tag.remove("neoforge:attachments");
		h.setBlock(dst, Content.connector.get());
		BlockEntity loaded = BlockEntity.loadStatic(h.absolutePos(dst), h.getBlockState(dst), tag, reg);
		h.assertTrue(loaded != null && loaded.hasData(Platform.BLOCK_FILTER), "the Fabric block filter was not read");
		BlockFilter g = loaded.getData(Platform.BLOCK_FILTER).getFilter();
		h.assertTrue(g.isKeepLast(), "keep last lost");
		h.assertTrue(g.skip(), "skip lost");
		h.assertValueEqual(g.getSide(), Direction.EAST, "side");
		ItemStack fi = g.filter.getItem(0);
		h.assertTrue(fi.is(Content.itemFilter.get()), "filter item lost: " + fi);

		// The block entity's own NeoForge attachment wins over the CCA copy
		CompoundTag both = srcBe.saveWithFullMetadata(reg);
		both.getCompound("cardinal_components").getCompound("obscuras_storage:block_filter").getCompound("data").putBoolean("keepLast", false);
		BlockEntity loaded2 = BlockEntity.loadStatic(h.absolutePos(dst), h.getBlockState(dst), both, reg);
		h.assertTrue(loaded2 != null && loaded2.getData(Platform.BLOCK_FILTER).getFilter().isKeepLast(), "the attachment must win over the CCA copy");
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 40)
	public void vanillaChestBlockFilterSurvivesLoaderSwitch(GameTestHelper h) {
		// The Fabric build registers the filter component on every block entity, so a filter on a vanilla chest
		// is part of a Fabric chunk as cardinal_components, and a NeoForge chunk must carry it too. Vanilla block
		// entities have no hook of their own: it goes through the chunk NBT (ChunkDataEvent, FabricDataCompat).
		BlockPos src = new BlockPos(2, 2, 2);
		h.setBlock(src, Blocks.CHEST);
		BlockPos abs = h.absolutePos(src);
		BlockFilter f = BlockFilter.findBlockFilterAt(h.getLevel(), abs);
		h.assertTrue(f != null, "no block filter at the chest");
		f.setSkip(true);
		f.setKeepLast(true);
		f.filter.setItem(0, GameTestUtil.itemFilter(Items.DIAMOND));

		var reg = h.getLevel().registryAccess();
		LevelChunk chunk = h.getLevel().getChunkAt(abs);
		// NeoForge -> Fabric: the saved chunk carries the CCA entry next to the attachment
		CompoundTag chunkTag = ChunkSerializer.write(h.getLevel(), chunk);
		NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, h.getLevel(), chunkTag));
		CompoundTag entry = blockEntityTag(chunkTag, abs);
		h.assertTrue(entry != null, "chest missing from the chunk NBT: " + chunkTag.getList("block_entities", Tag.TAG_COMPOUND));
		CompoundTag cca = entry.getCompound("cardinal_components").getCompound("obscuras_storage:block_filter");
		h.assertTrue(cca.contains("data", Tag.TAG_COMPOUND), "chunk lacks cardinal_components/obscuras_storage:block_filter/data for the chest: " + entry);

		// Fabric -> NeoForge: a chunk written by the Fabric build has no attachment
		entry.remove("neoforge:attachments");
		NeoForge.EVENT_BUS.post(new ChunkDataEvent.Load(chunk, chunkTag, ChunkType.LEVELCHUNK));
		h.assertTrue(entry.getCompound("neoforge:attachments").contains("obscuras_storage:block_filter", Tag.TAG_COMPOUND), "the Fabric filter was not turned into the attachment: " + entry);
		BlockEntity loaded = BlockEntity.loadStatic(abs, Blocks.CHEST.defaultBlockState(), entry, reg);
		h.assertTrue(loaded != null && loaded.hasData(Platform.BLOCK_FILTER), "the Fabric block filter of the chest was not read");
		BlockFilter g = loaded.getData(Platform.BLOCK_FILTER).getFilter();
		h.assertTrue(g.skip() && g.isKeepLast(), "skip or keep last lost");
		h.assertTrue(g.filter.getItem(0).is(Content.itemFilter.get()), "filter item lost");
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
