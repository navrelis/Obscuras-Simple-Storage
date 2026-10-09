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
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.DimensionDataStorage;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.InventoryCableBlock;
import com.obscura.storage.block.entity.FilingCabinetBlockEntity;
import com.obscura.storage.block.entity.PaintedBlockEntity;
import com.obscura.storage.components.WorldPos;
import com.obscura.storage.inventory.RemoteConnections;
import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.migration.LegacyIds;

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

	/**
	 * Every entry of {@code reg} in the mod's namespace is found under its old id by every by-name
	 * lookup (and the by-name codec), while the old id is not registered itself.
	 *
	 * @return the number of entries checked
	 */
	public static <T> int checkRegistry(GameTestHelper h, Registry<T> reg) {
		int n = 0;
		for (ResourceLocation id : List.copyOf(reg.keySet())) {
			if (!id.getNamespace().equals(StorageMod.modid))continue;
			ResourceLocation old = LegacyIds.old(id.getPath());
			T v = reg.get(id);
			String what = reg.key().location() + " " + old;
			h.assertTrue(v != null, "missing " + id);
			h.assertTrue(reg.get(old) == v, what + ": get(ResourceLocation)");
			h.assertTrue(reg.getOptional(old).orElse(null) == v, what + ": getOptional");
			h.assertTrue(reg.getHolder(old).map(Holder::value).orElse(null) == v, what + ": getHolder(ResourceLocation)");
			ResourceKey<T> oldKey = ResourceKey.create(reg.key(), old);
			h.assertTrue(reg.get(oldKey) == v, what + ": get(ResourceKey)");
			h.assertTrue(reg.getHolder(oldKey).map(Holder::value).orElse(null) == v, what + ": getHolder(ResourceKey)");
			h.assertTrue(reg.asLookup().get(oldKey).map(Holder::value).orElse(null) == v, what + ": lookup");
			h.assertTrue(reg.byNameCodec().parse(NbtOps.INSTANCE, StringTag.valueOf(old.toString())).result().orElse(null) == v, what + ": byNameCodec");
			h.assertFalse(reg.containsKey(old), what + ": the old id must not be registered itself");
			n++;
		}
		h.assertTrue(n > 0, "no entries of the mod in " + reg.key().location());
		// Nothing else changes: unknown old ids stay unknown, other namespaces are untouched
		h.assertTrue(reg.getOptional(LegacyIds.old("no_such_entry_" + n)).isEmpty(), reg.key().location() + ": an unknown old id resolved");
		return n;
	}

	public static void checkVanillaRegistries(GameTestHelper h) {
		h.assertValueEqual(checkRegistry(h, BuiltInRegistries.BLOCK), 19, "blocks");
		h.assertValueEqual(checkRegistry(h, BuiltInRegistries.ITEM), 27, "items");
		h.assertValueEqual(checkRegistry(h, BuiltInRegistries.BLOCK_ENTITY_TYPE), 15, "block entity types");
		h.assertValueEqual(checkRegistry(h, BuiltInRegistries.DATA_COMPONENT_TYPE), 7, "data component types");
		h.assertValueEqual(checkRegistry(h, BuiltInRegistries.MENU), 12, "menu types");
		h.assertValueEqual(checkRegistry(h, BuiltInRegistries.CREATIVE_MODE_TAB), 1, "creative tabs");
		h.assertTrue(BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace("stone")) == Blocks.STONE, "vanilla lookup broken");
		h.assertTrue(BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("minecraft", "inventory_cable")).isEmpty(), "another namespace was redirected");
	}

	/** Block states as a chunk palette (BlockState.CODEC) and NbtUtils (structures, painted blocks) store them. */
	public static void checkBlockStates(GameTestHelper h) {
		BlockState st = Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.NORTH, true).setValue(InventoryCableBlock.UP, true);
		Tag old = toLegacy(NbtUtils.writeBlockState(st));
		assertLegacyOnly(h, old);
		BlockState viaCodec = BlockState.CODEC.parse(NbtOps.INSTANCE, old).result().orElse(null);
		h.assertValueEqual(viaCodec, st, "palette entry (BlockState.CODEC)");
		BlockState viaNbt = NbtUtils.readBlockState(h.getLevel().holderLookup(Registries.BLOCK), (CompoundTag) old);
		h.assertValueEqual(viaNbt, st, "NbtUtils.readBlockState");
	}

	/** Item stacks with the old item id and old data component ids keep all their data. */
	public static void checkItemStacks(GameTestHelper h) {
		var reg = h.getLevel().registryAccess();
		ItemStack filter = GameTestUtil.itemFilter(Items.DIAMOND, Items.IRON_INGOT);
		ItemStack terminal = new ItemStack(Content.advWirelessTerminal.get());
		terminal.set(Content.boundPosComponent.get(), new WorldPos(Level.OVERWORLD, new BlockPos(10, 64, -3)));
		terminal.set(Content.restockComponent.get(), true);
		ItemStack painted = new ItemStack(Content.invCableFramed.get(), 7);
		painted.set(Content.paintComponent.get(), Content.inventoryTrim.get().defaultBlockState());
		for (ItemStack s : List.of(filter, terminal, painted, GameTestUtil.conditionFilter())) {
			Tag current = s.save(reg);
			Tag old = toLegacy(current);
			assertLegacyOnly(h, old);
			ItemStack parsed = ItemStack.parse(reg, old).orElse(ItemStack.EMPTY);
			// Compared through the saved form: some components hold lists of stacks (no equals)
			h.assertTrue(parsed.getItem() == s.getItem() && parsed.getCount() == s.getCount(), "old stack " + old + " loaded as " + parsed);
			h.assertValueEqual(parsed.save(reg), current, "stack data after loading the old format");
		}
	}

	/**
	 * A Filing Cabinet (items with components inside) and a painted block saved with old ids load as the
	 * new block entities with all their data: saving them again gives exactly the current format.
	 */
	public static void checkBlockEntities(GameTestHelper h, BlockPos cabinetPos, BlockPos paintedPos) {
		var reg = h.getLevel().registryAccess();
		h.setBlock(cabinetPos, Content.filingCabinet.get());
		FilingCabinetBlockEntity cab = h.getBlockEntity(cabinetPos);
		cab.getInv().setItem(0, GameTestUtil.itemFilter(Items.DIAMOND));
		cab.getInv().setItem(1, new ItemStack(Content.wirelessTerminal.get()));
		cab.getInv().setItem(2, new ItemStack(Items.COBBLESTONE, 12));
		checkBlockEntity(h, cabinetPos, reg);

		h.setBlock(paintedPos, Content.paintedTrim.get());
		PaintedBlockEntity pbe = h.getBlockEntity(paintedPos);
		pbe.setPaintedBlockState(Content.invCable.get().defaultBlockState().setValue(InventoryCableBlock.EAST, true));
		checkBlockEntity(h, paintedPos, reg);
	}

	public static BlockEntity checkBlockEntity(GameTestHelper h, BlockPos rel, net.minecraft.core.HolderLookup.Provider reg) {
		BlockEntity be = h.getBlockEntity(rel);
		CompoundTag current = be.saveWithFullMetadata(reg);
		CompoundTag old = (CompoundTag) toLegacy(current);
		assertLegacyOnly(h, old);
		BlockEntity loaded = BlockEntity.loadStatic(h.absolutePos(rel), h.getBlockState(rel), old, reg);
		h.assertTrue(loaded != null, "old block entity " + old + " was not loaded");
		h.assertTrue(loaded.getType() == be.getType(), "loaded as " + loaded.getType());
		CompoundTag again = loaded.saveWithFullMetadata(reg);
		h.assertValueEqual(again, current, "block entity data after loading the old format");
		return loaded;
	}

	/** Inventory Link channels in {@code toms_storage_rc.dat} are moved to the new file and read. */
	public static void checkSavedData(GameTestHelper h) {
		var server = h.getLevel().getServer();
		Path dir = null;
		try {
			dir = Files.createTempDirectory("obscura-rc-migration");
			Player owner = h.makeMockPlayer(GameType.SURVIVAL);
			DimensionDataStorage scratch = new DimensionDataStorage(dir.resolve("scratch").toFile(), server.getFixerUpper(), server.registryAccess());
			RemoteConnections rc = RemoteConnections.load(scratch);
			UUID channel = rc.makeChannel("Old channel", true, owner);
			h.assertTrue(channel != null, "channel not created");
			// The file as an older version left it (same layout SavedData writes)
			CompoundTag root = new CompoundTag();
			root.put("data", rc.save(new CompoundTag(), server.registryAccess()));
			NbtUtils.addCurrentDataVersion(root);
			Path oldFile = dir.resolve(RemoteConnections.LEGACY_ID + ".dat");
			Path newFile = dir.resolve(RemoteConnections.ID + ".dat");
			NbtIo.writeCompressed(root, oldFile);

			h.assertTrue(RemoteConnections.migrateLegacyFile(dir), "old file not migrated");
			h.assertTrue(Files.isRegularFile(newFile), "new file missing");
			h.assertFalse(Files.exists(oldFile), "old file still in place");
			h.assertTrue(Files.isRegularFile(dir.resolve(RemoteConnections.LEGACY_ID + ".dat" + LegacyFiles.MIGRATED_SUFFIX)), "old file not kept as .migrated");
			h.assertFalse(RemoteConnections.migrateLegacyFile(dir), "migration ran twice");

			RemoteConnections.Channel ch = RemoteConnections.load(new DimensionDataStorage(dir.toFile(), server.getFixerUpper(), server.registryAccess())).getChannel(channel);
			h.assertTrue(ch != null, "channel lost");
			h.assertValueEqual(ch.displayName, "Old channel", "channel name");
			h.assertTrue(ch.publicChannel, "public flag lost");
			h.assertValueEqual(ch.owner, owner.getUUID(), "owner");
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
