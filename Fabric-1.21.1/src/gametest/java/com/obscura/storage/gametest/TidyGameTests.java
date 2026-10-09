package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.AABB;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.block.entity.FilingCabinetBlockEntity;
import com.obscura.storage.block.entity.InventoryConnectorBlockEntity;
import com.obscura.storage.block.entity.ShulkerDockBlockEntity;
import com.obscura.storage.block.entity.StorageTerminalBlockEntity;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.tidy.TidyJob;
import com.obscura.storage.tidy.TidyManager;

/**
 * Tidy in a real world: consolidation, item counts before and after, Frame Labels, docked shulker
 * boxes and Filing Cabinets left alone, a chest broken mid-run, stopping, cooldown, config switches
 * and the moves-per-tick limit. Tests that change a server-wide option or count item entities run
 * in batches of their own.
 */
public class TidyGameTests implements FabricGameTest {
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
		return h.getBlockEntity(CONNECTOR);
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
		h.assertValueEqual(a.size(), b.size(), what + " size");
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
		return te.getStacks().values().stream().filter(s -> s.getStack().is(item)).mapToLong(StoredItemStack::getQuantity).sum();
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
		for (int i = 0; i < ITEMS.length; i++)h.assertValueEqual(now[i], before[i], when + ": total " + ITEMS[i]);
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
		h.assertValueEqual(TidyManager.start(connector(h), null), TidyManager.Result.STARTED, "start");
	}

	// ---- tests ----

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
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
			h.assertValueEqual(terminalTotal(te, Items.COBBLESTONE), 64L, "terminal cobblestone");
			h.assertValueEqual(terminalTotal(te, Items.IRON_INGOT), 68L, "terminal iron");
		})
		.thenExecute(() -> {
			before[0] = totals(h);
			h.assertValueEqual(usedSlots(h, CHESTS), 11, "slots used before");
			start(h);
			h.assertValueEqual(TidyManager.start(connector(h), null), TidyManager.Result.ALREADY_RUNNING, "second start");
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			TidyManager.Summary j = finished(h);
			h.assertValueEqual(j.end(), TidyJob.End.FINISHED, "end");
			assertTotals(h, before[0], "after tidy");
			// Every item type sits in exactly one chest (its home), in as few slots as possible
			for (Item it : new Item[] {Items.COBBLESTONE, Items.IRON_INGOT, Items.DIRT}) {
				int holders = 0;
				for (BlockPos p : CHESTS)if (inChests(h, it, p) > 0)holders++;
				h.assertValueEqual(holders, 1, "chests holding " + it);
			}
			h.assertValueEqual(inChests(h, Items.IRON_INGOT, EAST), 68, "iron home is the chest that held the most");
			h.assertValueEqual(inChests(h, Items.DIRT, NORTH), 21, "dirt home is the chest that held the most");
			h.assertValueEqual(usedSlots(h, CHESTS), 4, "slots used after: cobblestone 1, iron 2, dirt 1");
			h.assertValueEqual(j.freed(), 7, "slots freed as reported");
			h.assertValueEqual(j.noRoom(), 0L, "no room problems");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void dockedShulkerBoxIsLeftAlone(GameTestHelper h) {
		h.setBlock(WEST, Content.shulkerDock.get());
		ItemStack box = new ItemStack(Items.BLUE_SHULKER_BOX);
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(st(Items.COBBLESTONE, 10), st(Items.COBBLESTONE, 20), st(Items.DIRT, 1))));
		ShulkerDockBlockEntity dock = h.getBlockEntity(WEST);
		h.assertTrue(dock.dock(box), "box not docked");
		chest(h, EAST, st(Items.COBBLESTONE, 5), st(Items.DIRT, 2));
		chest(h, NORTH, st(Items.COBBLESTONE, 7), st(Items.COBBLESTONE, 1));
		network(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		List<List<ItemStack>> dockBefore = new ArrayList<>();
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalTotal(te, Items.COBBLESTONE), 43L, "terminal cobblestone (box included)"))
		.thenExecute(() -> {
			dockBefore.add(contents(dock.getInv()));
			start(h);
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			assertSame(h, contents(dock.getInv()), dockBefore.get(0), "docked box");
			h.assertValueEqual(inChests(h, Items.COBBLESTONE, NORTH), 13, "loose cobblestone went home, the box kept its own");
			h.assertValueEqual(inChests(h, Items.COBBLESTONE, EAST), 0, "stray cobblestone left the east chest");
			h.assertValueEqual(usedSlots(container(h, NORTH)), 1, "merged in the home");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void filingCabinetIsLeftAlone(GameTestHelper h) {
		h.setBlock(WEST, Content.filingCabinet.get());
		FilingCabinetBlockEntity cab = h.getBlockEntity(WEST);
		cab.getInv().setItem(0, st(Items.COBBLESTONE, 30));
		cab.getInv().setItem(1, st(Items.IRON_INGOT, 2));
		chest(h, EAST, st(Items.COBBLESTONE, 5), st(Items.IRON_INGOT, 1));
		chest(h, NORTH, st(Items.COBBLESTONE, 7), st(Items.COBBLESTONE, 1));
		network(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		List<List<ItemStack>> cabBefore = new ArrayList<>();
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalTotal(te, Items.COBBLESTONE), 43L, "terminal cobblestone (cabinet included)"))
		.thenExecute(() -> {
			cabBefore.add(contents(cab.getInv()));
			start(h);
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			assertSame(h, contents(cab.getInv()), cabBefore.get(0), "filing cabinet");
			h.assertValueEqual(inChests(h, Items.COBBLESTONE, NORTH), 13, "cobblestone home");
			h.assertValueEqual(inChests(h, Items.IRON_INGOT, EAST), 1, "iron stays, the cabinet is no home");
		})
		.thenSucceed();
	}

	/** Its own batch: spawns entities (glow frame). */
	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300, batch = "tidy_label")
	public void labelledChestReceivesItsItems(GameTestHelper h) {
		chest(h, WEST, st(Items.DIRT, 1));
		chest(h, EAST, st(Items.IRON_INGOT, 20), st(Items.IRON_INGOT, 30), st(Items.COBBLESTONE, 5));
		chest(h, NORTH, st(Items.IRON_INGOT, 10));
		network(h);
		FrameLabelGameTests.glowFrame(h, new BlockPos(1, 2, 3), Direction.WEST, st(Items.IRON_INGOT, 1));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		int[][] before = new int[1][];
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(connector(h).getNetworkHandler().getConnected().stream().anyMatch(a -> a instanceof com.obscura.storage.inventory.FrameLabel.Labelled), "label not applied");
			h.assertValueEqual(terminalTotal(te, Items.IRON_INGOT), 60L, "terminal iron");
		})
		.thenExecute(() -> {
			before[0] = totals(h);
			start(h);
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			assertTotals(h, before[0], "after tidy");
			h.assertValueEqual(inChests(h, Items.IRON_INGOT, WEST), 60, "all iron in the labelled chest");
			h.assertValueEqual(inChests(h, Items.IRON_INGOT, EAST, NORTH), 0, "no iron elsewhere");
			h.assertValueEqual(inChests(h, Items.DIRT, WEST), 1, "the dirt put in by hand stays (its own home)");
			h.assertValueEqual(inChests(h, Items.COBBLESTONE, EAST), 5, "cobblestone stays");
		})
		.thenSucceed();
	}

	/** Its own batch: one move per tick (server-wide option) and item entities are counted. */
	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400, batch = "tidy_break")
	public void chestBrokenMidRunLosesNothing(GameTestHelper h) {
		messyChests(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		int movesPerTick = Config.get().tidyMovesPerTick;
		int[][] before = new int[1][];
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalTotal(te, Items.COBBLESTONE), 68L, "terminal cobblestone"))
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
			h.assertValueEqual(finished(h).end(), TidyJob.End.FINISHED, "end");
			// The broken chest's items lie on the ground, everything else is in the chests
			assertTotals(h, before[0], "after tidy with a chest broken mid-run");
		})
		.thenSucceed();
	}

	/** Its own batch: one move per tick (server-wide option). */
	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400, batch = "tidy_stop")
	public void stopMidRunKeepsEverything(GameTestHelper h) {
		messyChests(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		int movesPerTick = Config.get().tidyMovesPerTick;
		int[][] before = new int[1][];
		List<List<ItemStack>> afterStop = new ArrayList<>();
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalTotal(te, Items.COBBLESTONE), 68L, "terminal cobblestone"))
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
			h.assertValueEqual(TidyManager.stop(stats(h), null), TidyManager.Result.STOPPED, "stop");
			TidyManager.Summary j = finished(h);
			h.assertValueEqual(j.end(), TidyJob.End.STOPPED, "end");
			h.assertTrue(j.done() >= 3 && j.processed() < planned, "stopped half way: " + j.done() + " of " + planned);
			assertTotals(h, before[0], "right after stop");
			for (BlockPos p : CHESTS)afterStop.add(contents(container(h, p)));
			h.assertValueEqual(TidyManager.stop(stats(h), null), TidyManager.Result.NOT_RUNNING, "second stop");
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
	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400, batch = "tidy_config")
	public void cooldownSwitchAndPermissions(GameTestHelper h) {
		chest(h, WEST, st(Items.COBBLESTONE, 10), st(Items.COBBLESTONE, 20));
		chest(h, EAST, st(Items.COBBLESTONE, 5));
		network(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		ServerPlayer player = FakePlayer.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "tidy-test"));
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalTotal(te, Items.COBBLESTONE), 35L, "terminal cobblestone"))
		.thenExecute(() -> {
			TidyManager.resetMemory(stats(h));
			Config.get().tidyEnabled = false;
			try {
				h.assertValueEqual(TidyManager.start(connector(h), null), TidyManager.Result.DISABLED, "switched off");
			} finally {
				Config.get().tidyEnabled = true;
			}
			Config.get().tidyOpsOnly = true;
			try {
				h.assertValueEqual(TidyManager.start(connector(h), player), TidyManager.Result.NOT_ALLOWED, "operators only, not an operator");
			} finally {
				Config.get().tidyOpsOnly = false;
			}
			h.assertValueEqual(TidyManager.start(connector(h), player), TidyManager.Result.STARTED, "everyone may use it");
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			h.assertValueEqual(finished(h).end(), TidyJob.End.FINISHED, "first run");
			h.assertValueEqual(inChests(h, Items.COBBLESTONE, WEST), 35, "tidied");
			h.assertValueEqual(TidyManager.start(connector(h), null), TidyManager.Result.COOLDOWN, "cooldown after a run");
			// Without the cooldown: the network is tidy now
			TidyManager.resetMemory(stats(h));
			h.assertValueEqual(TidyManager.start(connector(h), null), TidyManager.Result.STARTED, "second run");
		})
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> {
			h.assertValueEqual(finished(h).end(), TidyJob.End.NOTHING, "nothing left to do");
			h.assertValueEqual(TidyManager.start(connector(h), null), TidyManager.Result.NOTHING, "remembered: nothing to tidy");
			// A change on the network makes it worth a look again
			container(h, EAST).setItem(0, st(Items.COBBLESTONE, 3));
			container(h, EAST).setChanged();
		})
		.thenWaitUntil(() -> h.assertValueEqual(terminalTotal(te, Items.COBBLESTONE), 38L, "terminal sees the change"))
		.thenExecute(() -> h.assertValueEqual(TidyManager.start(connector(h), null), TidyManager.Result.STARTED, "after a change"))
		.thenWaitUntil(() -> finished(h))
		.thenExecute(() -> h.assertValueEqual(inChests(h, Items.COBBLESTONE, WEST), 38, "tidied again"))
		.thenSucceed();
	}

	/** Its own batch: two moves per tick (server-wide option). */
	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400, batch = "tidy_rate")
	public void movesPerTickIsRespected(GameTestHelper h) {
		messyChests(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		int movesPerTick = Config.get().tidyMovesPerTick;
		int[][] before = new int[1][];
		long[] ticks = new long[2];
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalTotal(te, Items.COBBLESTONE), 68L, "terminal cobblestone"))
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
			h.assertValueEqual(j.end(), TidyJob.End.FINISHED, "end");
			h.assertTrue(j.planned() >= 12, "enough moves to measure: " + j.planned());
			h.assertValueEqual(j.maxMovesInTick(), 2, "at most (and at full speed exactly) 2 moves per tick");
			h.assertTrue(ticks[1] - ticks[0] >= (j.done() + 1) / 2 - 1, "took at least done/2 ticks: " + (ticks[1] - ticks[0]) + " for " + j.done());
			assertTotals(h, before[0], "after tidy");
		})
		.thenSucceed();
	}
}
