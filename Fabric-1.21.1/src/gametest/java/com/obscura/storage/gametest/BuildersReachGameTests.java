package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.block.entity.StorageTerminalBlockEntity;
import com.obscura.storage.components.WorldPos;
import com.obscura.storage.util.BuildersReach;

/**
 * Builder's Reach (pick-block from storage) with a bound Wireless Terminal. Uses Fabric's FakePlayer
 * (a survival ServerPlayer whose network handler drops packets): the vanilla mock player gets
 * disconnected right away by Cardinal Components ("unhandled packet: entity_sync").
 */
public class BuildersReachGameTests implements FabricGameTest {
	private static final BlockPos CHEST = new BlockPos(1, 2, 3);
	private static final BlockPos CONNECTOR = new BlockPos(2, 2, 3);
	private static final BlockPos TERMINAL = new BlockPos(2, 3, 3);
	private static final BlockPos TARGET = new BlockPos(5, 2, 5);

	/** A fresh survival player standing at x/y/z with a Wireless Terminal bound to {@code terminalAbs} in hotbar slot 8. */
	private static ServerPlayer player(GameTestHelper h, BlockPos terminalAbs, double x, double y, double z) {
		ServerPlayer p = FakePlayer.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "reach-test"));
		p.moveTo(x, y, z, 0, 0);
		ItemStack term = new ItemStack(Content.wirelessTerminal.get());
		term.set(Content.boundPosComponent.get(), new WorldPos(h.getLevel().dimension(), terminalAbs));
		Inventory inv = p.getInventory();
		inv.clearContent();
		inv.setItem(8, term);
		inv.selected = 0;
		return p;
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void pickBlockFromStorage(GameTestHelper h) {
		h.assertTrue(Config.get().buildersReachEnabled, "buildersReachEnabled is off in the test config");
		chest(h, CHEST, new ItemStack(Items.POLISHED_GRANITE, 64), new ItemStack(Items.POLISHED_GRANITE, 36));
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
		h.setBlock(TARGET, Blocks.POLISHED_GRANITE);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		BlockPos termAbs = h.absolutePos(TERMINAL);
		BlockPos targetAbs = h.absolutePos(TARGET);
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalCount(te, Items.POLISHED_GRANITE), 100L, "network ready"))
		.thenExecute(() -> {
			// In range: standing next to the target, the terminal a few blocks away
			ServerPlayer p = player(h, termAbs, targetAbs.getX() - 1.5, targetAbs.getY(), targetAbs.getZ() + 0.5);
			BuildersReach.pickFromStorage(p, targetAbs);
			ItemStack got = p.getInventory().getItem(0);
			h.assertTrue(got.is(Items.POLISHED_GRANITE), "selected slot must hold the picked block, has " + got);
			h.assertValueEqual(got.getCount(), 64, "one full stack picked");
			h.assertValueEqual(count(h, CHEST, Items.POLISHED_GRANITE), 36, "granite left in storage");
		})
		// Pick-block has a 5 tick cooldown per player
		.thenIdle(10)
		.thenExecute(() -> {
			// Carrying the block already: a pick does nothing
			ServerPlayer p = player(h, termAbs, targetAbs.getX() - 1.5, targetAbs.getY(), targetAbs.getZ() + 0.5);
			p.getInventory().setItem(3, new ItemStack(Items.POLISHED_GRANITE, 2));
			BuildersReach.pickFromStorage(p, targetAbs);
			h.assertTrue(p.getInventory().getItem(0).isEmpty(), "a player already carrying the block must not get more");
			h.assertValueEqual(count(h, CHEST, Items.POLISHED_GRANITE), 36, "storage after a pick while carrying the block");
		})
		.thenExecute(() -> {
			// Out of range: wireless range + 8 blocks above the terminal, with a target block next to the player
			BlockPos farTarget = targetAbs.above(Config.get().wirelessRange + 8);
			h.getLevel().setBlockAndUpdate(farTarget, Blocks.POLISHED_GRANITE.defaultBlockState());
			try {
				ServerPlayer p = player(h, termAbs, farTarget.getX() - 1.5, farTarget.getY(), farTarget.getZ() + 0.5);
				BuildersReach.pickFromStorage(p, farTarget);
				h.assertTrue(p.getInventory().getItem(0).isEmpty(), "out of range: nothing may be picked, got " + p.getInventory().getItem(0));
				h.assertValueEqual(p.getInventory().countItem(Items.POLISHED_GRANITE), 0, "granite in the far player's inventory");
				h.assertValueEqual(count(h, CHEST, Items.POLISHED_GRANITE), 36, "storage unchanged");

				// Control: a player in range still gets the rest, so the far pick failed because of the range
				ServerPlayer near = player(h, termAbs, targetAbs.getX() - 1.5, targetAbs.getY(), targetAbs.getZ() + 0.5);
				BuildersReach.pickFromStorage(near, targetAbs);
				h.assertValueEqual(near.getInventory().getItem(0).getCount(), 36, "the rest of the storage picked in range");
				h.assertValueEqual(count(h, CHEST, Items.POLISHED_GRANITE), 0, "storage after picking the rest");
			} finally {
				h.getLevel().setBlockAndUpdate(farTarget, Blocks.AIR.defaultBlockState());
			}
		})
		.thenSucceed();
	}

	/** A Wireless Terminal or Advanced Wireless Terminal bound to the block at {@code boundAbs}. */
	private static ItemStack wireless(GameTestHelper h, Item item, BlockPos boundAbs) {
		ItemStack s = new ItemStack(item);
		s.set(Content.boundPosComponent.get(), new WorldPos(h.getLevel().dimension(), boundAbs));
		return s;
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void terminalReachCountsOnlyBoundTerminals(GameTestHelper h) {
		terminalAbove(h, TERMINAL);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		BlockPos termAbs = h.absolutePos(TERMINAL);
		BlockPos elsewhere = termAbs.offset(3, 0, 3);
		int dist = 100;
		h.assertTrue(Config.get().wirelessRange < dist && dist < Config.get().advWirelessRange,
				"test setup: the 100 block distance must be outside the basic and inside the advanced range (" + Config.get().wirelessRange + " / " + Config.get().advWirelessRange + ")");
		ServerPlayer p = player(h, termAbs, termAbs.getX() + dist, termAbs.getY(), termAbs.getZ());
		Inventory inv = p.getInventory();
		inv.clearContent();

		// Nothing wireless: plain 6 block reach only
		h.assertFalse(te.canInteractWith(p, false), "no wireless terminal: 100 blocks away must be out of reach");

		// A basic terminal bound to another terminal, first in the inventory: says nothing about this one
		inv.setItem(0, wireless(h, Content.wirelessTerminal.get(), elsewhere));
		h.assertFalse(te.canInteractWith(p, false), "a terminal bound elsewhere must not give reach");

		// An advanced terminal bound elsewhere neither
		inv.setItem(1, wireless(h, Content.advWirelessTerminal.get(), elsewhere));
		h.assertFalse(te.canInteractWith(p, false), "an advanced terminal bound elsewhere must not give reach");

		// The advanced terminal bound to this terminal, after the basic one: its range counts
		inv.setItem(1, wireless(h, Content.advWirelessTerminal.get(), termAbs));
		h.assertTrue(te.canInteractWith(p, false), "the advanced terminal bound to this terminal must reach it, although a basic one bound elsewhere comes first");
		h.assertTrue(te.canInteractWith(p, true), "the open menu check must agree");

		// Bound to the same position in another dimension does not count
		ItemStack otherDim = new ItemStack(Content.advWirelessTerminal.get());
		otherDim.set(Content.boundPosComponent.get(), new WorldPos(Level.NETHER, termAbs));
		inv.setItem(1, otherDim);
		h.assertFalse(te.canInteractWith(p, false), "a terminal bound to the same position in another dimension must not give reach");

		// The basic one bound to this terminal reaches only its own range
		inv.clearContent();
		inv.setItem(0, wireless(h, Content.wirelessTerminal.get(), termAbs));
		h.assertFalse(te.canInteractWith(p, false), "a basic terminal does not reach 100 blocks");
		ServerPlayer near = player(h, termAbs, termAbs.getX() + Config.get().wirelessRange - 5, termAbs.getY(), termAbs.getZ());
		h.assertTrue(te.canInteractWith(near, false), "a basic terminal bound to this terminal reaches inside its range");

		// The largest range among the bound terminals is used, whatever the order
		inv.setItem(0, wireless(h, Content.advWirelessTerminal.get(), termAbs));
		inv.setItem(1, wireless(h, Content.wirelessTerminal.get(), termAbs));
		h.assertTrue(te.canInteractWith(p, false), "advanced before basic, both bound here");
		inv.setItem(0, wireless(h, Content.wirelessTerminal.get(), termAbs));
		inv.setItem(1, wireless(h, Content.advWirelessTerminal.get(), termAbs));
		h.assertTrue(te.canInteractWith(p, false), "basic before advanced, both bound here");
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void validatedHitKeepsPlausibleClientHit(GameTestHelper h) {
		BlockPos pos = h.absolutePos(TARGET);
		ServerPlayer p = player(h, pos, pos.getX() + 3.5, pos.getY(), pos.getZ() + 0.5);

		BlockHitResult ok = BuildersReach.validatedHit(p, pos, Direction.NORTH, new Vec3(0.25, 0.5, 0.0));
		h.assertValueEqual(ok.getBlockPos(), pos, "block of the validated hit");
		h.assertValueEqual(ok.getDirection(), Direction.NORTH, "face of a plausible hit");
		h.assertTrue(ok.getLocation().equals(new Vec3(pos.getX() + 0.25, pos.getY() + 0.5, pos.getZ())), "location of a plausible hit: " + ok.getLocation());

		// The limits are inclusive: one block outside the block on every side
		BlockHitResult edge = BuildersReach.validatedHit(p, pos, Direction.UP, new Vec3(-1, 2, 2));
		h.assertValueEqual(edge.getDirection(), Direction.UP, "face of a hit on the plausibility limit");

		Vec3 centre = Vec3.atCenterOf(pos);
		for (Vec3 bad : new Vec3[] {new Vec3(2.5, 0.5, 0.5), new Vec3(0.5, -1.5, 0.5), new Vec3(0.5, 0.5, 1e9), new Vec3(Double.NaN, 0.5, 0.5), new Vec3(0.5, Double.POSITIVE_INFINITY, 0.5)}) {
			BlockHitResult r = BuildersReach.validatedHit(p, pos, Direction.NORTH, bad);
			h.assertTrue(r.getLocation().equals(centre), "implausible hit " + bad + " must fall back to the block centre, got " + r.getLocation());
			h.assertValueEqual(r.getDirection(), Direction.EAST, "fallback face for " + bad + " (the player stands east of the block)");
			h.assertValueEqual(r.getBlockPos(), pos, "fallback block for " + bad);
		}
		BlockHitResult none = BuildersReach.validatedHit(p, pos, null, null);
		h.assertTrue(none.getLocation().equals(centre), "no hit information must fall back to the block centre");
		h.assertValueEqual(none.getDirection(), Direction.EAST, "fallback face without hit information");
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void pickBlockSwapsWithFullHotbar(GameTestHelper h) {
		chest(h, CHEST, new ItemStack(Items.POLISHED_GRANITE, 64));
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
		h.setBlock(TARGET, Blocks.POLISHED_GRANITE);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		BlockPos termAbs = h.absolutePos(TERMINAL);
		BlockPos targetAbs = h.absolutePos(TARGET);
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalCount(te, Items.POLISHED_GRANITE), 64L, "network ready"))
		.thenExecute(() -> {
			ServerPlayer p = player(h, termAbs, targetAbs.getX() - 1.5, targetAbs.getY(), targetAbs.getZ() + 0.5);
			Inventory inv = p.getInventory();
			// Hotbar full: sticks 1..8 in slots 0..7, the terminal in slot 8
			for (int i = 0; i < 8; i++)inv.setItem(i, new ItemStack(Items.STICK, i + 1));
			inv.selected = 3;
			BuildersReach.pickFromStorage(p, targetAbs, Direction.UP, new Vec3(0.5, 1, 0.5));
			ItemStack got = inv.getItem(3);
			h.assertTrue(got.is(Items.POLISHED_GRANITE) && got.getCount() == 64, "the selected slot must hold the picked stack, has " + got);
			ItemStack moved = inv.getItem(BuildersReach.HOTBAR_SIZE);
			h.assertTrue(moved.is(Items.STICK) && moved.getCount() == 4, "the item that was selected must be swapped into the first main inventory slot, has " + moved);
			h.assertValueEqual(inv.selected, 3, "selected slot");
			for (int i = 0; i < 8; i++) {
				if (i == 3)continue;
				h.assertTrue(inv.getItem(i).is(Items.STICK) && inv.getItem(i).getCount() == i + 1, "hotbar slot " + i + " must be untouched, has " + inv.getItem(i));
			}
			h.assertTrue(inv.getItem(8).is(Content.wirelessTerminal.get()), "the terminal must stay in slot 8");
			h.assertValueEqual(count(h, CHEST, Items.POLISHED_GRANITE), 0, "granite left in storage");
		})
		.thenSucceed();
	}
}
