package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.util.FilingCabinetContainer;

/** Saving of the 512 slot Filing Cabinet: the slot number must not wrap around like vanilla's byte slot does. */
@DisplayName("Filing Cabinet container")
public class FilingCabinetContainerTest {
	private static final int SIZE = 512;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static FilingCabinetContainer cabinet() {
		return new FilingCabinetContainer(SIZE, null, p -> true);
	}

	/** An unstackable item that carries its slot number, so a mix up of slots can be seen. */
	private static ItemStack tool(int n) {
		ItemStack s = new ItemStack(Items.DIAMOND_SWORD);
		s.getOrCreateTag().putInt("n", n);
		return s;
	}

	@Test
	@DisplayName("All 512 slots survive a save and load in their slots")
	void fullCabinetKeepsEverything() {
		FilingCabinetContainer a = cabinet();
		for (int i = 0; i < SIZE; i++) {
			a.setItem(i, tool(i));
		}
		CompoundTag tag = new CompoundTag();
		a.saveToTag(tag);

		FilingCabinetContainer b = cabinet();
		b.loadFromTag(tag);
		for (int i = 0; i < SIZE; i++) {
			ItemStack s = b.getItem(i);
			assertFalse(s.isEmpty(), "slot " + i);
			assertEquals(Items.DIAMOND_SWORD, s.getItem(), "slot " + i);
			assertEquals(i, s.getTag().getInt("n"), "slot " + i);
		}
		assertFalse(b.isEmpty());
	}

	@Test
	@DisplayName("Slots above 255 and 127 keep their place, empty slots stay empty")
	void highSlotsAndGaps() {
		FilingCabinetContainer a = cabinet();
		int[] slots = {0, 1, 127, 128, 255, 256, 257, 300, 383, 384, 511};
		for (int i : slots)a.setItem(i, tool(i));
		CompoundTag tag = new CompoundTag();
		a.saveToTag(tag);
		assertEquals(slots.length, tag.getList("Items2", Tag.TAG_COMPOUND).size());

		FilingCabinetContainer b = cabinet();
		b.loadFromTag(tag);
		int found = 0;
		for (int i = 0; i < SIZE; i++) {
			if (b.getItem(i).isEmpty())continue;
			found++;
			assertEquals(i, b.getItem(i).getTag().getInt("n"), "slot " + i);
		}
		assertEquals(slots.length, found);
		for (int i : slots)assertFalse(b.getItem(i).isEmpty(), "slot " + i);
	}

	@Test
	@DisplayName("The slot is saved as an int in a list under Items2 and the old key is not written")
	void saveFormat() {
		FilingCabinetContainer a = cabinet();
		a.setItem(300, tool(300));
		CompoundTag tag = new CompoundTag();
		a.saveToTag(tag);
		assertTrue(tag.contains("Items2", Tag.TAG_LIST));
		assertFalse(tag.contains("Items"));
		CompoundTag entry = tag.getList("Items2", Tag.TAG_COMPOUND).getCompound(0);
		assertEquals(300, entry.getInt("Slot"));
		assertEquals(Tag.TAG_INT, entry.get("Slot").getId());
	}

	@Test
	@DisplayName("A cabinet saved in the legacy format (Items, byte slots) still loads")
	void legacyItemsStillLoad() {
		NonNullList<ItemStack> legacy = NonNullList.withSize(SIZE, ItemStack.EMPTY);
		for (int i = 0; i < 27; i++)legacy.set(i, tool(i));
		legacy.set(200, tool(200));
		CompoundTag tag = new CompoundTag();
		ContainerHelper.saveAllItems(tag, legacy);
		assertTrue(tag.contains("Items", Tag.TAG_LIST));
		assertFalse(tag.contains("Items2"));

		FilingCabinetContainer b = cabinet();
		b.loadFromTag(tag);
		for (int i = 0; i < 27; i++) {
			assertEquals(i, b.getItem(i).getTag().getInt("n"), "slot " + i);
		}
		assertEquals(200, b.getItem(200).getTag().getInt("n"));
		assertTrue(b.getItem(100).isEmpty());
		// the next save uses the new format
		CompoundTag saved = new CompoundTag();
		b.saveToTag(saved);
		assertTrue(saved.contains("Items2"));
		assertEquals(28, saved.getList("Items2", Tag.TAG_COMPOUND).size());
	}

	@Test
	@DisplayName("Entries with a bad or taken slot go into free slots instead of being lost")
	void nothingIsLost() {
		ListTag list = new ListTag();
		int[] slots = {5, 5, 9999, -3};
		for (int i = 0; i < slots.length; i++) {
			CompoundTag st = new CompoundTag();
			tool(i).save(st);
			st.putInt("Slot", slots[i]);
			list.add(st);
		}
		CompoundTag tag = new CompoundTag();
		tag.put("Items2", list);

		FilingCabinetContainer b = cabinet();
		b.loadFromTag(tag);
		int found = 0;
		for (int i = 0; i < SIZE; i++)if (!b.getItem(i).isEmpty())found++;
		assertEquals(4, found);
		assertEquals(0, b.getItem(5).getTag().getInt("n"), "the first entry keeps its slot");
	}

	@Test
	@DisplayName("Loading replaces the previous contents")
	void loadReplaces() {
		FilingCabinetContainer a = cabinet();
		a.setItem(1, tool(1));
		CompoundTag tag = new CompoundTag();
		a.saveToTag(tag);

		FilingCabinetContainer b = cabinet();
		b.setItem(2, tool(2));
		b.loadFromTag(tag);
		assertTrue(b.getItem(2).isEmpty());
		assertFalse(b.getItem(1).isEmpty());

		b.loadFromTag(new CompoundTag());
		assertTrue(b.isEmpty());
	}

	@Test
	@DisplayName("After loading only the stored item type is accepted, after emptying anything that fits")
	void itemTypeIsRestored() {
		FilingCabinetContainer a = cabinet();
		a.setItem(400, tool(400));
		CompoundTag tag = new CompoundTag();
		a.saveToTag(tag);

		FilingCabinetContainer b = cabinet();
		b.loadFromTag(tag);
		assertTrue(b.canPlaceItem(0, new ItemStack(Items.DIAMOND_SWORD)));
		assertFalse(b.canPlaceItem(0, new ItemStack(Items.IRON_SWORD)));
		b.removeItemNoUpdate(400);
		assertTrue(b.canPlaceItem(0, new ItemStack(Items.IRON_SWORD)));
	}

	@Test
	@DisplayName("Only unstackable items that fit into containers can be stored")
	void canStore() {
		assertTrue(FilingCabinetContainer.canStore(new ItemStack(Items.DIAMOND_SWORD)));
		assertFalse(FilingCabinetContainer.canStore(new ItemStack(Items.STONE)));
		assertFalse(FilingCabinetContainer.canStore(new ItemStack(Items.SHULKER_BOX)));
		assertFalse(FilingCabinetContainer.canStore(new ItemStack(Items.BUNDLE)));
		assertEquals(1, cabinet().getMaxStackSize());
		assertEquals(SIZE, cabinet().getContainerSize());
	}
}
