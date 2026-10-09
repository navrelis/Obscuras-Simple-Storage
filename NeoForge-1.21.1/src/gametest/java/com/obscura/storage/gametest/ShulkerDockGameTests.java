package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;

import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.SeededContainerLoot;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.ShulkerDockBlock;
import com.obscura.storage.block.entity.InventoryConnectorBlockEntity;
import com.obscura.storage.block.entity.ShulkerDockBlockEntity;
import com.obscura.storage.block.entity.StorageTerminalBlockEntity;
import com.obscura.storage.doctor.DoctorScan;
import com.obscura.storage.inventory.ITidyExempt;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.util.ItemLocator;
import com.obscura.storage.util.RedstoneSignals;

/**
 * Shulker Dock: hand interactions, the exposed 27 slots on the network, removal while a network is
 * attached (no dupe), drops on break and explosion, save/load, comparator, Frame Labels, data files
 * and the blockstate variants. Copy of the Fabric build's test, loader independent except for the
 * fake player (see the bottom).
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class ShulkerDockGameTests {
	private static final String EMPTY_STRUCTURE = GameTestUtil.EMPTY_STRUCTURE;
	private static final BlockPos DOCK = new BlockPos(2, 2, 3);
	private static final BlockPos CONNECTOR = new BlockPos(3, 2, 3);
	private static final BlockPos TERMINAL = new BlockPos(3, 3, 3);

	// ---- helpers ----

	static ItemStack box(Item boxItem, ItemStack... contents) {
		ItemStack b = new ItemStack(boxItem);
		b.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(contents)));
		return b;
	}

	/** Items of {@code item} stored in a box item's container component. */
	static int inBox(ItemStack box, Item item) {
		ItemContainerContents c = box.get(DataComponents.CONTAINER);
		if (c == null)return 0;
		int n = 0;
		for (ItemStack s : c.nonEmptyItems()) {
			if (s.is(item))n += s.getCount();
		}
		return n;
	}

	static ShulkerDockBlockEntity dock(GameTestHelper h, BlockPos rel) {
		return h.getBlockEntity(rel);
	}

	static ShulkerDockBlock.Box shown(GameTestHelper h, BlockPos rel) {
		return h.getBlockState(rel).getValue(ShulkerDockBlock.BOX);
	}

	/** A fresh player in {@code mode}, standing two blocks south of {@code rel}, with an empty inventory. */
	static ServerPlayer player(GameTestHelper h, BlockPos rel, GameType mode) {
		ServerPlayer p = fakePlayer(h);
		BlockPos a = h.absolutePos(rel);
		p.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 2.5, 180, 0);
		p.getInventory().clearContent();
		p.getInventory().selected = 0;
		p.setShiftKeyDown(false);
		p.setGameMode(mode);
		return p;
	}

	/**
	 * A right click on {@code face} of the block at {@code rel}, the way the client and server run it:
	 * main hand first, then the off hand, stopping at the first hand that did something.
	 */
	static void click(GameTestHelper h, ServerPlayer p, BlockPos rel, Direction face) {
		BlockPos a = h.absolutePos(rel);
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(a).relative(face, 0.5), face, a, false);
		for (InteractionHand hand : InteractionHand.values()) {
			InteractionResult r = p.gameMode.useItemOn(p, h.getLevel(), p.getItemInHand(hand), hand, hit);
			if (r.consumesAction())return;
		}
	}

	static void click(GameTestHelper h, ServerPlayer p, BlockPos rel) {
		click(h, p, rel, Direction.SOUTH);
	}

	static List<ItemEntity> drops(GameTestHelper h, BlockPos rel) {
		return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(rel)).inflate(3));
	}

	/** Every item of {@code item}: in the dock's live slots, in docked or loose boxes, in player inventories and on the ground. */
	static int total(GameTestHelper h, Item item, BlockPos dockRel, ServerPlayer... players) {
		int n = 0;
		if (h.getBlockEntity(dockRel) instanceof ShulkerDockBlockEntity be)n += count(be.getInv(), item);
		for (ServerPlayer p : players) {
			for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
				ItemStack s = p.getInventory().getItem(i);
				if (s.is(item))n += s.getCount();
				n += inBox(s, item);
			}
		}
		for (ItemEntity e : drops(h, dockRel)) {
			if (e.getItem().is(item))n += e.getItem().getCount();
			n += inBox(e.getItem(), item);
		}
		return n;
	}

	static void dockWithNetwork(GameTestHelper h, ItemStack docked) {
		h.setBlock(DOCK, Content.shulkerDock.get());
		h.assertTrue(dock(h, DOCK).dock(docked.copy()), "box not docked");
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
	}

	// ---- hand interactions ----

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void handInsertSwapTakeOut(GameTestHelper h) {
		h.setBlock(DOCK, Content.shulkerDock.get().defaultBlockState().setValue(ShulkerDockBlock.FACING, Direction.SOUTH));
		ServerPlayer p = player(h, DOCK, GameType.SURVIVAL);
		ShulkerDockBlockEntity be = dock(h, DOCK);

		// Empty hand on an empty dock: nothing
		click(h, p, DOCK);
		h.assertFalse(be.hasBox(), "empty hand docked something");
		h.assertTrue(p.getMainHandItem().isEmpty(), "empty hand got something");
		h.assertValueEqual(be.getInv().getContainerSize(), 0, "slots without a box");

		// Insert: the held box goes in, with its contents
		ItemStack red = box(Items.RED_SHULKER_BOX, new ItemStack(Items.DIAMOND, 5));
		red.set(DataComponents.CUSTOM_NAME, Component.literal("Diamonds"));
		ItemStack redOriginal = red.copy();
		p.setItemInHand(InteractionHand.MAIN_HAND, red);
		click(h, p, DOCK);
		h.assertTrue(p.getMainHandItem().isEmpty(), "box still in hand after docking");
		h.assertTrue(be.hasBox(), "box not docked");
		h.assertValueEqual(shown(h, DOCK), ShulkerDockBlock.Box.RED, "window colour");
		h.assertValueEqual(be.getInv().getContainerSize(), 27, "slots with a box");
		h.assertValueEqual(count(be.getInv(), Items.DIAMOND), 5, "diamonds in the live slots");

		// Anything else passes: cobblestone is placed on top as usual, the dock keeps its box
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 3));
		click(h, p, DOCK, Direction.UP);
		h.assertTrue(h.getBlockState(DOCK.above()).is(Blocks.COBBLESTONE), "cobblestone was not placed on the dock");
		h.assertValueEqual(p.getMainHandItem().getCount(), 2, "cobblestone used");
		h.assertTrue(be.hasBox(), "other item took the box out");
		p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

		// Swap: the held box goes in, the docked one comes back, exactly as it went in
		ItemStack blue = box(Items.BLUE_SHULKER_BOX, new ItemStack(Items.EMERALD, 3));
		p.setItemInHand(InteractionHand.MAIN_HAND, blue.copy());
		click(h, p, DOCK);
		h.assertTrue(ItemStack.matches(p.getMainHandItem(), redOriginal), "swapped-out box differs: " + p.getMainHandItem().getComponents());
		h.assertValueEqual(shown(h, DOCK), ShulkerDockBlock.Box.BLUE, "window colour after swap");
		h.assertValueEqual(count(be.getInv(), Items.EMERALD), 3, "emeralds in the live slots");
		h.assertValueEqual(count(be.getInv(), Items.DIAMOND), 0, "diamonds left behind in the dock");

		// Take out into the empty hand
		p.getInventory().add(p.getMainHandItem().copy());
		p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		click(h, p, DOCK);
		h.assertTrue(ItemStack.matches(p.getMainHandItem(), blue), "taken-out box differs");
		h.assertFalse(be.hasBox(), "dock still has a box");
		h.assertValueEqual(shown(h, DOCK), ShulkerDockBlock.Box.NONE, "window after take-out");
		h.assertValueEqual(be.getInv().getContainerSize(), 0, "slots after take-out");
		h.assertValueEqual(total(h, Items.DIAMOND, DOCK, p), 5, "diamonds conserved");
		h.assertValueEqual(total(h, Items.EMERALD, DOCK, p), 3, "emeralds conserved");
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void offHandAndSneak(GameTestHelper h) {
		h.setBlock(DOCK, Content.shulkerDock.get());
		ServerPlayer p = player(h, DOCK, GameType.SURVIVAL);
		ShulkerDockBlockEntity be = dock(h, DOCK);
		ItemStack box = box(Items.SHULKER_BOX, new ItemStack(Items.GOLD_INGOT, 9));

		// Main hand empty, box in the off hand: the main hand does nothing on an empty dock, the off hand docks
		p.setItemInHand(InteractionHand.OFF_HAND, box.copy());
		click(h, p, DOCK);
		h.assertTrue(be.hasBox(), "off hand box not docked");
		h.assertTrue(p.getOffhandItem().isEmpty(), "off hand box not consumed");
		h.assertValueEqual(shown(h, DOCK), ShulkerDockBlock.Box.SHULKER, "undyed window");

		// Empty main hand takes it out into the main hand; the off hand stays empty
		click(h, p, DOCK);
		h.assertTrue(ItemStack.matches(p.getMainHandItem(), box), "box not in the main hand");
		h.assertTrue(p.getOffhandItem().isEmpty(), "off hand got something");
		h.assertFalse(be.hasBox(), "box still docked");

		// Block in the main hand, box in the off hand: the block is placed, the box stays in the off hand
		p.setItemInHand(InteractionHand.OFF_HAND, p.getMainHandItem().copy());
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT));
		click(h, p, DOCK, Direction.UP);
		h.assertTrue(h.getBlockState(DOCK.above()).is(Blocks.DIRT), "dirt not placed");
		h.assertFalse(be.hasBox(), "off hand box docked although the main hand acted");
		h.assertTrue(ItemStack.matches(p.getOffhandItem(), box), "off hand box changed");
		h.setBlock(DOCK.above(), Blocks.AIR);

		// Sneaking with a box: vanilla skips the block, the box is placed as a block on the dock
		p.setItemInHand(InteractionHand.MAIN_HAND, p.getOffhandItem().copy());
		p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
		p.setShiftKeyDown(true);
		click(h, p, DOCK, Direction.UP);
		p.setShiftKeyDown(false);
		h.assertFalse(be.hasBox(), "sneaking docked the box");
		h.assertTrue(h.getBlockState(DOCK.above()).is(Blocks.SHULKER_BOX), "sneaking did not place the box");
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void creativeMovesTheBoxNeverCopies(GameTestHelper h) {
		h.setBlock(DOCK, Content.shulkerDock.get());
		ServerPlayer p = player(h, DOCK, GameType.CREATIVE);
		ShulkerDockBlockEntity be = dock(h, DOCK);
		ItemStack box = box(Items.LIME_SHULKER_BOX, new ItemStack(Items.NETHERITE_INGOT, 2));
		p.setItemInHand(InteractionHand.MAIN_HAND, box.copy());
		click(h, p, DOCK);
		h.assertTrue(be.hasBox(), "creative insert did not dock");
		h.assertTrue(p.getMainHandItem().isEmpty(), "creative insert copied the box instead of moving it");
		h.assertValueEqual(total(h, Items.NETHERITE_INGOT, DOCK, p), 2, "netherite after creative insert");
		click(h, p, DOCK);
		h.assertTrue(ItemStack.matches(p.getMainHandItem(), box), "creative take-out");
		h.assertFalse(be.hasBox(), "box still docked after creative take-out");
		h.assertValueEqual(total(h, Items.NETHERITE_INGOT, DOCK, p), 2, "netherite after creative take-out");
		// A second click with the box in hand docks it again, the next empty-hand click is the only way out
		click(h, p, DOCK);
		click(h, p, DOCK);
		h.assertValueEqual(total(h, Items.NETHERITE_INGOT, DOCK, p), 2, "netherite after another round");
		h.succeed();
	}

	// ---- network ----

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void networkReadsAndWritesTheBox(GameTestHelper h) {
		dockWithNetwork(h, box(Items.CYAN_SHULKER_BOX, new ItemStack(Items.DIAMOND, 10)));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		ShulkerDockBlockEntity be = dock(h, DOCK);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertValueEqual(terminalCount(te, Items.DIAMOND), 10L, "terminal diamonds");
			h.assertValueEqual(te.getSlotCount(), 27, "terminal slots");
		})
		.thenExecute(() -> {
			StoredItemStack pulled = te.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)), 4);
			h.assertTrue(pulled != null && pulled.getQuantity() == 4, "pulled diamonds");
			h.assertValueEqual(count(be.getInv(), Items.DIAMOND), 6, "diamonds left in the box");
			h.assertTrue(te.pushStack(new StoredItemStack(new ItemStack(Items.IRON_INGOT), 20)) == null, "iron push left a remainder");
			h.assertValueEqual(count(be.getInv(), Items.IRON_INGOT), 20, "iron in the box");
			// Shulker boxes and bundles never go into a docked box
			StoredItemStack rest = te.pushStack(new StoredItemStack(new ItemStack(Items.SHULKER_BOX), 1));
			h.assertTrue(rest != null && rest.getQuantity() == 1, "a shulker box went into the docked box");
			rest = te.pushStack(new StoredItemStack(new ItemStack(Items.BUNDLE), 1));
			h.assertTrue(rest != null && rest.getQuantity() == 1, "a bundle went into the docked box");
			// The box item carries what the network did, once it leaves
			ItemStack out = be.peekBox();
			h.assertValueEqual(inBox(out, Items.DIAMOND), 6, "diamonds written back");
			h.assertValueEqual(inBox(out, Items.IRON_INGOT), 20, "iron written back");
			// Item Locator and Network Doctor see it as an ordinary member
			InventoryConnectorBlockEntity conn = h.getBlockEntity(CONNECTOR);
			ItemLocator.SearchResult res = ItemLocator.collect(conn.getMergedHandler(), new StoredItemStack(new ItemStack(Items.DIAMOND), 1), h.getLevel(), ItemLocator::locateBlock);
			h.assertValueEqual(res.located().stream().map(ItemLocator.Location::pos).toList(), List.of(h.absolutePos(DOCK)), "item locator");
			h.assertValueEqual(DoctorScan.checkBlock(Content.shulkerDock.get(), h.getLevel(), h.absolutePos(DOCK)).verdict(), DoctorScan.Verdict.JOINS, "doctor verdict");
			h.assertTrue(ITidyExempt.isExempt(h.getLevel(), h.absolutePos(DOCK)), "dock not marked for Tidy");
			h.assertTrue(be.getInv() instanceof ITidyExempt, "dock container not marked for Tidy");
		})
		.thenWaitUntil(() -> h.assertValueEqual(terminalCount(te, Items.IRON_INGOT), 20L, "terminal iron"))
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void takeOutWhileNetworkAttachedNoDupe(GameTestHelper h) {
		dockWithNetwork(h, box(Items.ORANGE_SHULKER_BOX, new ItemStack(Items.DIAMOND, 64), new ItemStack(Items.DIAMOND, 30)));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		ShulkerDockBlockEntity be = dock(h, DOCK);
		ServerPlayer p = player(h, DOCK, GameType.SURVIVAL);
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(terminalCount(te, Items.DIAMOND), 94L, "terminal diamonds"))
		.thenExecute(() -> {
			h.assertValueEqual(total(h, Items.DIAMOND, DOCK, p), 94, "diamonds before");
			click(h, p, DOCK);
			h.assertFalse(be.hasBox(), "box not taken out");
			h.assertValueEqual(inBox(p.getMainHandItem(), Items.DIAMOND), 94, "diamonds in the taken-out box");
			// Same tick, before any tracker rescan: the network must not hand out anything
			StoredItemStack pulled = te.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)), 64);
			h.assertTrue(pulled == null || pulled.getQuantity() == 0, "network pulled diamonds from a removed box: " + (pulled == null ? 0 : pulled.getQuantity()));
			StoredItemStack rest = te.pushStack(new StoredItemStack(new ItemStack(Items.GOLD_INGOT), 5));
			h.assertTrue(rest != null && rest.getQuantity() == 5, "network inserted into an empty dock");
			h.assertValueEqual(be.getInv().getContainerSize(), 0, "slots after take-out");
			h.assertValueEqual(total(h, Items.DIAMOND, DOCK, p), 94, "diamonds after");
		})
		.thenWaitUntil(() -> {
			h.assertValueEqual(terminalCount(te, Items.DIAMOND), 0L, "terminal still lists the removed box");
			h.assertValueEqual(te.getSlotCount(), 0, "terminal slots after removal");
		})
		.thenExecute(() -> {
			// Docked again: visible again, still 94
			click(h, p, DOCK);
			h.assertTrue(be.hasBox(), "box not docked again");
		})
		.thenWaitUntil(() -> h.assertValueEqual(terminalCount(te, Items.DIAMOND), 94L, "terminal diamonds after docking again"))
		.thenExecute(() -> h.assertValueEqual(total(h, Items.DIAMOND, DOCK, p), 94, "diamonds at the end"))
		.thenSucceed();
	}

	// ---- break, explosion, save ----

	private static void assertDockAndBoxDropped(GameTestHelper h, ItemStack expectedBox) {
		List<ItemEntity> drops = drops(h, DOCK);
		List<ItemStack> stacks = drops.stream().map(ItemEntity::getItem).toList();
		long docks = stacks.stream().filter(s -> s.is(Content.shulkerDock.get().asItem())).mapToInt(ItemStack::getCount).sum();
		List<ItemStack> boxes = stacks.stream().filter(s -> s.is(ShulkerDockBlockEntity.DOCKABLE)).toList();
		h.assertValueEqual(docks, 1L, "dropped docks");
		h.assertValueEqual(boxes.size(), 1, "dropped boxes");
		h.assertTrue(ItemStack.matches(boxes.get(0), expectedBox), "dropped box differs: " + boxes.get(0).getComponents());
		// Never 27 spilled stacks: none of the box's items lies loose on the ground
		h.assertValueEqual(stacks.stream().filter(s -> s.is(Items.DIAMOND) || s.is(Items.ENDER_PEARL) || s.is(Items.NAME_TAG)).count(), 0L, "spilled contents: " + stacks);
	}

	private static ItemStack namedBox() {
		ItemStack b = box(Items.PURPLE_SHULKER_BOX, new ItemStack(Items.DIAMOND, 64), new ItemStack(Items.ENDER_PEARL, 16), new ItemStack(Items.NAME_TAG));
		b.set(DataComponents.CUSTOM_NAME, Component.literal("Loot"));
		return b;
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void breakingDropsDockAndBoxWithContents(GameTestHelper h) {
		ItemStack b = namedBox();
		h.setBlock(DOCK, Content.shulkerDock.get());
		h.assertTrue(dock(h, DOCK).dock(b.copy()), "not docked");
		h.getLevel().destroyBlock(h.absolutePos(DOCK), true);
		h.assertTrue(h.getBlockState(DOCK).isAir(), "dock not broken");
		assertDockAndBoxDropped(h, b);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void explosionDropsDockAndBoxWithContents(GameTestHelper h) {
		ItemStack b = namedBox();
		h.setBlock(DOCK.below(), Blocks.STONE);
		h.setBlock(DOCK, Content.shulkerDock.get());
		h.assertTrue(dock(h, DOCK).dock(b.copy()), "not docked");
		Vec3 c = Vec3.atCenterOf(h.absolutePos(DOCK));
		h.getLevel().explode(null, c.x, c.y, c.z, 1.5F, Level.ExplosionInteraction.TNT);
		h.assertTrue(h.getBlockState(DOCK).isAir(), "dock not exploded");
		assertDockAndBoxDropped(h, b);
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void saveLoadRoundTrip(GameTestHelper h) {
		ItemStack b = namedBox();
		h.setBlock(DOCK, Content.shulkerDock.get());
		ShulkerDockBlockEntity be = dock(h, DOCK);
		h.assertTrue(be.dock(b.copy()), "not docked");
		// A change made by the network after docking must survive the save too
		be.getInv().setItem(26, new ItemStack(Items.EMERALD, 7));
		be.getInv().setChanged();
		ItemStack expected = be.peekBox();
		h.assertValueEqual(inBox(expected, Items.EMERALD), 7, "emeralds in the expected box");

		CompoundTag tag = be.saveWithFullMetadata(h.getLevel().registryAccess());
		BlockEntity loaded = BlockEntity.loadStatic(h.absolutePos(DOCK), h.getBlockState(DOCK), tag, h.getLevel().registryAccess());
		h.assertTrue(loaded instanceof ShulkerDockBlockEntity, "reloaded block entity type");
		ShulkerDockBlockEntity l = (ShulkerDockBlockEntity) loaded;
		h.assertTrue(l.hasBox(), "box lost on reload");
		h.assertTrue(ItemStack.matches(l.peekBox(), expected), "reloaded box differs: " + l.peekBox().getComponents());
		Container inv = l.getInv();
		h.assertValueEqual(inv.getContainerSize(), 27, "reloaded slots");
		h.assertValueEqual(count(inv, Items.DIAMOND), 64, "reloaded diamonds");
		h.assertValueEqual(count(inv, Items.EMERALD), 7, "reloaded emeralds");
		h.assertTrue(inv.getItem(26).is(Items.EMERALD), "slot positions kept");

		// An empty dock saves nothing and loads empty
		be.undock();
		CompoundTag emptyTag = be.saveWithFullMetadata(h.getLevel().registryAccess());
		h.assertFalse(emptyTag.contains("Box"), "empty dock saved a box");
		BlockEntity loadedEmpty = BlockEntity.loadStatic(h.absolutePos(DOCK), h.getBlockState(DOCK), emptyTag, h.getLevel().registryAccess());
		h.assertFalse(((ShulkerDockBlockEntity) loadedEmpty).hasBox(), "empty dock loaded a box");
		h.succeed();
	}

	// ---- rules ----

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void containerRules(GameTestHelper h) {
		h.setBlock(DOCK, Content.shulkerDock.get());
		ShulkerDockBlockEntity be = dock(h, DOCK);
		Container inv = be.getInv();
		h.assertValueEqual(inv.getContainerSize(), 0, "no box: slots");
		h.assertFalse(inv.canPlaceItem(0, new ItemStack(Items.DIRT)), "no box: dirt accepted");
		h.assertTrue(inv.getItem(0).isEmpty(), "no box: slot 0 not empty");

		h.assertFalse(ShulkerDockBlockEntity.isDockable(new ItemStack(Items.CHEST)), "a chest is dockable");
		h.assertFalse(ShulkerDockBlockEntity.isDockable(new ItemStack(Items.BUNDLE)), "a bundle is dockable");
		h.assertFalse(be.dock(new ItemStack(Items.CHEST)), "a chest was docked");
		for (Item i : List.of(Items.SHULKER_BOX, Items.WHITE_SHULKER_BOX, Items.BLACK_SHULKER_BOX))
			h.assertTrue(ShulkerDockBlockEntity.isDockable(new ItemStack(i)), i + " not dockable");

		h.assertTrue(be.dock(new ItemStack(Items.YELLOW_SHULKER_BOX)), "empty box not docked");
		h.assertValueEqual(inv.getContainerSize(), 27, "box: slots");
		h.assertTrue(inv.canPlaceItem(0, new ItemStack(Items.DIRT)), "dirt rejected");
		for (Item rejected : List.of(Items.SHULKER_BOX, Items.RED_SHULKER_BOX, Items.BUNDLE))
			h.assertFalse(inv.canPlaceItem(0, new ItemStack(rejected)), rejected + " accepted");
		h.assertFalse(be.dock(new ItemStack(Items.RED_SHULKER_BOX)), "a second box was docked");

		// An empty box comes out identical to a fresh one (no leftover empty component)
		ItemStack out = be.undock();
		h.assertTrue(ItemStack.matches(out, new ItemStack(Items.YELLOW_SHULKER_BOX)), "empty box changed: " + out.getComponentsPatch());
		h.assertTrue(be.undock().isEmpty(), "second undock returned something");
		h.succeed();
	}

	/**
	 * A box that still carries its loot table (never opened, minecraft:container_loot) is refused like any
	 * other item that can not be docked: no block interaction, nothing consumed. Placed later it would roll
	 * the loot table and ignore the contents the dock wrote back.
	 */
	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void lootBoxRefused(GameTestHelper h) {
		h.setBlock(DOCK, Content.shulkerDock.get());
		ItemStack loot = new ItemStack(Items.BLUE_SHULKER_BOX);
		loot.set(DataComponents.CONTAINER_LOOT, new SeededContainerLoot(BuiltInLootTables.SIMPLE_DUNGEON, 42L));
		h.assertFalse(ShulkerDockBlockEntity.isDockable(loot), "a loot box is dockable");
		h.assertFalse(dock(h, DOCK).dock(loot.copy()), "a loot box was docked");

		ServerPlayer p = player(h, DOCK, GameType.SURVIVAL);
		p.setItemInHand(InteractionHand.MAIN_HAND, loot.copy());
		click(h, p, DOCK);
		h.assertFalse(dock(h, DOCK).hasBox(), "the dock took the loot box");
		h.assertValueEqual(shown(h, DOCK), ShulkerDockBlock.Box.NONE, "dock state");
		// Passed on to the item, like every other refused item: vanilla places the box in front of the dock,
		// loot table and all (nothing was taken out of it or voided)
		BlockPos placed = DOCK.south();
		h.assertTrue(h.getBlockState(placed).is(Blocks.BLUE_SHULKER_BOX), "the box did not go its vanilla way: " + h.getBlockState(placed) + ", hand " + p.getMainHandItem());
		h.assertTrue(h.getBlockEntity(placed) instanceof ShulkerBoxBlockEntity sb && BuiltInLootTables.SIMPLE_DUNGEON.equals(sb.getLootTable()), "placed box lost its loot table");
		h.assertTrue(p.getMainHandItem().isEmpty(), "box placed and still in hand: " + p.getMainHandItem());
		h.setBlock(placed, Blocks.AIR);

		// The same box after its loot was rolled (component gone) docks as usual
		ItemStack opened = new ItemStack(Items.BLUE_SHULKER_BOX);
		h.assertTrue(ShulkerDockBlockEntity.isDockable(opened), "an opened box is not dockable");
		p.setItemInHand(InteractionHand.MAIN_HAND, opened);
		click(h, p, DOCK);
		h.assertTrue(dock(h, DOCK).hasBox(), "opened box not docked");
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void comparatorValues(GameTestHelper h) {
		h.setBlock(DOCK.below(), Blocks.STONE);
		h.setBlock(DOCK.east().below(), Blocks.STONE);
		h.setBlock(DOCK, Content.shulkerDock.get());
		// Comparator east of the dock, reading it from its back (west)
		h.setBlock(DOCK.east(), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST));
		ShulkerDockBlockEntity be = dock(h, DOCK);
		ServerLevel l = h.getLevel();
		BlockPos a = h.absolutePos(DOCK);
		h.assertValueEqual(h.getBlockState(DOCK).getAnalogOutputSignal(l, a), 0, "no box");
		be.dock(new ItemStack(Items.GREEN_SHULKER_BOX));
		h.assertValueEqual(h.getBlockState(DOCK).getAnalogOutputSignal(l, a), 0, "empty box");

		Container inv = be.getInv();
		inv.setItem(0, new ItemStack(Items.DIRT));
		inv.setChanged();
		h.assertValueEqual(h.getBlockState(DOCK).getAnalogOutputSignal(l, a), 1, "one item");
		for (int i = 0; i < 13; i++)inv.setItem(i, new ItemStack(Items.DIRT, 64));
		inv.setItem(13, new ItemStack(Items.ENDER_PEARL, 16));
		inv.setChanged();
		int half = RedstoneSignals.fill(14, true, 27);
		h.assertValueEqual(h.getBlockState(DOCK).getAnalogOutputSignal(l, a), half, "14 of 27 full slots");
		h.assertValueEqual(be.getComparatorSignal(), be.vanillaComparatorSignal(), "vanilla formula");
		for (int i = 0; i < 27; i++)inv.setItem(i, new ItemStack(Items.DIRT, 64));
		inv.setChanged();
		h.assertValueEqual(h.getBlockState(DOCK).getAnalogOutputSignal(l, a), 15, "full box");
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(((ComparatorBlockEntity) h.getBlockEntity(DOCK.east())).getOutputSignal(), 15, "comparator block output, full box"))
		.thenExecute(() -> {
			ItemStack out = be.undock();
			h.assertValueEqual(inBox(out, Items.DIRT), 27 * 64, "dirt in the taken-out box");
			h.assertValueEqual(h.getBlockState(DOCK).getAnalogOutputSignal(l, a), 0, "after take-out");
		})
		.thenWaitUntil(() -> h.assertValueEqual(((ComparatorBlockEntity) h.getBlockEntity(DOCK.east())).getOutputSignal(), 0, "comparator block output after take-out"))
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void frameLabelOnDock(GameTestHelper h) {
		dockWithNetwork(h, box(Items.BROWN_SHULKER_BOX, new ItemStack(Items.COBBLESTONE, 1)));
		chest(h, new BlockPos(4, 2, 3), new ItemStack(Items.IRON_INGOT, 1));
		// Glow frame on the dock's west face, showing iron
		FrameLabelGameTests.glowFrame(h, DOCK.west(), Direction.WEST, new ItemStack(Items.IRON_INGOT));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		ShulkerDockBlockEntity be = dock(h, DOCK);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(FrameLabelGameTests.labelActive(h), "label not applied");
			h.assertValueEqual(terminalCount(te, Items.COBBLESTONE), 1L, "terminal cobblestone");
		})
		.thenExecute(() -> {
			h.assertValueEqual(FrameLabelGameTests.push(te, Items.IRON_INGOT, 20), 0L, "iron remainder");
			h.assertValueEqual(count(be.getInv(), Items.IRON_INGOT), 20, "iron goes into the labelled dock first");
			h.assertValueEqual(FrameLabelGameTests.push(te, Items.DIRT, 10), 0L, "dirt remainder");
			h.assertValueEqual(count(be.getInv(), Items.DIRT), 0, "dirt went into the labelled dock");
		})
		.thenSucceed();
	}

	// ---- data ----

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void recipeTagsAndLoot(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("obscuras_storage", "shulker_dock");
		RecipeHolder<?> r = server.getRecipeManager().byKey(id).orElse(null);
		h.assertTrue(r != null, "recipe obscuras_storage:shulker_dock not loaded");
		h.assertTrue(r.value().getResultItem(h.getLevel().registryAccess()).is(Content.shulkerDock.get().asItem()), "recipe result");
		h.assertValueEqual(r.value().getIngredients().size(), 4, "recipe ingredients");
		h.assertTrue(r.value().getIngredients().stream().anyMatch(i -> i.test(new ItemStack(Items.BARREL))), "barrel ingredient");
		h.assertTrue(r.value().getIngredients().stream().anyMatch(i -> i.test(new ItemStack(Content.invCable.get()))), "cable ingredient");
		h.assertValueEqual(r.value().getIngredients().stream().filter(i -> i.test(new ItemStack(Items.IRON_INGOT))).count(), 2L, "iron ingredients");
		h.assertTrue(server.getAdvancements().get(ResourceLocation.fromNamespaceAndPath("obscuras_storage", "recipes/decorations/shulker_dock")) != null, "recipe advancement");
		h.assertTrue(Content.shulkerDock.get().defaultBlockState().is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE), "not mineable with an axe");
		int dockable = 0;
		for (Item i : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
			if (new ItemStack(i).is(ShulkerDockBlockEntity.DOCKABLE))dockable++;
		}
		h.assertValueEqual(dockable, 17, "dockable items");
		h.assertTrue(new ItemStack(Content.shulkerDock.get()).getItem().getDescriptionId().equals("block.obscuras_storage.shulker_dock"), "description id");
		h.succeed();
	}

	/** Every state of the block resolves to a blockstate variant, a model file and existing textures (no client needed). */
	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 20)
	public void blockstateVariantsResolve(GameTestHelper h) {
		JsonObject variants = json(h, "assets/obscuras_storage/blockstates/shulker_dock.json").getAsJsonObject("variants");
		List<BlockState> states = Content.shulkerDock.get().getStateDefinition().getPossibleStates();
		h.assertValueEqual(states.size(), 72, "block states");
		h.assertValueEqual(variants.size(), 72, "blockstate variants");
		Set<String> models = new HashSet<>();
		for (BlockState st : states) {
			String key = st.getValues().entrySet().stream()
					.sorted(Map.Entry.comparingByKey((x, y) -> x.getName().compareTo(y.getName())))
					.map(e -> e.getKey().getName() + "=" + name(e.getKey(), e.getValue()))
					.collect(Collectors.joining(","));
			h.assertTrue(variants.has(key), "no variant for " + key);
			JsonObject v = variants.getAsJsonObject(key);
			int y = v.has("y") ? v.get("y").getAsInt() : 0;
			int expectedY = switch (st.getValue(ShulkerDockBlock.FACING)) {
				case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0;
			};
			h.assertValueEqual(y, expectedY, "rotation of " + key);
			String model = v.get("model").getAsString();
			ShulkerDockBlock.Box box = st.getValue(ShulkerDockBlock.BOX);
			String expectedModel = "obscuras_storage:block/shulker_dock" + (box == ShulkerDockBlock.Box.NONE ? "" : box == ShulkerDockBlock.Box.SHULKER ? "_box" : "_box_" + box.getSerializedName());
			h.assertValueEqual(model, expectedModel, "model of " + key);
			models.add(model);
		}
		h.assertValueEqual(models.size(), 18, "distinct models");
		models.add("obscuras_storage:item/shulker_dock");
		for (String m : models)checkModel(h, m, new ArrayList<>());
		h.succeed();
	}

	private static String name(Property<?> p, Comparable<?> v) {
		return nameOf(p, v);
	}

	@SuppressWarnings("unchecked")
	private static <T extends Comparable<T>> String nameOf(Property<T> p, Comparable<?> v) {
		return p.getName((T) v);
	}

	private static void checkModel(GameTestHelper h, String model, List<String> chain) {
		h.assertFalse(chain.contains(model), "parent loop " + chain);
		chain.add(model);
		ResourceLocation rl = ResourceLocation.parse(model);
		if (rl.getNamespace().equals("minecraft")) {
			h.assertTrue(rl.getPath().equals("block/block"), "unexpected vanilla parent " + model);
			return;
		}
		JsonObject m = json(h, "assets/" + rl.getNamespace() + "/models/" + rl.getPath() + ".json");
		if (m.has("textures")) {
			for (var e : m.getAsJsonObject("textures").entrySet()) {
				String t = e.getValue().getAsString();
				if (t.startsWith("#"))continue;
				ResourceLocation tex = ResourceLocation.parse(t);
				if (tex.getNamespace().equals("minecraft")) {
					h.assertTrue(tex.getPath().matches("block/([a-z_]+_)?shulker_box"), "unexpected vanilla texture " + t + " in " + model);
				} else {
					String path = "assets/" + tex.getNamespace() + "/textures/" + tex.getPath() + ".png";
					h.assertTrue(Content.class.getClassLoader().getResource(path) != null, "missing texture " + path + " (" + model + ")");
				}
			}
		}
		if (m.has("parent"))checkModel(h, m.get("parent").getAsString(), chain);
		else h.assertTrue(m.has("elements"), model + " has neither parent nor elements");
	}

	private static JsonObject json(GameTestHelper h, String path) {
		InputStream in = Content.class.getClassLoader().getResourceAsStream(path);
		h.assertTrue(in != null, "missing resource " + path);
		try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(r).getAsJsonObject();
		} catch (IOException e) {
			throw new IllegalStateException(path, e);
		}
	}

	// ---- loader specific ----

	static ServerPlayer fakePlayer(GameTestHelper h) {
		return FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "dock-test"));
	}
}
