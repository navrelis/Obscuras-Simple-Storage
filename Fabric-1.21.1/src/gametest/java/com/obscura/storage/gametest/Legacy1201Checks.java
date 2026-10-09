package com.obscura.storage.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.storage.DimensionDataStorage;

import com.obscura.storage.Content;
import com.obscura.storage.block.InventoryCableBlock;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.block.entity.BasicInventoryHopperBlockEntity;
import com.obscura.storage.block.entity.CraftingTerminalBlockEntity;
import com.obscura.storage.block.entity.FilingCabinetBlockEntity;
import com.obscura.storage.block.entity.InventoryCableConnectorBlockEntity;
import com.obscura.storage.block.entity.LevelEmitterBlockEntity;
import com.obscura.storage.block.entity.PaintedBlockEntity;
import com.obscura.storage.block.entity.ShulkerDockBlockEntity;
import com.obscura.storage.components.ConditionFilterComponent;
import com.obscura.storage.components.ConfiguratorComponent;
import com.obscura.storage.components.SimpleItemFilterComponent;
import com.obscura.storage.components.TagFilterComponent;
import com.obscura.storage.components.WorldPos;
import com.obscura.storage.inventory.BlockFilter;
import com.obscura.storage.inventory.RemoteConnections;
import com.obscura.storage.migration.LegacyBlockFilters;
import com.obscura.storage.migration.LegacyData;
import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.util.HopperMode;
import com.obscura.storage.util.Priority;

/**
 * Data written by the mod's Minecraft 1.20.1 builds, loaded by this build after a world was upgraded
 * from 1.20.1. The NBT below has exactly the shapes the 1.20.1 code writes (its saveAdditional methods,
 * BlockFilterData, RemoteConnections and the item NBT of FilterItem, TagFilter, ConditionFilterState,
 * the terminals, ConfiguratorData and PaintKitItem): item stacks as {@code {id, Count:byte, tag}}.
 * Minecraft's data fixers never touch a mod's block entity NBT, so this is what a 1.21.1 server reads.
 * Same file in both loaders' gametest sources; the loader specific parts come in as arguments.
 */
public final class Legacy1201Checks {
	private Legacy1201Checks() {}

	/** 1.20.1 SNBT, Count as a byte. */
	static CompoundTag nbt(String snbt) {
		try {
			return TagParser.parseTag(snbt);
		} catch (Exception e) {
			throw new IllegalArgumentException(snbt, e);
		}
	}

	static HolderLookup.Provider reg(GameTestHelper h) {
		return h.getLevel().registryAccess();
	}

	/** Loads {@code tag} like a chunk does (BlockEntity.loadStatic) at {@code rel}, where the block was placed. */
	@SuppressWarnings("unchecked")
	static <T extends BlockEntity> T load(GameTestHelper h, BlockPos rel, CompoundTag tag) {
		BlockPos abs = h.absolutePos(rel);
		BlockEntity be = BlockEntity.loadStatic(abs, h.getLevel().getBlockState(abs), tag, reg(h));
		h.assertTrue(be != null, "block entity not loaded from " + tag);
		return (T) be;
	}

	static void assertEq(GameTestHelper h, Object actual, Object expected, String what) {
		h.assertValueEqual(actual, expected, what);
	}

	static String name(ItemStack s) {
		var n = s.get(DataComponents.CUSTOM_NAME);
		return n == null ? null : n.getString();
	}

	static int sharpness(GameTestHelper h, ItemStack s) {
		return EnchantmentHelper.getItemEnchantmentLevel(reg(h).lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), s);
	}

	/** A 1.20.1 Item Filter as an item stack tag: allow list of the given items. */
	static String itemFilter(String... ids) {
		StringBuilder sb = new StringBuilder("{id:\"obscuras_storage:item_filter\",Count:1b,tag:{Filter:[");
		for (int i = 0; i < ids.length; i++) {
			if (i > 0)sb.append(',');
			sb.append("{id:\"").append(ids[i]).append("\",Count:1b}");
		}
		return sb.append("],matchNBT:0b,allowlist:1b}}").toString();
	}

	/** An Item Filter converted from 1.20.1 data: allow list with exactly {@code items}, no custom data left. */
	static void assertItemFilter(GameTestHelper h, ItemStack s, Item... items) {
		h.assertTrue(s.is(Content.itemFilter.get()), "not an item filter: " + s);
		SimpleItemFilterComponent c = s.get(Content.simpleItemFilterComponent.get());
		h.assertTrue(c != null, "item filter without its component: " + s.getComponentsPatch());
		h.assertTrue(c.allowList(), "allow list lost");
		assertEq(h, c.stacks().size(), 9, "item filter slots");
		for (int i = 0; i < items.length; i++)
			h.assertTrue(c.stacks().get(i).is(items[i]), "item filter slot " + i + ": " + c.stacks().get(i));
		h.assertTrue(c.stacks().get(items.length).isEmpty(), "extra item in the filter");
		h.assertFalse(s.has(DataComponents.CUSTOM_DATA), "1.20.1 keys left in custom data: " + s.get(DataComponents.CUSTOM_DATA));
	}

	// ---- block entities ----

	/** Shulker Dock: Box is the 1.20.1 box item with BlockEntityTag.Items (contents with NBT, a nested filter). */
	public static void shulkerDock(GameTestHelper h, BlockPos rel) {
		h.setBlock(rel, Content.shulkerDock.get());
		CompoundTag tag = nbt("{id:\"obscuras_storage:shulker_dock\",Box:{id:\"minecraft:red_shulker_box\",Count:1b,tag:{"
				+ "display:{Name:'{\"text\":\"Ore box\"}'},BlockEntityTag:{id:\"minecraft:shulker_box\",Items:["
				+ "{Slot:0b,id:\"minecraft:diamond\",Count:64b},"
				+ "{Slot:13b,id:\"minecraft:diamond_sword\",Count:1b,tag:{Damage:7,Enchantments:[{id:\"minecraft:sharpness\",lvl:5s}],display:{Name:'{\"text\":\"Blade\"}'}}},"
				+ "{Slot:26b," + itemFilter("minecraft:iron_ingot", "minecraft:gold_ingot").substring(1) + "]}}}}");
		ShulkerDockBlockEntity be = load(h, rel, tag);
		h.assertTrue(be.hasBox(), "box lost");
		ItemStack box = be.peekBox();
		h.assertTrue(box.is(Items.RED_SHULKER_BOX), "box item " + box);
		assertEq(h, name(box), "Ore box", "box name");
		Container inv = be.getInv();
		assertEq(h, inv.getContainerSize(), 27, "dock slots");
		h.assertTrue(inv.getItem(0).is(Items.DIAMOND) && inv.getItem(0).getCount() == 64, "slot 0: " + inv.getItem(0));
		ItemStack sword = inv.getItem(13);
		h.assertTrue(sword.is(Items.DIAMOND_SWORD), "slot 13: " + sword);
		assertEq(h, sword.getDamageValue(), 7, "sword damage");
		assertEq(h, sharpness(h, sword), 5, "sword sharpness");
		assertEq(h, name(sword), "Blade", "sword name");
		assertItemFilter(h, inv.getItem(26), Items.IRON_INGOT, Items.GOLD_INGOT);
		int n = 0;
		for (int i = 0; i < 27; i++)if (!inv.getItem(i).isEmpty())n++;
		assertEq(h, n, 3, "non-empty dock slots");
		// Saved again in the 1.21.1 format and read back: identical
		CompoundTag again = be.saveWithFullMetadata(reg(h));
		h.assertFalse(again.getCompound("Box").contains("Count"), "still saved in the 1.20.1 format: " + again);
		ShulkerDockBlockEntity be2 = load(h, rel, again);
		assertEq(h, be2.peekBox().save(reg(h)), box.save(reg(h)), "box after a 1.21.1 round trip");
	}

	/** Filing Cabinet: inventory is a compound with Items2 (int slots), 300+ stacks, one slot taken twice. */
	public static void filingCabinet(GameTestHelper h, BlockPos rel) {
		h.setBlock(rel, Content.filingCabinet.get());
		StringBuilder sb = new StringBuilder("{id:\"obscuras_storage:filing_cabinet\",CustomName:'{\"text\":\"Swords\"}',inventory:{Items2:[");
		for (int i = 0; i < 320; i++) {
			if (i > 0)sb.append(',');
			sb.append("{id:\"minecraft:diamond_sword\",Count:1b,tag:{Damage:").append(i).append(i == 7 ? ",Enchantments:[{id:\"minecraft:sharpness\",lvl:2s}],display:{Name:'{\"text\":\"Seven\"}'}" : "").append("},Slot:").append(i).append('}');
		}
		// Two stacks for slot 5 (never written by 1.20.1, but its reader allowed it): the second must not be lost
		sb.append(",{id:\"minecraft:diamond_sword\",Count:1b,tag:{Damage:999},Slot:5}");
		sb.append("]}}");
		FilingCabinetBlockEntity be = load(h, rel, nbt(sb.toString()));
		Container inv = be.getInv();
		int n = 0;
		boolean found999 = false;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty())continue;
			n++;
			h.assertTrue(s.is(Items.DIAMOND_SWORD), "slot " + i + ": " + s);
			if (s.getDamageValue() == 999)found999 = true;
			else if (i < 320)assertEq(h, s.getDamageValue(), i, "damage of the sword in slot " + i);
		}
		assertEq(h, n, 321, "swords in the cabinet");
		h.assertTrue(found999, "the second stack for slot 5 was lost");
		assertEq(h, sharpness(h, inv.getItem(7)), 2, "sharpness in slot 7");
		assertEq(h, name(inv.getItem(7)), "Seven", "name in slot 7");
		assertEq(h, be.getCustomName() == null ? null : be.getCustomName().getString(), "Swords", "cabinet name");

		// Older 1.20.1 versions: vanilla Items list with byte slots
		FilingCabinetBlockEntity old = load(h, rel, nbt("{id:\"obscuras_storage:filing_cabinet\",inventory:{Items:["
				+ "{Slot:0b,id:\"minecraft:shield\",Count:1b,tag:{Damage:3}},{Slot:-56b,id:\"minecraft:shield\",Count:1b}]}}"));
		assertEq(h, old.getInv().getItem(0).getDamageValue(), 3, "Items format: slot 0");
		h.assertTrue(old.getInv().getItem(200).is(Items.SHIELD), "Items format: slot 200 (byte -56)");
	}

	/** Inventory Hopper: Filter (a 1.20.1 Item Filter with NBT inside), Mode, Amount; an empty filter is air. */
	public static void hopper(GameTestHelper h, BlockPos rel) {
		h.setBlock(rel, Content.basicInvHopper.get());
		BasicInventoryHopperBlockEntity be = load(h, rel, nbt("{id:\"obscuras_storage:basic_inventory_hopper\",Filter:"
				+ "{id:\"obscuras_storage:item_filter\",Count:1b,tag:{Filter:[{id:\"minecraft:cobblestone\",Count:1b},"
				+ "{id:\"minecraft:iron_pickaxe\",Count:1b,tag:{Damage:4}}],matchNBT:1b,allowlist:1b}},Mode:1b,Amount:16b,Powered:0b}"));
		ItemStack f = be.getFilter();
		h.assertTrue(f.is(Content.itemFilter.get()), "hopper filter " + f);
		SimpleItemFilterComponent c = f.get(Content.simpleItemFilterComponent.get());
		h.assertTrue(c != null && c.matchComp() && c.allowList(), "filter settings lost: " + f.getComponentsPatch());
		h.assertTrue(c.stacks().get(0).is(Items.COBBLESTONE), "filter slot 0");
		h.assertTrue(c.stacks().get(1).is(Items.IRON_PICKAXE) && c.stacks().get(1).getDamageValue() == 4, "filter slot 1 " + c.stacks().get(1));
		assertEq(h, be.getMode(), HopperMode.PULSE, "mode");
		assertEq(h, be.getAmount(), 16, "amount");

		BasicInventoryHopperBlockEntity empty = load(h, rel, nbt("{id:\"obscuras_storage:basic_inventory_hopper\",Filter:{id:\"minecraft:air\",Count:0b},Mode:0b,Amount:1b,Powered:0b}"));
		h.assertTrue(empty.getFilter().isEmpty(), "empty 1.20.1 filter loaded as " + empty.getFilter());
	}

	/** Level Emitter: Filter (1.20.1 stack with NBT); its own Count is an int and stays the threshold. */
	public static void levelEmitter(GameTestHelper h, BlockPos rel) {
		h.setBlock(rel, Content.levelEmitter.get());
		LevelEmitterBlockEntity be = load(h, rel, nbt("{id:\"obscuras_storage:level_emitter\",Filter:{id:\"minecraft:enchanted_book\",Count:1b,"
				+ "tag:{StoredEnchantments:[{id:\"minecraft:sharpness\",lvl:3s}]}},Count:42,lessThan:1b,analog:1b}"));
		h.assertTrue(be.getFilter() != null, "emitter filter lost");
		ItemStack f = be.getFilter().getStack();
		h.assertTrue(f.is(Items.ENCHANTED_BOOK), "emitter filter " + f);
		h.assertTrue(f.has(DataComponents.STORED_ENCHANTMENTS), "stored enchantments lost: " + f.getComponentsPatch());
		assertEq(h, be.getCount(), 42, "threshold");
		h.assertTrue(be.isLessThan() && be.isAnalog(), "less than / analog");
	}

	/**
	 * Crafting Terminal: the grid (CraftingTable, byte slots, 1.20.1 stacks) and the 1.20.1 settings int
	 * {@code sort} with every bit set that 1.20.1 knows (third sort type bit 12, smart search bit 11).
	 */
	public static void craftingTerminal(GameTestHelper h, BlockPos rel) {
		h.setBlock(rel, Content.craftingTerminal.get());
		int sort = 1 | 4 | (1 << 3) | (1 << 12) | (1 << 5) | (1 << 11) | (1 << 9) | (1 << 10) | (1 << 8);
		CraftingTerminalBlockEntity be = load(h, rel, nbt("{id:\"obscuras_storage:crafting_terminal\",sort:" + sort + ",CraftingTable:["
				+ "{Slot:0b,id:\"minecraft:oak_planks\",Count:3b},{Slot:4b,id:\"minecraft:diamond_pickaxe\",Count:1b,tag:{Damage:10}},"
				+ "{Slot:8b,id:\"minecraft:stick\",Count:64b}]}"));
		var grid = be.getCraftingInv();
		h.assertTrue(grid.getItem(0).is(Items.OAK_PLANKS) && grid.getItem(0).getCount() == 3, "grid 0: " + grid.getItem(0));
		h.assertTrue(grid.getItem(4).is(Items.DIAMOND_PICKAXE) && grid.getItem(4).getDamageValue() == 10, "grid 4: " + grid.getItem(4));
		h.assertTrue(grid.getItem(8).is(Items.STICK) && grid.getItem(8).getCount() == 64, "grid 8: " + grid.getItem(8));
		// DURABILITY (5), reversed, ghost items off; control mode 1, tall, pull from inventory; auto focus + smart search off
		assertEq(h, be.getSorting(), 5 | 0x100 | 0x200, "sorting");
		assertEq(h, be.getModes(), 1 | 0x10 | 0x20, "modes");
		assertEq(h, be.getSearchType(), 1 | 8, "search type");
	}

	/**
	 * Painted blocks keep their block state; 1.20.1 states go through the data fixers (grass is short_grass
	 * since 1.20.3). The 1.20.1 block entity ids inventory_cable_connector and inventory_proxy (and the
	 * pre-v5 toms_storage ones) load as this build's inv_cable_connector and inv_proxy, keeping the link
	 * channel and the paint.
	 */
	public static void paintedAndRenamed(GameTestHelper h, BlockPos painted, BlockPos connector, BlockPos proxy) {
		h.setBlock(painted, Content.paintedTrim.get());
		PaintedBlockEntity p = load(h, painted, nbt("{id:\"obscuras_storage:painted\",block:{Name:\"minecraft:grass\"}}"));
		assertEq(h, p.getPaintedBlockState(), Blocks.SHORT_GRASS.defaultBlockState(), "1.20.1 grass paint");
		p = load(h, painted, nbt("{id:\"obscuras_storage:painted\",block:{Name:\"minecraft:oak_stairs\",Properties:{facing:\"east\",half:\"top\",shape:\"straight\",waterlogged:\"false\"}}}"));
		assertEq(h, p.getPaintedBlockState(), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, net.minecraft.world.level.block.state.properties.Half.TOP), "stairs paint");
		CompoundTag again = p.saveWithFullMetadata(reg(h));
		assertEq(h, again.getCompound("block").getInt(LegacyData.DATA_VERSION_KEY), LegacyData.currentDataVersion(), "data version saved with the paint");

		h.setBlock(connector, Content.invCableConnectorFramed.get());
		UUID channel = new UUID(0x1234_5678_9abcL, 0xfedc_ba98_7654L);
		int[] ch = net.minecraft.core.UUIDUtil.uuidToIntArray(channel);
		for (String id : List.of("obscuras_storage:inventory_cable_connector", "toms_storage:inventory_cable_connector")) {
			InventoryCableConnectorBlockEntity c = load(h, connector, nbt("{id:\"" + id + "\",block:{Name:\"minecraft:bricks\"},channel:[I;"
					+ ch[0] + "," + ch[1] + "," + ch[2] + "," + ch[3] + "]}"));
			assertEq(h, c.getType(), Content.cableConnectorBE.get(), id + " type");
			assertEq(h, c.getChannel(), channel, id + " channel");
			assertEq(h, c.getPaintedBlockState(), Blocks.BRICKS.defaultBlockState(), id + " paint");
		}
		h.setBlock(proxy, Content.invProxy.get());
		for (String id : List.of("obscuras_storage:inventory_proxy", "toms_storage:inventory_proxy")) {
			BlockEntity b = load(h, proxy, nbt("{id:\"" + id + "\",block:{Name:\"minecraft:stone\"}}"));
			assertEq(h, b.getType(), Content.invProxyBE.get(), id + " type");
		}
	}

	/**
	 * The blocks 1.20.1 swapped a painted framed block for ({@code ts.inventory_*_painted}) load as the
	 * framed blocks. The painted cable had no connection properties: placed as a framed cable without
	 * connections, its block entity (1.20.1 data, no data version) works them out after loading.
	 *
	 * @return the cable position, for the test to wait for its connections
	 */
	public static BlockPos paintedVariants(GameTestHelper h, BlockPos cableRel) {
		for (String ns : List.of("obscuras_storage", "toms_storage")) {
			BlockState cable = BlockState.CODEC.parse(NbtOps.INSTANCE, nbt("{Name:\"" + ns + ":ts.inventory_cable_painted\"}")).result().orElse(null);
			h.assertTrue(cable != null && cable.is(Content.invCableFramed.get()), ns + " painted cable: " + cable);
			BlockState conn = BlockState.CODEC.parse(NbtOps.INSTANCE, nbt("{Name:\"" + ns + ":ts.inventory_cable_connector_painted\",Properties:{facing:\"down\",north:\"true\",south:\"false\",east:\"false\",west:\"false\",up:\"false\",down:\"false\"}}")).result().orElse(null);
			h.assertTrue(conn != null && conn.is(Content.invCableConnectorFramed.get()) && conn.getValue(InventoryCableConnectorBlock.FACING) == Direction.DOWN
					&& conn.getValue(InventoryCableBlock.NORTH), ns + " painted connector: " + conn);
			BlockState proxy = BlockState.CODEC.parse(NbtOps.INSTANCE, nbt("{Name:\"" + ns + ":ts.inventory_proxy_painted\",Properties:{facing:\"east\"}}")).result().orElse(null);
			h.assertTrue(proxy != null && proxy.is(Content.invProxy.get()) && proxy.getValue(InventoryCableConnectorBlock.FACING) == Direction.EAST, ns + " painted proxy: " + proxy);
		}
		// A painted cable between two cables, as the alias leaves it: framed, no connections
		h.setBlock(cableRel.west(), Content.invCable.get());
		h.setBlock(cableRel.east(), Content.invCable.get());
		h.setBlock(cableRel, Content.invCableFramed.get().defaultBlockState());
		PaintedBlockEntity p = h.getBlockEntity(cableRel);
		p.loadWithComponents(nbt("{id:\"obscuras_storage:painted\",block:{Name:\"minecraft:bricks\"}}"), reg(h));
		p.onLoad();
		assertEq(h, p.getPaintedBlockState(), Blocks.BRICKS.defaultBlockState(), "painted cable paint");
		return cableRel;
	}

	/** The removed Filtered Inventory Cable Connector: block, item and block entity ids resolve to the connector. */
	public static void filteredConnectorIds(GameTestHelper h) {
		for (String ns : List.of("obscuras_storage", "toms_storage")) {
			ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ns, "inventory_cable_connector_filtered");
			h.assertTrue(BuiltInRegistries.BLOCK.get(id) == Content.invCableConnector.get(), id + " block");
			h.assertTrue(BuiltInRegistries.ITEM.get(id) == Content.invCableConnector.get().asItem(), id + " item");
			h.assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id) == Content.cableConnectorBE.get(), id + " block entity");
			// A chunk palette entry of the old block
			BlockState st = BlockState.CODEC.parse(NbtOps.INSTANCE, nbt("{Name:\"" + id + "\",Properties:{facing:\"west\",north:\"true\",south:\"false\",east:\"false\",west:\"false\",up:\"true\",down:\"false\"}}")).result().orElse(null);
			h.assertTrue(st != null && st.is(Content.invCableConnector.get()), id + " palette entry: " + st);
			assertEq(h, st.getValue(InventoryCableConnectorBlock.FACING), Direction.WEST, "facing");
			h.assertTrue(st.getValue(InventoryCableBlock.NORTH) && st.getValue(InventoryCableBlock.UP), "connections");
		}
		// Nothing else is redirected
		h.assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath("obscuras_storage", "inventory_cable")).isEmpty(), "a path that was never renamed resolved");
	}

	/**
	 * A Filtered Inventory Cable Connector that 1.20.1 never converted loads as a normal connector and drops
	 * the filter items it held on its first tick (the other entries were ghost copies).
	 *
	 * @return the connector, for the test to wait for the drop
	 */
	public static InventoryCableConnectorBlockEntity filteredConnectorLoads(GameTestHelper h, BlockPos rel) {
		h.setBlock(rel, Content.invCableConnector.get());
		CompoundTag tag = nbt("{id:\"obscuras_storage:inventory_cable_connector_filtered\",filter:[" + itemFilter("minecraft:gold_ingot")
				+ ",{id:\"minecraft:stone\",Count:1b}]}");
		InventoryCableConnectorBlockEntity loaded = load(h, rel, tag);
		assertEq(h, loaded.getLegacyFilterItems().size(), 1, "filter items to drop");
		assertItemFilter(h, loaded.getLegacyFilterItems().get(0), Items.GOLD_INGOT);
		// Saved before its first tick: the items are kept
		h.assertTrue(loaded.saveWithFullMetadata(reg(h)).contains("filter"), "pending filter items not saved");
		// The same data put into the placed connector: it ticks and drops the filter
		InventoryCableConnectorBlockEntity placed = h.getBlockEntity(rel);
		placed.loadWithComponents(tag, reg(h));
		return placed;
	}

	// ---- saved data ----

	/** Inventory Link channels: the 1.20.1 file has the same content and is read through DataFixTypes.LEVEL. */
	public static void remoteConnections(GameTestHelper h) {
		var server = h.getLevel().getServer();
		Path dir = null;
		try {
			dir = Files.createTempDirectory("obscura-rc-1201");
			UUID id = new UUID(1, 2), owner = new UUID(3, 4);
			int[] a = net.minecraft.core.UUIDUtil.uuidToIntArray(id), o = net.minecraft.core.UUIDUtil.uuidToIntArray(owner);
			CompoundTag root = nbt("{DataVersion:3465,data:{connections:[{id:[I;" + a[0] + "," + a[1] + "," + a[2] + "," + a[3] + "],owner:[I;"
					+ o[0] + "," + o[1] + "," + o[2] + "," + o[3] + "],public:1b,name:\"Base\",owner_name:\"Steve\"}]}}");
			NbtIo.writeCompressed(root, dir.resolve(RemoteConnections.ID + ".dat"));
			RemoteConnections rc = RemoteConnections.load(new DimensionDataStorage(dir.toFile(), server.getFixerUpper(), server.registryAccess()));
			RemoteConnections.Channel c = rc.getChannel(id);
			h.assertTrue(c != null, "1.20.1 channel lost");
			assertEq(h, c.displayName, "Base", "channel name");
			assertEq(h, c.owner, owner, "owner");
			assertEq(h, c.ownerName, "Steve", "owner name");
			h.assertTrue(c.publicChannel, "public flag");
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			LegacyMigrationChecks.delete(dir);
		}
	}

	/**
	 * Inventory Configurator filters of the 1.20.1 saved data file: moved into the chunk NBT of their block
	 * entity on chunk load, read with all settings and the (upgraded) filter item; entries without a block
	 * entity stay in the file; a file without entries is renamed to .migrated.
	 *
	 * @param target   the loader's chunk NBT format of a block filter
	 * @param filterOf the loader's block filter of a loaded block entity (null without one)
	 */
	public static void blockFilterFile(GameTestHelper h, BlockPos chestRel, LegacyBlockFilters.Target target, Function<BlockEntity, BlockFilter> filterOf) {
		Path dir = null;
		try {
			dir = Files.createTempDirectory("obscura-filters-1201");
			h.setBlock(chestRel, Blocks.CHEST);
			BlockPos chest = h.absolutePos(chestRel);
			LevelChunk chunk = h.getLevel().getChunkAt(chest);
			// A position in the same chunk without a block entity
			BlockPos noBe = new BlockPos(chunk.getPos().getMinBlockX(), h.getLevel().getMinBuildHeight() + 1, chunk.getPos().getMinBlockZ());
			if (noBe.equals(chest))noBe = noBe.east();
			String dim = h.getLevel().dimension().location().toString();
			String filterData = "{connected:[{x:0,y:0,z:0},{x:1,y:0,z:0}],skip:1b,side:\"up\",filter:" + itemFilter("minecraft:diamond")
					+ ",priority:" + Priority.HIGHEST.ordinal() + ",keepLast:1b}";
			CompoundTag root = nbt("{DataVersion:3465,data:{filters:["
					+ "{dim:\"" + dim + "\",x:" + chest.getX() + ",y:" + chest.getY() + ",z:" + chest.getZ() + ",data:" + filterData + "},"
					+ "{dim:\"" + dim + "\",x:" + noBe.getX() + ",y:" + noBe.getY() + ",z:" + noBe.getZ() + ",data:" + filterData + "}]}}");
			// Before v5.0.0 the file had the old name; it is read under either
			Path file = dir.resolve(LegacyBlockFilters.LEGACY_FILE_NAME);
			NbtIo.writeCompressed(root, file);

			assertEq(h, LegacyBlockFilters.load(dir), 2, "filters read from the 1.20.1 file");
			CompoundTag chunkTag = ChunkSerializer.write(h.getLevel(), chunk);
			CompoundTag entry = blockEntityTag(chunkTag, chest);
			h.assertTrue(entry != null, "chest missing from the chunk NBT");
			h.assertFalse(target.hasFilter(entry), "the chest already has a filter: " + entry);
			LegacyBlockFilters.inject(h.getLevel().dimension(), chunk.getPos(), chunkTag, target);
			h.assertTrue(target.hasFilter(entry), "filter not put into the chunk NBT: " + entry);
			assertEq(h, LegacyBlockFilters.pending(), 1, "filters still waiting");

			BlockEntity be = BlockEntity.loadStatic(chest, h.getLevel().getBlockState(chest), entry, reg(h));
			BlockFilter f = be == null ? null : filterOf.apply(be);
			h.assertTrue(f != null, "chest loaded without its 1.20.1 filter");
			h.assertTrue(f.skip() && f.isKeepLast(), "skip / keep last");
			assertEq(h, f.getSide(), Direction.UP, "side");
			assertEq(h, f.getPriority(), Priority.HIGHEST, "priority");
			h.assertTrue(f.getConnectedBlocks().contains(chest) && f.getConnectedBlocks().contains(chest.east()), "connected blocks " + f.getConnectedBlocks());
			assertItemFilter(h, f.filter.getItem(0), Items.DIAMOND);

			// Loading the chunk again (another load of the same file after a crash) never applies twice
			LegacyBlockFilters.inject(h.getLevel().dimension(), chunk.getPos(), chunkTag, target);
			assertEq(h, LegacyBlockFilters.pending(), 1, "filters still waiting after a second load");

			LegacyBlockFilters.save();
			h.assertTrue(Files.isRegularFile(file), "file with the remaining filter missing");
			assertEq(h, LegacyBlockFilters.load(dir), 1, "remaining filters after save");
			// The block entity shows up: last filter applied, the file is retired
			CompoundTag fake = new CompoundTag();
			ListTag list = new ListTag();
			CompoundTag barrel = nbt("{id:\"minecraft:barrel\",x:" + noBe.getX() + ",y:" + noBe.getY() + ",z:" + noBe.getZ() + "}");
			list.add(barrel);
			fake.put("block_entities", list);
			LegacyBlockFilters.inject(h.getLevel().dimension(), chunk.getPos(), fake, target);
			h.assertTrue(target.hasFilter(barrel), "second filter not applied");
			assertEq(h, LegacyBlockFilters.pending(), 0, "filters waiting");
			LegacyBlockFilters.save();
			h.assertFalse(Files.exists(file), "1.20.1 file still active");
			h.assertTrue(Files.isRegularFile(dir.resolve(LegacyBlockFilters.LEGACY_FILE_NAME + LegacyFiles.MIGRATED_SUFFIX)), "1.20.1 file not kept as .migrated");
			assertEq(h, LegacyBlockFilters.load(dir), 0, "a retired file was read again");
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			LegacyBlockFilters.clear();
			LegacyMigrationChecks.delete(dir);
		}
	}

	static CompoundTag blockEntityTag(CompoundTag chunkTag, BlockPos pos) {
		ListTag list = chunkTag.getList("block_entities", 10);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag t = list.getCompound(i);
			if (t.getInt("x") == pos.getX() && t.getInt("y") == pos.getY() && t.getInt("z") == pos.getZ())return t;
		}
		return null;
	}

	// ---- items ----

	/**
	 * A mod item as it sits in a 1.20.1 player inventory or chest: Minecraft's data fixers turn it into a
	 * 1.21.1 stack with the mod's keys in minecraft:custom_data, then the vanilla parser reads it.
	 */
	static ItemStack vanillaUpgrade(GameTestHelper h, String snbt) {
		CompoundTag fixed = LegacyData.upgradeItemStack(nbt(snbt));
		h.assertFalse(fixed.contains("Count"), "not upgraded: " + fixed);
		ItemStack s = ItemStack.parse(reg(h), fixed).orElse(ItemStack.EMPTY);
		h.assertFalse(s.isEmpty(), "stack lost: " + fixed);
		return s;
	}

	public static void items(GameTestHelper h) {
		// Item Filter: nested 1.20.1 stacks with NBT, match NBT, allow list, a renamed filter
		ItemStack filter = vanillaUpgrade(h, "{id:\"obscuras_storage:item_filter\",Count:1b,tag:{display:{Name:'{\"text\":\"Ores\"}'},Filter:["
				+ "{id:\"minecraft:diamond_sword\",Count:1b,tag:{Damage:3,display:{Name:'{\"text\":\"X\"}'}}},{id:\"minecraft:dirt\",Count:1b}],matchNBT:1b,allowlist:1b}}");
		SimpleItemFilterComponent c = filter.get(Content.simpleItemFilterComponent.get());
		h.assertTrue(c != null, "item filter not converted: " + filter.getComponentsPatch());
		h.assertTrue(c.matchComp() && c.allowList(), "match NBT / allow list");
		h.assertTrue(c.stacks().get(0).is(Items.DIAMOND_SWORD) && c.stacks().get(0).getDamageValue() == 3 && "X".equals(name(c.stacks().get(0))), "filter slot 0 " + c.stacks().get(0));
		h.assertTrue(c.stacks().get(1).is(Items.DIRT), "filter slot 1");
		assertEq(h, name(filter), "Ores", "filter name");
		h.assertFalse(filter.has(DataComponents.CUSTOM_DATA), "custom data left: " + filter.get(DataComponents.CUSTOM_DATA));

		ItemStack tags = vanillaUpgrade(h, "{id:\"obscuras_storage:tag_item_filter\",Count:1b,tag:{tags:[\"minecraft:logs\",\"toms_storage:trims\"],allowlist:1b}}");
		TagFilterComponent t = tags.get(Content.tagFilterComponent.get());
		h.assertTrue(t != null && t.allowList(), "tag filter " + tags.getComponentsPatch());
		assertEq(h, t.tags(), List.of(TagKey.create(Registries.ITEM, ResourceLocation.withDefaultNamespace("logs")),
				TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("obscuras_storage", "trims"))), "tags");
		h.assertFalse(tags.has(DataComponents.CUSTOM_DATA), "tag filter custom data left");

		ItemStack cond = vanillaUpgrade(h, "{id:\"obscuras_storage:condition_filter\",Count:1b,tag:{Required:5,Excluded:2}}");
		assertEq(h, cond.get(Content.conditionFilterComponent.get()), new ConditionFilterComponent(5, 2), "condition filter");
		h.assertFalse(cond.has(DataComponents.CUSTOM_DATA), "condition filter custom data left");

		ItemStack term = vanillaUpgrade(h, "{id:\"obscuras_storage:wireless_terminal\",Count:1b,tag:{BindX:10,BindY:64,BindZ:-3,BindDim:\"minecraft:the_nether\"}}");
		assertEq(h, term.get(Content.boundPosComponent.get()), new WorldPos(Level.NETHER, new BlockPos(10, 64, -3)), "terminal binding");
		h.assertFalse(term.has(DataComponents.CUSTOM_DATA), "terminal custom data left");
		ItemStack adv = vanillaUpgrade(h, "{id:\"obscuras_storage:adv_wireless_terminal\",Count:1b,tag:{BindX:1,BindY:2,BindZ:3,BindDim:\"minecraft:overworld\",Restock:1b,display:{Name:'{\"text\":\"Remote\"}'}}}");
		assertEq(h, adv.get(Content.boundPosComponent.get()), new WorldPos(Level.OVERWORLD, new BlockPos(1, 2, 3)), "adv terminal binding");
		assertEq(h, adv.get(Content.restockComponent.get()), Boolean.TRUE, "restock");
		assertEq(h, name(adv), "Remote", "adv terminal name");
		h.assertFalse(adv.has(DataComponents.CUSTOM_DATA), "adv terminal custom data left");

		ItemStack conf = vanillaUpgrade(h, "{id:\"obscuras_storage:inventory_configurator\",Count:1b,tag:{ts_configurator:{bound:{X:1,Y:2,Z:3},is_bound:1b,selecting:1b,"
				+ "show_inv_box:0b,mass_select:0b,box_start:{X:0,Y:0,Z:0},selection:[{X:4,Y:5,Z:6},{X:7,Y:8,Z:9}],last_action:100L}}}");
		ConfiguratorComponent cc = conf.get(Content.configuratorComponent.get());
		h.assertTrue(cc != null, "configurator not converted");
		assertEq(h, cc.bound(), new BlockPos(1, 2, 3), "configurator bound");
		h.assertTrue(cc.isBound() && cc.selecting(), "configurator flags");
		assertEq(h, cc.selection(), List.of(new BlockPos(4, 5, 6), new BlockPos(7, 8, 9)), "configurator selection");
		assertEq(h, cc.lastAction(), 100L, "configurator last action");

		ItemStack kit = vanillaUpgrade(h, "{id:\"obscuras_storage:paint_kit\",Count:1b,tag:{Damage:5,block:{Name:\"minecraft:grass\"}}}");
		assertEq(h, kit.get(Content.paintComponent.get()), Blocks.SHORT_GRASS.defaultBlockState(), "paint kit state");
		assertEq(h, kit.getDamageValue(), 5, "paint kit damage");
		h.assertFalse(kit.has(DataComponents.CUSTOM_DATA), "paint kit custom data left");

		// Old namespace, and other mods' custom data is never touched
		ItemStack oldNs = vanillaUpgrade(h, "{id:\"toms_storage:condition_filter\",Count:1b,tag:{Required:1}}");
		h.assertTrue(oldNs.is(Content.conditionFilter.get()), "old id: " + oldNs);
		assertEq(h, oldNs.get(Content.conditionFilterComponent.get()), new ConditionFilterComponent(1, 0), "old id condition filter");
		ItemStack stick = vanillaUpgrade(h, "{id:\"minecraft:stick\",Count:2b,tag:{Filter:[],BindX:1}}");
		h.assertTrue(stick.has(DataComponents.CUSTOM_DATA), "a vanilla item's custom data was changed");

		// A 1.21.1 stack is never touched by LegacyData
		ItemStack modern = new ItemStack(Items.DIAMOND, 5);
		CompoundTag saved = (CompoundTag) modern.save(reg(h));
		h.assertTrue(LegacyData.upgradeItemStack(saved) == saved, "a 1.21.1 stack went through the data fixers");
		h.assertFalse(LegacyData.isLegacyItemStack(saved), "1.21.1 stack seen as 1.20.1");
	}
}
