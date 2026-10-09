package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.tidy.TidyJob;
import com.obscura.storage.tidy.TidyManager;
import com.obscura.storage.tile.FilingCabinetBlockEntity;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.tile.ShulkerDockBlockEntity;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.BlockFilter;
import com.obscura.storage.util.FrameLabel;

/**
 * Tidy in a real world: consolidation, item counts before and after, Frame Labels, docked shulker
 * boxes and Filing Cabinets left alone, a chest broken mid-run, stopping, cooldown, config switches
 * and the moves-per-tick limit. Same tests as the Fabric build. Tests that change a server-wide option or count item entities run
 * in batches of their own.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class TidyGameTests {
	private static final BlockPos CONNECTOR = new BlockPos(3, 2, 3);
	private static final BlockPos TERMINAL = new BlockPos(3, 3, 3);
	private static final BlockPos WEST = new BlockPos(2, 2, 3);
	private static final BlockPos EAST = new BlockPos(4, 2, 3);
	private static final BlockPos NORTH = new BlockPos(3, 2, 2);
	private static final BlockPos SOUTH = new BlockPos(3, 2, 4);
	private static final BlockPos[] CHESTS = {WEST, EAST, NORTH, SOUTH};
	private static final Item[] ITEMS = {Items.COBBLESTONE, Items.IRON_INGOT, Items.DIRT, Items.OAK_LOG};

	// ---- helpers ----

	static ItemStack st(Item i, int n) {
		return new ItemStack(i, n);
	}

	static InventoryConnectorBlockEntity connector(GameTestHelper h) {
		return be(h, CONNECTOR);
	}

	static NetworkStats stats(GameTestHelper h) {
		return connector(h).getDoctorStats();
	}

	static void network(GameTestHelper h) {
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
	}

	/** Items of {@code item} in the given chests (ground truth). */
	static int inChests(GameTestHelper h, Item item, BlockPos... chests) {
		int n = 0;
		for (BlockPos p : chests) {
			// Null-safe: a chest broken during the test (or one the test does not use) holds nothing
			if (h.getLevel().getBlockEntity(h.absolutePos(p)) instanceof Container c)n += count(c, item);
		}
		return n;
	}

	static int usedSlots(Container c) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++)if (!c.getItem(i).isEmpty())n++;
		return n;
	}

	static int usedSlots(GameTestHelper h, BlockPos... chests) {
		int n = 0;
		for (BlockPos p : chests)n += usedSlots(container(h, p));
		return n;
	}

	static List<ItemStack> contents(Container c) {
		List<ItemStack> l = new ArrayList<>();
		for (int i = 0; i < c.getContainerSize(); i++)l.add(c.getItem(i).copy());
		return l;
	}

	static void assertSame(GameTestHelper h, List<ItemStack> a, List<ItemStack> b, String what) {
		eq(a.size(), b.size(), what + " size");
		for (int i = 0; i < a.size(); i++) {
			h.assertTrue(ItemStack.matches(a.get(i), b.get(i)), what + " slot " + i + ": " + a.get(i) + " vs " + b.get(i));
		}
	}

	/** Item entities of {@code item} around the test area. */
	static int onGround(GameTestHelper h, Item item) {
		int n = 0;
		for (ItemEntity e : h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(CONNECTOR)).inflate(6))) {
			if (e.getItem().is(item))n += e.getItem().getCount();
		}
		return n;
	}

	static long terminalTotal(StorageTerminalBlockEntity te, Item item) {
		return terminalCount(te, item);
	}

	/** Many partial stacks: plenty of moves for the slow-motion tests. */
	static void messyChests(GameTestHelper h) {
		chest(h, WEST, st(Items.COBBLESTONE, 5), st(Items.COBBLESTONE, 6), st(Items.COBBLESTONE, 7), st(Items.COBBLESTONE, 8),
				st(Items.DIRT, 3), st(Items.DIRT, 4), st(Items.IRON_INGOT, 2), st(Items.IRON_INGOT, 3));
		chest(h, EAST, st(Items.COBBLESTONE, 9), st(Items.COBBLESTONE, 10), st(Items.DIRT, 5), st(Items.DIRT, 6),
				st(Items.IRON_INGOT, 4), st(Items.IRON_INGOT, 5), st(Items.OAK_LOG, 1), st(Items.OAK_LOG, 2));
		chest(h, NORTH, st(Items.COBBLESTONE, 11), st(Items.DIRT, 7), st(Items.DIRT, 8), st(Items.IRON_INGOT, 6),
				st(Items.OAK_LOG, 3), st(Items.OAK_LOG, 4), st(Items.OAK_LOG, 5));
		chest(h, SOUTH, st(Items.COBBLESTONE, 12), st(Items.IRON_INGOT, 7), st(Items.OAK_LOG, 6), st(Items.DIRT, 9));
		network(h);
	}

	static int[] totals(GameTestHelper h) {
		int[] t = new int[ITEMS.length];
		for (int i = 0; i < ITEMS.length; i++)t[i] = inChests(h, ITEMS[i], CHESTS) + onGround(h, ITEMS[i]);
		return t;
	}

	static void assertTotals(GameTestHelper h, int[] before, String when) {
		int[] now = totals(h);
		for (int i = 0; i < ITEMS.length; i++)eq(now[i], before[i], when + ": total " + ITEMS[i]);
	}

	static TidyJob running(GameTestHelper h) {
		return TidyManager.jobFor(stats(h));
	}

	static TidyManager.Summary finished(GameTestHelper h) {
		h.assertTrue(running(h) == null, "tidy still running");
		TidyManager.Summary j = TidyManager.lastRun(stats(h));
		h.assertTrue(j != null, "tidy did not end");
		return j;
	}

	static void start(GameTestHelper h) {
		TidyManager.resetMemory(stats(h));
		eq(TidyManager.start(connector(h), null), TidyManager.Result.STARTED, "start");
	}

	// ---- tests ----

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void scatteredStacksGoHomeNothingLost(GameTestHelper h) {
		chest(h, WEST, st(Items.COBBLESTONE, 10), st(Items.COBBLESTONE, 20), st(Items.IRON_INGOT, 5), st(Items.DIRT, 3));
		chest(h, EAST, st(Items.COBBLESTONE, 30), st(Items.DIRT, 7), st(Items.IRON_INGOT, 3), st(Items.IRON_INGOT, 60));
		chest(h, NORTH, st(Items.DIRT, 5), st(Items.DIRT, 6), st(Items.COBBLESTONE, 4));
		chest(h, SOUTH);
		network(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		int[][] before = new int[1][];
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(terminalTotal(te, Items.COBBLESTONE), 64L, "terminal cobblestone");
			eq(terminalTotal(te, Items.IRON_INGOT), 68L, "terminal iron");
		})
		.thenExecute(() -> {
			before[0] = totals(h);
			eq(usedSlots(h, CHESTS), 11, "slots used before");
			start(h);
			eq(TidyManager.start(connector(h), null), TidyManager.Result.ALREADY_RUNNING, "second start");
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			TidyManager.Summary j = finished(h);
			eq(j.end(), TidyJob.End.FINISHED, "end");
			assertTotals(h, before[0], "after tidy");
			// Every item type sits in exactly one chest (its home), in as few slots as possible
			for (Item it : new Item[] {Items.COBBLESTONE, Items.IRON_INGOT, Items.DIRT}) {
				int holders = 0;
				for (BlockPos p : CHESTS)if (inChests(h, it, p) > 0)holders++;
				eq(holders, 1, "chests holding " + it);
			}
			eq(inChests(h, Items.IRON_INGOT, EAST), 68, "iron home is the chest that held the most");
			eq(inChests(h, Items.DIRT, NORTH), 21, "dirt home is the chest that held the most");
			eq(usedSlots(h, CHESTS), 4, "slots used after: cobblestone 1, iron 2, dirt 1");
			eq(j.freed(), 7, "slots freed as reported");
			eq(j.noRoom(), 0L, "no room problems");
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void dockedShulkerBoxIsLeftAlone(GameTestHelper h) {
		h.setBlock(WEST, Content.shulkerDock.get());
		ItemStack box = ShulkerDockChecks.box(Items.BLUE_SHULKER_BOX, st(Items.COBBLESTONE, 10), st(Items.COBBLESTONE, 20), st(Items.DIRT, 1));
		ShulkerDockBlockEntity dock = be(h, WEST);
		h.assertTrue(dock.dock(box), "box not docked");
		chest(h, EAST, st(Items.COBBLESTONE, 5), st(Items.DIRT, 2));
		chest(h, NORTH, st(Items.COBBLESTONE, 7), st(Items.COBBLESTONE, 1));
		network(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		List<List<ItemStack>> dockBefore = new ArrayList<>();
		h.startSequence()
		.thenWaitUntil(() -> eq(terminalTotal(te, Items.COBBLESTONE), 43L, "terminal cobblestone (box included)"))
		.thenExecute(() -> {
			dockBefore.add(contents(dock.getInv()));
			start(h);
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			assertSame(h, contents(dock.getInv()), dockBefore.get(0), "docked box");
			eq(inChests(h, Items.COBBLESTONE, NORTH), 13, "loose cobblestone went home, the box kept its own");
			eq(inChests(h, Items.COBBLESTONE, EAST), 0, "stray cobblestone left the east chest");
			eq(usedSlots(container(h, NORTH)), 1, "merged in the home");
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void filingCabinetIsLeftAlone(GameTestHelper h) {
		h.setBlock(WEST, Content.filingCabinet.get());
		FilingCabinetBlockEntity cab = be(h, WEST);
		cab.getInv().setItem(0, st(Items.COBBLESTONE, 30));
		cab.getInv().setItem(1, st(Items.IRON_INGOT, 2));
		chest(h, EAST, st(Items.COBBLESTONE, 5), st(Items.IRON_INGOT, 1));
		chest(h, NORTH, st(Items.COBBLESTONE, 7), st(Items.COBBLESTONE, 1));
		network(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		List<List<ItemStack>> cabBefore = new ArrayList<>();
		h.startSequence()
		.thenWaitUntil(() -> eq(terminalTotal(te, Items.COBBLESTONE), 43L, "terminal cobblestone (cabinet included)"))
		.thenExecute(() -> {
			cabBefore.add(contents(cab.getInv()));
			start(h);
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			assertSame(h, contents(cab.getInv()), cabBefore.get(0), "filing cabinet");
			eq(inChests(h, Items.COBBLESTONE, NORTH), 13, "cobblestone home");
			eq(inChests(h, Items.IRON_INGOT, EAST), 1, "iron stays, the cabinet is no home");
		})
		.thenSucceed();
	}

	/** A Configurator allow-list makes its chest the home, a keep-last chest is never emptied or merged. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void allowListChestIsHomeKeepLastStaysPut(GameTestHelper h) {
		chest(h, WEST, st(Items.IRON_INGOT, 5));
		chest(h, EAST, st(Items.IRON_INGOT, 30), st(Items.IRON_INGOT, 20));
		chest(h, NORTH, st(Items.COBBLESTONE, 10), st(Items.COBBLESTONE, 5));
		chest(h, SOUTH, st(Items.COBBLESTONE, 20));
		BlockFilter allow = BlockFilter.findBlockFilterAt(h.getLevel(), h.absolutePos(WEST));
		h.assertTrue(allow != null, "no block filter could be created");
		allow.filter.setItem(0, itemFilter(Items.IRON_INGOT));
		BlockFilter keep = BlockFilter.findBlockFilterAt(h.getLevel(), h.absolutePos(NORTH));
		h.assertTrue(keep != null, "no block filter could be created");
		keep.setKeepLast(true);
		network(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		int[][] before = new int[1][];
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(terminalTotal(te, Items.IRON_INGOT), 55L, "terminal iron");
			// keep-last: one item of every stack stays out of the listing
			eq(terminalTotal(te, Items.COBBLESTONE), 33L, "terminal cobblestone (the keep-last chest shows one less per stack)");
		})
		.thenExecute(() -> {
			before[0] = totals(h);
			start(h);
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			eq(finished(h).end(), TidyJob.End.FINISHED, "end");
			assertTotals(h, before[0], "after tidy");
			eq(inChests(h, Items.IRON_INGOT, WEST), 55, "the allow-list chest is the iron home although the east chest held more");
			eq(inChests(h, Items.IRON_INGOT, EAST), 0, "iron left the east chest");
			eq(inChests(h, Items.COBBLESTONE, NORTH), 15, "nothing taken out of the keep-last chest");
			eq(usedSlots(container(h, NORTH)), 2, "nor merged in it");
			eq(inChests(h, Items.COBBLESTONE, SOUTH), 20, "cobblestone of the other chest stays");
		})
		.thenSucceed();
	}

	/** Its own batch: spawns entities (glow frame). */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = "tidy_label")
	public void labelledChestReceivesItsItems(GameTestHelper h) {
		chest(h, WEST, st(Items.DIRT, 1));
		chest(h, EAST, st(Items.IRON_INGOT, 20), st(Items.IRON_INGOT, 30), st(Items.COBBLESTONE, 5));
		chest(h, NORTH, st(Items.IRON_INGOT, 10), st(Items.DIRT, 4));
		network(h);
		FrameLabelGameTests.glowFrame(h, new BlockPos(1, 2, 3), Direction.WEST, st(Items.IRON_INGOT, 1));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		int[][] before = new int[1][];
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(connector(h).getNetworkStorage().getStorages().stream().anyMatch(a -> a instanceof FrameLabel.Labelled), "label not applied");
			eq(terminalTotal(te, Items.IRON_INGOT), 60L, "terminal iron");
		})
		.thenExecute(() -> {
			before[0] = totals(h);
			start(h);
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			assertTotals(h, before[0], "after tidy");
			eq(inChests(h, Items.IRON_INGOT, WEST), 60, "all iron in the labelled chest");
			eq(inChests(h, Items.IRON_INGOT, EAST, NORTH), 0, "no iron elsewhere");
			eq(inChests(h, Items.DIRT, WEST), 0, "the dirt put in by hand leaves: a label does not stop extraction, and it refuses dirt");
			eq(inChests(h, Items.DIRT, NORTH), 5, "dirt home");
			eq(inChests(h, Items.COBBLESTONE, EAST), 5, "cobblestone stays");
		})
		.thenSucceed();
	}

	/** Its own batch: one move per tick (server-wide option) and item entities are counted. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = "tidy_break")
	public void chestBrokenMidRunLosesNothing(GameTestHelper h) {
		messyChests(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		int movesPerTick = Config.get().tidyMovesPerTick;
		int[][] before = new int[1][];
		h.startSequence()
		.thenWaitUntil(() -> eq(terminalTotal(te, Items.COBBLESTONE), 68L, "terminal cobblestone"))
		.thenExecute(() -> {
			Config.get().tidyMovesPerTick = 1;
			before[0] = totals(h);
			start(h);
		})
		.thenWaitUntil(() -> {
			TidyJob j = running(h);
			h.assertTrue(j != null && j.phase() == TidyJob.Phase.EXECUTE && j.processed() >= 4, "tidy not far enough yet");
		})
		.thenExecute(() -> {
			h.assertTrue(running(h).processed() < running(h).planned(), "the run must still have moves left");
			h.destroyBlock(EAST);
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			Config.get().tidyMovesPerTick = movesPerTick;
			eq(finished(h).end(), TidyJob.End.FINISHED, "end");
			// The broken chest's items lie on the ground, everything else is in the chests
			assertTotals(h, before[0], "after tidy with a chest broken mid-run");
		})
		.thenSucceed();
	}

	/**
	 * Its own batch: one move per tick (server-wide option) and item entities are counted. The network
	 * lists a chest for a few ticks after it was replaced, its old inventory must not be written to.
	 */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = "tidy_break")
	public void chestReplacedMidRunLosesNothing(GameTestHelper h) {
		messyChests(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		int movesPerTick = Config.get().tidyMovesPerTick;
		int[][] before = new int[1][];
		h.startSequence()
		.thenWaitUntil(() -> eq(terminalTotal(te, Items.COBBLESTONE), 68L, "terminal cobblestone"))
		.thenExecute(() -> {
			Config.get().tidyMovesPerTick = 1;
			before[0] = totals(h);
			start(h);
		})
		.thenWaitUntil(() -> {
			TidyJob j = running(h);
			h.assertTrue(j != null && j.phase() == TidyJob.Phase.EXECUTE && j.processed() >= 4, "tidy not far enough yet");
		})
		.thenExecute(() -> {
			h.assertTrue(running(h).processed() < running(h).planned(), "the run must still have moves left");
			h.setBlock(NORTH, Blocks.BARREL);
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			Config.get().tidyMovesPerTick = movesPerTick;
			eq(finished(h).end(), TidyJob.End.FINISHED, "end");
			assertTotals(h, before[0], "after tidy with a chest replaced mid-run");
		})
		.thenSucceed();
	}

	/** Its own batch: one move per tick (server-wide option). */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = "tidy_stop")
	public void stopMidRunKeepsEverything(GameTestHelper h) {
		messyChests(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		int movesPerTick = Config.get().tidyMovesPerTick;
		int[][] before = new int[1][];
		List<List<ItemStack>> afterStop = new ArrayList<>();
		h.startSequence()
		.thenWaitUntil(() -> eq(terminalTotal(te, Items.COBBLESTONE), 68L, "terminal cobblestone"))
		.thenExecute(() -> {
			Config.get().tidyMovesPerTick = 1;
			before[0] = totals(h);
			start(h);
		})
		.thenWaitUntil(() -> {
			TidyJob j = running(h);
			h.assertTrue(j != null && j.phase() == TidyJob.Phase.EXECUTE && j.done() >= 3, "tidy not far enough yet");
		})
		.thenExecute(() -> {
			// The Network Doctor shows the run
			DoctorGameTests.Run at = DoctorGameTests.run(h, "obscuras_storage doctor at " + DoctorGameTests.abs(h, CONNECTOR));
			h.assertTrue(at.text().contains("<chat.obscuras_storage.doctor.at.tidy.running>"), "doctor at shows the running tidy: " + at.text());
			int planned = running(h).planned();
			eq(TidyManager.stop(stats(h), null), TidyManager.Result.STOPPED, "stop");
			TidyManager.Summary j = finished(h);
			eq(j.end(), TidyJob.End.STOPPED, "end");
			h.assertTrue(j.done() >= 3 && j.processed() < planned, "stopped half way: " + j.done() + " of " + planned);
			assertTotals(h, before[0], "right after stop");
			for (BlockPos p : CHESTS)afterStop.add(contents(container(h, p)));
			eq(TidyManager.stop(stats(h), null), TidyManager.Result.NOT_RUNNING, "second stop");
		})
		.thenIdle(10)
		.thenExecute(() -> {
			Config.get().tidyMovesPerTick = movesPerTick;
			for (int i = 0; i < CHESTS.length; i++)assertSame(h, contents(container(h, CHESTS[i])), afterStop.get(i), "chest " + CHESTS[i] + " after stop");
			assertTotals(h, before[0], "after stop");
		})
		.thenSucceed();
	}

	/** Its own batch: switches server-wide options. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = "tidy_config")
	public void cooldownSwitchAndPermissions(GameTestHelper h) {
		chest(h, WEST, st(Items.COBBLESTONE, 10), st(Items.COBBLESTONE, 20));
		chest(h, EAST, st(Items.COBBLESTONE, 5));
		network(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		ServerPlayer player = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "tidy-test"));
		h.startSequence()
		.thenWaitUntil(() -> eq(terminalTotal(te, Items.COBBLESTONE), 35L, "terminal cobblestone"))
		.thenExecute(() -> {
			TidyManager.resetMemory(stats(h));
			Config.get().tidyEnabled = false;
			try {
				eq(TidyManager.start(connector(h), null), TidyManager.Result.DISABLED, "switched off");
			} finally {
				Config.get().tidyEnabled = true;
			}
			Config.get().tidyOpsOnly = true;
			try {
				eq(TidyManager.start(connector(h), player), TidyManager.Result.NOT_ALLOWED, "operators only, not an operator");
			} finally {
				Config.get().tidyOpsOnly = false;
			}
			eq(TidyManager.start(connector(h), player), TidyManager.Result.STARTED, "everyone may use it");
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			eq(finished(h).end(), TidyJob.End.FINISHED, "first run");
			eq(inChests(h, Items.COBBLESTONE, WEST), 35, "tidied");
			eq(TidyManager.start(connector(h), null), TidyManager.Result.COOLDOWN, "cooldown after a run");
			// Without the cooldown: the network is tidy now
			TidyManager.resetMemory(stats(h));
			eq(TidyManager.start(connector(h), null), TidyManager.Result.STARTED, "second run");
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			eq(finished(h).end(), TidyJob.End.NOTHING, "nothing left to do");
			eq(TidyManager.start(connector(h), null), TidyManager.Result.NOTHING, "remembered: nothing to tidy");
			// A change on the network makes it worth a look again
			container(h, EAST).setItem(0, st(Items.COBBLESTONE, 3));
			container(h, EAST).setChanged();
		})
		.thenWaitUntil(() -> eq(terminalTotal(te, Items.COBBLESTONE), 38L, "terminal sees the change"))
		.thenExecute(() -> eq(TidyManager.start(connector(h), null), TidyManager.Result.STARTED, "after a change"))
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> eq(inChests(h, Items.COBBLESTONE, WEST), 38, "tidied again"))
		.thenSucceed();
	}

	/** Its own batch: two moves per tick (server-wide option). */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = "tidy_rate")
	public void movesPerTickIsRespected(GameTestHelper h) {
		messyChests(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		int movesPerTick = Config.get().tidyMovesPerTick;
		int[][] before = new int[1][];
		long[] ticks = new long[2];
		h.startSequence()
		.thenWaitUntil(() -> eq(terminalTotal(te, Items.COBBLESTONE), 68L, "terminal cobblestone"))
		.thenExecute(() -> {
			Config.get().tidyMovesPerTick = 2;
			before[0] = totals(h);
			start(h);
		})
		.thenWaitUntil(() -> {
			TidyJob j = running(h);
			h.assertTrue(j != null && j.phase() == TidyJob.Phase.EXECUTE, "not executing yet");
			ticks[0] = h.getTick();
		})
		.thenWaitUntil(() -> {
			finished(h);
			ticks[1] = h.getTick();
		})
		.thenExecute(() -> {
			Config.get().tidyMovesPerTick = movesPerTick;
			TidyManager.Summary j = finished(h);
			eq(j.end(), TidyJob.End.FINISHED, "end");
			h.assertTrue(j.planned() >= 12, "enough moves to measure: " + j.planned());
			eq(j.maxMovesInTick(), 2, "at most (and at full speed exactly) 2 moves per tick");
			h.assertTrue(ticks[1] - ticks[0] >= (j.done() + 1) / 2 - 1, "took at least done/2 ticks: " + (ticks[1] - ticks[0]) + " for " + j.done());
			assertTotals(h, before[0], "after tidy");
		})
		.thenSucceed();
	}
}
