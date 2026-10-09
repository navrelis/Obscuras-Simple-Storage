package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.List;

import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.obscura.storage.StorageMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import com.obscura.storage.Content;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.block.entity.InventoryConnectorBlockEntity;
import com.obscura.storage.block.entity.StorageTerminalBlockEntity;
import com.obscura.storage.inventory.BlockFilter;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.util.ItemLocator;

/** Storage network, terminal, cables, filters and the Item Locator search, in a real world. */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class NetworkGameTests {
	private static final String EMPTY_STRUCTURE = GameTestUtil.EMPTY_STRUCTURE;
	// Connector with a single chest west of it, a double chest south of it and a terminal on top
	private static final BlockPos CONNECTOR = new BlockPos(3, 2, 3);
	private static final BlockPos SINGLE = new BlockPos(2, 2, 3);
	private static final BlockPos DOUBLE_LEFT = new BlockPos(3, 2, 4);
	private static final BlockPos DOUBLE_RIGHT = new BlockPos(4, 2, 4);
	private static final BlockPos TERMINAL = new BlockPos(3, 3, 3);

	/** 10 iron + 5 cobblestone in the single chest, 20 + 30 iron in the two halves of the double chest. */
	private static void buildBasicNetwork(GameTestHelper h) {
		chest(h, SINGLE, new ItemStack(Items.IRON_INGOT, 10), new ItemStack(Items.COBBLESTONE, 5));
		doubleChest(h, DOUBLE_LEFT);
		fill(h, DOUBLE_LEFT, new ItemStack(Items.IRON_INGOT, 20));
		fill(h, DOUBLE_RIGHT, new ItemStack(Items.IRON_INGOT, 30));
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
	}

	/** Ground truth: iron directly in the three chest blocks. */
	private static int ironInChests(GameTestHelper h) {
		return count(h, SINGLE, Items.IRON_INGOT) + count(h, DOUBLE_LEFT, Items.IRON_INGOT) + count(h, DOUBLE_RIGHT, Items.IRON_INGOT);
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void terminalCountsDoubleChestOnce(GameTestHelper h) {
		buildBasicNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.succeedWhen(() -> {
			long iron = terminalCount(te, Items.IRON_INGOT);
			h.assertValueEqual(iron, 60L, "terminal iron (10 single + 50 double chest, double chest counted once)");
			h.assertValueEqual(terminalCount(te, Items.COBBLESTONE), 5L, "terminal cobblestone");
			// 27 + 54 slots: the double chest's 54 slots are counted once, not once per half
			h.assertValueEqual(te.getSlotCount(), 81, "terminal slot count");
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void terminalPullPushConservesItems(GameTestHelper h) {
		buildBasicNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		StoredItemStack[] held = new StoredItemStack[1];
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalCount(te, Items.IRON_INGOT), 60L, "network ready"))
		.thenExecute(() -> {
			held[0] = te.pullStack(new StoredItemStack(new ItemStack(Items.IRON_INGOT)), 25);
			h.assertTrue(held[0] != null, "pull returned nothing");
			h.assertValueEqual(held[0].getQuantity(), 25L, "pulled amount");
			h.assertValueEqual(ironInChests(h) + (int) held[0].getQuantity(), 60, "iron in chests + pulled");
			StoredItemStack rest = te.pushStack(held[0]);
			h.assertTrue(rest == null, "push back left a remainder: " + (rest == null ? 0 : rest.getQuantity()));
			h.assertValueEqual(ironInChests(h), 60, "iron after push back");

			// Asking for more than the network has returns exactly what is there
			held[0] = te.pullStack(new StoredItemStack(new ItemStack(Items.IRON_INGOT)), 1000);
			h.assertTrue(held[0] != null, "second pull returned nothing");
			h.assertValueEqual(held[0].getQuantity(), 60L, "pull of everything");
			h.assertValueEqual(ironInChests(h), 0, "chests after pulling everything");
			rest = te.pushStack(held[0]);
			h.assertTrue(rest == null, "second push back left a remainder");
			h.assertValueEqual(ironInChests(h), 60, "iron after second push back");

			// A new item goes in without loss; 200 gold needs several slots and several chests
			StoredItemStack gold = new StoredItemStack(new ItemStack(Items.GOLD_INGOT), 200);
			rest = te.pushStack(gold);
			h.assertTrue(rest == null, "gold push left a remainder");
			int goldInChests = count(h, SINGLE, Items.GOLD_INGOT) + count(h, DOUBLE_LEFT, Items.GOLD_INGOT) + count(h, DOUBLE_RIGHT, Items.GOLD_INGOT);
			h.assertValueEqual(goldInChests, 200, "gold in chests");
		})
		.thenWaitUntil(() -> {
			h.assertValueEqual(terminalCount(te, Items.GOLD_INGOT), 200L, "terminal gold");
			h.assertValueEqual(terminalCount(te, Items.IRON_INGOT), 60L, "terminal iron");
		})
		.thenSucceed();
	}

	// Connector at (1,2,3) with a terminal on top, cables (2..4,2,3), cable connector (5,2,3) facing a chest at (6,2,3)
	private static final BlockPos C_CONNECTOR = new BlockPos(1, 2, 3);
	private static final BlockPos C_TERMINAL = new BlockPos(1, 3, 3);
	private static final BlockPos C_LOCAL_CHEST = new BlockPos(1, 2, 2);
	private static final BlockPos C_MID_CABLE = new BlockPos(3, 2, 3);
	private static final BlockPos C_CABLE_CONNECTOR = new BlockPos(5, 2, 3);
	private static final BlockPos C_REMOTE_CHEST = new BlockPos(6, 2, 3);

	private static void buildCableNetwork(GameTestHelper h, boolean localChest, boolean remoteChest) {
		if (localChest)chest(h, C_LOCAL_CHEST, new ItemStack(Items.GOLD_INGOT, 7));
		if (remoteChest)chest(h, C_REMOTE_CHEST, new ItemStack(Items.DIAMOND, 11), new ItemStack(Items.EMERALD, 3));
		h.setBlock(C_CONNECTOR, Content.connector.get());
		terminalAbove(h, C_TERMINAL);
		h.setBlock(C_CABLE_CONNECTOR, Content.invCableConnector.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING, Direction.EAST));
		for (int x = 2; x <= 4; x++)place(h, new BlockPos(x, 2, 3), Content.invCable.get());
		refreshShape(h, C_CABLE_CONNECTOR);
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void cableNetworkBreakAndRestore(GameTestHelper h) {
		buildCableNetwork(h, true, true);
		StorageTerminalBlockEntity te = terminal(h, C_TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertValueEqual(terminalCount(te, Items.DIAMOND), 11L, "remote chest visible through cables");
			h.assertValueEqual(terminalCount(te, Items.GOLD_INGOT), 7L, "local chest visible");
		})
		// Break the middle cable (plain block removal, as a piston or explosion would do)
		.thenExecute(() -> h.setBlock(C_MID_CABLE, Blocks.AIR))
		.thenWaitUntil(() -> {
			h.assertValueEqual(terminalCount(te, Items.DIAMOND), 0L, "remote chest must disappear after the cable broke");
			h.assertValueEqual(terminalCount(te, Items.GOLD_INGOT), 7L, "local chest stays");
		})
		.thenExecute(() -> place(h, C_MID_CABLE, Content.invCable.get()))
		.thenWaitUntil(() -> h.assertValueEqual(terminalCount(te, Items.DIAMOND), 11L, "remote chest visible again after replacing the cable"))
		.thenSucceed();
	}

	/** Filter at the cable connector, set up the way the Inventory Configurator does it. */
	private static void filterCableConnector(GameTestHelper h, ItemStack filter) {
		BlockFilter f = BlockFilter.findBlockFilterAt(h.getLevel(), h.absolutePos(C_CABLE_CONNECTOR));
		h.assertTrue(f != null, "no block filter could be created at the cable connector");
		f.filter.setItem(0, filter);
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void filteredCableConnectorOnlyAllowsFilteredItems(GameTestHelper h) {
		// The filtered chest is the only storage on the network
		buildCableNetwork(h, false, true);
		StorageTerminalBlockEntity te = terminal(h, C_TERMINAL);
		filterCableConnector(h, itemFilter(Items.DIAMOND));
		h.startSequence()
		// The filter is set on the tick the connector is placed, so the unfiltered view may show briefly
		.thenWaitUntil(() -> {
			h.assertValueEqual(terminalCount(te, Items.DIAMOND), 11L, "allowed item visible");
			h.assertValueEqual(terminalCount(te, Items.EMERALD), 0L, "filtered-out item must not be visible");
		})
		.thenExecute(() -> {
			StoredItemStack rest = te.pushStack(new StoredItemStack(new ItemStack(Items.EMERALD), 4));
			h.assertTrue(rest != null && rest.getQuantity() == 4, "filtered-out item must not be insertable, remainder " + (rest == null ? 0 : rest.getQuantity()));
			h.assertValueEqual(count(h, C_REMOTE_CHEST, Items.EMERALD), 3, "emeralds in the chest");
			rest = te.pushStack(new StoredItemStack(new ItemStack(Items.DIAMOND), 4));
			h.assertTrue(rest == null, "allowed item must be insertable");
			h.assertValueEqual(count(h, C_REMOTE_CHEST, Items.DIAMOND), 15, "diamonds in the chest");
			StoredItemStack pulled = te.pullStack(new StoredItemStack(new ItemStack(Items.EMERALD)), 64);
			h.assertTrue(pulled == null, "filtered-out item must not be extractable");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void filteredCableConnectorSeesChestPlacedLater(GameTestHelper h) {
		// Regression A4: a filtered cable connector facing air, a chest is placed afterwards
		buildCableNetwork(h, true, false);
		StorageTerminalBlockEntity te = terminal(h, C_TERMINAL);
		filterCableConnector(h, itemFilter(Items.DIAMOND));
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalCount(te, Items.GOLD_INGOT), 7L, "network ready"))
		.thenIdle(20)
		.thenExecute(() -> chest(h, C_REMOTE_CHEST, new ItemStack(Items.DIAMOND, 11), new ItemStack(Items.EMERALD, 3)))
		.thenWaitUntil(() -> h.assertValueEqual(terminalCount(te, Items.DIAMOND), 11L, "chest placed behind the filtered connector must appear"))
		.thenExecute(() -> h.assertValueEqual(terminalCount(te, Items.EMERALD), 0L, "filter still applies"))
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void itemLocatorFindsInventories(GameTestHelper h) {
		buildBasicNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalCount(te, Items.IRON_INGOT), 60L, "network ready"))
		.thenExecute(() -> {
			InventoryConnectorBlockEntity conn = h.getBlockEntity(CONNECTOR);
			Level level = h.getLevel();
			ItemLocator.SearchResult iron = ItemLocator.collect(conn.getMergedHandler(), new StoredItemStack(new ItemStack(Items.IRON_INGOT), 1), level, ItemLocator::locateBlock);
			List<BlockPos> ironPos = iron.located().stream().map(ItemLocator.Location::pos).toList();
			h.assertValueEqual(iron.unknown(), 0, "inventories without a position");
			h.assertValueEqual(ironPos.size(), 2, "iron locations (single chest + double chest once): " + ironPos);
			h.assertTrue(ironPos.contains(h.absolutePos(SINGLE)), "single chest not located: " + ironPos);
			boolean left = ironPos.contains(h.absolutePos(DOUBLE_LEFT)), right = ironPos.contains(h.absolutePos(DOUBLE_RIGHT));
			h.assertTrue(left ^ right, "double chest must be located exactly once: " + ironPos);

			ItemLocator.SearchResult cobble = ItemLocator.collect(conn.getMergedHandler(), new StoredItemStack(new ItemStack(Items.COBBLESTONE), 1), level, ItemLocator::locateBlock);
			h.assertValueEqual(cobble.located().stream().map(ItemLocator.Location::pos).toList(), List.of(h.absolutePos(SINGLE)), "cobblestone locations");

			ItemLocator.SearchResult none = ItemLocator.collect(conn.getMergedHandler(), new StoredItemStack(new ItemStack(Items.DIAMOND), 1), level, ItemLocator::locateBlock);
			h.assertValueEqual(none.located().size() + none.unknown(), 0, "diamond locations");

			ItemLocator.Selection sel = ItemLocator.select(iron.located(), level.dimension(), h.absolutePos(TERMINAL), ItemLocator.MAX_POSITIONS);
			h.assertValueEqual(sel.inDimension(), 2, "selection in dimension");
			h.assertValueEqual(sel.otherDimensions(), 0, "selection other dimensions");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void itemLocatorFollowsCables(GameTestHelper h) {
		buildCableNetwork(h, true, true);
		StorageTerminalBlockEntity te = terminal(h, C_TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalCount(te, Items.DIAMOND), 11L, "network ready"))
		.thenExecute(() -> {
			InventoryConnectorBlockEntity conn = h.getBlockEntity(C_CONNECTOR);
			ItemLocator.SearchResult res = ItemLocator.collect(conn.getMergedHandler(), new StoredItemStack(new ItemStack(Items.DIAMOND), 1), h.getLevel(), ItemLocator::locateBlock);
			h.assertValueEqual(res.located().stream().map(ItemLocator.Location::pos).toList(), List.of(h.absolutePos(C_REMOTE_CHEST)), "diamond locations");
			h.assertValueEqual(res.unknown(), 0, "unknown");
		})
		.thenSucceed();
	}
}
