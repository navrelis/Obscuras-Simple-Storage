package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.world.item.ItemStack;

import net.minecraftforge.items.IItemHandler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.util.IProxy;
import com.obscura.storage.util.MergedStorage;

/** The Network Doctor's hooks in the merged network storage: duplicate counting and finding the owning network. */
@DisplayName("Network Doctor storage hooks")
public class DoctorStorageTest {

	/** A handler without slots; none of the tests reads stacks, so no item registry is needed. */
	private static IItemHandler part() {
		return new IItemHandler() {
			@Override
			public int getSlots() {
				return 0;
			}

			@Override
			public ItemStack getStackInSlot(int slot) {
				return null;
			}

			@Override
			public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
				return stack;
			}

			@Override
			public ItemStack extractItem(int slot, int amount, boolean simulate) {
				return null;
			}

			@Override
			public int getSlotLimit(int slot) {
				return 0;
			}

			@Override
			public boolean isItemValid(int slot, ItemStack stack) {
				return false;
			}
		};
	}

	@Test
	@DisplayName("Parts offered twice are counted as duplicates until the next clear")
	void duplicatesAreCounted() {
		MergedStorage m = new MergedStorage();
		IItemHandler a = part(), b = part();
		m.add(a);
		m.add(b);
		m.add(a);
		assertEquals(2, m.getStorages().size());
		assertEquals(1, m.getDuplicatesSkipped());
		m.clear();
		assertEquals(0, m.getDuplicatesSkipped());
	}

	@Test
	@DisplayName("The owning network is found through proxies")
	void statsOfFollowsProxies() {
		MergedStorage m = new MergedStorage();
		assertNull(NetworkDoctor.statsOf(m), "a storage without a window is not attributed");
		NetworkStats s = new NetworkStats();
		m.setDoctorStats(s);
		assertSame(s, NetworkDoctor.statsOf(m));
		IProxy p = () -> m;
		assertSame(s, NetworkDoctor.statsOf(p));
		assertNull(NetworkDoctor.statsOf(part()));
		assertNull(NetworkDoctor.statsOf(null));
	}
}
