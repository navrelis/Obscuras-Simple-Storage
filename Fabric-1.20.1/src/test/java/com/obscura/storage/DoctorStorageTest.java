package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Collections;
import java.util.Iterator;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.util.IProxy;
import com.obscura.storage.util.MergedStorage;

/** The Network Doctor's hooks in the merged network storage: duplicate counting and finding the owning network. */
@DisplayName("Network Doctor storage hooks")
public class DoctorStorageTest {

	private static Storage<ItemVariant> part() {
		return new Storage<>() {
			@Override
			public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
				return 0;
			}

			@Override
			public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
				return 0;
			}

			@Override
			public Iterator<StorageView<ItemVariant>> iterator() {
				return Collections.emptyIterator();
			}
		};
	}

	@Test
	@DisplayName("Parts offered twice are counted as duplicates until the next clear")
	void duplicatesAreCounted() {
		MergedStorage m = new MergedStorage();
		Storage<ItemVariant> a = part(), b = part();
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
