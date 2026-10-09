package com.obscura.storage.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.InventoryCableBlock;
import com.obscura.storage.block.entity.InventoryCableConnectorBlockEntity;
import com.obscura.storage.inventory.BlockFilter;
import com.obscura.storage.migration.LegacyBlockFilters;
import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.platform.FabricDataCompat;
import com.obscura.storage.platform.Platform;

/**
 * A world saved by the mod's Minecraft 1.20.1 builds, upgraded to 1.21.1: every block entity, the saved
 * data and the mod's items keep their contents and settings. See Legacy1201Checks for the data.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class Upgrade1201GameTests {
	private static final String EMPTY_STRUCTURE = GameTestUtil.EMPTY_STRUCTURE;

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void dockBoxLoads(GameTestHelper h) {
		Legacy1201Checks.shulkerDock(h, new BlockPos(2, 2, 2));
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void filingCabinetLoads(GameTestHelper h) {
		Legacy1201Checks.filingCabinet(h, new BlockPos(2, 2, 2));
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void hopperAndEmitterFiltersLoad(GameTestHelper h) {
		Legacy1201Checks.hopper(h, new BlockPos(2, 2, 2));
		Legacy1201Checks.levelEmitter(h, new BlockPos(4, 2, 4));
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void craftingTerminalLoads(GameTestHelper h) {
		Legacy1201Checks.craftingTerminal(h, new BlockPos(2, 2, 2));
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void paintedAndRenamedBlockEntitiesLoad(GameTestHelper h) {
		Legacy1201Checks.paintedAndRenamed(h, new BlockPos(2, 2, 2), new BlockPos(4, 2, 2), new BlockPos(6, 2, 2));
		Legacy1201Checks.filteredConnectorIds(h);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 40)
	public void paintedVariantsLoad(GameTestHelper h) {
		BlockPos cable = Legacy1201Checks.paintedVariants(h, new BlockPos(3, 2, 3));
		h.succeedWhen(() -> {
			var st = h.getBlockState(cable);
			h.assertTrue(st.getValue(InventoryCableBlock.WEST) && st.getValue(InventoryCableBlock.EAST), "connections not worked out: " + st);
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 60)
	public void filteredConnectorDropsItsFilters(GameTestHelper h) {
		BlockPos rel = new BlockPos(3, 2, 3);
		InventoryCableConnectorBlockEntity c = Legacy1201Checks.filteredConnectorLoads(h, rel);
		h.succeedWhen(() -> {
			h.assertTrue(c.getLegacyFilterItems().isEmpty(), "filter items not handed out yet");
			boolean dropped = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(rel)).inflate(2))
					.stream().anyMatch(e -> e.getItem().is(Content.itemFilter.get()));
			h.assertTrue(dropped, "filter item not dropped");
			h.assertFalse(c.saveWithFullMetadata(h.getLevel().registryAccess()).contains("filter"), "dropped items still saved");
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void savedDataLoads(GameTestHelper h) {
		Legacy1201Checks.remoteConnections(h);
		Legacy1201Checks.blockFilterFile(h, new BlockPos(2, 2, 2), FabricDataCompat.LEGACY_TARGET,
				be -> be.hasData(Platform.BLOCK_FILTER) ? be.getData(Platform.BLOCK_FILTER).getFilter() : null);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void itemSettingsLoad(GameTestHelper h) {
		Legacy1201Checks.items(h);
		h.succeed();
	}
	/** The 1.20.1 filters reach the chunk NBT through ServerEvents.onChunkLoad (ChunkDataEvent.Load). */
	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void blockFilterFileThroughChunkEvent(GameTestHelper h) {
		Path dir = null;
		try {
			dir = Files.createTempDirectory("obscura-filters-event");
			BlockPos rel = new BlockPos(2, 2, 2);
			h.setBlock(rel, Blocks.BARREL);
			BlockPos abs = h.absolutePos(rel);
			CompoundTag root = Legacy1201Checks.nbt("{DataVersion:3465,data:{filters:[{dim:\"" + h.getLevel().dimension().location() + "\",x:" + abs.getX()
					+ ",y:" + abs.getY() + ",z:" + abs.getZ() + ",data:{connected:[{x:0,y:0,z:0}],skip:0b,side:\"down\",filter:"
					+ Legacy1201Checks.itemFilter("minecraft:emerald") + ",priority:1,keepLast:1b}}]}}");
			NbtIo.writeCompressed(root, dir.resolve(LegacyBlockFilters.FILE_NAME));
			h.assertValueEqual(LegacyBlockFilters.load(dir), 1, "filters read");
			LevelChunk chunk = h.getLevel().getChunkAt(abs);
			CompoundTag chunkTag = ChunkSerializer.write(h.getLevel(), chunk);
			NeoForge.EVENT_BUS.post(new ChunkDataEvent.Load(chunk, chunkTag, ChunkType.LEVELCHUNK));
			CompoundTag entry = Legacy1201Checks.blockEntityTag(chunkTag, abs);
			h.assertTrue(entry != null && entry.getCompound("neoforge:attachments").contains(LegacyIds.blockFilterKey()), "filter not put into the chunk NBT: " + entry);
			BlockEntity be = BlockEntity.loadStatic(abs, h.getLevel().getBlockState(abs), entry, h.getLevel().registryAccess());
			h.assertTrue(be != null && be.hasData(Platform.BLOCK_FILTER), "barrel loaded without the filter");
			BlockFilter f = be.getData(Platform.BLOCK_FILTER).getFilter();
			h.assertTrue(f.isKeepLast(), "keep last");
			Legacy1201Checks.assertItemFilter(h, f.filter.getItem(0), Items.EMERALD);
			h.assertValueEqual(LegacyBlockFilters.pending(), 0, "filters waiting");
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			LegacyBlockFilters.clear();
			LegacyMigrationChecks.delete(dir);
		}
		h.succeed();
	}
}
