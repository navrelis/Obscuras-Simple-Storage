package com.obscura.storage.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.obscura.storage.gametest.GameTestUtil.*;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.block.entity.InventoryConnectorBlockEntity;
import com.obscura.storage.block.entity.StorageTerminalBlockEntity;
import com.obscura.storage.gametest.GameTestUtil;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.inventory.PlatformInventoryAccess.BlockInventoryAccess;

/**
 * NeoForge only: the IItemHandler platform layer (inventory identity used for deduplication, and the
 * change tracker), in the same package as the package-private hooks it checks.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class InventoryPlatformGameTests {
	private static final String EMPTY_STRUCTURE = GameTestUtil.EMPTY_STRUCTURE;

	private static BlockInventoryAccess access(GameTestHelper h, BlockPos rel, Direction side) {
		BlockInventoryAccess a = new BlockInventoryAccess();
		a.onLoad(h.getLevel(), h.absolutePos(rel), side, () -> true);
		return a;
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 40)
	public void inventoryIdentity(GameTestHelper h) {
		BlockPos left = new BlockPos(2, 2, 2);
		GameTestUtil.doubleChest(h, left);
		BlockPos single = new BlockPos(2, 2, 5);
		GameTestUtil.chest(h, single);
		BlockPos furnace = new BlockPos(5, 2, 5);
		h.setBlock(furnace, Blocks.FURNACE);

		Object l = PlatformMultiInventoryAccess.storageIdentity(access(h, left, Direction.UP));
		Object r = PlatformMultiInventoryAccess.storageIdentity(access(h, left.east(), Direction.NORTH));
		Object s = PlatformMultiInventoryAccess.storageIdentity(access(h, single, Direction.UP));
		Object s2 = PlatformMultiInventoryAccess.storageIdentity(access(h, single, Direction.WEST));
		h.assertTrue(l != null && l == r, "both halves of a double chest must be one inventory");
		h.assertTrue(s != null && s == s2, "one chest seen from two sides must be one inventory");
		h.assertTrue(s != l, "different chests must stay different");

		Object fUp = PlatformMultiInventoryAccess.storageIdentity(access(h, furnace, Direction.UP));
		Object fUp2 = PlatformMultiInventoryAccess.storageIdentity(access(h, furnace, Direction.UP));
		Object fDown = PlatformMultiInventoryAccess.storageIdentity(access(h, furnace, Direction.DOWN));
		h.assertTrue(fUp != null && fUp == fUp2, "the same face of a furnace must be one inventory");
		h.assertTrue(fUp != fDown, "different faces of a furnace expose different slots and must both stay");
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void trackerCountsAndShrinks(GameTestHelper h) {
		BlockPos pos = new BlockPos(3, 2, 3);
		GameTestUtil.chest(h, pos, new ItemStack(Items.IRON_INGOT, 10), new ItemStack(Items.IRON_INGOT, 5), new ItemStack(Items.COBBLESTONE, 3));
		BlockInventoryAccess acc = access(h, pos, Direction.UP);
		StoredItemStack iron = new StoredItemStack(new ItemStack(Items.IRON_INGOT));
		long[] v = new long[1];
		h.startSequence()
		.thenExecute(() -> {
			IInventoryChangeTracker tr = acc.tracker();
			v[0] = tr.getChangeTracker(h.getLevel());
			h.assertValueEqual(tr.countItems(iron), 15L, "iron counted");
			h.assertValueEqual(tr.streamWrappedStacks(false).count(), 3L, "tracked stacks");
			Container c = GameTestUtil.container(h, pos);
			c.setItem(20, new ItemStack(Items.IRON_INGOT, 7));
			c.setChanged();
		})
		.thenIdle(1)
		.thenExecute(() -> {
			IInventoryChangeTracker tr = acc.tracker();
			long v2 = tr.getChangeTracker(h.getLevel());
			h.assertTrue(v2 != v[0], "a change must be reported");
			h.assertValueEqual(tr.countItems(iron), 22L, "iron after adding 7");
			// A slot handed out by the tracker, and continuing the search after it
			InventorySlot first = tr.findSlot(s -> s.getStack().is(Items.IRON_INGOT), false);
			h.assertTrue(first != null && tr.owns(first), "first iron slot");
			InventorySlot next = tr.findSlotAfter(first, s -> s.getStack().is(Items.IRON_INGOT), false, false);
			h.assertTrue(next != null && next.getId() > first.getId(), "search continues after the slot");
			// Replace the chest with an empty hopper (5 slots): the new handler holds nothing. Empty
			// the chest first, or its dropped items land in the hopper.
			GameTestUtil.container(h, pos).clearContent();
			h.setBlock(pos, Blocks.HOPPER);
		})
		.thenIdle(1)
		.thenExecute(() -> {
			IInventoryChangeTracker tr = acc.tracker();
			tr.getChangeTracker(h.getLevel());
			h.assertValueEqual(tr.countItems(iron), 0L, "iron after the chest was replaced by an empty hopper");
			h.assertValueEqual(tr.streamWrappedStacks(false).count(), 0L, "tracked stacks after the replacement");
		})
		.thenSucceed();
	}

	// ---- Two faces of one sided block on one network ----
	// Target block at (4,4,4) with a cable connector below it (bottom face), one west of it (west
	// face) and one east of it (east face: the same slots as the west face). The connectors'
	// cables run down to a Connector at (3,2,4) with a terminal on top.
	private static final BlockPos SIDED = new BlockPos(4, 4, 4);
	private static final BlockPos SIDED_CONNECTOR = new BlockPos(3, 2, 4);
	private static final BlockPos SIDED_TERMINAL = new BlockPos(3, 3, 4);

	private static void buildSidedNetwork(GameTestHelper h) {
		h.setBlock(SIDED_CONNECTOR, Content.connector.get());
		terminalAbove(h, SIDED_TERMINAL);
		BlockPos[] cables = {
				new BlockPos(4, 2, 4), new BlockPos(5, 2, 4), new BlockPos(6, 2, 4), new BlockPos(6, 3, 4), new BlockPos(6, 4, 4),
				new BlockPos(2, 2, 4), new BlockPos(2, 3, 4), new BlockPos(2, 4, 4)};
		for (BlockPos c : cables)place(h, c, Content.invCable.get());
		BlockPos[] connectors = {new BlockPos(4, 3, 4), new BlockPos(3, 4, 4), new BlockPos(5, 4, 4)};
		Direction[] facing = {Direction.UP, Direction.EAST, Direction.WEST};
		for (int i = 0; i < connectors.length; i++) {
			h.setBlock(connectors[i], Content.invCableConnector.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING, facing[i]));
		}
		for (BlockPos c : cables)refreshShape(h, c);
		for (BlockPos c : connectors)refreshShape(h, c);
	}

	/** Whether the network has settled: bottom face and side face are members, the two equal side faces are one. */
	private static boolean sidedNetworkReady(GameTestHelper h) {
		InventoryConnectorBlockEntity conn = h.getBlockEntity(SIDED_CONNECTOR);
		return conn.getMergedHandler() instanceof MultiInventoryAccess m && m.getInventoryCount() == 2;
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void furnaceFacesAreCountedOnce(GameTestHelper h) {
		// Bottom face: result (2) and fuel (1). Side face: fuel (1). The fuel slot is on both.
		h.setBlock(SIDED, Blocks.FURNACE);
		Container furnace = container(h, SIDED);
		furnace.setItem(1, new ItemStack(Items.COAL, 5));
		furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
		furnace.setChanged();
		buildSidedNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, SIDED_TERMINAL);
		StoredItemStack iron = new StoredItemStack(new ItemStack(Items.IRON_INGOT));
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(sidedNetworkReady(h), "network must hold the bottom and the side face (the two side faces are one)");
			h.assertValueEqual(terminalCount(te, Items.IRON_INGOT), 3L, "result slot seen through the bottom face");
		})
		.thenExecute(() -> {
			h.assertValueEqual(terminalCount(te, Items.COAL), 5L, "fuel is on two faces and must be counted once, not twice");
			h.assertValueEqual(te.getSlotCount(), 2, "result + fuel, the fuel slot counted once");
			// Both faces stay usable: fuel goes in, the result can be taken out through the bottom face
			StoredItemStack rest = te.pushStack(new StoredItemStack(new ItemStack(Items.COAL), 3));
			h.assertTrue(rest == null, "coal push left a remainder");
			h.assertValueEqual(count(furnace, Items.COAL), 8, "coal in the furnace");
			StoredItemStack pulled = te.pullStack(iron, 2);
			h.assertTrue(pulled != null && pulled.getQuantity() == 2, "result pulled through the bottom face");
			h.assertValueEqual(count(furnace, Items.IRON_INGOT), 1, "iron left in the result slot");
		})
		.thenWaitUntil(() -> {
			h.assertValueEqual(terminalCount(te, Items.COAL), 8L, "fuel after the push");
			h.assertValueEqual(terminalCount(te, Items.IRON_INGOT), 1L, "result after the pull");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void brewingStandFacesAreCountedOnce(GameTestHelper h) {
		// Bottom face: bottles (0, 1, 2) + ingredient (3). Side face: bottles (0, 1, 2) + fuel (4).
		h.setBlock(SIDED, Blocks.BREWING_STAND);
		Container stand = container(h, SIDED);
		for (int i = 0; i < 3; i++)stand.setItem(i, new ItemStack(Items.GLASS_BOTTLE));
		stand.setItem(3, new ItemStack(Items.SUGAR, 2));
		stand.setChanged();
		buildSidedNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, SIDED_TERMINAL);
		h.succeedWhen(() -> {
			h.assertTrue(sidedNetworkReady(h), "network must hold the bottom and the side face (the two side faces are one)");
			h.assertValueEqual(terminalCount(te, Items.GLASS_BOTTLE), 3L, "bottles are on both faces and must be counted once");
			h.assertValueEqual(terminalCount(te, Items.SUGAR), 2L, "ingredient seen through the bottom face");
			h.assertValueEqual(te.getSlotCount(), 5, "5 slots in total, the 3 shared ones counted once");
		});
	}
}
