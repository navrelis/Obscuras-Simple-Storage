package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.netty.buffer.Unpooled;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.obscura.storage.network.PickFromStoragePacket;
import com.obscura.storage.util.BuildersReach;
import com.obscura.storage.util.BuildersReach.Cooldown;
import com.obscura.storage.util.BuildersReach.FirstResolved;
import com.obscura.storage.util.BuildersReach.Placement;
import com.obscura.storage.util.BuildersReach.PlacementKind;

/** Pure decision logic of Builder's Reach (pick-block from storage and Restock). */
@DisplayName("Builder's Reach")
public class BuildersReachTest {

	/** Slots 0..35, all empty except the given ones. */
	private static Placement place(int selected, int... filled) {
		Set<Integer> f = new HashSet<>();
		for (int i : filled)f.add(i);
		return BuildersReach.choosePlacement(selected, i -> !f.contains(i));
	}

	private static int[] range(int from, int to) {
		int[] r = new int[to - from];
		for (int i = from;i < to;i++)r[i - from] = i;
		return r;
	}

	@Test
	@DisplayName("empty selected slot is used first")
	void selectedSlotFirst() {
		assertEquals(new Placement(PlacementKind.SELECTED, 4), place(4, 0, 1, 2));
		assertEquals(new Placement(PlacementKind.SELECTED, 0), place(0));
	}

	@Test
	@DisplayName("otherwise the first empty hotbar slot")
	void firstEmptyHotbarSlot() {
		assertEquals(new Placement(PlacementKind.HOTBAR, 2), place(4, 0, 1, 4));
		assertEquals(new Placement(PlacementKind.HOTBAR, 8), place(0, range(0, 8)));
	}

	@Test
	@DisplayName("full hotbar swaps through the first empty main slot")
	void mainInventorySwap() {
		assertEquals(new Placement(PlacementKind.MAIN_SWAP, 9), place(3, range(0, 9)));
		assertEquals(new Placement(PlacementKind.MAIN_SWAP, 35), place(3, range(0, 35)));
	}

	@Test
	@DisplayName("full inventory places nothing")
	void fullInventory() {
		assertEquals(PlacementKind.FULL, place(3, range(0, 36)).kind());
		// Slots past the main inventory (armor, offhand) are never used
		assertEquals(Placement.FULL, BuildersReach.choosePlacement(3, i -> i >= 36));
	}

	@Test
	@DisplayName("an out of range selected index falls back to the hotbar scan")
	void invalidSelected() {
		assertEquals(new Placement(PlacementKind.HOTBAR, 0), place(-1));
		assertEquals(new Placement(PlacementKind.HOTBAR, 1), place(9, 0, 9));
	}

	@Test
	@DisplayName("restock tops up block stacks below 16 to the max stack size")
	void restockAmount() {
		assertEquals(49, BuildersReach.restockAmount(true, 15, 64));
		assertEquals(63, BuildersReach.restockAmount(true, 1, 64));
		assertEquals(0, BuildersReach.restockAmount(true, 16, 64));
		assertEquals(0, BuildersReach.restockAmount(true, 64, 64));
		assertEquals(15, BuildersReach.restockAmount(true, 1, 16));
		assertEquals(0, BuildersReach.restockAmount(true, 1, 1));
	}

	@Test
	@DisplayName("restock ignores non-blocks and empty hands")
	void restockSkips() {
		assertEquals(0, BuildersReach.restockAmount(false, 3, 64));
		assertEquals(0, BuildersReach.restockAmount(true, 0, 64));
		assertEquals(0, BuildersReach.restockAmount(false, 0, 64));
	}

	@Test
	@DisplayName("each player is checked exactly once per restock interval")
	void restockSchedule() {
		for (int id : new int[] {0, 1, 7, 123, Integer.MAX_VALUE}) {
			int hits = 0;
			for (int tick = 1000;tick < 1000 + BuildersReach.RESTOCK_INTERVAL;tick++) {
				if (BuildersReach.isRestockTick(tick, id))hits++;
			}
			assertEquals(1, hits, "id=" + id);
		}
	}

	@Test
	@DisplayName("new config switches default to on when missing from the file")
	void configDefaults() {
		Gson gson = new GsonBuilder().create();
		Config c = gson.fromJson("{\"configVersion\":2,\"wirelessRange\":40}", Config.class);
		c.validatePostLoad();
		assertTrue(c.buildersReachEnabled);
		assertTrue(c.restockEnabled);
		assertTrue(c.itemLocatorEnabled);
		assertFalse(c.wasMigrated(), "a current file is not rewritten");
		assertEquals(40, c.wirelessRange);

		Config off = gson.fromJson("{\"configVersion\":2,\"restockEnabled\":false}", Config.class);
		off.validatePostLoad();
		assertFalse(off.restockEnabled);
		assertTrue(off.buildersReachEnabled);

		Config old = gson.fromJson("{\"wirelessRange\":16}", Config.class);
		old.validatePostLoad();
		assertTrue(old.wasMigrated());
		assertFalse(gson.toJson(old).contains("migrated"), "the flag is not written to the file or the sync packet");
	}

	@Test
	@DisplayName("terminal search keeps the first resolved result when every stack is evaluated")
	void firstResolvedSurvivesFullEvaluation() {
		// Stacks: unreachable, reachable, unreachable, reachable. Distinct instances like ItemStacks.
		List<String> stacks = List.of(new String("a"), new String("b"), new String("c"), new String("d"));
		Map<String, String> terminals = new IdentityHashMap<>();
		terminals.put(stacks.get(1), "T1");
		terminals.put(stacks.get(3), "T2");
		List<String> resolved = new ArrayList<>();

		// Trinkets' getEquipped(predicate) style: the predicate runs on every stack
		FirstResolved<String, String> all = new FirstResolved<>(s -> {
			resolved.add(s);
			return terminals.get(s);
		});
		List<String> matched = stacks.stream().filter(all).toList();
		assertEquals("T1", all.getResult(), "a later unreachable stack must not clear the result");
		assertSame(stacks.get(1), all.getSource());
		assertEquals(1, matched.size());
		assertSame(stacks.get(1), matched.get(0), "only the stack that produced the result matches");
		assertEquals(List.of(stacks.get(0), stacks.get(1)), resolved, "nothing is resolved after the first hit");

		// PlayerInvUtil style: stops at the first match
		FirstResolved<String, String> first = new FirstResolved<>(terminals::get);
		String hit = stacks.stream().filter(first).findFirst().orElse(null);
		assertSame(stacks.get(1), hit);
		assertEquals("T1", first.getResult());

		FirstResolved<String, String> none = new FirstResolved<>(s -> null);
		assertEquals(0, stacks.stream().filter(none).count());
		assertNull(none.getResult());
		assertNull(none.getSource());
	}

	@Test
	@DisplayName("pick requests are rate limited per player")
	void cooldown() {
		Cooldown<String> c = new Cooldown<>(BuildersReach.PICK_COOLDOWN_TICKS);
		String a = "a", b = "b";
		assertTrue(c.tryUse(a, 100));
		assertFalse(c.tryUse(a, 101));
		assertFalse(c.tryUse(a, 104));
		assertTrue(c.tryUse(b, 104), "other players are independent");
		assertTrue(c.tryUse(a, 105));
		assertFalse(c.tryUse(a, 105));
		// Tick counter restarted (new world in the same session)
		assertTrue(c.tryUse(a, 3));
	}

	@Test
	@DisplayName("the pick packet carries the block, the face and the hit offset in a fixed size")
	void pickPacketRoundTrip() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		PickFromStoragePacket sent = new PickFromStoragePacket(new BlockPos(-12, 64, 300), Direction.WEST, new Vec3(0.25, 0.5, 0.75));
		PickFromStoragePacket.STREAM_CODEC.encode(buf, sent);
		// Block position (long) + face (byte) + three floats
		assertEquals(8 + 1 + 3 * 4, buf.readableBytes());
		assertEquals(sent, PickFromStoragePacket.STREAM_CODEC.decode(buf));
		assertEquals(0, buf.readableBytes());
	}

	@Test
	@DisplayName("the pick packet is built from the crosshair hit, relative to the block")
	void pickPacketFromHit() {
		BlockPos pos = new BlockPos(10, -5, 7);
		PickFromStoragePacket p = PickFromStoragePacket.of(new BlockHitResult(new Vec3(10.25, -4.0, 7.5), Direction.UP, pos, false));
		assertEquals(pos, p.pos());
		assertEquals(Direction.UP, p.face());
		assertEquals(new Vec3(0.25, 1.0, 0.5), p.offset());
	}

	@Test
	@DisplayName("a garbage face byte in a pick packet wraps to a valid face instead of throwing")
	void pickPacketGarbageFace() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeBlockPos(BlockPos.ZERO);
		buf.writeByte(-100);
		buf.writeFloat(0).writeFloat(0).writeFloat(0);
		PickFromStoragePacket p = PickFromStoragePacket.STREAM_CODEC.decode(buf);
		assertNotNull(p.face());
	}
}
