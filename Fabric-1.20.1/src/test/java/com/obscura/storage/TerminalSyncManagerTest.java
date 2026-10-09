package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.util.StorageSnapshot;
import com.obscura.storage.util.StoredItemStack;
import com.obscura.storage.util.TerminalSyncManager;

/**
 * Server to client sync of the terminal item list. The packets the server manager sends are captured
 * (NetworkHandler.sendTo is replaced) and fed into a client side manager.
 */
@DisplayName("Terminal sync")
public class TerminalSyncManagerTest {

	private MockedStatic<NetworkHandler> network;
	private List<CompoundTag> sent;
	private ServerPlayer player;
	private TerminalSyncManager server;
	private TerminalSyncManager client;
	private long time;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach
	void setUp() {
		sent = new ArrayList<>();
		player = mock(ServerPlayer.class);
		network = mockStatic(NetworkHandler.class);
		network.when(() -> NetworkHandler.sendTo(any(), any())).thenAnswer(inv -> {
			sent.add(inv.getArgument(1));
			return null;
		});
		server = new TerminalSyncManager();
		client = new TerminalSyncManager();
		time = 0;
	}

	@AfterEach
	void tearDown() {
		network.close();
	}

	/** Refreshes {@code snapshot} from {@code c}, sends what the server has to send and delivers it to the client. */
	private int sync(StorageSnapshot snapshot, SimpleContainer c) {
		Storage<ItemVariant> st = InventoryStorage.of(c, null);
		snapshot.refresh(st, time++, 1);
		return deliver(snapshot);
	}

	private int deliver(StorageSnapshot snapshot) {
		sent.clear();
		server.update(snapshot, player, null);
		int packets = sent.size();
		for (CompoundTag t : new ArrayList<>(sent)) {
			client.receiveUpdate(t);
		}
		return packets;
	}

	private Map<ItemVariant, Long> clientItems() {
		Map<ItemVariant, Long> m = new HashMap<>();
		for (StoredItemStack s : client.getAsList()) {
			Long old = m.put(ItemVariant.of(s.getStack()), s.getQuantity());
			assertNull(old, "the client list holds a variant twice");
		}
		return m;
	}

	private void assertClientMatches(StorageSnapshot snapshot) {
		Map<ItemVariant, Long> expected = new HashMap<>();
		snapshot.counts().forEach((v, n) -> expected.put(v, n.longValue()));
		assertEquals(expected, clientItems());
	}

	private static SimpleContainer container() {
		SimpleContainer c = new SimpleContainer(27);
		c.setItem(0, new ItemStack(Items.STONE, 40));
		c.setItem(1, new ItemStack(Items.STONE, 24));
		c.setItem(2, new ItemStack(Items.DIRT, 3));
		return c;
	}

	@Test
	@DisplayName("The first update sends the whole list")
	void firstUpdate() {
		StorageSnapshot snap = new StorageSnapshot();
		assertEquals(1, sync(snap, container()));
		assertClientMatches(snap);
		assertEquals(64, client.getAmount(new StoredItemStack(new ItemStack(Items.STONE))));
		assertEquals(3, client.getAmount(new StoredItemStack(new ItemStack(Items.DIRT))));
		assertTrue(client.isStructuralChange());
	}

	@Test
	@DisplayName("Nothing is sent while the snapshot is unchanged")
	void unchangedSendsNothing() {
		StorageSnapshot snap = new StorageSnapshot();
		SimpleContainer c = container();
		sync(snap, c);
		assertEquals(0, sync(snap, c));
		assertEquals(0, sync(snap, c));
		assertClientMatches(snap);
	}

	@Test
	@DisplayName("A count only change updates the amount in place and is not structural")
	void countOnlyChange() {
		StorageSnapshot snap = new StorageSnapshot();
		SimpleContainer c = container();
		sync(snap, c);
		StoredItemStack before = client.getAsList().stream().filter(s -> s.getStack().is(Items.DIRT)).findFirst().orElseThrow();

		c.setItem(2, new ItemStack(Items.DIRT, 10));
		assertEquals(1, sync(snap, c));
		assertFalse(client.isStructuralChange());
		assertClientMatches(snap);
		assertEquals(10, client.getAmount(new StoredItemStack(new ItemStack(Items.DIRT))));
		// the object the client sorts and displays is the same one, only its amount changed
		assertEquals(10, before.getQuantity());
		assertEquals(2, client.getAsList().size());
	}

	@Test
	@DisplayName("Only the changed variants are sent")
	void onlyChangedVariantsAreSent() {
		StorageSnapshot snap = new StorageSnapshot();
		SimpleContainer c = container();
		sync(snap, c);
		c.setItem(2, new ItemStack(Items.DIRT, 4));
		sync(snap, c);
		CompoundTag t = sent.get(0);
		assertEquals(1, t.getShort("l"));
	}

	@Test
	@DisplayName("A removed variant disappears from the client list")
	void removal() {
		StorageSnapshot snap = new StorageSnapshot();
		SimpleContainer c = container();
		sync(snap, c);
		StoredItemStack dirt = client.getAsList().stream().filter(s -> s.getStack().is(Items.DIRT)).findFirst().orElseThrow();

		c.setItem(2, ItemStack.EMPTY);
		assertEquals(1, sync(snap, c));
		assertTrue(client.isStructuralChange());
		assertClientMatches(snap);
		assertEquals(1, client.getAsList().size());
		assertEquals(0, dirt.getQuantity(), "lists that still hold the old object show it as empty");
		assertEquals(0, client.getAmount(new StoredItemStack(new ItemStack(Items.DIRT))));

		c.clearContent();
		sync(snap, c);
		assertTrue(client.getAsList().isEmpty());
	}

	@Test
	@DisplayName("A variant that comes back after it was removed reuses its id")
	void idReuse() {
		StorageSnapshot snap = new StorageSnapshot();
		SimpleContainer c = container();
		sync(snap, c);

		c.setItem(2, ItemStack.EMPTY);
		sync(snap, c);
		c.setItem(2, new ItemStack(Items.DIRT, 7));
		assertEquals(1, sync(snap, c));
		assertTrue(client.isStructuralChange());
		assertClientMatches(snap);
		assertEquals(7, client.getAmount(new StoredItemStack(new ItemStack(Items.DIRT))));

		// again, with other variants coming and going in between
		for (int round = 0; round < 5; round++) {
			c.setItem(2, ItemStack.EMPTY);
			c.setItem(3, new ItemStack(Items.SAND, round + 1));
			sync(snap, c);
			assertClientMatches(snap);
			c.setItem(3, ItemStack.EMPTY);
			c.setItem(2, new ItemStack(Items.DIRT, round + 2));
			sync(snap, c);
			assertClientMatches(snap);
		}
		assertEquals(6, client.getAmount(new StoredItemStack(new ItemStack(Items.DIRT))));
		assertEquals(0, client.getAmount(new StoredItemStack(new ItemStack(Items.SAND))));
	}

	@Test
	@DisplayName("Several changes in one update arrive together")
	void multipleChanges() {
		StorageSnapshot snap = new StorageSnapshot();
		SimpleContainer c = container();
		sync(snap, c);
		c.setItem(0, ItemStack.EMPTY);
		c.setItem(1, new ItemStack(Items.STONE, 5));
		c.setItem(2, ItemStack.EMPTY);
		c.setItem(3, new ItemStack(Items.SAND, 9));
		assertEquals(1, sync(snap, c));
		assertClientMatches(snap);
		assertEquals(5, client.getAmount(new StoredItemStack(new ItemStack(Items.STONE))));
		assertEquals(9, client.getAmount(new StoredItemStack(new ItemStack(Items.SAND))));
	}

	@Test
	@DisplayName("Moving to another snapshot syncs the difference")
	void otherSnapshot() {
		StorageSnapshot a = new StorageSnapshot();
		sync(a, container());
		assertClientMatches(a);

		SimpleContainer other = new SimpleContainer(9);
		other.setItem(0, new ItemStack(Items.STONE, 2));
		other.setItem(1, new ItemStack(Items.SAND, 8));
		StorageSnapshot b = new StorageSnapshot();
		b.refresh(InventoryStorage.of(other, null), 0, 1);
		deliver(b);
		assertClientMatches(b);
		assertEquals(0, client.getAmount(new StoredItemStack(new ItemStack(Items.DIRT))));
		assertEquals(2, client.getAmount(new StoredItemStack(new ItemStack(Items.STONE))));
		assertEquals(8, client.getAmount(new StoredItemStack(new ItemStack(Items.SAND))));

		// and back, nothing is left over from the other network
		deliver(a);
		assertClientMatches(a);
	}

	@Test
	@DisplayName("A viewer that fell behind the trimmed change log is brought up to date")
	void trimmedLogFallback() {
		StorageSnapshot snap = new StorageSnapshot();
		SimpleContainer c = new SimpleContainer(3);
		c.setItem(0, new ItemStack(Items.STONE, 1));
		c.setItem(1, new ItemStack(Items.DIRT, 1));
		sync(snap, c);
		assertClientMatches(snap);

		// the viewer misses 1500 versions, more than the log keeps
		for (int i = 0; i < 1500; i++) {
			c.setItem(0, new ItemStack(Items.STONE, (i % 2) + 2));
			snap.refresh(InventoryStorage.of(c, null), time++, 1);
		}
		c.setItem(1, ItemStack.EMPTY);
		c.setItem(2, new ItemStack(Items.SAND, 3));
		snap.refresh(InventoryStorage.of(c, null), time++, 1);
		assertFalse(snap.canDiffSince(1));

		deliver(snap);
		assertClientMatches(snap);
		assertEquals(0, client.getAmount(new StoredItemStack(new ItemStack(Items.DIRT))));
		assertEquals(3, client.getAmount(new StoredItemStack(new ItemStack(Items.SAND))));
	}

	private static ItemVariant damaged(int n) {
		// items that can be damaged keep their tag in the sync, every n is a variant of its own
		CompoundTag tag = new CompoundTag();
		tag.putInt("Damage", 1);
		tag.putInt("n", n);
		return ItemVariant.of(Items.DIAMOND_PICKAXE, tag);
	}

	@Test
	@DisplayName("5000 variants with NBT survive the round trip, over several packets")
	void manyVariants() {
		int count = 5000;
		SimpleContainer c = new SimpleContainer(count);
		for (int i = 0; i < count; i++) {
			c.setItem(i, damaged(i).toStack(1));
		}
		StorageSnapshot snap = new StorageSnapshot();
		int packets = sync(snap, c);
		assertTrue(packets > 1, "5000 tagged stacks do not fit one packet, got " + packets);
		assertEquals(count, snap.counts().size());
		assertEquals(count, client.getAsList().size());
		assertClientMatches(snap);

		// remove a third, add 200 new ones and give some of the others a second stack so their amount changes
		for (int i = 0; i < count; i += 3)c.setItem(i, ItemStack.EMPTY);
		for (int i = 0; i < 200; i++) {
			c.setItem(i * 3, damaged(count + i).toStack(1));
		}
		for (int i = 1; i < count; i += 7) {
			c.setItem(i, damaged(i).toStack(3));
		}
		sync(snap, c);
		assertClientMatches(snap);
		assertTrue(client.getAsList().size() > count / 2);
	}

	@Test
	@DisplayName("5000 plain variants and amounts round trip")
	void manyPlainAmounts() {
		// every item of the registry as a plain variant, then amounts change without a structural change
		SimpleContainer c = new SimpleContainer(6000);
		int n = 0;
		for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
			if (item == Items.AIR)continue;
			c.setItem(n++, new ItemStack(item, 1));
			if (n >= 6000)break;
		}
		StorageSnapshot snap = new StorageSnapshot();
		sync(snap, c);
		assertClientMatches(snap);
		int variants = client.getAsList().size();
		assertTrue(variants > 1000, "the registry has more than 1000 items, got " + variants);

		for (int i = 0; i < n; i += 2)c.setItem(i, new ItemStack(c.getItem(i).getItem(), 1 + (i % 60)));
		// stacks of unstackable items are limited to 1, that is fine, the total is what counts
		sync(snap, c);
		assertClientMatches(snap);
		assertEquals(variants, client.getAsList().size());
	}
}
