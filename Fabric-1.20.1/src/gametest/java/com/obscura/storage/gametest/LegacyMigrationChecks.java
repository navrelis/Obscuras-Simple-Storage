package com.obscura.storage.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.DimensionDataStorage;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.IPaintable;
import com.obscura.storage.block.InventoryCableBlock;
import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.tile.FilingCabinetBlockEntity;
import com.obscura.storage.tile.FilteredInventoryCableConnectorBlockEntity;
import com.obscura.storage.tile.PaintedBlockEntity;
import com.obscura.storage.util.BlockFilter;
import com.obscura.storage.util.BlockFilterData;
import com.obscura.storage.util.Priority;
import com.obscura.storage.util.RemoteConnections;

/**
 * Loader independent checks for loading data saved before v5.0.0 (ids in the {@code toms_storage}
 * namespace), used by the loader's MigrationGameTests. Same file in both loaders' gametest sources.
 */
public final class LegacyMigrationChecks {
	private LegacyMigrationChecks() {}

	/** Every string (keys and values) of {@code tag} with {@code obscuras_storage:} replaced by {@code toms_storage:}. */
	public static Tag toLegacy(Tag tag) {
		if (tag instanceof CompoundTag c) {
			CompoundTag out = new CompoundTag();
			for (String k : c.getAllKeys())out.put(legacy(k), toLegacy(c.get(k)));
			return out;
		}
		if (tag instanceof ListTag l) {
			ListTag out = new ListTag();
			for (Tag e : l)out.add(toLegacy(e));
			return out;
		}
		if (tag instanceof StringTag s)return StringTag.valueOf(legacy(s.getAsString()));
		return tag.copy();
	}

	private static String legacy(String s) {
		return s.replace(StorageMod.modid + ":", LegacyIds.OLD_NAMESPACE + ":");
	}

	public static void assertLegacyOnly(GameTestHelper h, Tag tag) {
		String s = tag.toString();
		h.assertFalse(s.contains(StorageMod.modid), "test data still has a new id: " + s);
		h.assertTrue(s.contains(LegacyIds.OLD_NAMESPACE + ":"), "test data has no old id: " + s);
	}

	/** No string of {@code tag} names the old namespace any more. */
	public static void assertCurrentOnly(GameTestHelper h, Tag tag, String what) {
		h.assertFalse(tag.toString().contains(LegacyIds.OLD_NAMESPACE), what + " still has an old id: " + tag);
	}

	/**
	 * Every entry of {@code reg} in the mod's namespace is found under its old id by the by-name lookups
	 * (and the by-name codec), while the old id is not registered itself.
	 *
	 * @param holderLookups also check the holder lookups by key (getHolder, HolderGetter). Fabric resolves
	 *        them (MappedRegistryMixin); Forge's registry aliases only cover the lookups by name.
	 * @return the number of entries checked
	 */
	public static <T> int checkRegistry(GameTestHelper h, Registry<T> reg, boolean holderLookups) {
		int n = 0;
		for (ResourceLocation id : List.copyOf(reg.keySet())) {
			if (!id.getNamespace().equals(StorageMod.modid))continue;
			ResourceLocation old = LegacyIds.old(id.getPath());
			T v = reg.get(id);
			String what = reg.key().location() + " " + old;
			h.assertTrue(v != null, "missing " + id);
			h.assertTrue(reg.get(old) == v, what + ": get(ResourceLocation)");
			h.assertTrue(reg.getOptional(old).orElse(null) == v, what + ": getOptional");
			ResourceKey<T> oldKey = ResourceKey.create(reg.key(), old);
			h.assertTrue(reg.get(oldKey) == v, what + ": get(ResourceKey)");
			h.assertTrue(reg.byNameCodec().parse(NbtOps.INSTANCE, StringTag.valueOf(old.toString())).result().orElse(null) == v, what + ": byNameCodec");
			if (holderLookups) {
				h.assertTrue(reg.getHolder(oldKey).map(Holder::value).orElse(null) == v, what + ": getHolder(ResourceKey)");
				h.assertTrue(reg.asLookup().get(oldKey).map(Holder::value).orElse(null) == v, what + ": lookup");
			}
			h.assertFalse(reg.keySet().contains(old), what + ": the old id must not be registered itself");
			n++;
		}
		h.assertTrue(n > 0, "no entries of the mod in " + reg.key().location());
		// Nothing else changes: unknown old ids stay unknown, other namespaces are untouched
		h.assertTrue(reg.getOptional(LegacyIds.old("no_such_entry_" + n)).isEmpty(), reg.key().location() + ": an unknown old id resolved");
		return n;
	}

	/**
	 * The registries this mod registers into: 23 blocks (with the three {@code ts.*} painted variants
	 * and the Filtered Connector converter), 28 items, 16 block entity types, 12 menus, 1 creative tab.
	 */
	public static void checkVanillaRegistries(GameTestHelper h, boolean holderLookups, boolean creativeTabs) {
		GameTestUtil.eq(checkRegistry(h, BuiltInRegistries.BLOCK, holderLookups), 23, "blocks");
		GameTestUtil.eq(checkRegistry(h, BuiltInRegistries.ITEM, holderLookups), 28, "items");
		GameTestUtil.eq(checkRegistry(h, BuiltInRegistries.BLOCK_ENTITY_TYPE, holderLookups), 16, "block entity types");
		GameTestUtil.eq(checkRegistry(h, BuiltInRegistries.MENU, holderLookups), 12, "menu types");
		if (creativeTabs)GameTestUtil.eq(checkRegistry(h, BuiltInRegistries.CREATIVE_MODE_TAB, holderLookups), 1, "creative tabs");
		// The ts.* painted blocks and the converter keep their paths
		h.assertTrue(BuiltInRegistries.BLOCK.get(LegacyIds.old("ts.inventory_cable_painted")) == StorageMod.invCablePainted.get(), "painted cable");
		h.assertTrue(BuiltInRegistries.BLOCK.get(LegacyIds.old("ts.inventory_cable_connector_painted")) == StorageMod.invCableConnectorPainted.get(), "painted connector");
		h.assertTrue(BuiltInRegistries.BLOCK.get(LegacyIds.old("ts.inventory_proxy_painted")) == StorageMod.invProxyPainted.get(), "painted proxy");
		h.assertTrue(BuiltInRegistries.BLOCK.get(LegacyIds.old("inventory_cable_connector_filtered")) == Content.invCableConnectorFiltered.get(), "filtered connector block");
		h.assertTrue(BuiltInRegistries.ITEM.get(LegacyIds.old("inventory_cable_connector_filtered")) == Content.invCableConnectorFiltered.get().asItem(), "filtered connector item");
		h.assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.get(LegacyIds.old("inventory_cable_connector_filtered")) == Content.invCableConnectorFilteredTile.get(), "filtered connector block entity");
		h.assertTrue(BuiltInRegistries.BLOCK.get(new ResourceLocation("stone")) == Blocks.STONE, "vanilla lookup broken");
		h.assertTrue(BuiltInRegistries.ITEM.getOptional(new ResourceLocation("minecraft", "inventory_cable")).isEmpty(), "another namespace was redirected");
	}

	/**
	 * Block states as a chunk palette (BlockState.CODEC) and painted blocks (IPaintable, NBT) store them.
	 *
	 * @param holderLookups also NbtUtils.readBlockState with the level's holder lookup (structures)
	 */
	public static void checkBlockStates(GameTestHelper h, boolean holderLookups) {
		BlockState st = Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.NORTH, true).setValue(InventoryCableBlock.UP, true);
		CompoundTag old = (CompoundTag) toLegacy(NbtUtils.writeBlockState(st));
		assertLegacyOnly(h, old);
		BlockState viaCodec = BlockState.CODEC.parse(NbtOps.INSTANCE, old).result().orElse(null);
		GameTestUtil.eq(viaCodec, st, "palette entry (BlockState.CODEC)");
		GameTestUtil.eq(IPaintable.readBlockState(h.getLevel(), old), st, "painted state (IPaintable)");
		GameTestUtil.eq(IPaintable.readBlockState(null, old), st, "painted state without a level (IPaintable)");
		if (holderLookups) {
			GameTestUtil.eq(NbtUtils.readBlockState(h.getLevel().holderLookup(Registries.BLOCK), old), st, "NbtUtils.readBlockState");
		}
		BlockState painted = StorageMod.invCablePainted.get().defaultBlockState();
		GameTestUtil.eq(BlockState.CODEC.parse(NbtOps.INSTANCE, toLegacy(NbtUtils.writeBlockState(painted))).result().orElse(null), painted, "ts.* painted block in a palette");
	}

	/** The stacks whose NBT holds ids of this mod. */
	public static List<ItemStack> legacyStacks() {
		ItemStack filter = GameTestUtil.itemFilter(Items.DIAMOND, Items.IRON_INGOT);
		ItemStack filterWithModItem = GameTestUtil.itemFilter(Content.terminal.get().asItem(), Content.paintingKit.get());
		ItemStack terminal = new ItemStack(Content.advWirelessTerminal.get());
		CompoundTag t = terminal.getOrCreateTag();
		t.putInt("BindX", 10);
		t.putInt("BindY", 64);
		t.putInt("BindZ", -3);
		t.putString("BindDim", "minecraft:overworld");
		ItemStack painted = new ItemStack(Content.invCableFramed.get(), 7);
		painted.getOrCreateTagElement("BlockEntityTag").put("block", NbtUtils.writeBlockState(Content.inventoryTrim.get().defaultBlockState()));
		ItemStack kit = new ItemStack(Content.paintingKit.get());
		kit.getOrCreateTag().put("block", NbtUtils.writeBlockState(Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST, true)));
		ItemStack tagFilter = new ItemStack(Content.tagItemFliter.get());
		ListTag tags = new ListTag();
		tags.add(StringTag.valueOf(StorageMod.modid + ":trims"));
		tags.add(StringTag.valueOf("minecraft:logs"));
		tagFilter.getOrCreateTag().put("tags", tags);
		tagFilter.getOrCreateTag().putBoolean("allowlist", true);
		return List.of(filter, filterWithModItem, terminal, painted, kit, tagFilter, GameTestUtil.conditionFilter(), new ItemStack(Content.invCableConnectorFiltered.get(), 2));
	}

	/** Item stacks with the old item id and old ids in their NBT load with all their data, saved again in the current format. */
	public static void checkItemStacks(GameTestHelper h) {
		for (ItemStack s : legacyStacks()) {
			CompoundTag current = s.save(new CompoundTag());
			CompoundTag old = (CompoundTag) toLegacy(current);
			assertLegacyOnly(h, old);
			ItemStack parsed = ItemStack.of(old);
			h.assertTrue(parsed.getItem() == s.getItem() && parsed.getCount() == s.getCount(), "old stack " + old + " loaded as " + parsed);
			GameTestUtil.eq(parsed.save(new CompoundTag()), current, "stack data after loading the old format");
		}
		// The painted item still names its block, the tag filter its tags
		ItemStack painted = ItemStack.of((CompoundTag) toLegacy(legacyStacks().get(3).save(new CompoundTag())));
		h.assertTrue(painted.getHoverName().getString().contains(Content.inventoryTrim.get().getName().getString()), "painted item name: " + painted.getHoverName().getString());
	}

	/**
	 * A Filing Cabinet (items with ids in their NBT inside) and a painted block saved with old ids load as
	 * the new block entities with all their data: saving them again gives exactly the current format.
	 */
	public static void checkBlockEntities(GameTestHelper h, BlockPos cabinetPos, BlockPos paintedPos) {
		h.setBlock(cabinetPos, Content.filingCabinet.get());
		FilingCabinetBlockEntity cab = (FilingCabinetBlockEntity) h.getBlockEntity(cabinetPos);
		List<ItemStack> stacks = legacyStacks();
		for (int i = 0; i < stacks.size(); i++)cab.getInv().setItem(i, stacks.get(i));
		cab.getInv().setItem(stacks.size(), new ItemStack(Items.COBBLESTONE, 12));
		checkBlockEntity(h, cabinetPos);

		h.setBlock(paintedPos, Content.paintedTrim.get());
		PaintedBlockEntity pbe = (PaintedBlockEntity) h.getBlockEntity(paintedPos);
		BlockState paint = Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST, true);
		pbe.setPaintedBlockState(paint);
		PaintedBlockEntity loaded = (PaintedBlockEntity) checkBlockEntity(h, paintedPos);
		GameTestUtil.eq(loaded.getPaintedBlockState(), paint, "painted state");

		// A ts.* painted block (own block id, the painted block entity type)
		BlockPos p2 = paintedPos.above();
		h.setBlock(p2, StorageMod.invCablePainted.get());
		((PaintedBlockEntity) h.getBlockEntity(p2)).setPaintedBlockState(Content.inventoryTrim.get().defaultBlockState());
		checkBlockEntity(h, p2);
	}

	public static BlockEntity checkBlockEntity(GameTestHelper h, BlockPos rel) {
		BlockEntity be = h.getBlockEntity(rel);
		CompoundTag current = be.saveWithFullMetadata();
		CompoundTag old = (CompoundTag) toLegacy(current);
		assertLegacyOnly(h, old);
		BlockEntity loaded = BlockEntity.loadStatic(h.absolutePos(rel), h.getBlockState(rel), old);
		h.assertTrue(loaded != null, "old block entity " + old + " was not loaded");
		h.assertTrue(loaded.getType() == be.getType(), "loaded as " + loaded.getType());
		CompoundTag again = loaded.saveWithFullMetadata();
		GameTestUtil.eq(again, current, "block entity data after loading the old format");
		assertCurrentOnly(h, again, "saved block entity");
		return loaded;
	}

	/** The removed Filtered Connector, saved with its old id, still loads as the converter block entity. */
	public static void checkFilteredConnectorLoads(GameTestHelper h, BlockPos rel) {
		h.setBlock(rel, Content.invCableConnectorFiltered.get());
		CompoundTag old = new CompoundTag();
		old.putString("id", LegacyIds.old("inventory_cable_connector_filtered").toString());
		old.putInt("x", h.absolutePos(rel).getX());
		old.putInt("y", h.absolutePos(rel).getY());
		old.putInt("z", h.absolutePos(rel).getZ());
		BlockEntity loaded = BlockEntity.loadStatic(h.absolutePos(rel), h.getBlockState(rel), old);
		h.assertTrue(loaded instanceof FilteredInventoryCableConnectorBlockEntity, "old filtered connector loaded as " + loaded);
	}

	/** Old saved data file layout: {@code {data: ..., DataVersion: n}}, gzip compressed (as SavedData#save writes it). */
	private static void writeSavedData(Path file, CompoundTag data) throws IOException {
		CompoundTag root = new CompoundTag();
		root.put("data", data);
		NbtUtils.addCurrentDataVersion(root);
		NbtIo.writeCompressed(root, file.toFile());
	}

	/** Inventory Link channels in {@code toms_storage_rc.dat} are moved to the new file and read. */
	public static void checkSavedData(GameTestHelper h) {
		var server = h.getLevel().getServer();
		Path dir = null;
		try {
			dir = Files.createTempDirectory("obscura-rc-migration");
			Player owner = h.makeMockPlayer();
			DimensionDataStorage scratch = new DimensionDataStorage(dir.resolve("scratch").toFile(), server.getFixerUpper());
			RemoteConnections rc = RemoteConnections.load(scratch);
			CompoundTag ch = new CompoundTag();
			ch.putString(RemoteConnections.DISPLAY_NAME, "Old channel");
			ch.putBoolean(RemoteConnections.PUBLIC_TAG, true);
			UUID channel = rc.makeChannel(ch, owner);
			h.assertTrue(channel != null, "channel not created");
			Path oldFile = dir.resolve(RemoteConnections.LEGACY_ID + ".dat");
			Path newFile = dir.resolve(RemoteConnections.ID + ".dat");
			GameTestUtil.eq(RemoteConnections.LEGACY_ID, "toms_storage_rc", "old file name");
			writeSavedData(oldFile, rc.save(new CompoundTag()));

			h.assertTrue(RemoteConnections.migrateLegacyFile(dir), "old file not migrated");
			h.assertTrue(Files.isRegularFile(newFile), "new file missing");
			h.assertFalse(Files.exists(oldFile), "old file still in place");
			h.assertTrue(Files.isRegularFile(dir.resolve(RemoteConnections.LEGACY_ID + ".dat" + LegacyFiles.MIGRATED_SUFFIX)), "old file not kept as .migrated");
			h.assertFalse(RemoteConnections.migrateLegacyFile(dir), "migration ran twice");

			RemoteConnections.Channel c = RemoteConnections.load(new DimensionDataStorage(dir.toFile(), server.getFixerUpper())).getChannel(channel);
			h.assertTrue(c != null, "channel lost");
			GameTestUtil.eq(c.displayName, "Old channel", "channel name");
			h.assertTrue(c.publicChannel, "public flag lost");
			GameTestUtil.eq(c.owner, owner.getUUID(), "owner");
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			delete(dir);
		}
	}

	/**
	 * Inventory Configurator settings (block filters, on a vanilla chest and on a mod block) in
	 * {@code toms_storage_block_filters.dat}, with old item ids in the filter item, are moved to the new
	 * file and read with all their settings.
	 */
	public static void checkBlockFilterData(GameTestHelper h, BlockPos chestRel, BlockPos cabinetRel) {
		var server = h.getLevel().getServer();
		var level = h.getLevel();
		BlockPos chest = h.absolutePos(chestRel);
		BlockPos cabinet = h.absolutePos(cabinetRel);
		Path dir = null;
		try {
			dir = Files.createTempDirectory("obscura-filter-migration");
			BlockFilterData data = BlockFilterData.load(new DimensionDataStorage(dir.resolve("scratch").toFile(), server.getFixerUpper()));
			BlockFilter f = data.getFilterAt(level, chest, true);
			f.setSkip(true);
			f.filter.setItem(0, GameTestUtil.itemFilter(Content.terminal.get().asItem()));
			BlockFilter g = data.getFilterAt(level, cabinet, true);
			g.setKeepLast(true);
			g.setPriority(Priority.HIGH);
			g.filter.setItem(0, GameTestUtil.itemFilter(Items.DIAMOND));
			CompoundTag current = data.save(new CompoundTag());
			Tag old = toLegacy(current);
			assertLegacyOnly(h, old);
			Path oldFile = dir.resolve(BlockFilterData.LEGACY_ID + ".dat");
			GameTestUtil.eq(BlockFilterData.LEGACY_ID, "toms_storage_block_filters", "old file name");
			writeSavedData(oldFile, (CompoundTag) old);

			h.assertTrue(BlockFilterData.migrateLegacyFile(dir), "old file not migrated");
			h.assertTrue(Files.isRegularFile(dir.resolve(BlockFilterData.ID + ".dat")), "new file missing");
			h.assertTrue(Files.isRegularFile(dir.resolve(BlockFilterData.LEGACY_ID + ".dat" + LegacyFiles.MIGRATED_SUFFIX)), "old file not kept as .migrated");
			h.assertFalse(BlockFilterData.migrateLegacyFile(dir), "migration ran twice");

			BlockFilterData loaded = BlockFilterData.load(new DimensionDataStorage(dir.toFile(), server.getFixerUpper()));
			BlockFilter lf = loaded.getFilterAt(level, chest, false);
			h.assertTrue(lf != null && lf.skip(), "chest filter lost");
			h.assertTrue(lf.filter.getItem(0).is(Content.itemFilter.get()), "chest filter item lost");
			BlockFilter lg = loaded.getFilterAt(level, cabinet, false);
			h.assertTrue(lg != null && lg.isKeepLast() && lg.getPriority() == Priority.HIGH, "cabinet filter settings lost");
			CompoundTag again = loaded.save(new CompoundTag());
			GameTestUtil.eq(again, current, "block filter data after loading the old format");
			assertCurrentOnly(h, again, "saved block filters");
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			delete(dir);
		}
	}

	public static void delete(Path dir) {
		if (dir == null)return;
		try (Stream<Path> s = Files.walk(dir)) {
			s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			// temp dir, best effort
		}
	}
}
