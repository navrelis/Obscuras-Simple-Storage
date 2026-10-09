package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import com.obscura.storage.Content;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.gui.StorageTerminalMenu;
import com.obscura.storage.tile.FilteredInventoryCableConnectorBlockEntity;
import com.obscura.storage.tile.InventoryCableConnectorBlockEntity;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.BlockFilter;
import com.obscura.storage.util.ItemLocator;
import com.obscura.storage.util.Priority;
import com.obscura.storage.util.StoredItemStack;

/** Storage network, terminal, cables, filters and the Item Locator search, in a real world. */
public class NetworkGameTests implements FabricGameTest {
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
			eq(terminalCount(te, Items.IRON_INGOT), 60L, "terminal iron (10 single + 50 double chest, double chest counted once)");
			eq(terminalCount(te, Items.COBBLESTONE), 5L, "terminal cobblestone");
			// 27 + 54 slots: the double chest's 54 slots are counted once, not once per half
			eq(te.getSlotCount(), 81, "terminal slot count");
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void terminalPullPushConservesItems(GameTestHelper h) {
		buildBasicNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		StoredItemStack[] held = new StoredItemStack[1];
		h.startSequence()
		.thenWaitUntil(() -> eq(terminalCount(te, Items.IRON_INGOT), 60L, "network ready"))
		.thenExecute(() -> {
			held[0] = te.pullStack(new StoredItemStack(new ItemStack(Items.IRON_INGOT)), 25);
			h.assertTrue(held[0] != null, "pull returned nothing");
			eq(held[0].getQuantity(), 25L, "pulled amount");
			eq(ironInChests(h) + (int) held[0].getQuantity(), 60, "iron in chests + pulled");
			StoredItemStack rest = te.pushStack(held[0]);
			h.assertTrue(rest == null, "push back left a remainder: " + (rest == null ? 0 : rest.getQuantity()));
			eq(ironInChests(h), 60, "iron after push back");

			// Asking for more than the network has returns exactly what is there
			held[0] = te.pullStack(new StoredItemStack(new ItemStack(Items.IRON_INGOT)), 1000);
			h.assertTrue(held[0] != null, "second pull returned nothing");
			eq(held[0].getQuantity(), 60L, "pull of everything");
			eq(ironInChests(h), 0, "chests after pulling everything");
			rest = te.pushStack(held[0]);
			h.assertTrue(rest == null, "second push back left a remainder");
			eq(ironInChests(h), 60, "iron after second push back");

			// A new item goes in without loss; 200 gold needs several slots and several chests
			StoredItemStack gold = new StoredItemStack(new ItemStack(Items.GOLD_INGOT), 200);
			rest = te.pushStack(gold);
			h.assertTrue(rest == null, "gold push left a remainder");
			int goldInChests = count(h, SINGLE, Items.GOLD_INGOT) + count(h, DOUBLE_LEFT, Items.GOLD_INGOT) + count(h, DOUBLE_RIGHT, Items.GOLD_INGOT);
			eq(goldInChests, 200, "gold in chests");
		})
		.thenWaitUntil(() -> {
			eq(terminalCount(te, Items.GOLD_INGOT), 200L, "terminal gold");
			eq(terminalCount(te, Items.IRON_INGOT), 60L, "terminal iron");
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
			eq(terminalCount(te, Items.DIAMOND), 11L, "remote chest visible through cables");
			eq(terminalCount(te, Items.GOLD_INGOT), 7L, "local chest visible");
		})
		// Break the middle cable (plain block removal, as a piston or explosion would do)
		.thenExecute(() -> h.setBlock(C_MID_CABLE, Blocks.AIR))
		.thenWaitUntil(() -> {
			eq(terminalCount(te, Items.DIAMOND), 0L, "remote chest must disappear after the cable broke");
			eq(terminalCount(te, Items.GOLD_INGOT), 7L, "local chest stays");
		})
		.thenExecute(() -> place(h, C_MID_CABLE, Content.invCable.get()))
		.thenWaitUntil(() -> eq(terminalCount(te, Items.DIAMOND), 11L, "remote chest visible again after replacing the cable"))
		.thenSucceed();
	}

	/** Filter on a block, set up the way the Inventory Configurator does it (right click creates the block filter). */
	private static void blockFilter(GameTestHelper h, BlockPos rel, ItemStack filter) {
		BlockFilter f = BlockFilter.findBlockFilterAt(h.getLevel(), h.absolutePos(rel));
		h.assertTrue(f != null, "no block filter could be created at " + rel);
		f.filter.setItem(0, filter);
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void blockFilterOnConnectedChest(GameTestHelper h) {
		// Configurator filter on a chest right next to the connector (the connector scan path)
		chest(h, SINGLE, new ItemStack(Items.DIAMOND, 11), new ItemStack(Items.EMERALD, 3));
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		blockFilter(h, SINGLE, itemFilter(Items.DIAMOND));
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(terminalCount(te, Items.DIAMOND), 11L, "allowed item visible");
			eq(terminalCount(te, Items.EMERALD), 0L, "filtered-out item must not be visible");
		})
		.thenExecute(() -> {
			StoredItemStack rest = te.pushStack(new StoredItemStack(new ItemStack(Items.EMERALD), 4));
			h.assertTrue(rest != null && rest.getQuantity() == 4, "filtered-out item must not be insertable, remainder " + (rest == null ? 0 : rest.getQuantity()));
			eq(count(h, SINGLE, Items.EMERALD), 3, "emeralds in the chest");
			rest = te.pushStack(new StoredItemStack(new ItemStack(Items.DIAMOND), 4));
			h.assertTrue(rest == null, "allowed item must be insertable");
			eq(count(h, SINGLE, Items.DIAMOND), 15, "diamonds in the chest");
			h.assertTrue(te.pullStack(new StoredItemStack(new ItemStack(Items.EMERALD)), 64) == null, "filtered-out item must not be extractable");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void filteredCableConnectorOnlyAllowsFilteredItems(GameTestHelper h) {
		// The filtered chest is the only storage on the network. The Configurator replaced the Filtered
		// Inventory Cable Connector: a block filter on a cable connector filters what it connects.
		buildCableNetwork(h, false, true);
		StorageTerminalBlockEntity te = terminal(h, C_TERMINAL);
		blockFilter(h, C_CABLE_CONNECTOR, itemFilter(Items.DIAMOND));
		h.startSequence()
		// The filter is set on the tick the connector is placed, so the unfiltered view may show briefly
		.thenWaitUntil(() -> {
			eq(terminalCount(te, Items.DIAMOND), 11L, "allowed item visible");
			eq(terminalCount(te, Items.EMERALD), 0L, "filtered-out item must not be visible");
		})
		.thenExecute(() -> {
			StoredItemStack rest = te.pushStack(new StoredItemStack(new ItemStack(Items.EMERALD), 4));
			h.assertTrue(rest != null && rest.getQuantity() == 4, "filtered-out item must not be insertable, remainder " + (rest == null ? 0 : rest.getQuantity()));
			eq(count(h, C_REMOTE_CHEST, Items.EMERALD), 3, "emeralds in the chest");
			rest = te.pushStack(new StoredItemStack(new ItemStack(Items.DIAMOND), 4));
			h.assertTrue(rest == null, "allowed item must be insertable");
			eq(count(h, C_REMOTE_CHEST, Items.DIAMOND), 15, "diamonds in the chest");
			h.assertTrue(te.pullStack(new StoredItemStack(new ItemStack(Items.EMERALD)), 64) == null, "filtered-out item must not be extractable");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void filteredCableConnectorSeesChestPlacedLater(GameTestHelper h) {
		// Regression A4: a filtered cable connector facing air, a chest is placed afterwards
		buildCableNetwork(h, true, false);
		StorageTerminalBlockEntity te = terminal(h, C_TERMINAL);
		blockFilter(h, C_CABLE_CONNECTOR, itemFilter(Items.DIAMOND));
		h.startSequence()
		.thenWaitUntil(() -> eq(terminalCount(te, Items.GOLD_INGOT), 7L, "network ready"))
		.thenIdle(20)
		.thenExecute(() -> chest(h, C_REMOTE_CHEST, new ItemStack(Items.DIAMOND, 11), new ItemStack(Items.EMERALD, 3)))
		.thenWaitUntil(() -> eq(terminalCount(te, Items.DIAMOND), 11L, "chest placed behind the filtered connector must appear"))
		.thenIdle(10)
		.thenExecute(() -> eq(terminalCount(te, Items.EMERALD), 0L, "filter still applies"))
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void blockFilterOnChestBehindCableConnector(GameTestHelper h) {
		// The filter is on the remote chest itself, the cable connector has none
		buildCableNetwork(h, true, true);
		StorageTerminalBlockEntity te = terminal(h, C_TERMINAL);
		blockFilter(h, C_REMOTE_CHEST, itemFilter(Items.EMERALD));
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(terminalCount(te, Items.EMERALD), 3L, "allowed item visible");
			eq(terminalCount(te, Items.DIAMOND), 0L, "filtered-out item must not be visible");
			eq(terminalCount(te, Items.GOLD_INGOT), 7L, "unfiltered local chest visible");
		})
		.thenExecute(() -> {
			h.assertTrue(te.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)), 64) == null, "filtered-out item must not be extractable");
			eq(count(h, C_REMOTE_CHEST, Items.DIAMOND), 11, "diamonds in the remote chest");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void cableConnectorFilterPriorityAndSkip(GameTestHelper h) {
		buildCableNetwork(h, true, true);
		StorageTerminalBlockEntity te = terminal(h, C_TERMINAL);
		// A filter without a filter item allows everything: only its priority and skip settings matter here
		BlockFilter f = BlockFilter.findBlockFilterAt(h.getLevel(), h.absolutePos(C_CABLE_CONNECTOR));
		h.assertTrue(f != null, "no block filter at the cable connector");
		f.setPriority(Priority.HIGHEST);
		h.startSequence()
		.thenWaitUntil(() -> eq(terminalCount(te, Items.DIAMOND), 11L, "network ready"))
		.thenIdle(10)
		.thenExecute(() -> {
			h.assertTrue(te.pushStack(new StoredItemStack(new ItemStack(Items.COBBLESTONE), 10)) == null, "push cobblestone");
			eq(count(h, C_REMOTE_CHEST, Items.COBBLESTONE), 10, "highest priority: new items go to the chest behind the cable connector");
			eq(count(h, C_LOCAL_CHEST, Items.COBBLESTONE), 0, "cobblestone in the local chest");
			f.setPriority(Priority.LOWEST);
		})
		.thenIdle(30)
		.thenExecute(() -> {
			h.assertTrue(te.pushStack(new StoredItemStack(new ItemStack(Items.STONE), 10)) == null, "push stone");
			eq(count(h, C_LOCAL_CHEST, Items.STONE), 10, "lowest priority: new items go to the local chest");
			eq(count(h, C_REMOTE_CHEST, Items.STONE), 0, "stone in the remote chest");
			f.setSkip(true);
		})
		.thenWaitUntil(() -> {
			eq(terminalCount(te, Items.DIAMOND), 0L, "skip: the chest behind the cable connector is left out");
			eq(terminalCount(te, Items.GOLD_INGOT), 7L, "local chest stays");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300, batch = "entities_legacy_converter")
	public void legacyFilteredConnectorConverts(GameTestHelper h) {
		// A Filtered Inventory Cable Connector from an old world, holding an Item Filter
		buildCableNetwork(h, true, true);
		BlockState legacy = Content.invCableConnectorFiltered.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING, Direction.EAST);
		place(h, C_CABLE_CONNECTOR, legacy);
		BlockEntity old = h.getBlockEntity(C_CABLE_CONNECTOR);
		h.assertTrue(old instanceof FilteredInventoryCableConnectorBlockEntity, "legacy block entity placed, got " + old);
		CompoundTag tag = old.saveWithoutMetadata();
		SimpleContainer inv = new SimpleContainer(9);
		inv.setItem(0, itemFilter(Items.DIAMOND));
		tag.put("filter", inv.createTag());
		old.load(tag);
		StorageTerminalBlockEntity te = terminal(h, C_TERMINAL);
		// The dropped filter is thrown with a random velocity and can fly several blocks while the network settles,
		// so it is looked up once, on the tick of the conversion (it is then still at the connector), and followed
		// as that very entity afterwards instead of being searched again in a fixed box.
		ItemEntity[] dropped = new ItemEntity[1];
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(h.getBlockState(C_CABLE_CONNECTOR).is(Content.invCableConnector.get()), "converted to a normal cable connector");
			h.assertTrue(h.getBlockEntity(C_CABLE_CONNECTOR) instanceof InventoryCableConnectorBlockEntity, "block entity converted");
			eq(h.getBlockState(C_CABLE_CONNECTOR).getValue(InventoryCableConnectorBlock.FACING), Direction.EAST, "facing kept");
			List<ItemEntity> near = filterItems(h, new AABB(h.absolutePos(C_CABLE_CONNECTOR)).inflate(2));
			eq(near.size(), 1, "the stored filter item is dropped once, at the connector");
			dropped[0] = near.get(0);
		})
		.thenWaitUntil(() -> {
			ItemEntity e = dropped[0];
			h.assertTrue(e.isAlive(), "the dropped filter item was lost: " + e);
			eq(e.getItem().getCount(), 1, "dropped filter stack size");
			// Nothing else may hold the filter: no second drop anywhere around the test, none inserted into the network chests
			eq(filterItems(h, new AABB(h.absolutePos(BlockPos.ZERO), h.absolutePos(new BlockPos(8, 8, 8))).inflate(6)).size(), 1, "filter items around the test");
			eq(count(h, C_LOCAL_CHEST, Content.itemFilter.get()), 0, "filters in the local chest");
			eq(count(h, C_REMOTE_CHEST, Content.itemFilter.get()), 0, "filters in the remote chest");
			eq(terminalCount(te, Items.DIAMOND), 11L, "the converted connector connects the remote chest");
			eq(terminalCount(te, Items.EMERALD), 3L, "the converted connector has no filter");
		})
		.thenExecute(() -> h.killAllEntitiesOfClass(ItemEntity.class))
		.thenSucceed();
	}

	/** Item Filter item entities within {@code box}. */
	private static List<ItemEntity> filterItems(GameTestHelper h, AABB box) {
		return h.getLevel().getEntitiesOfClass(ItemEntity.class, box, e -> e.getItem().is(Content.itemFilter.get()));
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void shiftPullNeverFillsOffhand(GameTestHelper h) {
		buildBasicNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(terminalCount(te, Items.IRON_INGOT), 60L, "network ready");
			h.assertTrue(te.getItemHandler() != null, "terminal has no storage yet");
		})
		.thenExecute(() -> {
			FakePlayer p = FakePlayer.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "offhand-test"));
			Inventory inv = p.getInventory();
			inv.clearContent();
			for (int i = 0; i < 36; i++)inv.setItem(i, new ItemStack(Items.DIRT, 64));
			StorageTerminalMenu menu = new StorageTerminalMenu(0, inv, te);
			StoredItemStack iron = new StoredItemStack(new ItemStack(Items.IRON_INGOT), 60);

			// Hotbar and main inventory full: nothing may land in the empty offhand, the items stay in storage
			menu.onInteract(iron, StorageTerminalMenu.SlotAction.SHIFT_PULL, false);
			h.assertTrue(p.getOffhandItem().isEmpty(), "shift-pull put " + p.getOffhandItem() + " into the offhand");
			eq(ironInChests(h), 60, "iron back in storage");

			// One free hotbar slot: the stack goes there, still not into the offhand
			inv.setItem(4, ItemStack.EMPTY);
			menu.onInteract(iron, StorageTerminalMenu.SlotAction.SHIFT_PULL, false);
			h.assertTrue(p.getOffhandItem().isEmpty(), "shift-pull put " + p.getOffhandItem() + " into the offhand");
			h.assertTrue(inv.getItem(4).is(Items.IRON_INGOT), "pulled iron in the free hotbar slot, has " + inv.getItem(4));
			eq(inv.getItem(4).getCount() + ironInChests(h), 60, "iron in the hotbar slot + storage");

			// The offhand still works when filled by hand
			inv.offhand.set(0, new ItemStack(Items.SHIELD));
			h.assertTrue(p.getOffhandItem().is(Items.SHIELD), "offhand usable");
			menu.removed(p);
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void itemLocatorFindsInventories(GameTestHelper h) {
		buildBasicNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(terminalCount(te, Items.IRON_INGOT), 60L, "network ready");
			h.assertTrue(te.getItemHandler() != null, "terminal has no storage yet");
		})
		.thenExecute(() -> {
			ServerLevel level = h.getLevel();
			ItemLocator.SearchResult iron = ItemLocator.search(te, level, ItemVariant.of(Items.IRON_INGOT));
			List<BlockPos> ironPos = iron.located().stream().map(ItemLocator.Location::pos).toList();
			eq(iron.unknown(), 0, "inventories without a position");
			eq(ironPos.size(), 2, "iron locations (single chest + double chest once): " + ironPos);
			h.assertTrue(ironPos.contains(h.absolutePos(SINGLE)), "single chest not located: " + ironPos);
			boolean left = ironPos.contains(h.absolutePos(DOUBLE_LEFT)), right = ironPos.contains(h.absolutePos(DOUBLE_RIGHT));
			h.assertTrue(left ^ right, "double chest must be located exactly once: " + ironPos);

			ItemLocator.SearchResult cobble = ItemLocator.search(te, level, ItemVariant.of(Items.COBBLESTONE));
			eq(cobble.located().stream().map(ItemLocator.Location::pos).toList(), List.of(h.absolutePos(SINGLE)), "cobblestone locations");

			ItemLocator.SearchResult none = ItemLocator.search(te, level, ItemVariant.of(Items.DIAMOND));
			eq(none.located().size() + none.unknown(), 0, "diamond locations");

			ItemLocator.Selection sel = ItemLocator.select(iron.located(), level.dimension(), h.absolutePos(TERMINAL), ItemLocator.MAX_POSITIONS);
			eq(sel.inDimension(), 2, "selection in dimension");
			eq(sel.otherDimensions(), 0, "selection other dimensions");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void itemLocatorFollowsCables(GameTestHelper h) {
		buildCableNetwork(h, true, true);
		StorageTerminalBlockEntity te = terminal(h, C_TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(terminalCount(te, Items.DIAMOND), 11L, "network ready");
			h.assertTrue(te.getItemHandler() != null, "terminal has no storage yet");
		})
		.thenExecute(() -> {
			ItemLocator.SearchResult res = ItemLocator.search(te, h.getLevel(), ItemVariant.of(Items.DIAMOND));
			eq(res.located().stream().map(ItemLocator.Location::pos).toList(), List.of(h.absolutePos(C_REMOTE_CHEST)), "diamond locations");
			eq(res.unknown(), 0, "unknown");
		})
		.thenSucceed();
	}
}
