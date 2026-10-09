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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.block.AbstractStorageTerminalBlock;
import com.obscura.storage.block.AbstractStorageTerminalBlock.TerminalPos;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.tile.InventoryCableConnectorBlockEntity;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.RemoteConnections;
import com.obscura.storage.util.StoredItemStack;

/**
 * Inventory Link without a beacon: cable connectors on one channel link at beacon level 0, within
 * {@code invLinkBeaconRange} blocks (see {@link InventoryCableConnectorBlockEntity#canLink}). Two
 * networks in the test area share their items; a third network on the same channel, placed
 * {@link #FAR} blocks away in a force-loaded chunk, is out of range and must stay separate.
 */
public class InventoryLinkGameTests implements FabricGameTest {
	/** Further than the default link range (4096) but a chunk away from nothing else. */
	private static final int FAR = 5000;

	// Network A: chest, connector with a terminal on top, link connector facing up (nothing in front of it)
	private static final BlockPos A_CHEST = new BlockPos(1, 2, 1);
	private static final BlockPos A_CONNECTOR = new BlockPos(2, 2, 1);
	private static final BlockPos A_TERMINAL = new BlockPos(2, 3, 1);
	private static final BlockPos A_LINK = new BlockPos(3, 2, 1);
	// Network B, four blocks south
	private static final BlockPos B_CHEST = new BlockPos(1, 2, 5);
	private static final BlockPos B_CONNECTOR = new BlockPos(2, 2, 5);
	private static final BlockPos B_TERMINAL = new BlockPos(2, 3, 5);
	private static final BlockPos B_LINK = new BlockPos(3, 2, 5);

	private static BlockState linkState() {
		return Content.invCableConnector.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING, Direction.UP);
	}

	private static BlockState terminalState() {
		return Content.terminal.get().defaultBlockState().setValue(AbstractStorageTerminalBlock.TERMINAL_POS, TerminalPos.DOWN);
	}

	/** Network in the test area, its link connector on {@code channel}. */
	private static void buildLocal(GameTestHelper h, BlockPos chest, BlockPos connector, BlockPos terminal, BlockPos link, UUID channel, ItemStack content) {
		chest(h, chest, content);
		h.setBlock(connector, Content.connector.get());
		h.setBlock(terminal, terminalState());
		place(h, link, linkState());
		InventoryCableConnectorBlockEntity be = be(h, link);
		be.setChannel(channel);
	}

	/** The same network, built with absolute positions (outside the test area). Chest west, terminal on top, link east. */
	private static void buildFar(ServerLevel level, BlockPos connector, UUID channel, ItemStack content) {
		for (BlockPos p : BlockPos.betweenClosed(connector.offset(-2, -1, -2), connector.offset(2, 2, 2))) {
			level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		BlockPos chest = connector.west();
		level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
		Container c = (BaseContainerBlockEntity) level.getBlockEntity(chest);
		c.setItem(0, content.copy());
		c.setChanged();
		level.setBlock(connector, Content.connector.get().defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(connector.above(), terminalState(), Block.UPDATE_ALL);
		BlockPos link = connector.east();
		level.setBlock(link, linkState(), Block.UPDATE_ALL);
		level.setBlock(link, Block.updateFromNeighbourShapes(level.getBlockState(link), level, link), Block.UPDATE_ALL);
		((InventoryCableConnectorBlockEntity) level.getBlockEntity(link)).setChannel(channel);
	}

	private static void clearFar(ServerLevel level, BlockPos connector) {
		// Chest contents first, so nothing drops
		if (level.getBlockEntity(connector.west()) instanceof Container c)c.clearContent();
		for (BlockPos p : BlockPos.betweenClosed(connector.offset(-2, -1, -2), connector.offset(2, 2, 2))) {
			level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 1200)
	public void beaconlessLinkSharesWithinRange(GameTestHelper h) {
		h.assertTrue(Config.get().invLinkBeaconLvl == 0, "test expects the default invLinkBeaconLvl 0 (links without a beacon)");
		h.assertTrue(Config.get().invLinkBeaconRange < FAR, "test expects the default link range to be below " + FAR);
		ServerLevel level = h.getLevel();
		FakePlayer owner = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "link-test"));
		CompoundTag chTag = new CompoundTag();
		chTag.putString(RemoteConnections.DISPLAY_NAME, "gametest link");
		chTag.putBoolean(RemoteConnections.PUBLIC_TAG, true);
		UUID channel = RemoteConnections.get(level).makeChannel(chTag, owner);
		h.assertTrue(channel != null, "could not create a channel");

		buildLocal(h, A_CHEST, A_CONNECTOR, A_TERMINAL, A_LINK, channel, new ItemStack(Items.DIAMOND, 5));
		buildLocal(h, B_CHEST, B_CONNECTOR, B_TERMINAL, B_LINK, channel, new ItemStack(Items.EMERALD, 7));

		// Network C, same channel, out of range
		BlockPos farConnector = h.absolutePos(A_CONNECTOR).offset(FAR, 0, 0);
		int cx = farConnector.getX() >> 4, cz = farConnector.getZ() >> 4;
		level.getChunk(cx, cz); // loads (generates) it right now
		level.setChunkForced(cx, cz, true);
		buildFar(level, farConnector, channel, new ItemStack(Items.GOLD_INGOT, 9));

		StorageTerminalBlockEntity a = terminal(h, A_TERMINAL);
		StorageTerminalBlockEntity b = terminal(h, B_TERMINAL);
		StorageTerminalBlockEntity c = (StorageTerminalBlockEntity) level.getBlockEntity(farConnector.above());
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(terminalCount(c, Items.GOLD_INGOT), 9L, "far network sees its own chest (it is loaded and ticking)");
			eq(terminalCount(a, Items.DIAMOND), 5L, "A sees its own chest");
			eq(terminalCount(a, Items.EMERALD), 7L, "A sees B's chest over the link");
			eq(terminalCount(b, Items.DIAMOND), 5L, "B sees A's chest over the link");
			eq(terminalCount(b, Items.EMERALD), 7L, "B sees its own chest");
		})
		// Give the far end every chance to show up (rescans every 20 ticks at the latest)
		.thenIdle(60)
		.thenExecute(() -> {
			eq(terminalCount(a, Items.GOLD_INGOT), 0L, "A must not see the out-of-range network");
			eq(terminalCount(b, Items.GOLD_INGOT), 0L, "B must not see the out-of-range network");
			eq(terminalCount(c, Items.DIAMOND), 0L, "the out-of-range network must not see A");
			eq(terminalCount(c, Items.EMERALD), 0L, "the out-of-range network must not see B");

			// Items move over the link: A pulls B's emeralds and puts them back
			StoredItemStack pulled = a.pullStack(new StoredItemStack(new ItemStack(Items.EMERALD)), 3);
			h.assertTrue(pulled != null && pulled.getQuantity() == 3, "A pulls 3 emeralds from B, got " + (pulled == null ? 0 : pulled.getQuantity()));
			eq(count(h, B_CHEST, Items.EMERALD), 4, "emeralds left in B's chest");
			h.assertTrue(a.pushStack(pulled) == null, "A pushes the emeralds back");
			eq(count(h, B_CHEST, Items.EMERALD) + count(h, A_CHEST, Items.EMERALD), 7, "emeralds after pushing back");
			// Pulling gold through A must fail: the far network is not linked
			h.assertTrue(a.pullStack(new StoredItemStack(new ItemStack(Items.GOLD_INGOT)), 9) == null, "A must not pull from the out-of-range network");
		})
		.thenExecute(() -> {
			clearFar(level, farConnector);
			level.setChunkForced(cx, cz, false);
			RemoteConnections.get(level).removeChannel(channel, owner.getUUID());
		})
		.thenSucceed();
	}
}
