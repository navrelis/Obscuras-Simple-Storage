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
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.obscura.storage.Content;
import com.obscura.storage.block.ShulkerDockBlock;
import com.obscura.storage.doctor.DoctorScan;
import com.obscura.storage.tile.ShulkerDockBlockEntity;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.ITidyExempt;
import com.obscura.storage.util.RedstoneSignals;
import com.obscura.storage.util.StoredItemStack;

/**
 * The Shulker Dock GameTests of both 1.20.1 loaders (identical copies in Fabric-1.20.1 and
 * Forge-1.20.1, like {@link LegacyMigrationChecks}); the loader specific parts come in through
 * {@link Platform}. Ported from the 1.21.1 tests, with the 1.20.1 shulker box item format
 * ({@code BlockEntityTag.Items}).
 */
public final class ShulkerDockChecks {
	static final BlockPos DOCK = new BlockPos(2, 2, 3);
	static final BlockPos CONNECTOR = new BlockPos(3, 2, 3);
	static final BlockPos TERMINAL = new BlockPos(3, 3, 3);

	private ShulkerDockChecks() {}

	/** What differs between the loaders. */
	public interface Platform {
		ServerPlayer fakePlayer(GameTestHelper h);

		long terminalCount(StorageTerminalBlockEntity te, Item item);

		/** Item Locator: the blocks of the terminal's network holding {@code item}. */
		List<BlockPos> locate(StorageTerminalBlockEntity te, GameTestHelper h, Item item);

		/** A resource of the mod jar (assets, data), null when missing. */
		InputStream resource(String path);
	}

	// ---- helpers ----

	/** A shulker box item with contents, written like vanilla writes a broken box (id + Items). */
	static ItemStack box(Item boxItem, ItemStack... contents) {
		ItemStack b = new ItemStack(boxItem);
		NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
		for (int i = 0; i < contents.length; i++)items.set(i, contents[i].copy());
		CompoundTag bet = new CompoundTag();
		ContainerHelper.saveAllItems(bet, items);
		BlockItem.setBlockEntityData(b, BlockEntityType.SHULKER_BOX, bet);
		return b;
	}

	/** Items of {@code item} stored in a box item's {@code BlockEntityTag.Items}. */
	static int inBox(ItemStack box, Item item) {
		// A used up stack (count 0) keeps its NBT in 1.20.1, it holds nothing
		if (box.isEmpty())return 0;
		CompoundTag bet = BlockItem.getBlockEntityData(box);
		if (bet == null)return 0;
		ListTag l = bet.getList("Items", Tag.TAG_COMPOUND);
		int n = 0;
		for (int i = 0; i < l.size(); i++) {
			ItemStack s = ItemStack.of(l.getCompound(i));
			if (s.is(item))n += s.getCount();
		}
		return n;
	}

	static ShulkerDockBlockEntity dock(GameTestHelper h, BlockPos rel) {
		return be(h, rel);
	}

	static ShulkerDockBlock.Box shown(GameTestHelper h, BlockPos rel) {
		return h.getBlockState(rel).getValue(ShulkerDockBlock.BOX);
	}

	/** A fresh player in {@code mode}, standing two blocks south of {@code rel}, with an empty inventory. */
	static ServerPlayer player(GameTestHelper h, Platform pf, BlockPos rel, GameType mode) {
		ServerPlayer p = pf.fakePlayer(h);
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

	static boolean same(ItemStack a, ItemStack b) {
		return ItemStack.matches(a, b);
	}

	// ---- hand interactions ----

	static void handInsertSwapTakeOut(GameTestHelper h, Platform pf) {
		h.setBlock(DOCK, Content.shulkerDock.get().defaultBlockState().setValue(ShulkerDockBlock.FACING, Direction.SOUTH));
		ServerPlayer p = player(h, pf, DOCK, GameType.SURVIVAL);
		ShulkerDockBlockEntity be = dock(h, DOCK);

		// Empty hand on an empty dock: nothing
		click(h, p, DOCK);
		h.assertFalse(be.hasBox(), "empty hand docked something");
		h.assertTrue(p.getMainHandItem().isEmpty(), "empty hand got something");
		eq(be.getInv().getContainerSize(), 0, "slots without a box");

		// Insert: the held box goes in, with its contents
		ItemStack red = box(Items.RED_SHULKER_BOX, new ItemStack(Items.DIAMOND, 5));
		red.setHoverName(Component.literal("Diamonds"));
		ItemStack redOriginal = red.copy();
		p.setItemInHand(InteractionHand.MAIN_HAND, red);
		click(h, p, DOCK);
		h.assertTrue(p.getMainHandItem().isEmpty(), "box still in hand after docking");
		h.assertTrue(be.hasBox(), "box not docked");
		eq(shown(h, DOCK), ShulkerDockBlock.Box.RED, "window colour");
		eq(be.getInv().getContainerSize(), 27, "slots with a box");
		eq(count(be.getInv(), Items.DIAMOND), 5, "diamonds in the live slots");

		// Anything else passes: cobblestone is placed on top as usual, the dock keeps its box
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 3));
		click(h, p, DOCK, Direction.UP);
		h.assertTrue(h.getBlockState(DOCK.above()).is(Blocks.COBBLESTONE), "cobblestone was not placed on the dock");
		eq(p.getMainHandItem().getCount(), 2, "cobblestone used");
		h.assertTrue(be.hasBox(), "other item took the box out");
		p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

		// Swap: the held box goes in, the docked one comes back, exactly as it went in
		ItemStack blue = box(Items.BLUE_SHULKER_BOX, new ItemStack(Items.EMERALD, 3));
		p.setItemInHand(InteractionHand.MAIN_HAND, blue.copy());
		click(h, p, DOCK);
		h.assertTrue(same(p.getMainHandItem(), redOriginal), "swapped-out box differs: " + p.getMainHandItem().getTag());
		eq(shown(h, DOCK), ShulkerDockBlock.Box.BLUE, "window colour after swap");
		eq(count(be.getInv(), Items.EMERALD), 3, "emeralds in the live slots");
		eq(count(be.getInv(), Items.DIAMOND), 0, "diamonds left behind in the dock");

		// Take out into the empty hand
		p.getInventory().add(p.getMainHandItem().copy());
		p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		click(h, p, DOCK);
		h.assertTrue(same(p.getMainHandItem(), blue), "taken-out box differs: " + p.getMainHandItem().getTag());
		h.assertFalse(be.hasBox(), "dock still has a box");
		eq(shown(h, DOCK), ShulkerDockBlock.Box.NONE, "window after take-out");
		eq(be.getInv().getContainerSize(), 0, "slots after take-out");
		eq(total(h, Items.DIAMOND, DOCK, p), 5, "diamonds conserved");
		eq(total(h, Items.EMERALD, DOCK, p), 3, "emeralds conserved");
		h.succeed();
	}

	static void offHandAndSneak(GameTestHelper h, Platform pf) {
		h.setBlock(DOCK, Content.shulkerDock.get());
		ServerPlayer p = player(h, pf, DOCK, GameType.SURVIVAL);
		ShulkerDockBlockEntity be = dock(h, DOCK);
		ItemStack box = box(Items.SHULKER_BOX, new ItemStack(Items.GOLD_INGOT, 9));

		// Main hand empty, box in the off hand: the main hand does nothing on an empty dock, the off hand docks
		p.setItemInHand(InteractionHand.OFF_HAND, box.copy());
		click(h, p, DOCK);
		h.assertTrue(be.hasBox(), "off hand box not docked");
		h.assertTrue(p.getOffhandItem().isEmpty(), "off hand box not consumed");
		eq(shown(h, DOCK), ShulkerDockBlock.Box.SHULKER, "undyed window");

		// Empty main hand takes it out into the main hand; the off hand stays empty
		click(h, p, DOCK);
		h.assertTrue(same(p.getMainHandItem(), box), "box not in the main hand");
		h.assertTrue(p.getOffhandItem().isEmpty(), "off hand got something");
		h.assertFalse(be.hasBox(), "box still docked");

		// Block in the main hand, box in the off hand: the block is placed, the box stays in the off hand
		p.setItemInHand(InteractionHand.OFF_HAND, p.getMainHandItem().copy());
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT));
		click(h, p, DOCK, Direction.UP);
		h.assertTrue(h.getBlockState(DOCK.above()).is(Blocks.DIRT), "dirt not placed");
		h.assertFalse(be.hasBox(), "off hand box docked although the main hand acted");
		h.assertTrue(same(p.getOffhandItem(), box), "off hand box changed");
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

	static void creativeMovesTheBoxNeverCopies(GameTestHelper h, Platform pf) {
		h.setBlock(DOCK, Content.shulkerDock.get());
		ServerPlayer p = player(h, pf, DOCK, GameType.CREATIVE);
		ShulkerDockBlockEntity be = dock(h, DOCK);
		ItemStack box = box(Items.LIME_SHULKER_BOX, new ItemStack(Items.NETHERITE_INGOT, 2));
		p.setItemInHand(InteractionHand.MAIN_HAND, box.copy());
		click(h, p, DOCK);
		h.assertTrue(be.hasBox(), "creative insert did not dock");
		h.assertTrue(p.getMainHandItem().isEmpty(), "creative insert copied the box instead of moving it");
		eq(total(h, Items.NETHERITE_INGOT, DOCK, p), 2, "netherite after creative insert");
		click(h, p, DOCK);
		h.assertTrue(same(p.getMainHandItem(), box), "creative take-out");
		h.assertFalse(be.hasBox(), "box still docked after creative take-out");
		eq(total(h, Items.NETHERITE_INGOT, DOCK, p), 2, "netherite after creative take-out");
		// A second click with the box in hand docks it again, the next empty-hand click is the only way out
		click(h, p, DOCK);
		click(h, p, DOCK);
		eq(total(h, Items.NETHERITE_INGOT, DOCK, p), 2, "netherite after another round");
		h.succeed();
	}

	// ---- network ----

	static void networkReadsAndWritesTheBox(GameTestHelper h, Platform pf) {
		dockWithNetwork(h, box(Items.CYAN_SHULKER_BOX, new ItemStack(Items.DIAMOND, 10)));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		ShulkerDockBlockEntity be = dock(h, DOCK);
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(pf.terminalCount(te, Items.DIAMOND), 10L, "terminal diamonds");
			eq(te.getSlotCount(), 27, "terminal slots");
		})
		.thenExecute(() -> {
			StoredItemStack pulled = te.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)), 4);
			h.assertTrue(pulled != null && pulled.getQuantity() == 4, "pulled diamonds");
			eq(count(be.getInv(), Items.DIAMOND), 6, "diamonds left in the box");
			h.assertTrue(te.pushStack(new StoredItemStack(new ItemStack(Items.IRON_INGOT), 20)) == null, "iron push left a remainder");
			eq(count(be.getInv(), Items.IRON_INGOT), 20, "iron in the box");
			// Shulker boxes and bundles never go into a docked box
			StoredItemStack rest = te.pushStack(new StoredItemStack(new ItemStack(Items.SHULKER_BOX), 1));
			h.assertTrue(rest != null && rest.getQuantity() == 1, "a shulker box went into the docked box");
			rest = te.pushStack(new StoredItemStack(new ItemStack(Items.BUNDLE), 1));
			h.assertTrue(rest != null && rest.getQuantity() == 1, "a bundle went into the docked box");
			// The box item carries what the network did, once it leaves
			ItemStack out = be.peekBox();
			eq(inBox(out, Items.DIAMOND), 6, "diamonds written back");
			eq(inBox(out, Items.IRON_INGOT), 20, "iron written back");
			// Item Locator and Network Doctor see it as an ordinary member
			eq(pf.locate(te, h, Items.DIAMOND), List.of(h.absolutePos(DOCK)), "item locator");
			eq(DoctorScan.checkBlock(Content.shulkerDock.get(), h.getLevel(), h.absolutePos(DOCK)).verdict(), DoctorScan.Verdict.JOINS, "doctor verdict");
			h.assertTrue(ITidyExempt.isExempt(h.getLevel(), h.absolutePos(DOCK)), "dock not marked for Tidy");
			h.assertTrue(be.getInv() instanceof ITidyExempt, "dock container not marked for Tidy");
		})
		.thenWaitUntil(() -> eq(pf.terminalCount(te, Items.IRON_INGOT), 20L, "terminal iron"))
		.thenSucceed();
	}

	static void takeOutWhileNetworkAttachedNoDupe(GameTestHelper h, Platform pf) {
		dockWithNetwork(h, box(Items.ORANGE_SHULKER_BOX, new ItemStack(Items.DIAMOND, 64), new ItemStack(Items.DIAMOND, 30)));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		ShulkerDockBlockEntity be = dock(h, DOCK);
		ServerPlayer p = player(h, pf, DOCK, GameType.SURVIVAL);
		h.startSequence()
		.thenWaitUntil(() -> eq(pf.terminalCount(te, Items.DIAMOND), 94L, "terminal diamonds"))
		.thenExecute(() -> {
			eq(total(h, Items.DIAMOND, DOCK, p), 94, "diamonds before");
			click(h, p, DOCK);
			h.assertFalse(be.hasBox(), "box not taken out");
			eq(inBox(p.getMainHandItem(), Items.DIAMOND), 94, "diamonds in the taken-out box");
			// Same tick, before any rescan: the network must not hand out anything
			StoredItemStack pulled = te.pullStack(new StoredItemStack(new ItemStack(Items.DIAMOND)), 64);
			h.assertTrue(pulled == null || pulled.getQuantity() == 0, "network pulled diamonds from a removed box: " + (pulled == null ? 0 : pulled.getQuantity()));
			StoredItemStack rest = te.pushStack(new StoredItemStack(new ItemStack(Items.GOLD_INGOT), 5));
			h.assertTrue(rest != null && rest.getQuantity() == 5, "network inserted into an empty dock");
			eq(be.getInv().getContainerSize(), 0, "slots after take-out");
			eq(total(h, Items.DIAMOND, DOCK, p), 94, "diamonds after");
			eq(total(h, Items.GOLD_INGOT, DOCK, p), 0, "gold that went nowhere");
		})
		.thenWaitUntil(() -> {
			eq(pf.terminalCount(te, Items.DIAMOND), 0L, "terminal still lists the removed box");
			eq(te.getSlotCount(), 0, "terminal slots after removal");
		})
		.thenExecute(() -> {
			// Docked again: visible again, still 94
			click(h, p, DOCK);
			h.assertTrue(be.hasBox(), "box not docked again");
		})
		.thenWaitUntil(() -> eq(pf.terminalCount(te, Items.DIAMOND), 94L, "terminal diamonds after docking again"))
		.thenExecute(() -> eq(total(h, Items.DIAMOND, DOCK, p), 94, "diamonds at the end"))
		.thenSucceed();
	}

	// ---- break, explosion, save ----

	private static void assertDockAndBoxDropped(GameTestHelper h, ItemStack expectedBox) {
		List<ItemEntity> drops = drops(h, DOCK);
		List<ItemStack> stacks = drops.stream().map(ItemEntity::getItem).toList();
		long docks = stacks.stream().filter(s -> s.is(Content.shulkerDock.get().asItem())).mapToInt(ItemStack::getCount).sum();
		List<ItemStack> boxes = stacks.stream().filter(s -> s.is(ShulkerDockBlockEntity.DOCKABLE)).toList();
		eq(docks, 1L, "dropped docks");
		eq(boxes.size(), 1, "dropped boxes");
		h.assertTrue(same(boxes.get(0), expectedBox), "dropped box differs: " + boxes.get(0).getTag());
		// Never 27 spilled stacks: none of the box's items lies loose on the ground
		eq(stacks.stream().filter(s -> s.is(Items.DIAMOND) || s.is(Items.ENDER_PEARL) || s.is(Items.NAME_TAG)).count(), 0L, "spilled contents: " + stacks);
	}

	private static ItemStack namedBox() {
		ItemStack b = box(Items.PURPLE_SHULKER_BOX, new ItemStack(Items.DIAMOND, 64), new ItemStack(Items.ENDER_PEARL, 16), new ItemStack(Items.NAME_TAG));
		b.setHoverName(Component.literal("Loot"));
		return b;
	}

	static void breakingDropsDockAndBoxWithContents(GameTestHelper h) {
		ItemStack b = namedBox();
		h.setBlock(DOCK, Content.shulkerDock.get());
		h.assertTrue(dock(h, DOCK).dock(b.copy()), "not docked");
		h.getLevel().destroyBlock(h.absolutePos(DOCK), true);
		h.assertTrue(h.getBlockState(DOCK).isAir(), "dock not broken");
		assertDockAndBoxDropped(h, b);
		h.succeed();
	}

	static void explosionDropsDockAndBoxWithContents(GameTestHelper h) {
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

	static void saveLoadRoundTrip(GameTestHelper h) {
		ItemStack b = namedBox();
		h.setBlock(DOCK, Content.shulkerDock.get());
		ShulkerDockBlockEntity be = dock(h, DOCK);
		h.assertTrue(be.dock(b.copy()), "not docked");
		// A change made by the network after docking must survive the save too
		be.getInv().setItem(26, new ItemStack(Items.EMERALD, 7));
		be.getInv().setChanged();
		ItemStack expected = be.peekBox();
		eq(inBox(expected, Items.EMERALD), 7, "emeralds in the expected box");

		CompoundTag tag = be.saveWithFullMetadata();
		// Saved in the vanilla item format: the box item with BlockEntityTag.Items
		ItemStack saved = ItemStack.of(tag.getCompound("Box"));
		h.assertTrue(same(saved, expected), "saved box differs: " + saved.getTag());
		BlockEntity loaded = BlockEntity.loadStatic(h.absolutePos(DOCK), h.getBlockState(DOCK), tag);
		h.assertTrue(loaded instanceof ShulkerDockBlockEntity, "reloaded block entity type");
		ShulkerDockBlockEntity l = (ShulkerDockBlockEntity) loaded;
		h.assertTrue(l.hasBox(), "box lost on reload");
		h.assertTrue(same(l.peekBox(), expected), "reloaded box differs: " + l.peekBox().getTag());
		Container inv = l.getInv();
		eq(inv.getContainerSize(), 27, "reloaded slots");
		eq(count(inv, Items.DIAMOND), 64, "reloaded diamonds");
		eq(count(inv, Items.EMERALD), 7, "reloaded emeralds");
		h.assertTrue(inv.getItem(26).is(Items.EMERALD), "slot positions kept");

		// An empty dock saves nothing and loads empty
		be.undock();
		CompoundTag emptyTag = be.saveWithFullMetadata();
		h.assertFalse(emptyTag.contains("Box"), "empty dock saved a box");
		BlockEntity loadedEmpty = BlockEntity.loadStatic(h.absolutePos(DOCK), h.getBlockState(DOCK), emptyTag);
		h.assertFalse(((ShulkerDockBlockEntity) loadedEmpty).hasBox(), "empty dock loaded a box");
		h.succeed();
	}

	/**
	 * The 1.20.1 item formats vanilla produces come out unchanged when nothing touched them, and a
	 * box filled by the network is what vanilla makes of a filled box (it places and opens normally).
	 */
	static void vanillaBoxFormatsRoundTrip(GameTestHelper h) {
		h.setBlock(DOCK, Content.shulkerDock.get());
		ShulkerDockBlockEntity be = dock(h, DOCK);
		// Survival-broken empty box: BlockEntityTag {id, Items: []}
		ItemStack emptyList = box(Items.WHITE_SHULKER_BOX);
		CompoundTag bet = BlockItem.getBlockEntityData(emptyList);
		h.assertTrue(bet != null && bet.getList("Items", Tag.TAG_COMPOUND).isEmpty() && bet.contains("Items"), "test box has an empty Items list");
		// Locked box and a crafted one without any data
		ItemStack locked = box(Items.BLACK_SHULKER_BOX, new ItemStack(Items.APPLE, 3));
		BlockItem.getBlockEntityData(locked).putString("Lock", "key");
		ItemStack crafted = new ItemStack(Items.GRAY_SHULKER_BOX);
		for (ItemStack in : List.of(emptyList, locked, crafted)) {
			h.assertTrue(be.dock(in.copy()), "not docked: " + in.getTag());
			ItemStack out = be.undock();
			h.assertTrue(same(out, in), "box changed by the dock: " + in.getTag() + " -> " + out.getTag());
		}
		// The crafted box filled by the network: vanilla format, id and Items with Slot bytes
		h.assertTrue(be.dock(crafted.copy()), "crafted box not docked");
		be.getInv().setItem(4, new ItemStack(Items.COAL, 12));
		be.getInv().setChanged();
		ItemStack filled = be.undock();
		CompoundTag fb = BlockItem.getBlockEntityData(filled);
		h.assertTrue(fb != null, "no BlockEntityTag on the filled box");
		eq(fb.getString("id"), "minecraft:shulker_box", "block entity id");
		ListTag items = fb.getList("Items", Tag.TAG_COMPOUND);
		eq(items.size(), 1, "item entries");
		eq(items.getCompound(0).getByte("Slot"), (byte) 4, "slot");
		eq(ItemStack.of(items.getCompound(0)).getCount(), 12, "count");
		// The filled box placed as a block holds the coal
		h.setBlock(DOCK.above(), Blocks.GRAY_SHULKER_BOX);
		BlockEntity placed = h.getBlockEntity(DOCK.above());
		placed.load(fb.copy());
		eq(count((Container) placed, Items.COAL), 12, "coal in the placed box");
		// Refused: unopened loot boxes, slots past 27, a slot listed twice
		ItemStack loot = new ItemStack(Items.RED_SHULKER_BOX);
		loot.getOrCreateTagElement("BlockEntityTag").putString("LootTable", "minecraft:chests/simple_dungeon");
		h.assertFalse(ShulkerDockBlockEntity.isDockable(loot), "a loot table box is dockable");
		ItemStack big = box(Items.RED_SHULKER_BOX, new ItemStack(Items.APPLE));
		BlockItem.getBlockEntityData(big).getList("Items", Tag.TAG_COMPOUND).getCompound(0).putByte("Slot", (byte) 30);
		h.assertFalse(ShulkerDockBlockEntity.isDockable(big), "a box with slot 30 is dockable");
		ItemStack twice = box(Items.RED_SHULKER_BOX, new ItemStack(Items.APPLE), new ItemStack(Items.STICK));
		BlockItem.getBlockEntityData(twice).getList("Items", Tag.TAG_COMPOUND).getCompound(1).putByte("Slot", (byte) 0);
		h.assertFalse(ShulkerDockBlockEntity.isDockable(twice), "a box with slot 0 twice is dockable");
		h.assertFalse(be.dock(loot), "loot box docked");
		h.succeed();
	}

	// ---- rules ----

	static void containerRules(GameTestHelper h) {
		h.setBlock(DOCK, Content.shulkerDock.get());
		ShulkerDockBlockEntity be = dock(h, DOCK);
		Container inv = be.getInv();
		eq(inv.getContainerSize(), 0, "no box: slots");
		h.assertFalse(inv.canPlaceItem(0, new ItemStack(Items.DIRT)), "no box: dirt accepted");
		h.assertTrue(inv.getItem(0).isEmpty(), "no box: slot 0 not empty");

		h.assertFalse(ShulkerDockBlockEntity.isDockable(new ItemStack(Items.CHEST)), "a chest is dockable");
		h.assertFalse(ShulkerDockBlockEntity.isDockable(new ItemStack(Items.BUNDLE)), "a bundle is dockable");
		h.assertFalse(be.dock(new ItemStack(Items.CHEST)), "a chest was docked");
		for (Item i : List.of(Items.SHULKER_BOX, Items.WHITE_SHULKER_BOX, Items.BLACK_SHULKER_BOX))
			h.assertTrue(ShulkerDockBlockEntity.isDockable(new ItemStack(i)), i + " not dockable");

		h.assertTrue(be.dock(new ItemStack(Items.YELLOW_SHULKER_BOX)), "empty box not docked");
		eq(inv.getContainerSize(), 27, "box: slots");
		h.assertTrue(inv.canPlaceItem(0, new ItemStack(Items.DIRT)), "dirt rejected");
		for (Item rejected : List.of(Items.SHULKER_BOX, Items.RED_SHULKER_BOX, Items.BUNDLE))
			h.assertFalse(inv.canPlaceItem(0, new ItemStack(rejected)), rejected + " accepted");
		h.assertFalse(be.dock(new ItemStack(Items.RED_SHULKER_BOX)), "a second box was docked");

		// An empty box comes out identical to a fresh one (no leftover empty data)
		ItemStack out = be.undock();
		h.assertTrue(same(out, new ItemStack(Items.YELLOW_SHULKER_BOX)), "empty box changed: " + out.getTag());
		h.assertTrue(be.undock().isEmpty(), "second undock returned something");
		h.succeed();
	}

	static void comparatorValues(GameTestHelper h) {
		h.setBlock(DOCK.below(), Blocks.STONE);
		h.setBlock(DOCK.east().below(), Blocks.STONE);
		h.setBlock(DOCK, Content.shulkerDock.get());
		// Comparator east of the dock, reading it from its back (west)
		h.setBlock(DOCK.east(), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST));
		ShulkerDockBlockEntity be = dock(h, DOCK);
		ServerLevel l = h.getLevel();
		BlockPos a = h.absolutePos(DOCK);
		eq(h.getBlockState(DOCK).getAnalogOutputSignal(l, a), 0, "no box");
		be.dock(new ItemStack(Items.GREEN_SHULKER_BOX));
		eq(h.getBlockState(DOCK).getAnalogOutputSignal(l, a), 0, "empty box");

		Container inv = be.getInv();
		inv.setItem(0, new ItemStack(Items.DIRT));
		inv.setChanged();
		eq(h.getBlockState(DOCK).getAnalogOutputSignal(l, a), 1, "one item");
		for (int i = 0; i < 13; i++)inv.setItem(i, new ItemStack(Items.DIRT, 64));
		inv.setItem(13, new ItemStack(Items.ENDER_PEARL, 16));
		inv.setChanged();
		int half = RedstoneSignals.fill(14, true, 27);
		eq(h.getBlockState(DOCK).getAnalogOutputSignal(l, a), half, "14 of 27 full slots");
		eq(be.getComparatorSignal(), be.vanillaComparatorSignal(), "vanilla formula");
		for (int i = 0; i < 27; i++)inv.setItem(i, new ItemStack(Items.DIRT, 64));
		inv.setChanged();
		eq(h.getBlockState(DOCK).getAnalogOutputSignal(l, a), 15, "full box");
		h.startSequence()
		.thenWaitUntil(() -> eq(((ComparatorBlockEntity) h.getBlockEntity(DOCK.east())).getOutputSignal(), 15, "comparator block output, full box"))
		.thenExecute(() -> {
			ItemStack out = be.undock();
			eq(inBox(out, Items.DIRT), 27 * 64, "dirt in the taken-out box");
			eq(h.getBlockState(DOCK).getAnalogOutputSignal(l, a), 0, "after take-out");
		})
		.thenWaitUntil(() -> eq(((ComparatorBlockEntity) h.getBlockEntity(DOCK.east())).getOutputSignal(), 0, "comparator block output after take-out"))
		.thenSucceed();
	}

	static void frameLabelOnDock(GameTestHelper h, Platform pf) {
		dockWithNetwork(h, box(Items.BROWN_SHULKER_BOX, new ItemStack(Items.COBBLESTONE, 1)));
		chest(h, new BlockPos(4, 2, 3), new ItemStack(Items.IRON_INGOT, 1));
		// Glow frame on the dock's west face, showing iron
		FrameLabelGameTests.glowFrame(h, DOCK.west(), Direction.WEST, new ItemStack(Items.IRON_INGOT));
		StorageTerminalBlockEntity te = terminal(h, TERMINAL);
		ShulkerDockBlockEntity be = dock(h, DOCK);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(FrameLabelGameTests.labelActive(h), "label not applied");
			eq(pf.terminalCount(te, Items.COBBLESTONE), 1L, "terminal cobblestone");
		})
		.thenExecute(() -> {
			eq(FrameLabelGameTests.push(te, Items.IRON_INGOT, 20), 0L, "iron remainder");
			eq(count(be.getInv(), Items.IRON_INGOT), 20, "iron goes into the labelled dock first");
			eq(FrameLabelGameTests.push(te, Items.DIRT, 10), 0L, "dirt remainder");
			eq(count(be.getInv(), Items.DIRT), 0, "dirt went into the labelled dock");
		})
		.thenSucceed();
	}

	// ---- data ----

	/** @param ironTag the loader's iron ingot tag in the recipe */
	static void recipeTagsAndLoot(GameTestHelper h, String ironTag) {
		MinecraftServer server = h.getLevel().getServer();
		ResourceLocation id = new ResourceLocation("obscuras_storage", "shulker_dock");
		Recipe<?> r = server.getRecipeManager().byKey(id).orElse(null);
		h.assertTrue(r != null, "recipe obscuras_storage:shulker_dock not loaded");
		h.assertTrue(r.getResultItem(h.getLevel().registryAccess()).is(Content.shulkerDock.get().asItem()), "recipe result");
		List<Ingredient> ing = r.getIngredients();
		eq(ing.size(), 4, "recipe ingredients");
		h.assertTrue(ing.stream().anyMatch(i -> i.test(new ItemStack(Items.BARREL))), "barrel ingredient");
		h.assertTrue(ing.stream().anyMatch(i -> i.test(new ItemStack(Content.invCable.get()))), "cable ingredient");
		eq(ing.stream().filter(i -> i.test(new ItemStack(Items.IRON_INGOT))).count(), 2L, "iron ingredients (" + ironTag + ")");
		h.assertTrue(server.getAdvancements().getAdvancement(new ResourceLocation("obscuras_storage", "recipes/decorations/shulker_dock")) != null, "recipe advancement");
		h.assertTrue(Content.shulkerDock.get().defaultBlockState().is(BlockTags.MINEABLE_WITH_AXE), "not mineable with an axe");
		int dockable = 0;
		for (Item i : BuiltInRegistries.ITEM) {
			if (new ItemStack(i).is(ShulkerDockBlockEntity.DOCKABLE))dockable++;
		}
		eq(dockable, 17, "dockable items");
		eq(Content.shulkerDock.get().asItem().getDescriptionId(), "block.obscuras_storage.shulker_dock", "description id");
		h.assertTrue(server.getLootData().getLootTable(new ResourceLocation("obscuras_storage", "blocks/shulker_dock")) != LootTable.EMPTY, "loot table");
		eq(Content.shulkerDock.get().getLootTable(), new ResourceLocation("obscuras_storage", "blocks/shulker_dock"), "loot table id");
		h.succeed();
	}

	/** Every state of the block resolves to a blockstate variant, a model file and existing textures (no client needed). */
	static void blockstateVariantsResolve(GameTestHelper h, Platform pf) {
		JsonObject variants = json(h, pf, "assets/obscuras_storage/blockstates/shulker_dock.json").getAsJsonObject("variants");
		List<BlockState> states = Content.shulkerDock.get().getStateDefinition().getPossibleStates();
		eq(states.size(), 72, "block states");
		eq(variants.size(), 72, "blockstate variants");
		Set<String> models = new HashSet<>();
		for (BlockState st : states) {
			String key = st.getValues().entrySet().stream()
					.sorted(Map.Entry.comparingByKey((x, y) -> x.getName().compareTo(y.getName())))
					.map(e -> e.getKey().getName() + "=" + nameOf(e.getKey(), e.getValue()))
					.collect(Collectors.joining(","));
			h.assertTrue(variants.has(key), "no variant for " + key);
			JsonObject v = variants.getAsJsonObject(key);
			int y = v.has("y") ? v.get("y").getAsInt() : 0;
			int expectedY = switch (st.getValue(ShulkerDockBlock.FACING)) {
				case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0;
			};
			eq(y, expectedY, "rotation of " + key);
			String model = v.get("model").getAsString();
			ShulkerDockBlock.Box box = st.getValue(ShulkerDockBlock.BOX);
			String expectedModel = "obscuras_storage:block/shulker_dock" + (box == ShulkerDockBlock.Box.NONE ? "" : box == ShulkerDockBlock.Box.SHULKER ? "_box" : "_box_" + box.getSerializedName());
			eq(model, expectedModel, "model of " + key);
			models.add(model);
		}
		eq(models.size(), 18, "distinct models");
		models.add("obscuras_storage:item/shulker_dock");
		for (String m : models)checkModel(h, pf, m, new ArrayList<>());
		h.succeed();
	}

	@SuppressWarnings("unchecked")
	private static <T extends Comparable<T>> String nameOf(Property<T> p, Comparable<?> v) {
		return p.getName((T) v);
	}

	private static void checkModel(GameTestHelper h, Platform pf, String model, List<String> chain) {
		h.assertFalse(chain.contains(model), "parent loop " + chain);
		chain.add(model);
		ResourceLocation rl = new ResourceLocation(model);
		if (rl.getNamespace().equals("minecraft")) {
			h.assertTrue(rl.getPath().equals("block/block"), "unexpected vanilla parent " + model);
			return;
		}
		JsonObject m = json(h, pf, "assets/" + rl.getNamespace() + "/models/" + rl.getPath() + ".json");
		if (m.has("textures")) {
			for (var e : m.getAsJsonObject("textures").entrySet()) {
				String t = e.getValue().getAsString();
				if (t.startsWith("#"))continue;
				ResourceLocation tex = new ResourceLocation(t);
				if (tex.getNamespace().equals("minecraft")) {
					h.assertTrue(tex.getPath().matches("block/([a-z_]+_)?shulker_box"), "unexpected vanilla texture " + t + " in " + model);
				} else {
					String path = "assets/" + tex.getNamespace() + "/textures/" + tex.getPath() + ".png";
					try (InputStream in = pf.resource(path)) {
						h.assertTrue(in != null, "missing texture " + path + " (" + model + ")");
					} catch (IOException ex) {
						throw new IllegalStateException(path, ex);
					}
				}
			}
		}
		if (m.has("parent"))checkModel(h, pf, m.get("parent").getAsString(), chain);
		else h.assertTrue(m.has("elements"), model + " has neither parent nor elements");
	}

	private static JsonObject json(GameTestHelper h, Platform pf, String path) {
		InputStream in = pf.resource(path);
		h.assertTrue(in != null, "missing resource " + path);
		try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(r).getAsJsonObject();
		} catch (IOException e) {
			throw new IllegalStateException(path, e);
		}
	}
}
