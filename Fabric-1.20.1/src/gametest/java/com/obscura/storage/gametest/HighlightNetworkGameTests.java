package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.gui.StorageTerminalMenu;
import com.obscura.storage.item.BoundTerminal;
import com.obscura.storage.network.LocateResultPacket;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.HighlightNetwork;
import com.obscura.storage.util.ItemLocator;

/**
 * Highlight Connected Inventories: every inventory of a network (empty ones too, a double chest once, chests behind
 * cable connectors), the key's server side (reach, cable and terminal resolve to the same network, the wireless
 * terminal in hand) and the reply packet's size limits.
 */
public class HighlightNetworkGameTests implements FabricGameTest {
	// Connector with the terminal on top, a chest north of it and a double chest south of it. Cables run east to a
	// cable connector facing a chest, a second cable connector on the middle cable faces an empty chest.
	private static final BlockPos CONNECTOR = new BlockPos(1, 2, 3);
	private static final BlockPos TERMINAL = new BlockPos(1, 3, 3);
	private static final BlockPos LOCAL_CHEST = new BlockPos(1, 2, 2);
	private static final BlockPos DOUBLE_LEFT = new BlockPos(1, 2, 4);
	private static final BlockPos DOUBLE_RIGHT = new BlockPos(2, 2, 4);
	private static final BlockPos MID_CABLE = new BlockPos(3, 2, 3);
	private static final BlockPos CABLE_CONNECTOR = new BlockPos(5, 2, 3);
	private static final BlockPos REMOTE_CHEST = new BlockPos(6, 2, 3);
	private static final BlockPos SIDE_CABLE_CONNECTOR = new BlockPos(3, 2, 2);
	private static final BlockPos EMPTY_CHEST = new BlockPos(3, 2, 1);
	// A terminal of its own for the wireless range checks, on the top layer of the template. The GameTest framework builds
	// a working beacon (3x3 iron base) below the corner of every test as its status light, and a terminal looks for
	// beacons within 8 blocks: TERMINAL finds that beacon on its next look (every 40 ticks), and from then on the Advanced
	// Wireless Terminal has no range limit there. All status beacons stand on one level, 9 blocks below this terminal.
	private static final BlockPos RANGE_TERMINAL = new BlockPos(6, 8, 6);

	private static void buildNetwork(GameTestHelper h) {
		chest(h, LOCAL_CHEST, new ItemStack(Items.GOLD_INGOT, 7));
		doubleChest(h, DOUBLE_LEFT);
		fill(h, DOUBLE_LEFT, new ItemStack(Items.IRON_INGOT, 20));
		fill(h, DOUBLE_RIGHT, new ItemStack(Items.IRON_INGOT, 30));
		chest(h, REMOTE_CHEST, new ItemStack(Items.DIAMOND, 11));
		chest(h, EMPTY_CHEST);
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
		h.setBlock(CABLE_CONNECTOR, Content.invCableConnector.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING, Direction.EAST));
		h.setBlock(SIDE_CABLE_CONNECTOR, Content.invCableConnector.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING, Direction.NORTH));
		for (int x = 2; x <= 4; x++)place(h, new BlockPos(x, 2, 3), Content.invCable.get());
		refreshShape(h, CABLE_CONNECTOR);
		refreshShape(h, SIDE_CABLE_CONNECTOR);
	}

	/** The block positions of a search result. */
	private static Set<BlockPos> positions(ItemLocator.SearchResult res) {
		Set<BlockPos> out = new HashSet<>();
		for (ItemLocator.Location l : res.located())out.add(l.pos());
		return out;
	}

	/** One of the two halves of the double chest, whichever the network remembered. */
	private static void expectNetwork(GameTestHelper h, Set<BlockPos> got, String what) {
		boolean left = got.contains(h.absolutePos(DOUBLE_LEFT)), right = got.contains(h.absolutePos(DOUBLE_RIGHT));
		h.assertTrue(left ^ right, what + ": the double chest must be listed exactly once: " + got);
		Set<BlockPos> rest = new HashSet<>(got);
		rest.remove(h.absolutePos(left ? DOUBLE_LEFT : DOUBLE_RIGHT));
		eq(rest, Set.of(h.absolutePos(LOCAL_CHEST), h.absolutePos(REMOTE_CHEST), h.absolutePos(EMPTY_CHEST)), what + " (without the double chest)");
	}

	private static FakePlayer player(GameTestHelper h, double x, double y, double z) {
		FakePlayer p = FakePlayer.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "highlight-test"));
		p.moveTo(x, y, z, 0, 0);
		p.getInventory().clearContent();
		return p;
	}

	/** A player whose eyes are {@code dist} blocks east of the middle of {@code rel}'s block. */
	private static FakePlayer playerAt(GameTestHelper h, BlockPos rel, double dist) {
		BlockPos abs = h.absolutePos(rel);
		return player(h, abs.getX() + 0.5 + dist, abs.getY() + 0.5 - 1.62, abs.getZ() + 0.5);
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void locateAllFindsEveryInventory(GameTestHelper h) {
		buildNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(positions(ItemLocator.searchAll(te, h.getLevel())).size(), 4, "inventories found so far");
			eq(terminalCount(te, Items.DIAMOND), 11L, "remote chest visible through the cables");
		})
		.thenExecute(() -> {
			ServerLevel level = h.getLevel();
			ItemLocator.SearchResult all = ItemLocator.searchAll(te, level);
			eq(all.unknown(), 0, "inventories without a position");
			eq(all.located().size(), 4, "located inventories (4 chests, double chest once, empty chest included)");
			expectNetwork(h, positions(all), "terminal");
			for (ItemLocator.Location l : all.located())eq(l.dimension(), level.dimension(), "dimension of " + l.pos());

			// Same set from the connector the terminal sits on
			InventoryConnectorBlockEntity connector = be(h, CONNECTOR);
			expectNetwork(h, positions(ItemLocator.searchAll(connector)), "connector");

			// What the client is sent: all four, nearest to the player first, none cut off
			ItemLocator.Selection sel = ItemLocator.select(all.located(), level.dimension(), h.absolutePos(TERMINAL), ItemLocator.MAX_ALL_POSITIONS);
			eq(sel.positions().size(), 4, "positions sent");
			eq(sel.inDimension(), 4, "in dimension");
			eq(sel.otherDimensions(), 0, "other dimensions");
			double prev = -1;
			for (BlockPos p : sel.positions()) {
				double d = p.distSqr(h.absolutePos(TERMINAL));
				h.assertTrue(d >= prev, "positions must be nearest first: " + sel.positions());
				prev = d;
			}

			// The item locate is unchanged: only inventories that hold the item
			eq(positions(ItemLocator.search(te, level, ItemVariant.of(Items.GOLD_INGOT))), Set.of(h.absolutePos(LOCAL_CHEST)), "gold locations");
			eq(positions(ItemLocator.search(te, level, ItemVariant.of(Items.DIAMOND))), Set.of(h.absolutePos(REMOTE_CHEST)), "diamond locations");
			Set<BlockPos> iron = positions(ItemLocator.search(te, level, ItemVariant.of(Items.IRON_INGOT)));
			eq(iron.size(), 1, "iron locations (double chest once): " + iron);
			eq(ItemLocator.search(te, level, ItemVariant.of(Items.EMERALD)).located().size(), 0, "emerald locations");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void locateAllWithoutInventories(GameTestHelper h) {
		// A terminal with nothing behind it, and a connector network that has no inventory
		BlockPos aloneTerminal = new BlockPos(6, 3, 6);
		terminalAbove(h, aloneTerminal);
		BlockPos emptyConnector = new BlockPos(2, 2, 2);
		BlockPos emptyTerminal = new BlockPos(2, 3, 2);
		h.setBlock(emptyConnector, Content.connector.get());
		terminalAbove(h, emptyTerminal);
		StorageTerminalBlockEntity alone = terminal(h, aloneTerminal);
		StorageTerminalBlockEntity onEmpty = terminal(h, emptyTerminal);
		h.startSequence()
		.thenWaitUntil(() -> h.assertTrue(onEmpty.getItemHandler() != null, "the connector network is not ready"))
		.thenExecute(() -> {
			ItemLocator.SearchResult r = ItemLocator.searchAll(alone, h.getLevel());
			eq(r.located().size() + r.unknown(), 0, "terminal without storage");
			r = ItemLocator.searchAll(onEmpty, h.getLevel());
			eq(r.located().size() + r.unknown(), 0, "terminal on a network without inventories");
			InventoryConnectorBlockEntity c = be(h, emptyConnector);
			r = ItemLocator.searchAll(c);
			eq(r.located().size() + r.unknown(), 0, "connector without inventories");
			ItemLocator.Selection sel = ItemLocator.select(r.located(), h.getLevel().dimension(), h.absolutePos(emptyTerminal), ItemLocator.MAX_ALL_POSITIONS);
			eq(sel.positions().size() + sel.inDimension() + sel.otherDimensions(), 0, "selection of nothing");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void highlightRequestResolvesNetwork(GameTestHelper h) {
		buildNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> eq(positions(ItemLocator.searchAll(te, h.getLevel())).size(), 4, "network ready"))
		.thenExecute(() -> {
			ServerLevel level = h.getLevel();
			FakePlayer p = playerAt(h, TERMINAL, 3);

			// A terminal is its own source, a cable, connector and cable connectors lead to the connector
			BlockEntity viaTerminal = HighlightNetwork.resolve(p, h.absolutePos(TERMINAL));
			h.assertTrue(viaTerminal == te, "the terminal must resolve to itself, got " + viaTerminal);
			Set<BlockPos> expected = positions(ItemLocator.searchAll(te, level));
			expectNetwork(h, expected, "terminal network");
			for (BlockPos rel : List.of(MID_CABLE, CONNECTOR, CABLE_CONNECTOR, SIDE_CABLE_CONNECTOR)) {
				BlockEntity src = HighlightNetwork.resolve(p, h.absolutePos(rel));
				h.assertTrue(src instanceof InventoryConnectorBlockEntity, rel + " must resolve to the connector, got " + src);
				h.assertTrue(src.getBlockPos().equals(h.absolutePos(CONNECTOR)), rel + " resolved to " + src.getBlockPos());
				eq(positions(ItemLocator.searchAll((InventoryConnectorBlockEntity) src)), expected, "network via " + rel);
			}

			// Inventories and empty air are not network blocks
			h.assertTrue(HighlightNetwork.resolve(p, h.absolutePos(LOCAL_CHEST)) == null, "a chest must not resolve");
			h.assertTrue(HighlightNetwork.resolve(p, h.absolutePos(new BlockPos(6, 5, 6))) == null, "air must not resolve");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void highlightRequestChecksReach(GameTestHelper h) {
		buildNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> eq(positions(ItemLocator.searchAll(te, h.getLevel())).size(), 4, "network ready"))
		.thenExecute(() -> {
			BlockPos terminalAbs = h.absolutePos(TERMINAL), cableAbs = h.absolutePos(MID_CABLE);
			// Eyes 7 blocks from the block's middle: within the 8 blocks
			FakePlayer near = playerAt(h, TERMINAL, 7);
			h.assertTrue(HighlightNetwork.resolve(near, terminalAbs) == te, "7 blocks away: the terminal must resolve");
			// 9.5 blocks: too far, for a terminal and for a cable alike
			FakePlayer far = playerAt(h, TERMINAL, 9.5);
			h.assertTrue(HighlightNetwork.resolve(far, terminalAbs) == null, "9.5 blocks away: the terminal must not resolve");
			h.assertTrue(HighlightNetwork.resolve(far, h.absolutePos(CONNECTOR)) == null, "9.5 blocks away: the connector must not resolve");
			FakePlayer farCable = playerAt(h, MID_CABLE, 9.5);
			h.assertTrue(HighlightNetwork.resolve(farCable, cableAbs) == null, "9.5 blocks away: a cable must not resolve");
			h.assertTrue(HighlightNetwork.resolve(playerAt(h, MID_CABLE, 3), cableAbs) instanceof InventoryConnectorBlockEntity, "3 blocks away: the cable must resolve");
			// Far above the network: nothing, even though the position is a real terminal
			FakePlayer high = player(h, terminalAbs.getX() + 0.5, terminalAbs.getY() + 40, terminalAbs.getZ() + 0.5);
			h.assertTrue(HighlightNetwork.resolve(high, terminalAbs) == null, "40 blocks above: the terminal must not resolve");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void highlightRequestUsesWirelessTerminal(GameTestHelper h) {
		buildNetwork(h);
		terminalAbove(h, RANGE_TERMINAL);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		StorageTerminalBlockEntity rangeTe = terminal(h, RANGE_TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> eq(positions(ItemLocator.searchAll(te, h.getLevel())).size(), 4, "network ready"))
		// A terminal looks for beacons once in 40 ticks. After a full round the range checks see what it found,
		// not the "no beacon" a terminal starts with, however the test's start falls into that round.
		.thenIdle(40)
		.thenExecute(() -> {
			BlockPos terminalAbs = h.absolutePos(TERMINAL);
			FakePlayer p = playerAt(h, TERMINAL, 3);
			h.assertTrue(HighlightNetwork.resolve(p, null) == null, "no wireless terminal in hand: nothing");

			ItemStack unbound = new ItemStack(Content.wirelessTerminal.get());
			p.setItemInHand(InteractionHand.MAIN_HAND, unbound);
			h.assertTrue(HighlightNetwork.resolve(p, null) == null, "an unbound wireless terminal: nothing");

			p.setItemInHand(InteractionHand.MAIN_HAND, bound(h, Content.wirelessTerminal.get(), terminalAbs));
			h.assertTrue(HighlightNetwork.resolve(p, null) == te, "a bound wireless terminal in range leads to its terminal");

			// The off hand counts too
			p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			p.setItemInHand(InteractionHand.OFF_HAND, bound(h, Content.advWirelessTerminal.get(), terminalAbs));
			h.assertTrue(HighlightNetwork.resolve(p, null) == te, "a bound advanced wireless terminal in the off hand leads to its terminal");

			// Out of the item's range: the same rules as opening it. Asked of the terminal no beacon reaches (see
			// RANGE_TERMINAL), a beacon near the terminal lifts the limit and "out of range" would not apply.
			BlockPos rangeAbs = h.absolutePos(RANGE_TERMINAL);
			assertNoBeaconInReach(h, rangeAbs);
			// Control: in range it resolves, so the "nothing" below is the range and not a terminal nobody can reach
			FakePlayer nearRange = playerAt(h, RANGE_TERMINAL, 3);
			nearRange.setItemInHand(InteractionHand.MAIN_HAND, bound(h, Content.advWirelessTerminal.get(), rangeAbs));
			h.assertTrue(HighlightNetwork.resolve(nearRange, null) == rangeTe, "in range: the advanced wireless terminal leads to the terminal of the range checks");

			FakePlayer far = player(h, rangeAbs.getX() + 0.5, rangeAbs.getY() + Config.get().advWirelessRange + 20, rangeAbs.getZ() + 0.5);
			far.setItemInHand(InteractionHand.MAIN_HAND, bound(h, Content.advWirelessTerminal.get(), rangeAbs));
			eq(BoundTerminal.resolve(far, far.getMainHandItem()).status(), BoundTerminal.Status.OUT_OF_RANGE, "advanced wireless terminal 20 blocks beyond its range, no beacon");
			h.assertTrue(HighlightNetwork.resolve(far, null) == null, "out of range: nothing");
			FakePlayer farBasic = player(h, rangeAbs.getX() + 0.5, rangeAbs.getY() + Config.get().wirelessRange + 20, rangeAbs.getZ() + 0.5);
			farBasic.setItemInHand(InteractionHand.MAIN_HAND, bound(h, Content.wirelessTerminal.get(), rangeAbs));
			eq(BoundTerminal.resolve(farBasic, farBasic.getMainHandItem()).status(), BoundTerminal.Status.OUT_OF_RANGE, "basic wireless terminal 20 blocks beyond its range");
			h.assertTrue(HighlightNetwork.resolve(farBasic, null) == null, "basic wireless terminal out of range: nothing");
		})
		.thenSucceed();
	}

	/**
	 * Test setup of the range checks: no beacon block in the box a terminal searches for beacons (8 blocks down, west and
	 * north, 9 up, east and south). Fails with the beacon's position instead of leaving the range checks to the timing of
	 * the terminal's search.
	 */
	private static void assertNoBeaconInReach(GameTestHelper h, BlockPos terminalAbs) {
		for (BlockPos pos : BlockPos.betweenClosed(terminalAbs.offset(-8, -8, -8), terminalAbs.offset(9, 9, 9))) {
			if (h.getLevel().getBlockState(pos).is(Blocks.BEACON))h.fail("test setup: the beacon at " + pos.toShortString() + " reaches the terminal at " + terminalAbs.toShortString() + " and lifts its wireless range limit");
		}
	}

	private static ItemStack bound(GameTestHelper h, Item item, BlockPos terminalAbs) {
		ItemStack term = new ItemStack(item);
		CompoundTag tag = term.getOrCreateTag();
		tag.putInt("BindX", terminalAbs.getX());
		tag.putInt("BindY", terminalAbs.getY());
		tag.putInt("BindZ", terminalAbs.getZ());
		tag.putString("BindDim", h.getLevel().dimension().location().toString());
		return term;
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void highlightRequestGuards(GameTestHelper h) {
		buildNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> eq(positions(ItemLocator.searchAll(te, h.getLevel())).size(), 4, "network ready"))
		.thenExecute(() -> {
			BlockPos terminalAbs = h.absolutePos(TERMINAL);

			// A request is answered and starts the shared cooldown
			FakePlayer p = playerAt(h, TERMINAL, 3);
			HighlightNetwork.request(p, terminalAbs);
			h.assertTrue(!ItemLocator.tryStartRequest(p), "a request must start the cooldown it shares with the item locate");

			// The config switch turns the key off without costing the player its cooldown
			FakePlayer off = playerAt(h, TERMINAL, 3);
			boolean was = Config.get().itemLocatorEnabled;
			Config.get().itemLocatorEnabled = false;
			try {
				HighlightNetwork.request(off, terminalAbs);
			} finally {
				Config.get().itemLocatorEnabled = was;
			}
			h.assertTrue(ItemLocator.tryStartRequest(off), "with the switch off the request must be ignored before the cooldown");

			// A dead player is ignored
			FakePlayer dead = playerAt(h, TERMINAL, 3);
			dead.setHealth(0);
			HighlightNetwork.request(dead, terminalAbs);
			h.assertTrue(ItemLocator.tryStartRequest(dead), "a dead player must be ignored before the cooldown");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void terminalMessageHighlightsNetwork(GameTestHelper h) {
		buildNetwork(h);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		h.startSequence()
		.thenWaitUntil(() -> eq(positions(ItemLocator.searchAll(te, h.getLevel())).size(), 4, "network ready"))
		.thenExecute(() -> {
			FakePlayer p = playerAt(h, TERMINAL, 3);
			Inventory inv = p.getInventory();
			StorageTerminalMenu menu = new StorageTerminalMenu(0, inv, te);
			p.containerMenu = menu;
			CompoundTag msg = new CompoundTag();
			msg.putBoolean("locate_all", true);
			menu.receive(msg);
			h.assertTrue(!ItemLocator.tryStartRequest(p), "the highlight message must be answered (it starts the cooldown)");

			// A menu the player does not have open is ignored
			FakePlayer other = playerAt(h, TERMINAL, 3);
			StorageTerminalMenu stray = new StorageTerminalMenu(0, other.getInventory(), te);
			stray.receive(msg);
			h.assertTrue(ItemLocator.tryStartRequest(other), "a menu that is not the player's open menu must be ignored");
			menu.removed(p);
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void locateResultPacketLimits(GameTestHelper h) {
		BlockPos base = h.absolutePos(new BlockPos(2, 2, 2));
		// "All connected inventories" is an empty item and may carry 256 positions
		LocateResultPacket all = new LocateResultPacket(ItemStack.EMPTY, positionsList(base, ItemLocator.MAX_ALL_POSITIONS), 300, 4, 2);
		LocateResultPacket back = roundTrip(all);
		h.assertTrue(back.isAll(), "an empty item must stay empty");
		eq(back.positions(), all.positions(), "256 positions");
		eq(back.inDimension(), 300, "in dimension");
		eq(back.otherDimensions(), 4, "other dimensions");
		eq(back.unknown(), 2, "unknown");
		expectRejected(h, new LocateResultPacket(ItemStack.EMPTY, positionsList(base, ItemLocator.MAX_ALL_POSITIONS + 1), 257, 0, 0), "257 positions for all");

		// An item locate keeps its 64
		LocateResultPacket item = new LocateResultPacket(new ItemStack(Items.IRON_INGOT), positionsList(base, ItemLocator.MAX_POSITIONS), 64, 0, 0);
		LocateResultPacket itemBack = roundTrip(item);
		h.assertTrue(!itemBack.isAll() && itemBack.item().is(Items.IRON_INGOT), "the item must survive: " + itemBack.item());
		eq(itemBack.positions().size(), 64, "64 positions for an item");
		expectRejected(h, new LocateResultPacket(new ItemStack(Items.IRON_INGOT), positionsList(base, ItemLocator.MAX_POSITIONS + 1), 65, 0, 0), "65 positions for an item");
		h.succeed();
	}

	private static List<BlockPos> positionsList(BlockPos base, int count) {
		List<BlockPos> out = new ArrayList<>();
		for (int i = 0; i < count; i++)out.add(base.offset(i, 0, 0));
		return out;
	}

	private static LocateResultPacket roundTrip(LocateResultPacket p) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		p.write(buf);
		return LocateResultPacket.read(buf);
	}

	private static void expectRejected(GameTestHelper h, LocateResultPacket p, String what) {
		try {
			roundTrip(p);
		} catch (DecoderException e) {
			return;
		}
		h.fail(what + " must be rejected when decoding");
	}
}
