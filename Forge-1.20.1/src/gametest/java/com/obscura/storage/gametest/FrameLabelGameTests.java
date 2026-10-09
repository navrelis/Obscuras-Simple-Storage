package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;

import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.doctor.DoctorScan;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.util.BlockFace;
import com.obscura.storage.util.BlockFilter;
import com.obscura.storage.util.FilteredStorage;
import com.obscura.storage.util.IProxy;
import com.obscura.storage.util.ItemHandlers;
import com.obscura.storage.util.ItemKey;
import com.obscura.storage.util.LabelledStorage;
import com.obscura.storage.util.MergedStorage;
import com.obscura.storage.util.FrameLabel;
import com.obscura.storage.util.FrameLabels;
import com.obscura.storage.util.NetworkChanges;
import com.obscura.storage.util.Priority;
import com.obscura.storage.util.StoredItemStack;

/**
 * Frame Labels: a glow item frame on a network inventory makes the network insert only the framed
 * item there, and fill it first. Normal item frames stay decoration.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class FrameLabelGameTests {
	private static final BlockPos CONNECTOR = new BlockPos(3, 2, 3);
	private static final BlockPos TERMINAL = new BlockPos(3, 3, 3);
	/** The chest that gets labelled, west of the connector. */
	private static final BlockPos LABELLED = new BlockPos(2, 2, 3);
	private static final BlockPos OTHER_EAST = new BlockPos(4, 2, 3);
	private static final BlockPos OTHER_NORTH = new BlockPos(3, 2, 2);
	/** Frames on the labelled chest's west and south faces. */
	private static final BlockPos FRAME_WEST = new BlockPos(1, 2, 3);
	private static final BlockPos FRAME_SOUTH = new BlockPos(2, 2, 4);
	/** Unreported frame changes are picked up by the connector's 20 tick heartbeat (plus slack). */
	private static final int HEARTBEAT = 25;

	/** Empty labelled chest with 3 cobblestone put in by hand, two unlabelled chests with 5 iron each. */
	private static void threeChests(GameTestHelper h) {
		chest(h, LABELLED, new ItemStack(Items.COBBLESTONE, 3));
		chest(h, OTHER_EAST, new ItemStack(Items.IRON_INGOT, 5));
		chest(h, OTHER_NORTH, new ItemStack(Items.IRON_INGOT, 5));
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
	}

	/** Only the (to be labelled) chest, with one cobblestone so the terminal shows when it is ready. */
	private static void oneChest(GameTestHelper h) {
		chest(h, LABELLED, new ItemStack(Items.COBBLESTONE, 1));
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
	}

	static GlowItemFrame glowFrame(GameTestHelper h, BlockPos rel, Direction facing, ItemStack item) {
		ServerLevel l = h.getLevel();
		GlowItemFrame f = new GlowItemFrame(l, h.absolutePos(rel), facing);
		f.setItem(item.copy(), false);
		h.assertTrue(l.addFreshEntity(f), "glow frame could not be spawned at " + rel);
		return f;
	}

	static ItemFrame plainFrame(GameTestHelper h, BlockPos rel, Direction facing, ItemStack item) {
		ServerLevel l = h.getLevel();
		ItemFrame f = new ItemFrame(l, h.absolutePos(rel), facing);
		f.setItem(item.copy(), false);
		h.assertTrue(l.addFreshEntity(f), "item frame could not be spawned at " + rel);
		return f;
	}

	static boolean labelActive(GameTestHelper h) {
		InventoryConnectorBlockEntity c = be(h, CONNECTOR);
		return c.getNetworkStorage().getStorages().stream().anyMatch(a -> a instanceof FrameLabel.Labelled);
	}

	/** Pushes through the terminal (a network insert), returns what did not fit. */
	static long push(StorageTerminalBlockEntity te, Item item, int count) {
		StoredItemStack rest = te.pushStack(new StoredItemStack(new ItemStack(item), count));
		return rest == null ? 0 : rest.getQuantity();
	}

	static int status(GameTestHelper h, BlockPos rel) {
		return FrameLabels.statusAt(h.getLevel(), h.absolutePos(rel));
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void labelledChestTakesItsItemFirstAndOnlyIt(GameTestHelper h) {
		threeChests(h);
		glowFrame(h, FRAME_WEST, Direction.WEST, new ItemStack(Items.IRON_INGOT));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(labelActive(h), "label not applied");
			eq(terminalCount(te, Items.IRON_INGOT), 10L, "terminal iron");
			eq(terminalCount(te, Items.COBBLESTONE), 3L, "hand-placed cobblestone stays visible in the labelled chest");
		})
		.thenExecute(() -> {
			h.assertTrue((status(h, LABELLED) & FrameLabels.CONNECTED) != 0, "labelled chest not marked as connected");
			eq(DoctorScan.full(be(h, CONNECTOR), h.getLevel().getServer()).details().labelled(), 1, "doctor at: labelled members");
			eq(push(te, Items.IRON_INGOT, 20), 0L, "iron remainder");
			eq(count(h, LABELLED, Items.IRON_INGOT), 20, "iron goes into the labelled chest first");
			eq(count(h, OTHER_EAST, Items.IRON_INGOT) + count(h, OTHER_NORTH, Items.IRON_INGOT), 10, "unlabelled chests keep their iron");
			eq(push(te, Items.DIRT, 10), 0L, "dirt remainder");
			eq(count(h, LABELLED, Items.DIRT), 0, "dirt must never go into the labelled chest");
			eq(count(h, OTHER_EAST, Items.DIRT) + count(h, OTHER_NORTH, Items.DIRT), 10, "dirt in the unlabelled chests");
			// Extraction is unchanged: the hand-placed cobblestone comes out of the labelled chest
			StoredItemStack pulled = te.pullStack(new StoredItemStack(new ItemStack(Items.COBBLESTONE)), 64);
			h.assertTrue(pulled != null && pulled.getQuantity() == 3, "cobblestone pulled from the labelled chest");
			eq(count(h, LABELLED, Items.COBBLESTONE), 0, "cobblestone left in the labelled chest");
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void severalFramesLabelWithTheirUnion(GameTestHelper h) {
		threeChests(h);
		glowFrame(h, FRAME_WEST, Direction.WEST, new ItemStack(Items.IRON_INGOT));
		glowFrame(h, FRAME_SOUTH, Direction.SOUTH, new ItemStack(Items.GOLD_INGOT));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(labelActive(h), "label not applied");
			eq(terminalCount(te, Items.IRON_INGOT), 10L, "terminal iron");
		})
		.thenExecute(() -> {
			eq(push(te, Items.GOLD_INGOT, 12), 0L, "gold remainder");
			eq(push(te, Items.IRON_INGOT, 7), 0L, "iron remainder");
			eq(count(h, LABELLED, Items.GOLD_INGOT), 12, "gold into the labelled chest");
			eq(count(h, LABELLED, Items.IRON_INGOT), 7, "iron into the labelled chest");
			eq(push(te, Items.DIRT, 5), 0L, "dirt remainder");
			eq(count(h, LABELLED, Items.DIRT), 0, "dirt must not go into the labelled chest");
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void filterItemInFrameIsTheLabel(GameTestHelper h) {
		threeChests(h);
		glowFrame(h, FRAME_WEST, Direction.WEST, itemFilter(Items.DIAMOND));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(labelActive(h), "label not applied");
			eq(terminalCount(te, Items.IRON_INGOT), 10L, "terminal iron");
		})
		.thenExecute(() -> {
			eq(push(te, Items.DIAMOND, 9), 0L, "diamond remainder");
			eq(count(h, LABELLED, Items.DIAMOND), 9, "diamonds into the chest labelled with a diamond filter");
			eq(push(te, Items.IRON_INGOT, 5), 0L, "iron remainder");
			eq(count(h, LABELLED, Items.IRON_INGOT), 0, "the filter item labels by its filter, not as an item");
			eq(push(te, Items.PAPER, 5), 0L, "paper remainder");
			eq(count(h, LABELLED, Items.PAPER), 0, "paper must not go into the labelled chest");
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void normalItemFrameHasNoEffect(GameTestHelper h) {
		oneChest(h);
		plainFrame(h, FRAME_WEST, Direction.WEST, new ItemStack(Items.IRON_INGOT));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> eq(terminalCount(te, Items.COBBLESTONE), 1L, "network ready"))
		.thenIdle(HEARTBEAT)
		.thenExecute(() -> {
			h.assertFalse(labelActive(h), "a normal item frame must not label");
			eq(push(te, Items.DIRT, 5), 0L, "dirt goes into the chest behind a normal item frame");
			eq(count(h, LABELLED, Items.DIRT), 5, "dirt in the chest");
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void configuratorFilterOverridesFrame(GameTestHelper h) {
		threeChests(h);
		glowFrame(h, FRAME_WEST, Direction.WEST, new ItemStack(Items.IRON_INGOT));
		// Configurator filter on the same chest: dirt only, filled first
		BlockFilter f = BlockFilter.findBlockFilterAt(h.getLevel(), h.absolutePos(LABELLED));
		h.assertTrue(f != null, "no block filter could be created");
		f.filter.setItem(0, itemFilter(Items.DIRT));
		f.setPriority(Priority.HIGH);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue((status(h, LABELLED) & FrameLabels.OVERRIDDEN) != 0, "frame label not marked as overridden");
			eq(terminalCount(te, Items.IRON_INGOT), 10L, "terminal iron");
		})
		.thenExecute(() -> {
			h.assertFalse(labelActive(h), "the frame label must be ignored under a Configurator filter");
			eq(push(te, Items.IRON_INGOT, 6), 0L, "iron remainder");
			eq(count(h, LABELLED, Items.IRON_INGOT), 0, "the filter rejects iron although the frame holds iron");
			eq(push(te, Items.DIRT, 6), 0L, "dirt remainder");
			eq(count(h, LABELLED, Items.DIRT), 6, "the filter's dirt goes in, first");
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void emptiedFrameRemovesLabelWithinTheHeartbeat(GameTestHelper h) {
		oneChest(h);
		// Set straight on the entity, like a command or another mod would: no event reports it
		GlowItemFrame frame = glowFrame(h, FRAME_WEST, Direction.WEST, new ItemStack(Items.IRON_INGOT));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(labelActive(h), "label not applied");
			eq(terminalCount(te, Items.COBBLESTONE), 1L, "network ready");
		})
		.thenExecute(() -> {
			eq(push(te, Items.DIRT, 4), 4L, "the labelled chest rejects dirt");
			frame.setItem(ItemStack.EMPTY, false);
		})
		.thenExecuteAfter(HEARTBEAT, () -> {
			h.assertFalse(labelActive(h), "label still applied " + HEARTBEAT + " ticks after the frame was emptied");
			eq(push(te, Items.DIRT, 4), 0L, "dirt goes in without the label");
			eq(count(h, LABELLED, Items.DIRT), 4, "dirt in the chest");
		})
		.thenExecute(() -> frame.setItem(new ItemStack(Items.GOLD_INGOT), false))
		.thenExecuteAfter(HEARTBEAT, () -> {
			h.assertTrue(labelActive(h), "new label not applied " + HEARTBEAT + " ticks after the frame was filled");
			eq(push(te, Items.DIRT, 4), 4L, "the relabelled chest rejects dirt");
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void brokenFrameRemovesLabel(GameTestHelper h) {
		oneChest(h);
		GlowItemFrame frame = glowFrame(h, FRAME_WEST, Direction.WEST, new ItemStack(Items.IRON_INGOT));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(labelActive(h), "label not applied");
			eq(terminalCount(te, Items.COBBLESTONE), 1L, "network ready");
		})
		.thenExecute(frame::kill)
		// Removal is reported (entity unload): the connector rescans at its next 5 tick check
		.thenExecuteAfter(10, () -> {
			h.assertFalse(labelActive(h), "label still applied 10 ticks after the frame broke");
			eq(push(te, Items.DIRT, 4), 0L, "dirt goes in without the label");
		})
		.thenSucceed();
	}

	// Double chest south of the connector, the connector touches its left half; frame on the right half
	private static final BlockPos DOUBLE_LEFT = new BlockPos(3, 2, 4);
	private static final BlockPos DOUBLE_RIGHT = new BlockPos(4, 2, 4);
	private static final BlockPos FRAME_ON_RIGHT = new BlockPos(4, 2, 5);
	private static final BlockPos SINGLE_WEST = new BlockPos(2, 2, 3);

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void frameOnEitherHalfLabelsTheDoubleChest(GameTestHelper h) {
		doubleChest(h, DOUBLE_LEFT);
		chest(h, SINGLE_WEST, new ItemStack(Items.COBBLESTONE, 1));
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
		glowFrame(h, FRAME_ON_RIGHT, Direction.SOUTH, new ItemStack(Items.IRON_INGOT));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(labelActive(h), "label on the other half not applied");
			eq(terminalCount(te, Items.COBBLESTONE), 1L, "network ready");
			eq(te.getSlotCount(), 27 + 54, "the labelled double chest is counted once");
		})
		.thenExecute(() -> {
			eq(push(te, Items.IRON_INGOT, 30), 0L, "iron remainder");
			eq(count(h, DOUBLE_LEFT, Items.IRON_INGOT) + count(h, DOUBLE_RIGHT, Items.IRON_INGOT), 30, "iron into the labelled double chest");
			eq(count(h, SINGLE_WEST, Items.IRON_INGOT), 0, "no iron in the unlabelled chest");
			eq(push(te, Items.DIRT, 10), 0L, "dirt remainder");
			eq(count(h, SINGLE_WEST, Items.DIRT), 10, "dirt into the unlabelled chest");
			eq(count(h, DOUBLE_LEFT, Items.DIRT) + count(h, DOUBLE_RIGHT, Items.DIRT), 0, "no dirt in the labelled double chest");
		})
		.thenSucceed();
	}

	/** A cable connector pointing at a labelled chest: the label applies there too, below the connector's own filter. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void cableConnectorTargetIsLabelled(GameTestHelper h) {
		BlockPos cableConnector = new BlockPos(3, 2, 4);
		BlockPos target = new BlockPos(3, 2, 5);
		BlockPos frame = new BlockPos(2, 2, 5);
		chest(h, OTHER_EAST, new ItemStack(Items.COBBLESTONE, 1));
		chest(h, target);
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
		place(h, cableConnector, Content.invCableConnector.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING, Direction.SOUTH));
		glowFrame(h, frame, Direction.WEST, new ItemStack(Items.IRON_INGOT));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(te.getSlotCount(), 54, "network ready with the cable connector's chest");
			eq(DoctorScan.full(be(h, CONNECTOR), h.getLevel().getServer()).details().labelled(), 1, "doctor at: labelled members");
		})
		.thenExecute(() -> {
			eq(push(te, Items.IRON_INGOT, 10), 0L, "iron remainder");
			eq(count(h, target, Items.IRON_INGOT), 10, "iron goes into the labelled chest behind the cable connector first");
			eq(push(te, Items.DIRT, 10), 0L, "dirt remainder");
			eq(count(h, target, Items.DIRT), 0, "no dirt in the labelled chest behind the cable connector");
		})
		.thenSucceed();
	}

	/**
	 * A glow frame with an item loading next to a chest that is on no network must not trigger a
	 * rescan: the trigger ({@link NetworkChanges}) is global, and decorated chests are common. Its own
	 * batch, so no other test changes a block filter, cable or trim (and with it the generation) meanwhile.
	 */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = "frame_labels_generation")
	public void loadingFrameOffNetworkDoesNotBumpGeneration(GameTestHelper h) {
		chest(h, LABELLED, new ItemStack(Items.COBBLESTONE, 1));
		long before = NetworkChanges.get();
		glowFrame(h, FRAME_WEST, Direction.WEST, new ItemStack(Items.IRON_INGOT));
		eq(NetworkChanges.get(), before, "network generation after a glow frame loaded");
		h.runAfterDelay(10, () -> {
			eq(NetworkChanges.get(), before, "network generation 10 ticks after a glow frame loaded");
			h.succeed();
		});
	}

	/** Its own batch: the switch is a server-wide option, no other frame test may run while it is off. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = "frame_labels_off")
	public void configSwitchOffDisablesLabels(GameTestHelper h) {
		oneChest(h);
		glowFrame(h, FRAME_WEST, Direction.WEST, new ItemStack(Items.IRON_INGOT));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		Config.get().frameLabelsEnabled = false;
		h.startSequence()
		.thenWaitUntil(() -> eq(terminalCount(te, Items.COBBLESTONE), 1L, "network ready"))
		.thenIdle(HEARTBEAT)
		.thenExecute(() -> {
			boolean active = labelActive(h);
			if (active)Config.get().frameLabelsEnabled = true;
			h.assertFalse(active, "label applied although frameLabelsEnabled is off");
			long rest = push(te, Items.DIRT, 4);
			Config.get().frameLabelsEnabled = true;
			eq(rest, 0L, "dirt goes in while labels are off");
		})
		// Switched back on: the next heartbeat applies the label again
		.thenExecuteAfter(HEARTBEAT, () -> h.assertTrue(labelActive(h), "label not applied after switching labels back on"))
		.thenSucceed();
	}

	/**
	 * The label rule and the labelled item handler, without a network around them. Fabric checks the
	 * same in plain JUnit (FrameLabelTest); Forge's registries need the running game.
	 */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public void labelRulesAndLabelledStorage(GameTestHelper h) {
		FrameLabel iron = new FrameLabel(Set.of(Items.IRON_INGOT, Items.GOLD_INGOT), List.of(), s -> null);
		h.assertTrue(iron.test(new ItemStack(Items.GOLD_INGOT, 64)), "union of the framed items");
		ItemStack named = new ItemStack(Items.IRON_INGOT);
		named.setHoverName(Component.literal("Special"));
		h.assertTrue(iron.test(named), "a named iron ingot is still iron");
		h.assertFalse(iron.test(new ItemStack(Items.DIRT)), "dirt");
		h.assertFalse(iron.test(ItemStack.EMPTY), "empty stack");
		eq(FrameLabel.PRIORITY, Priority.HIGHEST, "labelled inventories are filled first");
		// A framed Item Filter labels by its filter, not as an item
		ItemStack filterStack = itemFilter(Items.DIAMOND);
		h.assertTrue(FrameLabel.isFilterItem(filterStack), "item filter is a filter item");
		FrameLabel byFilter = new FrameLabel(Set.of(), List.of(filterStack),
				s -> ((IItemFilter) s.getItem()).createFilter(new BlockFace(h.getLevel(), h.absolutePos(BlockPos.ZERO), Direction.UP), s));
		h.assertTrue(byFilter.test(new ItemStack(Items.DIAMOND)), "diamond through the filter");
		h.assertFalse(byFilter.test(filterStack), "the filter item itself");
		h.assertTrue(byFilter.sameContent(Set.of(), List.of(filterStack.copy())), "same frame contents");
		h.assertFalse(byFilter.sameContent(Set.of(Items.IRON_INGOT), List.of(filterStack)), "an item more");

		// The handler: only labelled items go in, everything is listed and comes out
		SimpleContainer labelled = new SimpleContainer(27);
		labelled.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		SimpleContainer other = new SimpleContainer(27);
		IItemHandler backing = new InvWrapper(labelled);
		LabelledStorage lab = new LabelledStorage(backing, new FrameLabel(Set.of(Items.IRON_INGOT), List.of(), s -> null));
		h.assertTrue(IProxy.resolve(lab) == backing, "a labelled member stands for its inventory");
		MergedStorage net = new MergedStorage();
		net.add(lab);
		net.add(new InvWrapper(other));
		eq(ItemHandlers.insert(net, new ItemStack(Items.IRON_INGOT), 20), 20L, "iron inserted");
		eq(count(labelled, Items.IRON_INGOT), 20, "iron goes into the labelled inventory first");
		eq(ItemHandlers.insert(net, new ItemStack(Items.DIRT), 10), 10L, "dirt inserted");
		eq(count(labelled, Items.DIRT), 0, "dirt never goes into the labelled inventory");
		eq(lab.insertItem(5, new ItemStack(Items.DIRT), false).getCount(), 1, "slot insert of dirt refused");
		h.assertFalse(lab.isItemValid(5, new ItemStack(Items.DIRT)), "dirt not valid");
		eq(ItemHandlers.count(net, ItemKey.of(Items.COBBLESTONE), Long.MAX_VALUE), 3L, "hand-placed cobblestone listed");
		eq(ItemHandlers.extract(net, ItemKey.of(Items.COBBLESTONE), 64, false), 3L, "cobblestone extracted");
		eq(count(labelled, Items.COBBLESTONE), 0, "cobblestone left");
		// The Network Doctor counts labelled members, also behind a cable connector's own filter
		MergedStorage doc = new MergedStorage();
		doc.add(new InvWrapper(new SimpleContainer(9)));
		doc.add(lab);
		doc.add(new FilteredStorage(new LabelledStorage(new InvWrapper(new SimpleContainer(9)), iron), s -> true, false));
		eq(doc.countLabelled(), 2, "labelled members");
		h.succeed();
	}
}
