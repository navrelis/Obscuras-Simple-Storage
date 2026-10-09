package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.obscura.storage.inventory.InventorySlot;
import com.obscura.storage.util.HopperLogic;
import com.obscura.storage.util.HopperMode;

/** Pure logic of the Basic Inventory Hopper Pulse / Keep Stock modes. */
@DisplayName("Basic Inventory Hopper modes")
public class HopperModesTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@DisplayName("Configurator amount steps cycle 1, 2, 4, 8, 9, 16, 32, 64 and wrap")
	void amountCycling() {
		int a = 1;
		List<Integer> seen = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			a = HopperLogic.nextAmount(a);
			seen.add(a);
		}
		assertEquals(List.of(2, 4, 8, 9, 16, 32, 64, 1), seen);
		// Amounts set from a held stack are not steps, the next step above is used
		assertEquals(4, HopperLogic.nextAmount(3));
		assertEquals(16, HopperLogic.nextAmount(12));
		assertEquals(1, HopperLogic.nextAmount(64));
	}

	@Test
	@DisplayName("Amount is clamped to 1..64, missing NBT reads as 1")
	void amountClamp() {
		assertEquals(1, HopperLogic.clampAmount(0));
		assertEquals(1, HopperLogic.clampAmount(-5));
		assertEquals(9, HopperLogic.clampAmount(9));
		assertEquals(64, HopperLogic.clampAmount(64));
		assertEquals(64, HopperLogic.clampAmount(99));
		assertEquals(1, HopperLogic.clampAmount(new CompoundTag().getByte("Amount")));
	}

	@Test
	@DisplayName("Mode cycles Normal -> Pulse -> Keep Stock -> Normal, unknown ids load as Normal")
	void modeCycling() {
		assertEquals(HopperMode.PULSE, HopperMode.NORMAL.next());
		assertEquals(HopperMode.KEEP_STOCK, HopperMode.PULSE.next());
		assertEquals(HopperMode.NORMAL, HopperMode.KEEP_STOCK.next());

		for (HopperMode m : HopperMode.values())
			assertEquals(m, HopperMode.byId(m.ordinal()));
		assertEquals(HopperMode.NORMAL, HopperMode.byId(-1));
		assertEquals(HopperMode.NORMAL, HopperMode.byId(99));
		// Old worlds have no Mode key
		assertEquals(HopperMode.NORMAL, HopperMode.byId(new CompoundTag().getByte("Mode")));
	}

	@Test
	@DisplayName("Only Pulse ignores redstone, Pulse and Keep Stock need a filter")
	void modeFlags() {
		assertTrue(HopperMode.NORMAL.isDisabledByRedstone());
		assertFalse(HopperMode.PULSE.isDisabledByRedstone());
		assertTrue(HopperMode.KEEP_STOCK.isDisabledByRedstone());

		assertFalse(HopperMode.NORMAL.needsFilter());
		assertTrue(HopperMode.PULSE.needsFilter());
		assertTrue(HopperMode.KEEP_STOCK.needsFilter());
	}

	@Test
	@DisplayName("Only unpowered -> powered is a rising edge")
	void risingEdge() {
		assertTrue(HopperLogic.isRisingEdge(false, true));
		assertFalse(HopperLogic.isRisingEdge(true, true));
		assertFalse(HopperLogic.isRisingEdge(true, false));
		assertFalse(HopperLogic.isRisingEdge(false, false));
	}

	@Test
	@DisplayName("Keep Stock moves only while the target holds fewer than amount")
	void keepStockDecision() {
		assertTrue(HopperLogic.needsStock(0, 9));
		assertTrue(HopperLogic.needsStock(8, 9));
		assertFalse(HopperLogic.needsStock(9, 9));
		assertFalse(HopperLogic.needsStock(200, 9));
		assertTrue(HopperLogic.needsStock(0, 1));
		assertFalse(HopperLogic.needsStock(1, 1));
	}

	/** Mimics the vanilla Crafter: an item is only accepted by the slot that keeps the slots balanced. */
	static final class BalancedSlots {
		final int[] counts = new int[9];

		/** Destination for the next item, recomputed from the current contents, -1 if full. */
		int findDest() {
			int best = -1;
			for (int i = 0; i < counts.length; i++) {
				if (counts[i] < 64 && (best == -1 || counts[i] < counts[best]))best = i;
			}
			return best;
		}

		boolean insert(int slot) {
			if (slot != findDest())return false;
			counts[slot]++;
			return true;
		}
	}

	@Test
	@DisplayName("Pulse moves one item at a time and re-resolves the destination for every item")
	void pulseResolvesDestinationPerItem() {
		BalancedSlots crafter = new BalancedSlots();
		int[] source = {20};
		@SuppressWarnings("unchecked")
		Function<Integer, Integer> findDest = mock(Function.class);
		when(findDest.apply(any())).thenAnswer(inv -> {
			int d = crafter.findDest();
			return d < 0 ? null : d;
		});

		int moved = HopperLogic.moveOneAtATime(9, prev -> source[0] > 0 ? 0 : null, findDest, (src, dst) -> {
			if (!crafter.insert(dst))return false;
			source[0]--;
			return true;
		});

		assertEquals(9, moved);
		assertEquals(11, source[0]);
		for (int c : crafter.counts)assertEquals(1, c);
		verify(findDest, times(9)).apply(any());
	}

	@Test
	@DisplayName("Pulse stops when the source runs out, no carry-over")
	void pulseStopsWhenSourceEmpty() {
		BalancedSlots crafter = new BalancedSlots();
		int[] source = {5};
		int moved = HopperLogic.moveOneAtATime(9, prev -> source[0] > 0 ? 0 : null, src -> crafter.findDest() < 0 ? null : crafter.findDest(), (src, dst) -> {
			source[0]--;
			return crafter.insert(dst);
		});
		assertEquals(5, moved);
		assertEquals(0, source[0]);
	}

	@Test
	@DisplayName("Pulse stops when the target is full or a transfer fails")
	void pulseStopsWhenTargetFull() {
		int[] space = {3};
		int moved = HopperLogic.moveOneAtATime(9, prev -> "src", src -> space[0] > 0 ? "dst" : null, (src, dst) -> {
			space[0]--;
			return true;
		});
		assertEquals(3, moved);

		int[] calls = {0};
		moved = HopperLogic.moveOneAtATime(9, prev -> "src", src -> "dst", (src, dst) -> ++calls[0] < 3);
		assertEquals(2, moved);
		assertEquals(3, calls[0], "must not keep retrying after a failed transfer");

		assertEquals(0, HopperLogic.moveOneAtATime(0, prev -> "src", src -> "dst", (src, dst) -> true));
	}

	@Test
	@DisplayName("Pulse into a real vanilla Crafter: 9 iron -> 1 per slot, 18 iron -> 2 per slot")
	void pulseIntoVanillaCrafter() {
		CrafterBlockEntity crafter = new CrafterBlockEntity(BlockPos.ZERO, Blocks.CRAFTER.defaultBlockState());
		SimpleContainer src = new SimpleContainer(1);
		src.setItem(0, new ItemStack(Items.IRON_INGOT, 64));

		assertEquals(9, pulse(src, crafter, 9));
		for (int i = 0; i < 9; i++)
			assertEquals(1, crafter.getItem(i).getCount(), "slot " + i);

		assertEquals(9, pulse(src, crafter, 9));
		for (int i = 0; i < 9; i++)
			assertEquals(2, crafter.getItem(i).getCount(), "slot " + i);
		assertEquals(64 - 18, src.getItem(0).getCount());
	}

	@Test
	@DisplayName("Moving the whole amount at once would put everything into the first Crafter slot")
	void bulkMoveUnbalancesCrafter() {
		CrafterBlockEntity crafter = new CrafterBlockEntity(BlockPos.ZERO, Blocks.CRAFTER.defaultBlockState());
		SimpleContainer src = new SimpleContainer(1);
		src.setItem(0, new ItemStack(Items.IRON_INGOT, 9));
		InventorySlot from = sourceSlot(src);
		InventorySlot to = destSlot(crafter);
		assertTrue(from.transferTo(9, to));
		assertEquals(9, crafter.getItem(0).getCount());
	}

	/** Same shape as the block entity's runPulse: source slot re-used while not empty, destination found per item. */
	private static int pulse(SimpleContainer src, CrafterBlockEntity crafter, int amount) {
		InventoryStorage crafterStorage = InventoryStorage.of(crafter, null);
		return HopperLogic.<InventorySlot, InventorySlot>moveOneAtATime(amount, prev -> {
			if (prev != null && !prev.getStack().isEmpty())return prev;
			return src.getItem(0).isEmpty() ? null : sourceSlot(src);
		}, s -> {
			ItemStack st = s.getStack();
			// Like InventoryChangeTracker.findSlotDest: simulate the insert, return a slot for the whole inventory
			try (Transaction tr = Transaction.openOuter()) {
				if (crafterStorage.insert(ItemVariant.of(st), 1, tr) == 0)return null;
			}
			return destSlot(crafter);
		}, (s, d) -> s.transferTo(1, d));
	}

	private static InventorySlot sourceSlot(SimpleContainer src) {
		return new InventorySlot(InventoryStorage.of(src, null), InventoryStorage.of(src, null).getSlot(0), slot -> {}, Collections.emptySet());
	}

	private static InventorySlot destSlot(CrafterBlockEntity crafter) {
		return new InventorySlot(InventoryStorage.of(crafter, null), null, slot -> {}, Collections.emptySet());
	}

	@Test
	@DisplayName("All hopper mode texts have en_us entries")
	void langKeys() throws IOException {
		// Shared assets live in the Common project (tests run from the Fabric-1.21.1 directory)
		Path lang = Path.of("../Common-1.21.1/src/main/resources/assets/obscuras_storage/lang/en_us.json");
		JsonObject json;
		try (Reader r = Files.newBufferedReader(lang, StandardCharsets.UTF_8)) {
			json = JsonParser.parseReader(r).getAsJsonObject();
		}
		for (HopperMode m : HopperMode.values())
			assertNotNull(json.get(m.getTranslationKey()), m.getTranslationKey());
		for (String k : List.of("hopper_status", "hopper_mode_amount", "hopper_mode_amount_no_filter", "inventory_hopper_modes"))
			assertNotNull(json.get("tooltip.obscuras_storage." + k), k);
	}
}
