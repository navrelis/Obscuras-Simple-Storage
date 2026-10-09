package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.obscura.storage.StorageMod;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.ItemKey;
import com.obscura.storage.util.ItemLocator;

/**
 * Shulker Dock on Forge: the shared checks of {@link ShulkerDockChecks} (hand interactions, the
 * exposed 27 slots on the network, removal while a network is attached, drops on break and
 * explosion, save/load, comparator, Frame Labels) plus the data files and the blockstate variants.
 * Tests that count item entities run in batches of their own, so no neighbouring test's drops are
 * counted.
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class ShulkerDockGameTests {
	private static final ShulkerDockChecks.Platform PLATFORM = new ShulkerDockChecks.Platform() {
		@Override
		public ServerPlayer fakePlayer(GameTestHelper h) {
			return FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "dock-test"));
		}

		@Override
		public long terminalCount(StorageTerminalBlockEntity te, Item item) {
			return GameTestUtil.terminalCount(te, item);
		}

		@Override
		public List<BlockPos> locate(StorageTerminalBlockEntity te, GameTestHelper h, Item item) {
			return ItemLocator.search(te, h.getLevel(), ItemKey.of(item)).located().stream().map(ItemLocator.Location::pos).toList();
		}

		@Override
		public InputStream resource(String path) {
			// The mod file, not the class loader: resources of a Forge mod are not on the class path in dev
			try {
				Path p = ModList.get().getModFileById(StorageMod.modid).getFile().findResource(path.split("/"));
				return Files.isRegularFile(p) ? Files.newInputStream(p) : null;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	};

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = "entities_dock_hand")
	public void handInsertSwapTakeOut(GameTestHelper h) {
		ShulkerDockChecks.handInsertSwapTakeOut(h, PLATFORM);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = "entities_dock_offhand")
	public void offHandAndSneak(GameTestHelper h) {
		ShulkerDockChecks.offHandAndSneak(h, PLATFORM);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = "entities_dock_creative")
	public void creativeMovesTheBoxNeverCopies(GameTestHelper h) {
		ShulkerDockChecks.creativeMovesTheBoxNeverCopies(h, PLATFORM);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200)
	public void networkReadsAndWritesTheBox(GameTestHelper h) {
		ShulkerDockChecks.networkReadsAndWritesTheBox(h, PLATFORM);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200, batch = "entities_dock_network_take_out")
	public void takeOutWhileNetworkAttachedNoDupe(GameTestHelper h) {
		ShulkerDockChecks.takeOutWhileNetworkAttachedNoDupe(h, PLATFORM);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = "entities_dock_break")
	public void breakingDropsDockAndBoxWithContents(GameTestHelper h) {
		ShulkerDockChecks.breakingDropsDockAndBoxWithContents(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = "entities_dock_explosion")
	public void explosionDropsDockAndBoxWithContents(GameTestHelper h) {
		ShulkerDockChecks.explosionDropsDockAndBoxWithContents(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100)
	public void saveLoadRoundTrip(GameTestHelper h) {
		ShulkerDockChecks.saveLoadRoundTrip(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100)
	public void vanillaBoxFormatsRoundTrip(GameTestHelper h) {
		ShulkerDockChecks.vanillaBoxFormatsRoundTrip(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100)
	public void containerRules(GameTestHelper h) {
		ShulkerDockChecks.containerRules(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100)
	public void comparatorValues(GameTestHelper h) {
		ShulkerDockChecks.comparatorValues(h);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void frameLabelOnDock(GameTestHelper h) {
		ShulkerDockChecks.frameLabelOnDock(h, PLATFORM);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public void recipeTagsAndLoot(GameTestHelper h) {
		ShulkerDockChecks.recipeTagsAndLoot(h, "forge:ingots/iron");
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 20)
	public void blockstateVariantsResolve(GameTestHelper h) {
		ShulkerDockChecks.blockstateVariantsResolve(h, PLATFORM);
	}
}
