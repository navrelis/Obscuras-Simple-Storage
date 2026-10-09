package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.util.BlockFilter;
import com.obscura.storage.util.BlockFilterData;
import com.obscura.storage.util.Priority;

/**
 * Block filters: the position index, one filter per block, removal, saving and the pruning of filters that
 * only hold default settings. The levels are mocks that hand out one shared {@link BlockFilterData}.
 */
@DisplayName("Block filters")
public class BlockFilterTest {
	private static final BlockPos A = new BlockPos(10, 64, 10);
	private static final BlockPos B = new BlockPos(11, 64, 10);
	private static final BlockPos C = new BlockPos(12, 64, 10);
	private static final BlockPos FAR = new BlockPos(-500, 5, 800);

	private BlockFilterData data;
	private ServerLevel overworld, nether;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static BlockFilterData newData() {
		try {
			Constructor<BlockFilterData> c = BlockFilterData.class.getDeclaredConstructor();
			c.setAccessible(true);
			return c.newInstance();
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static BlockFilterData loaded(CompoundTag tag) {
		try {
			Constructor<BlockFilterData> c = BlockFilterData.class.getDeclaredConstructor(CompoundTag.class);
			c.setAccessible(true);
			return c.newInstance(tag);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static ServerLevel level(ResourceKey<Level> dim, DimensionDataStorage storage) {
		ServerLevel level = mock(ServerLevel.class);
		MinecraftServer server = mock(MinecraftServer.class);
		when(level.dimension()).thenReturn(dim);
		when(level.getServer()).thenReturn(server);
		when(server.overworld()).thenReturn(level);
		when(level.getDataStorage()).thenReturn(storage);
		return level;
	}

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		data = newData();
		DimensionDataStorage storage = mock(DimensionDataStorage.class);
		// BlockFilterData.get(level) asks the overworld's data storage for the instance
		when(storage.computeIfAbsent(any(java.util.function.Function.class), any(java.util.function.Supplier.class), anyString())).thenReturn(data);
		overworld = level(Level.OVERWORLD, storage);
		nether = level(Level.NETHER, storage);
	}

	@Test
	@DisplayName("A filter is found at its own position and nowhere else")
	void lookupAtMainPosition() {
		assertNull(data.getFilterAt(overworld, A, false));
		assertNull(data.findContaining(overworld, A));

		BlockFilter f = data.getFilterAt(overworld, A, true);
		assertNotNull(f);
		assertSame(f, data.getFilterAt(overworld, A, true), "asking again returns the same filter");
		assertSame(f, data.getFilterAt(overworld, A, false));
		assertSame(f, data.findContaining(overworld, A));
		assertNull(data.findContaining(overworld, B));
		assertNull(data.getFilterAt(overworld, B, false));
		assertEquals(A, f.getMainPos());
		assertTrue(f.contains(A));
		assertTrue(data.isDirty());
	}

	@Test
	@DisplayName("The static helpers reach the same data")
	void staticHelpers() {
		assertNull(BlockFilter.findExistingAt(overworld, A));
		BlockFilter f = BlockFilter.getOrCreateFilterAt(overworld, A);
		assertSame(f, BlockFilter.getFilterAt(overworld, A));
		assertSame(f, BlockFilter.findExistingAt(overworld, A));
		assertTrue(BlockFilter.removeAt(overworld, A));
		assertFalse(BlockFilter.removeAt(overworld, A));
		assertNull(BlockFilter.getFilterAt(overworld, A));
	}

	@Test
	@DisplayName("Filters in different dimensions at the same position are separate")
	void dimensionsAreSeparate() {
		BlockFilter o = data.getFilterAt(overworld, A, true);
		BlockFilter n = data.getFilterAt(nether, A, true);
		assertNotSame(o, n);
		assertSame(o, data.findContaining(overworld, A));
		assertSame(n, data.findContaining(nether, A));
		data.removeFilterAt(nether, A);
		assertNull(data.findContaining(nether, A));
		assertSame(o, data.findContaining(overworld, A));
	}

	@Test
	@DisplayName("Connected blocks are found through the filter that covers them")
	void connectedBlocksAreIndexed() {
		BlockFilter f = data.getFilterAt(overworld, A, true);
		f.addConnected(overworld, B);
		f.addConnected(overworld, C);
		assertSame(f, data.findContaining(overworld, A));
		assertSame(f, data.findContaining(overworld, B));
		assertSame(f, data.findContaining(overworld, C));
		assertNull(data.getFilterAt(overworld, B, false), "only the main block owns the filter");
		assertEquals(3, f.getConnectedBlocks().size());
		assertTrue(f.contains(C));
		assertNull(data.findContaining(nether, B));
	}

	@Test
	@DisplayName("A block belongs to one filter only")
	void oneFilterPerBlock() {
		BlockFilter f1 = data.getFilterAt(overworld, A, true);
		BlockFilter f2 = data.getFilterAt(overworld, B, true);
		assertNotSame(f1, f2);

		// f1 takes over B, which had a filter of its own: that one goes away
		f1.addConnected(overworld, B);
		assertNull(data.getFilterAt(overworld, B, false));
		assertSame(f1, data.findContaining(overworld, B));

		// f3 takes over C, which f1 covers: f1 gives it up
		f1.addConnected(overworld, C);
		BlockFilter f3 = data.getFilterAt(overworld, FAR, true);
		f3.addConnected(overworld, C);
		assertSame(f3, data.findContaining(overworld, C));
		assertFalse(f1.contains(C));
		assertTrue(f3.contains(C));
		assertSame(f1, data.findContaining(overworld, B));
		assertEquals(2, f1.getConnectedBlocks().size());
		assertEquals(2, f3.getConnectedBlocks().size());
	}

	@Test
	@DisplayName("Removing a filter clears its whole coverage")
	void removeFilter() {
		BlockFilter f = data.getFilterAt(overworld, A, true);
		f.addConnected(overworld, B);
		assertTrue(data.removeFilterAt(overworld, A));
		assertNull(data.findContaining(overworld, A));
		assertNull(data.findContaining(overworld, B));
		assertNull(data.getFilterAt(overworld, A, false));
		assertFalse(data.removeFilterAt(overworld, A), "already gone");
		assertFalse(data.removeFilterAt(overworld, B), "a covered block is not a filter");
		// a stale reference does not write into the index any more
		f.addConnected(overworld, C);
		assertNull(data.findContaining(overworld, C));
	}

	@Test
	@DisplayName("A broken block only drops a filter when it was the main block")
	void blockRemoved() {
		BlockFilter f = data.getFilterAt(overworld, A, true);
		f.addConnected(overworld, B);
		f.addConnected(overworld, C);

		data.onBlockRemoved(overworld, FAR);
		assertSame(f, data.findContaining(overworld, A));

		data.onBlockRemoved(overworld, B);
		assertNull(data.findContaining(overworld, B));
		assertFalse(f.contains(B));
		assertSame(f, data.findContaining(overworld, A));
		assertSame(f, data.findContaining(overworld, C));
		assertSame(f, data.getFilterAt(overworld, A, false));

		data.onBlockRemoved(overworld, A);
		assertNull(data.getFilterAt(overworld, A, false));
		assertNull(data.findContaining(overworld, A));
		assertNull(data.findContaining(overworld, C));
	}

	@Test
	@DisplayName("Changing a setting marks the data dirty")
	void editsMarkDirty() {
		BlockFilter f = data.getFilterAt(overworld, A, true);
		data.setDirty(false);
		f.setSkip(true);
		assertTrue(data.isDirty());
		data.setDirty(false);
		f.setPriority(Priority.HIGH);
		assertTrue(data.isDirty());
		data.setDirty(false);
		f.setSide(Direction.UP);
		assertTrue(data.isDirty());
		data.setDirty(false);
		f.setKeepLast(true);
		assertTrue(data.isDirty());
		data.setDirty(false);
		f.addConnected(overworld, B);
		assertTrue(data.isDirty());
	}

	@Test
	@DisplayName("Settings, coverage and the filter item survive a save and load")
	void saveLoadRoundTrip() {
		BlockFilter f = data.getFilterAt(overworld, A, true);
		f.addConnected(overworld, B);
		f.addConnected(overworld, new BlockPos(9, 63, 12));
		f.setSkip(true);
		f.setSide(Direction.NORTH);
		f.setPriority(Priority.HIGHEST);
		f.setKeepLast(true);
		f.filter.setItem(0, new ItemStack(Items.DIAMOND));

		BlockFilter g = data.getFilterAt(nether, FAR, true);
		g.setPriority(Priority.LOW);

		CompoundTag tag = data.save(new CompoundTag());
		assertEquals(2, tag.getList("filters", Tag.TAG_COMPOUND).size());

		BlockFilterData copy = loaded(tag);
		// the instance methods only need the dimension of the level
		ServerLevel o2 = level(Level.OVERWORLD, mock(DimensionDataStorage.class));
		ServerLevel n2 = level(Level.NETHER, mock(DimensionDataStorage.class));

		BlockFilter f2 = copy.getFilterAt(o2, A, false);
		assertNotNull(f2);
		assertNotSame(f, f2);
		assertEquals(A, f2.getMainPos());
		assertTrue(f2.skip());
		assertEquals(Direction.NORTH, f2.getSide());
		assertEquals(Priority.HIGHEST, f2.getPriority());
		assertTrue(f2.isKeepLast());
		assertEquals(Items.DIAMOND, f2.filter.getItem(0).getItem());
		assertEquals(f.getConnectedBlocks(), f2.getConnectedBlocks());
		assertEquals(3, f2.getConnectedBlocks().size());
		assertTrue(f2.contains(new BlockPos(9, 63, 12)));

		// the index is rebuilt
		assertSame(f2, copy.findContaining(o2, A));
		assertSame(f2, copy.findContaining(o2, B));
		assertSame(f2, copy.findContaining(o2, new BlockPos(9, 63, 12)));
		assertNull(copy.findContaining(o2, C));
		assertNull(copy.findContaining(o2, FAR));

		BlockFilter g2 = copy.getFilterAt(n2, FAR, false);
		assertNotNull(g2);
		assertEquals(Priority.LOW, g2.getPriority());
		assertSame(g2, copy.findContaining(n2, FAR));
		assertNull(copy.findContaining(n2, A));
		assertFalse(copy.isDirty(), "loading real settings is not a change");
	}

	@Test
	@DisplayName("Filters that only hold default settings are dropped when loading")
	void defaultFiltersArePruned() {
		BlockFilter untouched = data.getFilterAt(overworld, A, true);
		assertNotNull(untouched);
		BlockFilter used = data.getFilterAt(overworld, B, true);
		used.setSkip(true);
		BlockFilter covering = data.getFilterAt(overworld, FAR, true);
		covering.addConnected(overworld, C);

		BlockFilterData copy = loaded(data.save(new CompoundTag()));
		ServerLevel o2 = level(Level.OVERWORLD, mock(DimensionDataStorage.class));

		assertNull(copy.getFilterAt(o2, A, false), "default settings only");
		assertNull(copy.findContaining(o2, A));
		assertNotNull(copy.getFilterAt(o2, B, false));
		assertTrue(copy.getFilterAt(o2, B, false).skip());
		assertNotNull(copy.getFilterAt(o2, FAR, false), "covers a second block, so it is not default");
		assertTrue(copy.isDirty(), "pruning has to be saved");
	}

	@Test
	@DisplayName("A filter with only an item, side or priority set is not pruned")
	void oneNonDefaultSettingKeepsTheFilter() {
		BlockPos p1 = new BlockPos(1, 1, 1), p2 = new BlockPos(2, 1, 1), p3 = new BlockPos(3, 1, 1), p4 = new BlockPos(4, 1, 1), p5 = new BlockPos(5, 1, 1);
		data.getFilterAt(overworld, p1, true).filter.setItem(0, new ItemStack(Items.STONE));
		data.getFilterAt(overworld, p2, true).setSide(Direction.EAST);
		data.getFilterAt(overworld, p3, true).setPriority(Priority.LOWEST);
		data.getFilterAt(overworld, p4, true).setKeepLast(true);
		data.getFilterAt(overworld, p5, true);

		BlockFilterData copy = loaded(data.save(new CompoundTag()));
		ServerLevel o2 = level(Level.OVERWORLD, mock(DimensionDataStorage.class));
		for (BlockPos p : List.of(p1, p2, p3, p4)) {
			assertNotNull(copy.getFilterAt(o2, p, false), p.toString());
		}
		assertNull(copy.getFilterAt(o2, p5, false));
	}

	@Test
	@DisplayName("Broken save data does not crash the load")
	void brokenDataLoads() {
		CompoundTag tag = new CompoundTag();
		ListTag list = new ListTag();
		CompoundTag e = new CompoundTag();
		e.putString("dim", "minecraft:overworld");
		e.putInt("x", 1);
		e.putInt("y", 2);
		e.putInt("z", 3);
		CompoundTag d = new CompoundTag();
		d.putString("side", "sideways");
		d.putInt("priority", -7);
		d.putBoolean("skip", true);
		e.put("data", d);
		list.add(e);
		CompoundTag noData = new CompoundTag();
		noData.putString("dim", "minecraft:overworld");
		list.add(noData);
		tag.put("filters", list);

		BlockFilterData copy = loaded(tag);
		ServerLevel o2 = level(Level.OVERWORLD, mock(DimensionDataStorage.class));
		BlockFilter f = copy.getFilterAt(o2, new BlockPos(1, 2, 3), false);
		assertNotNull(f);
		assertEquals(Direction.DOWN, f.getSide());
		assertNotNull(f.getPriority());
		assertTrue(f.skip());
	}
}
