package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.BuildersReach;

/**
 * Builder's Reach (pick-block from storage) with a bound Wireless Terminal. Uses Fabric's FakePlayer
 * (a survival ServerPlayer whose network handler drops packets), same as the 1.21.1 tests.
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
		CompoundTag tag = term.getOrCreateTag();
		tag.putInt("BindX", terminalAbs.getX());
		tag.putInt("BindY", terminalAbs.getY());
		tag.putInt("BindZ", terminalAbs.getZ());
		tag.putString("BindDim", h.getLevel().dimension().location().toString());
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
		.thenWaitUntil(() -> {
			eq(terminalCount(te, Items.POLISHED_GRANITE), 100L, "network ready");
			h.assertTrue(te.getItemHandler() != null, "terminal has no storage yet");
		})
		.thenExecute(() -> {
			// In range: standing next to the target, the terminal a few blocks away
			ServerPlayer p = player(h, termAbs, targetAbs.getX() - 1.5, targetAbs.getY(), targetAbs.getZ() + 0.5);
			BuildersReach.pickFromStorage(p, targetAbs);
			ItemStack got = p.getInventory().getItem(0);
			h.assertTrue(got.is(Items.POLISHED_GRANITE), "selected slot must hold the picked block, has " + got);
			eq(got.getCount(), 64, "one full stack picked");
			eq(count(h, CHEST, Items.POLISHED_GRANITE), 36, "granite left in storage");
		})
		// Pick-block has a 5 tick cooldown per player
		.thenIdle(10)
		.thenExecute(() -> {
			// Carrying the block already: a pick does nothing
			ServerPlayer p = player(h, termAbs, targetAbs.getX() - 1.5, targetAbs.getY(), targetAbs.getZ() + 0.5);
			p.getInventory().setItem(3, new ItemStack(Items.POLISHED_GRANITE, 2));
			BuildersReach.pickFromStorage(p, targetAbs);
			h.assertTrue(p.getInventory().getItem(0).isEmpty(), "a player already carrying the block must not get more");
			eq(count(h, CHEST, Items.POLISHED_GRANITE), 36, "storage after a pick while carrying the block");
		})
		.thenExecute(() -> {
			// Out of range: wireless range + 8 blocks above the terminal, with a target block next to the player
			BlockPos farTarget = targetAbs.above(Config.get().wirelessRange + 8);
			h.getLevel().setBlockAndUpdate(farTarget, Blocks.POLISHED_GRANITE.defaultBlockState());
			try {
				ServerPlayer p = player(h, termAbs, farTarget.getX() - 1.5, farTarget.getY(), farTarget.getZ() + 0.5);
				BuildersReach.pickFromStorage(p, farTarget);
				h.assertTrue(p.getInventory().getItem(0).isEmpty(), "out of range: nothing may be picked, got " + p.getInventory().getItem(0));
				eq(p.getInventory().countItem(Items.POLISHED_GRANITE), 0, "granite in the far player's inventory");
				eq(count(h, CHEST, Items.POLISHED_GRANITE), 36, "storage unchanged");

				// Control: a player in range still gets the rest, so the far pick failed because of the range
				ServerPlayer near = player(h, termAbs, targetAbs.getX() - 1.5, targetAbs.getY(), targetAbs.getZ() + 0.5);
				BuildersReach.pickFromStorage(near, targetAbs);
				eq(near.getInventory().getItem(0).getCount(), 36, "the rest of the storage picked in range");
				eq(count(h, CHEST, Items.POLISHED_GRANITE), 0, "storage after picking the rest");
			} finally {
				h.getLevel().setBlockAndUpdate(farTarget, Blocks.AIR.defaultBlockState());
			}
		})
		.thenSucceed();
	}

	/** A player carrying only the given wireless terminals (item, bound terminal), slot i for the i-th entry. */
	private static ServerPlayer carrying(GameTestHelper h, double x, double y, double z, Object... itemAndBinding) {
		ServerPlayer p = player(h, BlockPos.ZERO, x, y, z);
		Inventory inv = p.getInventory();
		inv.clearContent();
		for (int i = 0; i < itemAndBinding.length; i += 2) {
			ItemStack term = new ItemStack((Item) itemAndBinding[i]);
			BlockPos bound = (BlockPos) itemAndBinding[i + 1];
			CompoundTag tag = term.getOrCreateTag();
			tag.putInt("BindX", bound.getX());
			tag.putInt("BindY", bound.getY());
			tag.putInt("BindZ", bound.getZ());
			tag.putString("BindDim", h.getLevel().dimension().location().toString());
			inv.setItem(i / 2, term);
		}
		return p;
	}

	/**
	 * Builder's Reach needs no open terminal menu: nothing here calls getSnapshot() (which is what an open
	 * menu does), the terminal has to find its storage when a pick asks for it.
	 */
	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void pickBlockBeforeTheTerminalWasEverOpened(GameTestHelper h) {
		chest(h, CHEST, new ItemStack(Items.POLISHED_GRANITE, 64));
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
		h.setBlock(TARGET, Blocks.POLISHED_GRANITE);
		BlockPos termAbs = h.absolutePos(TERMINAL);
		BlockPos targetAbs = h.absolutePos(TARGET);
		h.startSequence()
		.thenIdle(60)
		.thenExecute(() -> {
			ServerPlayer p = player(h, termAbs, targetAbs.getX() - 1.5, targetAbs.getY(), targetAbs.getZ() + 0.5);
			BuildersReach.pickFromStorage(p, targetAbs);
			ItemStack got = p.getInventory().getItem(0);
			h.assertTrue(got.is(Items.POLISHED_GRANITE), "a terminal that was never opened must still serve the pick, slot 0 has " + got);
			eq(got.getCount(), 64, "one full stack picked");
			eq(count(h, CHEST, Items.POLISHED_GRANITE), 0, "granite left in storage");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void pushFollowsAReplacedChestOfAnUnopenedTerminal(GameTestHelper h) {
		pushFollowsReplacedChest(h, false);
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void pushFollowsAReplacedChestOfAnOpenedTerminal(GameTestHelper h) {
		pushFollowsReplacedChest(h, true);
	}

	/** Items pushed after the attached chest was swapped must land in the new chest, not in the orphaned one. */
	private static void pushFollowsReplacedChest(GameTestHelper h, boolean openedOnce) {
		BlockPos chestPos = new BlockPos(2, 2, 3);
		chest(h, chestPos);
		terminalAbove(h, TERMINAL);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		if (openedOnce)te.getSnapshot();
		h.startSequence()
		.thenIdle(5)
		.thenExecute(() -> {
			h.assertTrue(te.pushStack(new ItemStack(Items.COBBLESTONE, 10)).isEmpty(), "everything fits into the first chest");
			eq(count(h, chestPos, Items.COBBLESTONE), 10, "cobblestone in the first chest");
		})
		.thenIdle(2)
		.thenExecute(() -> {
			h.setBlock(chestPos, Blocks.AIR);
			chest(h, chestPos);
		})
		.thenIdle(2)
		.thenExecute(() -> {
			h.assertTrue(te.pushStack(new ItemStack(Items.COBBLESTONE, 7)).isEmpty(), "everything fits into the new chest");
			eq(count(h, chestPos, Items.COBBLESTONE), 7, "cobblestone in the replaced chest");
		})
		.thenSucceed();
	}

	/**
	 * A wireless terminal bound to another terminal says nothing about this one: a basic terminal in front of
	 * an Advanced one bound here must not take the reach away, and one bound elsewhere must not add any.
	 */
	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void reachOnlyCountsTerminalsBoundToThisTerminal(GameTestHelper h) {
		chest(h, CHEST);
		terminalAbove(h, TERMINAL);
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		BlockPos termAbs = h.absolutePos(TERMINAL);
		BlockPos elsewhere = termAbs.offset(40, 0, 0);
		double x = termAbs.getX() + 0.5 + 100, y = termAbs.getY(), z = termAbs.getZ() + 0.5;
		h.startSequence()
		.thenIdle(2)
		.thenExecute(() -> {
			h.assertTrue(Config.get().wirelessRange < 100 && Config.get().advWirelessRange >= 100, "the test needs a basic range below and an advanced range above 100 blocks");
			h.assertTrue(te.canInteractWith(carrying(h, x, y, z, Content.wirelessTerminal.get(), elsewhere, Content.advWirelessTerminal.get(), termAbs), false),
					"an Advanced terminal bound here must reach 100 blocks, behind a basic terminal bound elsewhere");
			h.assertTrue(te.canInteractWith(carrying(h, x, y, z, Content.advWirelessTerminal.get(), termAbs, Content.wirelessTerminal.get(), elsewhere), false),
					"the same with the terminals the other way round");
			h.assertTrue(!te.canInteractWith(carrying(h, x, y, z, Content.wirelessTerminal.get(), elsewhere), false),
					"a basic terminal bound elsewhere has no reach here");
			h.assertTrue(!te.canInteractWith(carrying(h, x, y, z, Content.advWirelessTerminal.get(), elsewhere), false),
					"an Advanced terminal bound elsewhere has no reach here either");
			h.assertTrue(!te.canInteractWith(carrying(h, x, y, z, Content.wirelessTerminal.get(), termAbs), false),
					"a basic terminal bound here only reaches its own range");
			h.assertTrue(!te.canInteractWith(carrying(h, x, y, z), false), "without a terminal only the plain reach applies");
			h.assertTrue(te.canInteractWith(carrying(h, termAbs.getX() + 0.5, y, termAbs.getZ() + 3.5), false), "within the plain reach without a terminal");
		})
		.thenSucceed();
	}
}
