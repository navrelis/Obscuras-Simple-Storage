package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.AbstractFilterBlock;
import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.block.entity.AbstractFilterBlockEntity;
import com.obscura.storage.block.entity.DisposalBlockEntity;
import com.obscura.storage.block.entity.EjectorBlockEntity;
import com.obscura.storage.block.entity.ExporterBlockEntity;
import com.obscura.storage.block.entity.InventoryConnectorBlockEntity;
import com.obscura.storage.block.entity.ShulkerDockBlockEntity;
import com.obscura.storage.components.TagFilterComponent;
import com.obscura.storage.doctor.DoctorScan;
import com.obscura.storage.doctor.NetworkReport;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.InventorySlot;
import com.obscura.storage.inventory.NetworkInventory;
import com.obscura.storage.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.obscura.storage.inventory.TargetExclusion;
import com.obscura.storage.inventory.filter.GhostFilter;
import com.obscura.storage.menu.AbstractFilterBlockMenu;
import com.obscura.storage.menu.DisposalMenu;
import com.obscura.storage.menu.EjectorMenu;
import com.obscura.storage.menu.ExporterMenu;
import com.obscura.storage.util.DestroyedCounter;
import com.obscura.storage.util.EjectorMode;
import com.obscura.storage.util.FilterAmount;
import com.obscura.storage.util.FilterBlockStatus;

/**
 * Ejector, Disposal and Exporter in a real world. Every block sits at {@link #BLOCK} facing east: its
 * source is the block west of it (a chest, or a cable leading to a connector with two chests), its
 * front is {@link #FRONT}. Loader independent except for the fake player (see the bottom).
 */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class FilterBlockGameTests {
	private static final String EMPTY_STRUCTURE = GameTestUtil.EMPTY_STRUCTURE;
	private static final BlockPos BLOCK = new BlockPos(3, 3, 3);
	/** The block behind: a chest (plain source) or a cable (network source). */
	private static final BlockPos BACK = new BlockPos(2, 3, 3);
	private static final BlockPos FRONT = new BlockPos(4, 3, 3);
	private static final BlockPos REDSTONE = new BlockPos(3, 4, 3);
	// Network source: connector with a chest north and south of it, a cable from it to the block's back
	private static final BlockPos CONNECTOR = new BlockPos(1, 3, 3);
	private static final BlockPos NET_A = new BlockPos(1, 3, 2);
	private static final BlockPos NET_B = new BlockPos(1, 3, 4);

	// ---- helpers ----

	private static int cooldown() {
		return Math.max(1, Config.get().filterBlockCooldown);
	}

	@SuppressWarnings("unchecked")
	private static <T extends AbstractFilterBlockEntity> T block(GameTestHelper h, Block block, ItemStack... filter) {
		h.setBlock(BLOCK, facing(block, Direction.EAST));
		AbstractFilterBlockEntity be = h.getBlockEntity(BLOCK);
		for (int i = 0; i < filter.length; i++)be.setFilterStack(i, filter[i]);
		return (T) be;
	}

	/** A block with a single chest directly behind it. */
	private static <T extends AbstractFilterBlockEntity> T withChest(GameTestHelper h, Block block, ItemStack... source) {
		chest(h, BACK, source);
		return block(h, block);
	}

	/** A block whose back is a cable leading to a connector with the chests {@link #NET_A} and {@link #NET_B}. */
	private static <T extends AbstractFilterBlockEntity> T withNetwork(GameTestHelper h, Block block, ItemStack[] a, ItemStack[] b) {
		chest(h, NET_A, a);
		chest(h, NET_B, b);
		h.setBlock(CONNECTOR, Content.connector.get());
		T be = block(h, block);
		place(h, BACK, Content.invCable.get());
		return be;
	}

	/** A glass cell around {@link #FRONT} so that ejected items stay where they can be counted. */
	private static void cell(GameTestHelper h) {
		for (BlockPos p : List.of(FRONT.east(), FRONT.north(), FRONT.south(), FRONT.above(), FRONT.below()))h.setBlock(p, Blocks.GLASS);
	}

	private static List<ItemEntity> drops(GameTestHelper h) {
		return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(FRONT)).inflate(2.5));
	}

	/** Items of {@code item} lying in front of the block as item entities. */
	private static int dropped(GameTestHelper h, Item item) {
		int n = 0;
		for (ItemEntity e : drops(h)) {
			if (e.getItem().is(item))n += e.getItem().getCount();
		}
		return n;
	}

	private static void power(GameTestHelper h, boolean on) {
		h.setBlock(REDSTONE, on ? Blocks.REDSTONE_BLOCK : Blocks.AIR);
	}

	private static ItemStack damagedPickaxe() {
		ItemStack s = new ItemStack(Items.IRON_PICKAXE);
		s.set(DataComponents.DAMAGE, 25);
		return s;
	}

	private static ItemStack named(ItemStack s, String name) {
		s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return s;
	}

	/** Every slot of a container as text, to compare two moments. */
	private static String snapshot(Container c) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty())sb.append(i).append(':').append(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()).append('x').append(s.getCount()).append(s.has(DataComponents.CUSTOM_NAME) ? "* " : " ");
		}
		return sb.toString();
	}

	private static ResourceLocation id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item);
	}

	/** Replaces the block entity at {@link #BLOCK} by one loaded from its own saved data, like a chunk reload does. */
	@SuppressWarnings("unchecked")
	private static <T extends AbstractFilterBlockEntity> T reload(GameTestHelper h, CompoundTag tag) {
		BlockPos abs = h.absolutePos(BLOCK);
		BlockState state = h.getLevel().getBlockState(abs);
		BlockEntity loaded = BlockEntity.loadStatic(abs, state, tag, h.getLevel().registryAccess());
		h.assertTrue(loaded instanceof AbstractFilterBlockEntity, "the saved tag must load into the block entity, got " + loaded);
		h.getLevel().setBlockEntity(loaded);
		// The neighbour update a loaded block gets
		((AbstractFilterBlock) state.getBlock()).updateRedstone(h.getLevel(), abs);
		return (T) loaded;
	}

	// ---- Ejector ----

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void ejectorContinuousDropsMatchingItemsWhilePowered(GameTestHelper h) {
		int cd = cooldown();
		cell(h);
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), new ItemStack(Items.COBBLESTONE, 32), new ItemStack(Items.STONE, 10));
		e.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		h.assertValueEqual(e.getMode(), EjectorMode.CONTINUOUS, "default mode");
		h.assertValueEqual(e.getAmount(), FilterAmount.ITEM, "default amount");
		List<Long> moves = new ArrayList<>();
		int[] last = {32};
		h.onEachTick(() -> {
			int now = count(h, BACK, Items.COBBLESTONE);
			for (int i = now; i < last[0]; i++)moves.add(h.getTick());
			last[0] = now;
			h.assertValueEqual(now + dropped(h, Items.COBBLESTONE), 32, "conservation: source + item entities");
		});
		int[] atStop = new int[1];
		h.startSequence()
		.thenIdle(30)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 32, "an unpowered Ejector must not take anything");
			h.assertTrue(drops(h).isEmpty(), "an unpowered Ejector must not drop anything: " + drops(h));
			h.assertValueEqual(e.getStatus(), FilterBlockStatus.NEEDS_REDSTONE, "status while unpowered");
			power(h, true);
		})
		.thenIdle(8 * cd + 2)
		.thenExecute(() -> {
			h.assertTrue(moves.size() >= 8, "too few drops while powered: " + moves);
			for (int i = 1; i < moves.size(); i++) {
				long d = moves.get(i) - moves.get(i - 1);
				h.assertValueEqual(d, (long) cd, "ticks between two drops (config filterBlockCooldown), drops at " + moves);
			}
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), moves.size(), "one item entity item per drop");
			h.assertValueEqual(count(h, BACK, Items.STONE), 10, "stone does not match and must stay");
			h.assertValueEqual(dropped(h, Items.STONE), 0, "stone dropped");
			h.assertValueEqual(e.getStatus(), FilterBlockStatus.RUNNING, "status while powered");
			power(h, false);
			atStop[0] = count(h, BACK, Items.COBBLESTONE);
		})
		.thenIdle(3 * cd)
		.thenExecute(() -> h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), atStop[0], "nothing may be taken after the signal is gone"))
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void ejectorExactDecidesAboutDamagedTools(GameTestHelper h) {
		cell(h);
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), damagedPickaxe());
		// A pristine pickaxe as the ghost entry
		e.setFilterStack(4, new ItemStack(Items.IRON_PICKAXE));
		e.setExact(true);
		power(h, true);
		h.startSequence()
		.thenIdle(40)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, BACK, Items.IRON_PICKAXE), 1, "Exact on: a damaged tool does not match a pristine entry");
			h.assertTrue(drops(h).isEmpty(), "nothing may be dropped with Exact on: " + drops(h));
			e.setExact(false);
		})
		.thenWaitUntil(() -> h.assertValueEqual(dropped(h, Items.IRON_PICKAXE), 1, "Exact off: the damaged tool matches"))
		.thenExecute(() -> {
			h.assertValueEqual(count(h, BACK, Items.IRON_PICKAXE), 0, "pickaxe left in the source");
			ItemStack out = drops(h).get(0).getItem();
			h.assertTrue(ItemStack.isSameItemSameComponents(out, damagedPickaxe()), "the dropped pickaxe must be the damaged one, unchanged: " + out);
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void ejectorEmptyFilterDoesNothing(GameTestHelper h) {
		cell(h);
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), new ItemStack(Items.COBBLESTONE, 32), damagedPickaxe());
		power(h, true);
		h.startSequence()
		.thenIdle(40)
		.thenExecute(() -> {
			h.assertTrue(e.getFilter().isEmpty(), "filter");
			h.assertValueEqual(e.getStatus(), FilterBlockStatus.NO_FILTER, "status");
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 32, "an empty filter must not match anything");
			h.assertValueEqual(count(h, BACK, Items.IRON_PICKAXE), 1, "an empty filter must not match anything");
			h.assertTrue(drops(h).isEmpty(), "nothing may be dropped with an empty filter: " + drops(h));
			// The same in Pulse mode, with a fresh rising edge
			e.setMode(EjectorMode.PULSE);
			power(h, false);
		})
		.thenIdle(5)
		.thenExecute(() -> power(h, true))
		.thenIdle(20)
		.thenExecute(() -> {
			h.assertTrue(drops(h).isEmpty(), "a pulse with an empty filter dropped something: " + drops(h));
			// The pulse was used up: setting a filter afterwards must not drop anything without a new edge
			e.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		})
		.thenIdle(20)
		.thenExecute(() -> h.assertTrue(drops(h).isEmpty(), "an old pulse fired after the filter was set: " + drops(h)))
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void ejectorPulseGivesOneBatchPerRisingEdge(GameTestHelper h) {
		int cd = cooldown();
		cell(h);
		// Two slots: a stack batch has to gather its 64 items from both
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), new ItemStack(Items.COBBLESTONE, 40), new ItemStack(Items.COBBLESTONE, 40), new ItemStack(Items.STONE, 5));
		e.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		e.setMode(EjectorMode.PULSE);
		h.onEachTick(() -> h.assertValueEqual(count(h, BACK, Items.COBBLESTONE) + dropped(h, Items.COBBLESTONE), 80, "conservation: source + item entities"));
		h.startSequence()
		.thenIdle(20)
		.thenExecute(() -> {
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), 0, "nothing may drop without a pulse");
			power(h, true);
		})
		.thenIdle(3)
		.thenExecute(() -> h.assertValueEqual(dropped(h, Items.COBBLESTONE), 1, "Item amount: one item per rising edge"))
		.thenIdle(4 * cd)
		.thenExecute(() -> {
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), 1, "a held signal must not drop more");
			power(h, false);
		})
		.thenIdle(cd + 2)
		.thenExecute(() -> {
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), 1, "a falling edge must not drop anything");
			power(h, true);
		})
		.thenIdle(3)
		.thenExecute(() -> {
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), 2, "second rising edge");
			power(h, false);
			e.setAmount(FilterAmount.STACK);
		})
		.thenIdle(cd + 2)
		.thenExecute(() -> {
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), 2, "switching the amount must not drop anything");
			power(h, true);
		})
		.thenIdle(3)
		.thenExecute(() -> {
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), 66, "Stack amount: one stack of 64 per rising edge, gathered from two slots");
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 14, "cobblestone left in the source");
		})
		.thenIdle(4 * cd)
		.thenExecute(() -> {
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), 66, "a held signal must not drop a second stack");
			power(h, false);
		})
		.thenIdle(cd + 2)
		.thenExecute(() -> power(h, true))
		.thenIdle(3)
		.thenExecute(() -> {
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), 80, "the last pulse drops the rest");
			h.assertValueEqual(count(h, BACK, Items.STONE), 5, "stone does not match and must stay");
			h.assertValueEqual(dropped(h, Items.STONE), 0, "stone dropped");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void ejectorPulseFollowsClockFasterThanCooldown(GameTestHelper h) {
		// The cooldown does not hold pulses back: rising edges every 4 ticks, half the default cooldown of 8
		h.assertTrue(cooldown() > 4, "this test needs a cooldown above 4 ticks, config filterBlockCooldown = " + cooldown());
		cell(h);
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), new ItemStack(Items.COBBLESTONE, 5), new ItemStack(Items.STONE, 3));
		e.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		e.setMode(EjectorMode.PULSE);
		List<Long> drops = new ArrayList<>();
		int[] last = {5};
		h.onEachTick(() -> {
			int now = count(h, BACK, Items.COBBLESTONE);
			for (int i = now; i < last[0]; i++)drops.add(h.getTick());
			last[0] = now;
			h.assertValueEqual(now + dropped(h, Items.COBBLESTONE), 5, "conservation: source + item entities");
		});
		GameTestSequence seq = h.startSequence().thenIdle(10);
		for (int i = 1; i <= 5; i++) {
			int edges = i;
			seq = seq.thenExecute(() -> power(h, true))
			.thenIdle(2)
			.thenExecute(() -> {
				h.assertValueEqual(dropped(h, Items.COBBLESTONE), edges, "one batch per rising edge, also inside the cooldown (edge " + edges + ")");
				power(h, false);
			})
			.thenIdle(2);
		}
		seq.thenExecute(() -> {
			h.assertValueEqual(drops.size(), 5, "batches for 5 rising edges: " + drops);
			for (int i = 1; i < drops.size(); i++) {
				h.assertValueEqual(drops.get(i) - drops.get(i - 1), 4L, "every batch comes right after its edge, 4 ticks apart: " + drops);
			}
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 0, "cobblestone left in the source");
			// Two more edges with nothing left to drop
			power(h, true);
		})
		.thenIdle(2)
		.thenExecute(() -> {
			h.assertTrue(e.isIdle(), "nothing matches any more: the Ejector must be idle");
			power(h, false);
		})
		.thenIdle(2)
		.thenExecute(() -> power(h, true))
		.thenIdle(2)
		.thenExecute(() -> {
			h.assertTrue(e.isIdle(), "a clock on a source without matching items must leave the Ejector idle (no search per pulse)");
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), 5, "nothing more to drop");
			h.assertValueEqual(count(h, BACK, Items.STONE), 3, "stone does not match and must stay");
			h.assertValueEqual(dropped(h, Items.STONE), 0, "stone dropped");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void ejectorSavedSignalPreventsPulseAfterReload(GameTestHelper h) {
		int cd = cooldown();
		cell(h);
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), new ItemStack(Items.COBBLESTONE, 16));
		e.setFilterStack(4, damagedPickaxe());
		e.setFilterStack(7, new ItemStack(Items.COBBLESTONE, 30));
		e.setExact(true);
		e.setMode(EjectorMode.PULSE);
		e.setAmount(FilterAmount.STACK);
		e.setAmount(FilterAmount.ITEM);
		h.startSequence()
		.thenIdle(10)
		.thenExecute(() -> power(h, true))
		.thenIdle(3)
		.thenExecute(() -> {
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), 1, "first pulse");
			CompoundTag tag = e.saveWithFullMetadata(h.getLevel().registryAccess());
			h.assertTrue(tag.getBoolean("Powered"), "the last seen redstone signal must be saved, tag: " + tag);
			h.assertValueEqual((int) tag.getByte("Mode"), EjectorMode.PULSE.ordinal(), "Mode, tag: " + tag);
			h.assertValueEqual((int) tag.getByte("Amount"), FilterAmount.ITEM.ordinal(), "Amount, tag: " + tag);
			h.assertTrue(tag.getBoolean("Exact"), "Exact, tag: " + tag);
			ListTag filter = tag.getList("Filter", Tag.TAG_COMPOUND);
			h.assertValueEqual(filter.size(), 2, "Filter entries, tag: " + tag);
			h.assertValueEqual((int) filter.getCompound(0).getByte("Slot"), 4, "Slot of the first entry");
			h.assertValueEqual(filter.getCompound(1).getString("id"), "minecraft:cobblestone", "id of the second entry");
			h.assertValueEqual(filter.getCompound(1).getInt("count"), 1, "a ghost entry is saved with count 1");

			EjectorBlockEntity re = reload(h, tag);
			h.assertValueEqual(re.getMode(), EjectorMode.PULSE, "mode after reload");
			h.assertValueEqual(re.getAmount(), FilterAmount.ITEM, "amount after reload");
			h.assertTrue(re.getFilter().isExact(), "Exact after reload");
			h.assertTrue(re.isPowered(), "stored signal after reload");
			h.assertTrue(ItemStack.isSameItemSameComponents(re.getFilter().getStack(4), damagedPickaxe()), "ghost entry 4 after reload: " + re.getFilter().getStack(4));
			h.assertTrue(re.getFilter().getStack(7).is(Items.COBBLESTONE) && re.getFilter().getStack(7).getCount() == 1, "ghost entry 7 after reload");
			h.assertTrue(re.getFilter().getStack(0).isEmpty(), "ghost entry 0 after reload");
		})
		.thenIdle(3 * cd)
		.thenExecute(() -> {
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), 1, "reloading while powered must not pulse");
			power(h, false);
		})
		.thenIdle(cd + 2)
		.thenExecute(() -> power(h, true))
		.thenIdle(3)
		.thenExecute(() -> {
			h.assertValueEqual(dropped(h, Items.COBBLESTONE), 2, "the reloaded Ejector must pulse on the next rising edge");
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 14, "cobblestone left in the source");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void ejectorFilterItemsWorkAsEntries(GameTestHelper h) {
		cell(h);
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.GOLD_INGOT, 3),
				new ItemStack(Items.OAK_PLANKS, 2), new ItemStack(Items.BIRCH_PLANKS, 2), new ItemStack(Items.STONE, 4));
		// An Item Filter (iron ingots) and a Tag Filter (#minecraft:planks) as ghost entries, next to a plain item
		ItemStack tagFilter = new ItemStack(Content.tagItemFilter.get());
		tagFilter.set(Content.tagFilterComponent.get(), new TagFilterComponent(List.of(net.minecraft.tags.ItemTags.PLANKS), true));
		e.setFilterStack(0, itemFilter(Items.IRON_INGOT));
		e.setFilterStack(1, tagFilter);
		e.setAmount(FilterAmount.STACK);
		h.assertFalse(e.getFilter().isPlainEntry(0), "a filter item is no plain entry");
		power(h, true);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertValueEqual(dropped(h, Items.IRON_INGOT), 3, "iron ingots (Item Filter entry)");
			h.assertValueEqual(dropped(h, Items.OAK_PLANKS), 2, "oak planks (Tag Filter entry)");
			h.assertValueEqual(dropped(h, Items.BIRCH_PLANKS), 2, "birch planks (Tag Filter entry)");
		})
		.thenIdle(30)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, BACK, Items.GOLD_INGOT), 3, "gold matches no entry");
			h.assertValueEqual(count(h, BACK, Items.STONE), 4, "stone matches no entry");
			h.assertValueEqual(dropped(h, Items.GOLD_INGOT) + dropped(h, Items.STONE), 0, "non matching items dropped");
			// The filter items themselves are references: none of them may ever come out
			h.assertValueEqual(dropped(h, Content.itemFilter.get()) + dropped(h, Content.tagItemFilter.get()), 0, "a ghost entry was dropped");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void ejectorWorksFromNetwork(GameTestHelper h) {
		cell(h);
		EjectorBlockEntity e = withNetwork(h, Content.ejector.get(),
				new ItemStack[] {new ItemStack(Items.COBBLESTONE, 3), new ItemStack(Items.DIRT, 4)},
				new ItemStack[] {new ItemStack(Items.COBBLESTONE, 2)});
		e.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		power(h, true);
		h.onEachTick(() -> h.assertValueEqual(count(h, NET_A, Items.COBBLESTONE) + count(h, NET_B, Items.COBBLESTONE) + dropped(h, Items.COBBLESTONE), 5, "conservation: network + item entities"));
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(dropped(h, Items.COBBLESTONE), 5, "cobblestone from both chests of the network"))
		.thenIdle(30)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, NET_A, Items.DIRT), 4, "dirt does not match and must stay");
			h.assertValueEqual(dropped(h, Items.DIRT), 0, "dirt dropped");
			h.assertTrue(e.isIdle(), "nothing left to do: the Ejector must be idle");
		})
		.thenSucceed();
	}

	// ---- Disposal ----

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void disposalStartsPausedThenDestroysMatchingOnly(GameTestHelper h) {
		DisposalBlockEntity d = withChest(h, Content.disposal.get(), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 6),
				new ItemStack(Items.DIRT, 5), new ItemStack(Items.STONE, 9), damagedPickaxe());
		d.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		d.setFilterStack(1, new ItemStack(Items.DIRT));
		d.setFilterStack(2, new ItemStack(Items.IRON_PICKAXE));
		h.startSequence()
		.thenIdle(40)
		.thenExecute(() -> {
			h.assertTrue(d.isPaused(), "a newly placed Disposal must be paused");
			h.assertValueEqual(d.getStatus(), FilterBlockStatus.PAUSED, "status");
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 70, "a paused Disposal must not destroy anything");
			h.assertValueEqual(d.getDestroyed().getTotal(), 0L, "total while paused");
			d.setPaused(false);
		})
		.thenWaitUntil(() -> {
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 0, "cobblestone left");
			h.assertValueEqual(count(h, BACK, Items.DIRT), 0, "dirt left");
			h.assertValueEqual(count(h, BACK, Items.IRON_PICKAXE), 0, "pickaxe left (a damaged tool matches a pristine entry)");
		})
		.thenIdle(30)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, BACK, Items.STONE), 9, "stone does not match and must stay");
			DestroyedCounter c = d.getDestroyed();
			h.assertValueEqual(c.getTotal(), 76L, "total = exactly what disappeared");
			h.assertValueEqual(c.get(id(Items.COBBLESTONE)), 70L, "cobblestone destroyed");
			h.assertValueEqual(c.get(id(Items.DIRT)), 5L, "dirt destroyed");
			h.assertValueEqual(c.get(id(Items.IRON_PICKAXE)), 1L, "pickaxes destroyed");
			h.assertValueEqual(c.get(id(Items.STONE)), 0L, "stone destroyed");
			h.assertValueEqual(c.top(1).get(0), new DestroyedCounter.Entry(id(Items.COBBLESTONE), 70), "most destroyed item");
			h.assertValueEqual(d.getStatus(), FilterBlockStatus.RUNNING, "status");
			h.assertTrue(d.isIdle(), "nothing left to do: the Disposal must be idle");
			h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(BLOCK)).inflate(3)).isEmpty(), "a Disposal must not drop anything");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void disposalDestroysAtMostOneStackPerCooldown(GameTestHelper h) {
		int cd = cooldown();
		DisposalBlockEntity d = withChest(h, Content.disposal.get(), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
				new ItemStack(Items.ENDER_PEARL, 16), new ItemStack(Items.ENDER_PEARL, 16));
		d.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		d.setFilterStack(1, new ItemStack(Items.ENDER_PEARL));
		List<Long> ops = new ArrayList<>();
		long[] last = {0};
		h.onEachTick(() -> {
			long now = d.getDestroyed().getTotal();
			if (now != last[0]) {
				h.assertTrue(now - last[0] <= 64, "more than one stack in one operation: " + (now - last[0]));
				ops.add(h.getTick());
				last[0] = now;
			}
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE) + count(h, BACK, Items.ENDER_PEARL) + (int) now, 224, "counted = disappeared");
		});
		d.setPaused(false);
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(d.getDestroyed().getTotal(), 224L, "everything destroyed"))
		.thenExecute(() -> {
			// 3 stacks of cobblestone, then the ender pearls one stack (16) at a time
			h.assertValueEqual(ops.size(), 5, "operations: " + ops);
			for (int i = 1; i < ops.size(); i++) {
				h.assertValueEqual(ops.get(i) - ops.get(i - 1), (long) cd, "ticks between two operations (config filterBlockCooldown), at " + ops);
			}
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void disposalCountsAndPauseSurviveReload(GameTestHelper h) {
		DisposalBlockEntity d = withChest(h, Content.disposal.get(), new ItemStack(Items.COBBLESTONE, 40), new ItemStack(Items.DIRT, 5));
		d.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		d.setFilterStack(1, new ItemStack(Items.DIRT));
		d.setPaused(false);
		DisposalBlockEntity[] re = new DisposalBlockEntity[1];
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(d.getDestroyed().getTotal(), 45L, "destroyed"))
		.thenExecute(() -> {
			// A count beyond 32 bit, as a long running Disposal would have
			d.getDestroyed().add(id(Items.COBBLESTONE), 5_000_000_000L);
			CompoundTag tag = d.saveWithFullMetadata(h.getLevel().registryAccess());
			h.assertFalse(tag.getBoolean("Paused"), "Paused, tag: " + tag);
			h.assertValueEqual((int) tag.getTagType("Destroyed"), (int) Tag.TAG_LONG, "Destroyed is a long, tag: " + tag);
			h.assertValueEqual(tag.getLong("Destroyed"), 5_000_000_045L, "Destroyed, tag: " + tag);
			h.assertValueEqual(tag.getList("DestroyedItems", Tag.TAG_COMPOUND).size(), 2, "DestroyedItems, tag: " + tag);
			re[0] = reload(h, tag);
			DestroyedCounter c = re[0].getDestroyed();
			h.assertFalse(re[0].isPaused(), "a running Disposal stays running after a reload");
			h.assertValueEqual(c.getTotal(), 5_000_000_045L, "total after reload");
			h.assertValueEqual(c.get(id(Items.COBBLESTONE)), 5_000_000_040L, "cobblestone after reload");
			h.assertValueEqual(c.get(id(Items.DIRT)), 5L, "dirt after reload");
			// It keeps working and counting
			fill(h, BACK, new ItemStack(Items.DIRT, 3));
		})
		.thenWaitUntil(() -> h.assertValueEqual(re[0].getDestroyed().get(id(Items.DIRT)), 8L, "dirt destroyed after the reload"))
		.thenExecute(() -> {
			re[0].setPaused(true);
			CompoundTag tag = re[0].saveWithFullMetadata(h.getLevel().registryAccess());
			h.assertTrue(tag.getBoolean("Paused"), "Paused, tag: " + tag);
			DisposalBlockEntity again = reload(h, tag);
			h.assertTrue(again.isPaused(), "a paused Disposal stays paused after a reload");
			h.assertValueEqual(again.getDestroyed().getTotal(), 5_000_000_048L, "total after the second reload");
			// A block entity without any saved data (placed from an item) is paused
			tag.remove("Paused");
			h.assertTrue(FilterBlockGameTests.<DisposalBlockEntity>reload(h, tag).isPaused(), "missing pause state must read as paused");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void disposalRedstoneStopsIt(GameTestHelper h) {
		int cd = cooldown();
		DisposalBlockEntity d = withChest(h, Content.disposal.get(), new ItemStack(Items.COBBLESTONE, 10));
		d.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		power(h, true);
		d.setPaused(false);
		h.startSequence()
		.thenIdle(5 * cd)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 10, "a powered Disposal must not destroy anything");
			h.assertValueEqual(d.getDestroyed().getTotal(), 0L, "total while powered");
			h.assertValueEqual(d.getStatus(), FilterBlockStatus.STOPPED_BY_REDSTONE, "status");
			power(h, false);
		})
		.thenWaitUntil(() -> h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 0, "cobblestone left after the signal is gone"))
		.thenExecute(() -> h.assertValueEqual(d.getDestroyed().getTotal(), 10L, "total"))
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void disposalEmptyFilterDestroysNothing(GameTestHelper h) {
		int cd = cooldown();
		DisposalBlockEntity d = withChest(h, Content.disposal.get(), new ItemStack(Items.COBBLESTONE, 10), new ItemStack(Items.DIAMOND, 3), damagedPickaxe());
		d.setPaused(false);
		Container c = container(h, BACK);
		String before = snapshot(c);
		h.startSequence()
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			h.assertValueEqual(snapshot(c), before, "a running Disposal with an empty filter must not touch anything");
			h.assertValueEqual(d.getDestroyed().getTotal(), 0L, "total");
			h.assertValueEqual(d.getStatus(), FilterBlockStatus.NO_FILTER, "status");
			// Exact on changes nothing about that
			d.setExact(true);
			// An entry that was set and cleared again leaves the filter empty
			d.setFilterStack(3, new ItemStack(Items.DIAMOND));
			d.setFilterStack(3, ItemStack.EMPTY);
		})
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			h.assertValueEqual(snapshot(c), before, "empty filter, Exact on");
			h.assertValueEqual(d.getDestroyed().getTotal(), 0L, "total");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void disposalWorksFromNetworkAndResets(GameTestHelper h) {
		DisposalBlockEntity d = withNetwork(h, Content.disposal.get(),
				new ItemStack[] {new ItemStack(Items.ROTTEN_FLESH, 20), new ItemStack(Items.DIAMOND, 2)},
				new ItemStack[] {new ItemStack(Items.ROTTEN_FLESH, 12)});
		d.setFilterStack(8, new ItemStack(Items.ROTTEN_FLESH));
		d.setPaused(false);
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(d.getDestroyed().getTotal(), 32L, "rotten flesh from both chests of the network"))
		.thenExecute(() -> {
			h.assertValueEqual(count(h, NET_A, Items.ROTTEN_FLESH) + count(h, NET_B, Items.ROTTEN_FLESH), 0, "rotten flesh left");
			h.assertValueEqual(count(h, NET_A, Items.DIAMOND), 2, "diamonds do not match and must stay");
			d.resetDestroyed();
			h.assertValueEqual(d.getDestroyed().getTotal(), 0L, "total after the reset");
			h.assertValueEqual(d.getDestroyed().get(id(Items.ROTTEN_FLESH)), 0L, "per item count after the reset");
			h.assertTrue(d.getDestroyed().isEmpty(), "counter after the reset");
			fill(h, NET_B, new ItemStack(Items.ROTTEN_FLESH, 4));
		})
		.thenWaitUntil(() -> h.assertValueEqual(d.getDestroyed().getTotal(), 4L, "counting starts again from zero"))
		.thenSucceed();
	}

	// ---- Exporter ----

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void exporterMovesItemsThenStacksIntoChest(GameTestHelper h) {
		int cd = cooldown();
		chest(h, FRONT);
		ExporterBlockEntity x = withChest(h, Content.exporter.get(), new ItemStack(Items.COBBLESTONE, 40), new ItemStack(Items.COBBLESTONE, 64),
				new ItemStack(Items.DIRT, 7), new ItemStack(Items.COBBLESTONE, 30));
		h.assertValueEqual(x.getAmount(), FilterAmount.STACK, "default amount");
		x.setAmount(FilterAmount.ITEM);
		x.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		List<Long> ops = new ArrayList<>();
		List<Integer> sizes = new ArrayList<>();
		int[] last = {0};
		h.onEachTick(() -> {
			int now = count(h, FRONT, Items.COBBLESTONE);
			if (now != last[0]) {
				ops.add(h.getTick());
				sizes.add(now - last[0]);
				last[0] = now;
			}
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE) + now, 134, "conservation: source + target");
		});
		int[] itemOps = new int[1];
		h.startSequence()
		.thenIdle(6 * cd + 2)
		.thenExecute(() -> {
			h.assertTrue(ops.size() >= 6, "too few moves: " + ops);
			for (int s : sizes)h.assertValueEqual(s, 1, "Item amount: one item per operation, sizes " + sizes);
			itemOps[0] = ops.size();
			x.setAmount(FilterAmount.STACK);
		})
		.thenWaitUntil(() -> h.assertValueEqual(count(h, FRONT, Items.COBBLESTONE), 134, "all cobblestone moved"))
		.thenIdle(3 * cd)
		.thenExecute(() -> {
			for (int i = 1; i < ops.size(); i++) {
				// Changing a setting starts the cooldown anew at most, it never shortens it
				long d = ops.get(i) - ops.get(i - 1);
				h.assertTrue(i == itemOps[0] ? d >= 1 && d <= cd : d == cd, "ticks between two operations must be the cooldown, at " + ops);
			}
			List<Integer> stacks = sizes.subList(itemOps[0], sizes.size());
			for (int s : stacks)h.assertTrue(s >= 1 && s <= 64, "Stack amount: at most 64 items per operation, sizes " + stacks);
			h.assertValueEqual(stacks.get(0), 64, "Stack amount: a full stack gathered from several source slots, sizes " + stacks);
			h.assertValueEqual(stacks.size(), (134 - itemOps[0] + 63) / 64, "operations for the rest, sizes " + stacks);
			h.assertValueEqual(count(h, BACK, Items.DIRT), 7, "dirt does not match and must stay");
			h.assertValueEqual(count(h, FRONT, Items.DIRT), 0, "dirt moved");
			h.assertTrue(x.isIdle(), "nothing left to do: the Exporter must be idle");
			h.assertValueEqual(x.getStatus(), FilterBlockStatus.RUNNING, "status");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void exporterFillsDoubleChestAndBarrel(GameTestHelper h) {
		// The front is the left half of a double chest; unstackable items need one slot each
		doubleChest(h, FRONT);
		ItemStack[] swords = new ItemStack[27];
		for (int i = 0; i < swords.length; i++)swords[i] = new ItemStack(Items.WOODEN_SWORD);
		h.setBlock(BACK, Blocks.BARREL.defaultBlockState());
		fill(h, BACK, swords);
		ExporterBlockEntity x = block(h, Content.exporter.get(), new ItemStack(Items.WOODEN_SWORD));
		// The first 30 slots of the double chest are taken, so the swords end up in both halves
		Container left = container(h, FRONT), right = container(h, FRONT.east());
		for (int i = 0; i < 27; i++)left.setItem(i, new ItemStack(Items.DIRT, 64));
		for (int i = 0; i < 3; i++)right.setItem(i, new ItemStack(Items.DIRT, 64));
		h.onEachTick(() -> h.assertValueEqual(count(h, BACK, Items.WOODEN_SWORD) + count(left, Items.WOODEN_SWORD) + count(right, Items.WOODEN_SWORD), 27, "conservation"));
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(count(right, Items.WOODEN_SWORD), 24, "swords in the far half (24 free slots)"))
		.thenIdle(40)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, BACK, Items.WOODEN_SWORD), 3, "swords that do not fit stay in the barrel");
			h.assertValueEqual(count(left, Items.WOODEN_SWORD), 0, "the near half is full");
			h.assertTrue(x.isIdle(), "target full: the Exporter must be idle");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void exporterIntoShulkerDock(GameTestHelper h) {
		int cd = cooldown();
		h.setBlock(FRONT, Content.shulkerDock.get());
		ShulkerDockBlockEntity dock = h.getBlockEntity(FRONT);
		ExporterBlockEntity x = withChest(h, Content.exporter.get(), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 20), new ItemStack(Items.DIRT, 5));
		x.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		h.startSequence()
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			h.assertValueEqual(dock.getInv().getContainerSize(), 0, "slots of a dock without a box");
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 84, "nothing may move into a dock without a box");
			h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(BLOCK)).inflate(3)).isEmpty(), "nothing may be dropped");
			h.assertTrue(dock.dock(new ItemStack(Items.SHULKER_BOX)), "box not docked");
		})
		.thenWaitUntil(() -> h.assertValueEqual(count(dock.getInv(), Items.COBBLESTONE), 84, "cobblestone in the docked box"))
		.thenExecute(() -> {
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 0, "cobblestone left in the source");
			h.assertValueEqual(count(h, BACK, Items.DIRT), 5, "dirt does not match and must stay");
			ItemStack box = dock.undock();
			h.assertValueEqual(ShulkerDockGameTests.inBox(box, Items.COBBLESTONE), 84, "cobblestone in the box that was taken out");
			fill(h, BACK, new ItemStack(Items.COBBLESTONE, 9));
		})
		.thenIdle(6 * cd)
		.thenExecute(() -> h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 9, "nothing may move after the box was taken out"))
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void exporterFullTargetLosesNothing(GameTestHelper h) {
		int cd = cooldown();
		chest(h, FRONT);
		Container target = container(h, FRONT);
		for (int i = 0; i < target.getContainerSize(); i++)target.setItem(i, new ItemStack(Items.DIRT, 64));
		ExporterBlockEntity x = withChest(h, Content.exporter.get(), new ItemStack(Items.COBBLESTONE, 30), new ItemStack(Items.STONE, 12));
		x.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		x.setFilterStack(1, new ItemStack(Items.STONE));
		// Cobblestone the test itself puts into the target later
		int[] added = new int[1];
		h.onEachTick(() -> {
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE) + count(target, Items.COBBLESTONE), 30 + added[0], "conservation: cobblestone");
			h.assertValueEqual(count(h, BACK, Items.STONE) + count(target, Items.STONE), 12, "conservation: stone");
		});
		h.startSequence()
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 30, "a full target: the items stay in the source");
			h.assertTrue(x.isIdle(), "full target: the Exporter must be idle");
			h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(BLOCK)).inflate(3)).isEmpty(), "nothing may be dropped");
			// Room for exactly 5 cobblestone, and nothing for stone
			target.setItem(13, new ItemStack(Items.COBBLESTONE, 59));
			added[0] = 59;
			target.setChanged();
		})
		.thenWaitUntil(() -> h.assertValueEqual(target.getItem(13).getCount(), 64, "the partial stack in the target is filled up"))
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 25, "only what fits moves");
			h.assertValueEqual(count(h, BACK, Items.STONE), 12, "no room for stone");
			// One free slot: one item type takes it, the other stays where it is
			target.setItem(20, ItemStack.EMPTY);
			target.setChanged();
		})
		.thenWaitUntil(() -> h.assertTrue(!target.getItem(20).isEmpty(), "the free slot is used"))
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			h.assertValueEqual(target.getItem(20).getCount(), target.getItem(20).is(Items.COBBLESTONE) ? 25 : 12, "one item type per operation fills the slot");
			h.assertTrue(x.isIdle(), "full target again: the Exporter must be idle");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void exporterRedstoneStopsIt(GameTestHelper h) {
		int cd = cooldown();
		chest(h, FRONT);
		ExporterBlockEntity x = withChest(h, Content.exporter.get(), new ItemStack(Items.COBBLESTONE, 10));
		x.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		power(h, true);
		h.startSequence()
		.thenIdle(5 * cd)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, FRONT, Items.COBBLESTONE), 0, "a powered Exporter must not move anything");
			h.assertValueEqual(x.getStatus(), FilterBlockStatus.STOPPED_BY_REDSTONE, "status");
			power(h, false);
		})
		.thenWaitUntil(() -> h.assertValueEqual(count(h, FRONT, Items.COBBLESTONE), 10, "cobblestone moved after the signal is gone"))
		.thenExecute(() -> {
			// No inventory in front: nothing moves, nothing is lost
			h.setBlock(FRONT, Blocks.AIR);
			fill(h, BACK, new ItemStack(Items.COBBLESTONE, 6));
		})
		.thenIdle(5 * cd)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, BACK, Items.COBBLESTONE), 6, "without a target the items stay in the source");
			h.assertValueEqual(x.getStatus(), FilterBlockStatus.NO_TARGET, "status without a target");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
	public void exporterWorksFromNetworkWithFilterItem(GameTestHelper h) {
		chest(h, FRONT);
		ExporterBlockEntity x = withNetwork(h, Content.exporter.get(),
				new ItemStack[] {new ItemStack(Items.IRON_INGOT, 40), new ItemStack(Items.GOLD_INGOT, 8)},
				new ItemStack[] {new ItemStack(Items.IRON_INGOT, 50), new ItemStack(Items.DIAMOND, 3)});
		x.setFilterStack(0, itemFilter(Items.IRON_INGOT, Items.DIAMOND));
		h.onEachTick(() -> h.assertValueEqual(count(h, NET_A, Items.IRON_INGOT) + count(h, NET_B, Items.IRON_INGOT) + count(h, FRONT, Items.IRON_INGOT), 90, "conservation: iron"));
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertValueEqual(count(h, FRONT, Items.IRON_INGOT), 90, "iron from both chests of the network");
			h.assertValueEqual(count(h, FRONT, Items.DIAMOND), 3, "diamonds");
		})
		.thenIdle(30)
		.thenExecute(() -> {
			h.assertValueEqual(count(h, NET_A, Items.GOLD_INGOT), 8, "gold matches no entry and must stay");
			h.assertValueEqual(count(h, FRONT, Items.GOLD_INGOT), 0, "gold moved");
			h.assertTrue(x.isIdle(), "nothing left to do: the Exporter must be idle");
		})
		.thenSucceed();
	}

	// The target is also part of the source network: cables lead from the block's back up and over it to a
	// cable connector that sits on the target (or on the far half of a double chest target).
	private static void attachToNetwork(GameTestHelper h, BlockPos inventory) {
		BlockPos cc = inventory.above();
		h.setBlock(cc, Content.invCableConnector.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING, Direction.DOWN));
		place(h, BACK, Content.invCable.get());
		place(h, BACK.above(), Content.invCable.get());
		for (int px = BACK.getX() + 1; px < cc.getX(); px++)place(h, new BlockPos(px, cc.getY(), cc.getZ()), Content.invCable.get());
		refreshShape(h, cc);
		refreshShape(h, BACK.above());
		refreshShape(h, BACK);
	}

	/** Target on the source network: the Exporter must settle, never take from the target, and lose nothing. */
	private static void targetOnSourceNetwork(GameTestHelper h, BlockPos attached, Container marked) {
		int cd = cooldown();
		chest(h, NET_A, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 36), new ItemStack(Items.DIRT, 9));
		// A second chest at the connector: a block on a cable works on the connector with the most
		// inventories of its own, which must be the Inventory Connector and not the cable connector
		chest(h, NET_B);
		h.setBlock(CONNECTOR, Content.connector.get());
		ExporterBlockEntity x = block(h, Content.exporter.get(), new ItemStack(Items.COBBLESTONE));
		attachToNetwork(h, attached);
		// Matching items that are in the target from the start: renamed, so they are still told apart
		// (the filter matches by item only). They must never be taken out and put back in.
		int markSlot = marked.getContainerSize() - 1;
		marked.setItem(markSlot, named(new ItemStack(Items.COBBLESTONE, 10), "already here"));
		marked.setChanged();
		Container near = container(h, FRONT);
		InventoryConnectorBlockEntity c = h.getBlockEntity(CONNECTOR);
		Runnable conserved = () -> {
			int inTarget = count(near, Items.COBBLESTONE) + (marked != near ? count(marked, Items.COBBLESTONE) : 0);
			h.assertValueEqual(count(h, NET_A, Items.COBBLESTONE) + inTarget, 110, "conservation: source chest + target");
			ItemStack mark = marked.getItem(markSlot);
			h.assertTrue(mark.is(Items.COBBLESTONE) && mark.getCount() == 10 && mark.has(DataComponents.CUSTOM_NAME), "the Exporter took items out of its own target: slot " + markSlot + " holds " + mark);
		};
		h.onEachTick(conserved);
		String[] steady = new String[2];
		h.startSequence()
		.thenWaitUntil(() -> h.assertValueEqual(c.getInventoryCount(), 3, "inventories on the network (two chests at the connector + the target)"))
		.thenWaitUntil(() -> h.assertValueEqual(count(h, NET_A, Items.COBBLESTONE), 0, "cobblestone left in the source chest"))
		.thenIdle(2 * cd)
		.thenExecute(() -> {
			h.assertTrue(x.isIdle(), "everything is in the target: the Exporter must be idle, not keep moving items inside it");
			steady[0] = snapshot(near);
			steady[1] = snapshot(marked);
			// The exclusion itself: the network is the source, exactly its one member that is the target is left out
			BlockPos abs = h.absolutePos(BLOCK);
			NetworkInventory src = new NetworkInventory(), dst = new NetworkInventory();
			src.onLoad(h.getLevel(), abs.west(), Direction.EAST, x);
			dst.onLoad(h.getLevel(), abs.east(), Direction.WEST, x);
			IInventoryAccess source = src.getAccess(h.getLevel(), abs.west()), target = dst.getAccess(h.getLevel(), abs.east());
			h.assertTrue(src.isNetwork(), "the source must be the network");
			h.assertTrue(target instanceof BlockInventoryAccess, "the target must be the inventory in front, got " + target);
			TargetExclusion ex = TargetExclusion.of(source, target);
			h.assertFalse(ex.isAll(), "the source chest is not the target");
			h.assertValueEqual(ex.excludedMembers(), 1, "source members that are the target");
			source.tracker().getChangeTracker(h.getLevel());
			int excluded = 0, open = 0;
			InventorySlot slot = null;
			while ((slot = source.tracker().findSlotAfter(slot, s -> true, false, false)) != null) {
				if (ex.excludes(slot))excluded++;
				else open++;
			}
			h.assertTrue(excluded >= 2, "slots of the target the source offers, all excluded: " + excluded);
			h.assertValueEqual(open, 1, "slots outside the target (the dirt in the source chest)");
			h.assertTrue(TargetExclusion.of(source, source).isAll(), "a source that is its own target has nothing to move");
		})
		.thenIdle(10 * cd)
		.thenExecute(() -> {
			h.assertTrue(x.isIdle(), "steady state: the Exporter must stay idle");
			h.assertValueEqual(snapshot(near), steady[0], "steady state: nothing may move any more");
			h.assertValueEqual(snapshot(marked), steady[1], "steady state: nothing may move any more");
			h.assertValueEqual(count(h, NET_A, Items.DIRT), 9, "dirt does not match and must stay");
		})
		.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 600)
	public void exporterTargetOnSourceNetworkSettles(GameTestHelper h) {
		chest(h, FRONT);
		targetOnSourceNetwork(h, FRONT, container(h, FRONT));
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 600)
	public void exporterDoubleChestTargetOnSourceNetworkSettles(GameTestHelper h) {
		// The Exporter fills the left half, the network is attached to the right half: one inventory
		doubleChest(h, FRONT);
		targetOnSourceNetwork(h, FRONT.east(), container(h, FRONT.east()));
	}

	// ---- Network ----

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void cablesConnectToTheBackOnly(GameTestHelper h) {
		for (Block b : List.of(Content.ejector.get(), Content.disposal.get(), Content.exporter.get())) {
			h.setBlock(BLOCK, facing(b, Direction.EAST));
			for (Direction d : Direction.values())place(h, BLOCK.relative(d), Content.invCable.get());
			for (Direction d : Direction.values()) {
				BlockState cable = h.getBlockState(BLOCK.relative(d));
				boolean arm = cable.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d.getOpposite()));
				h.assertValueEqual(arm, d == Direction.WEST, b + " facing east: cable arm from the " + d + " side");
				h.assertValueEqual(IInventoryCable.canConnect(h.getBlockState(BLOCK), d.getOpposite()), d == Direction.WEST, b + ": canConnect from " + d);
			}
			// Rotated in place: the arms follow the new back
			BlockState state = h.getBlockState(BLOCK);
			h.assertValueEqual(state.rotate(Rotation.CLOCKWISE_90).getValue(AbstractFilterBlock.FACING), Direction.SOUTH, "rotate");
			h.assertValueEqual(state.mirror(Mirror.FRONT_BACK).getValue(AbstractFilterBlock.FACING), Direction.WEST, "mirror");
			h.assertValueEqual(state.getProperties().size(), 1, "FACING is the only block state property");
			h.setBlock(BLOCK, facing(b, Direction.UP));
			for (Direction d : Direction.values()) {
				BlockState cable = h.getBlockState(BLOCK.relative(d));
				boolean arm = cable.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d.getOpposite()));
				h.assertValueEqual(arm, d == Direction.DOWN, b + " facing up: cable arm from the " + d + " side");
			}
			for (Direction d : Direction.values())h.setBlock(BLOCK.relative(d), Blocks.AIR);
		}
		h.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 300)
	public void neverAnInventoryOfAConnector(GameTestHelper h) {
		// A connector with one chest and all three blocks touching it, their backs to the connector
		BlockPos connector = new BlockPos(3, 3, 3);
		chest(h, connector.west(), new ItemStack(Items.COBBLESTONE, 5));
		h.setBlock(connector, Content.connector.get());
		h.setBlock(connector.east(), facing(Content.ejector.get(), Direction.EAST));
		h.setBlock(connector.above(), facing(Content.disposal.get(), Direction.UP));
		h.setBlock(connector.south(), facing(Content.exporter.get(), Direction.SOUTH));
		List<BlockPos> blocks = List.of(connector.east(), connector.above(), connector.south());
		InventoryConnectorBlockEntity c = h.getBlockEntity(connector);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(c.getDoctorStats() != null, "connector has not ticked yet");
			h.assertValueEqual(c.getInventoryCount(), 1, "inventories of the connector (the chest only)");
		})
		.thenIdle(40)
		.thenExecute(() -> {
			h.assertValueEqual(c.getInventoryCount(), 1, "inventories of the connector (the chest only)");
			h.assertValueEqual(c.getNetworkHandler().getSlotCount(), 27, "slots of the network");
			for (BlockPos rel : blocks) {
				BlockPos abs = h.absolutePos(rel);
				BlockState state = h.getLevel().getBlockState(abs);
				h.assertFalse(c.getConnectedBlocks().contains(abs), state.getBlock() + " is listed as an inventory of the connector");
				for (Direction d : Direction.values()) {
					h.assertFalse(BlockInventoryAccess.hasInventoryAt(h.getLevel(), abs, state, d), state.getBlock() + " exposes an inventory on its " + d + " side");
				}
				DoctorScan.BlockCheck check = DoctorScan.checkBlock(state.getBlock(), h.getLevel(), abs);
				h.assertValueEqual(check.verdict(), DoctorScan.Verdict.NETWORK_BLOCK, "Network Doctor verdict for " + state.getBlock());
			}
			// The Network Doctor counts the three together
			NetworkReport.Details d = DoctorScan.full(c, h.getLevel().getServer()).details();
			h.assertValueEqual(d.filterBlocks(), 3, "filter blocks attached to the network");
			h.assertValueEqual(d.hoppers(), 0, "hoppers");
			h.assertValueEqual(d.emitters(), 0, "level emitters");
			h.assertValueEqual(DoctorScan.attached(c.getDoctorStats(), h.getLevel().getServer())[4], 3, "DoctorScan.attached, filter blocks");
			// Turned away from the connector the Ejector is no longer on its network
			h.setBlock(connector.east(), facing(Content.ejector.get(), Direction.WEST));
		})
		.thenIdle(5)
		.thenExecute(() -> h.assertValueEqual(DoctorScan.full(c, h.getLevel().getServer()).details().filterBlocks(), 2, "filter blocks after one was turned away"))
		.thenSucceed();
	}

	// ---- Placement and menus ----

	private static ServerPlayer player(GameTestHelper h) {
		ServerPlayer p = fakePlayer(h);
		BlockPos a = h.absolutePos(BLOCK);
		p.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 2.5, 180, 0);
		// The looking direction of a living entity is its head rotation
		p.setYHeadRot(180);
		p.getInventory().clearContent();
		p.getInventory().selected = 0;
		p.setShiftKeyDown(false);
		p.setGameMode(GameType.SURVIVAL);
		return p;
	}

	/** Places {@code block} from the player's hand against {@code face} of the block at {@code against}. */
	private static Direction placeAgainst(GameTestHelper h, ServerPlayer p, Block block, BlockPos against, Direction face, boolean sneak) {
		BlockPos target = against.relative(face);
		h.setBlock(target, Blocks.AIR);
		p.setShiftKeyDown(sneak);
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(block));
		BlockPos a = h.absolutePos(against);
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(a).relative(face, 0.5), face, a, false);
		InteractionResult r = p.gameMode.useItemOn(p, h.getLevel(), p.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
		h.assertTrue(r.consumesAction() && h.getBlockState(target).is(block), block + " was not placed against " + h.getBlockState(against).getBlock() + ": " + r);
		return h.getBlockState(target).getValue(AbstractFilterBlock.FACING);
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void placementFollowsNetworkBlocksInventoriesAndThePlayer(GameTestHelper h) {
		// The player stands south of the blocks and looks north: a dropper placed by it would face south
		ServerPlayer p = player(h);
		BlockPos base = new BlockPos(3, 2, 3), pos = base.above();
		for (Block b : List.of(Content.ejector.get(), Content.disposal.get(), Content.exporter.get())) {
			// 1. Against a network block: the back faces it, the front points away. Sneaking changes nothing.
			h.setBlock(base, Content.invCable.get());
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.UP, false), Direction.UP, b + " on a cable");
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.UP, true), Direction.UP, b + " on a cable, sneaking");
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.EAST, false), Direction.EAST, b + " next to a cable");
			clearAround(h, base);
			// A connector answers a right click itself, so a block is placed against it while sneaking
			h.setBlock(base, Content.connector.get());
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.NORTH, true), Direction.NORTH, b + " next to a connector");
			clearAround(h, base);

			// 2. Against any other block with a block entity: the front points at it
			h.setBlock(base, Blocks.CHEST);
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.SOUTH, true), Direction.NORTH, b + " next to a chest");
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.UP, true), Direction.DOWN, b + " on a chest");
			clearAround(h, base);
			h.setBlock(base, Content.shulkerDock.get());
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.WEST, true), Direction.EAST, b + " next to a Shulker Dock");
			clearAround(h, base);

			// 4. Against a plain block with no network block around: like a dropper, the front faces the player
			h.setBlock(base, Blocks.STONE);
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.UP, false), Direction.SOUTH, b + " on stone");
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.WEST, false), Direction.SOUTH, b + " next to stone");
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.WEST, true), Direction.SOUTH, b + " next to stone, sneaking");
			p.setXRot(90);
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.UP, false), Direction.UP, b + " on stone, looking down");
			p.setXRot(0);
			clearAround(h, base);

			// 3. Against a plain block, a network block next to the new block: the back faces it
			h.setBlock(pos.east(), Content.invCable.get());
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.UP, false), Direction.WEST, b + " on stone, cable to the east");
			// Several: the first in the order down, up, north, south, west, east
			h.setBlock(pos.west(), Content.invCable.get());
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.UP, false), Direction.EAST, b + " on stone, cables to the west and east");
			// A clicked inventory still comes first
			h.setBlock(base, Blocks.BARREL);
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.UP, true), Direction.DOWN, b + " on a barrel, cables to the west and east");
			h.setBlock(base, Blocks.STONE);
			// ...and the network block opposite the clicked block wins over the fixed order
			h.setBlock(pos.above(), Content.invCable.get());
			h.assertValueEqual(placeAgainst(h, p, b, base, Direction.UP, false), Direction.DOWN, b + " on stone, cables above, to the west and east");
			clearAround(h, base);
		}
		// A Disposal placed by hand is paused
		placeAgainst(h, p, Content.disposal.get(), base, Direction.UP, false);
		h.assertTrue(h.<DisposalBlockEntity>getBlockEntity(pos).isPaused(), "a Disposal placed by hand must be paused");
		h.succeed();
	}

	/** Removes everything next to and above {@code base} that a placement test left behind. */
	private static void clearAround(GameTestHelper h, BlockPos base) {
		for (BlockPos q : BlockPos.betweenClosed(base.offset(-1, 0, -1), base.offset(1, 2, 1))) {
			if (!q.equals(base))h.setBlock(q.immutable(), Blocks.AIR);
		}
	}

	private static CompoundTag msg(String key, int value) {
		CompoundTag t = new CompoundTag();
		t.putInt(key, value);
		return t;
	}

	private static CompoundTag flag(String key, boolean value) {
		CompoundTag t = new CompoundTag();
		t.putBoolean(key, value);
		return t;
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 100)
	public void menusValidateClientMessages(GameTestHelper h) {
		ServerPlayer p = player(h);
		EjectorBlockEntity e = block(h, Content.ejector.get());
		EjectorMenu m = new EjectorMenu(1, p.getInventory(), e);
		h.assertTrue(m.stillValid(p), "the player stands next to the block");
		h.assertValueEqual(m.slots.size(), GhostFilter.SLOTS + 36, "slots: 9 ghost slots + the player inventory");

		// Settings
		m.receive(msg(EjectorMenu.MSG_MODE, EjectorMode.PULSE.ordinal()));
		h.assertValueEqual(e.getMode(), EjectorMode.PULSE, "mode message");
		m.receive(msg(EjectorMenu.MSG_MODE, 7));
		m.receive(msg(EjectorMenu.MSG_MODE, -1));
		h.assertValueEqual(e.getMode(), EjectorMode.PULSE, "a mode out of range must be ignored");
		m.receive(msg(EjectorMenu.MSG_AMOUNT, FilterAmount.STACK.ordinal()));
		h.assertValueEqual(e.getAmount(), FilterAmount.STACK, "amount message");
		m.receive(msg(EjectorMenu.MSG_AMOUNT, 2));
		h.assertValueEqual(e.getAmount(), FilterAmount.STACK, "an amount out of range must be ignored");
		m.receive(flag(AbstractFilterBlockMenu.MSG_EXACT, true));
		h.assertTrue(e.getFilter().isExact(), "exact message");
		m.receive(new CompoundTag());
		h.assertTrue(e.getFilter().isExact() && e.getMode() == EjectorMode.PULSE && e.getAmount() == FilterAmount.STACK, "an empty message must change nothing");

		// Ghost slots: a click leaves a reference of 1 and takes nothing from the cursor
		m.setCarried(new ItemStack(Items.DIAMOND, 5));
		m.clicked(0, 0, ClickType.PICKUP, p);
		h.assertTrue(e.getFilter().getStack(0).is(Items.DIAMOND) && e.getFilter().getStack(0).getCount() == 1, "ghost slot after a click: " + e.getFilter().getStack(0));
		h.assertValueEqual(m.getCarried().getCount(), 5, "the cursor stack must not be used up");
		m.clicked(0, 0, ClickType.QUICK_MOVE, p);
		h.assertTrue(e.getFilter().getStack(0).is(Items.DIAMOND), "a ghost entry cannot be shift-clicked out");
		m.setCarried(ItemStack.EMPTY);
		h.assertTrue(p.getInventory().isEmpty(), "nothing may reach the player inventory from a ghost slot");
		// Shift-click on an inventory stack: a copy goes into the first free ghost slot, once
		p.getInventory().setItem(9, new ItemStack(Items.EMERALD, 12));
		m.quickMoveStack(p, GhostFilter.SLOTS);
		m.quickMoveStack(p, GhostFilter.SLOTS);
		h.assertTrue(e.getFilter().getStack(1).is(Items.EMERALD) && e.getFilter().getStack(1).getCount() == 1, "ghost slot after a shift-click: " + e.getFilter().getStack(1));
		h.assertTrue(e.getFilter().getStack(2).isEmpty(), "the same entry must not be added twice");
		h.assertValueEqual(p.getInventory().getItem(9).getCount(), 12, "the inventory stack must not be used up");
		// The recipe viewer drag (setPhantom)
		CompoundTag phantom = new CompoundTag(), inner = new CompoundTag();
		inner.putInt("id", 5);
		inner.put("item", new ItemStack(Items.GOLD_INGOT, 40).save(h.getLevel().registryAccess()));
		phantom.put("setPhantom", inner);
		m.receive(phantom);
		h.assertTrue(e.getFilter().getStack(5).is(Items.GOLD_INGOT) && e.getFilter().getStack(5).getCount() == 1, "ghost slot after a recipe viewer drag: " + e.getFilter().getStack(5));
		// An empty hand clears the entry
		m.clicked(0, 0, ClickType.PICKUP, p);
		h.assertTrue(e.getFilter().getStack(0).isEmpty(), "ghost slot after a click with an empty cursor");

		// A spectator changes nothing
		p.setGameMode(GameType.SPECTATOR);
		m.receive(msg(EjectorMenu.MSG_MODE, EjectorMode.CONTINUOUS.ordinal()));
		m.receive(flag(AbstractFilterBlockMenu.MSG_EXACT, false));
		m.receive(phantom.copy());
		h.assertTrue(e.getMode() == EjectorMode.PULSE && e.getFilter().isExact(), "messages of a spectator must be ignored");
		p.setGameMode(GameType.SURVIVAL);
		// Neither does a player who is too far away
		BlockPos a = h.absolutePos(BLOCK);
		p.moveTo(a.getX() + 30.5, a.getY(), a.getZ() + 0.5, 0, 0);
		h.assertFalse(m.stillValid(p), "30 blocks away");
		m.receive(msg(EjectorMenu.MSG_MODE, EjectorMode.CONTINUOUS.ordinal()));
		h.assertValueEqual(e.getMode(), EjectorMode.PULSE, "messages from too far away must be ignored");
		p.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 2.5, 180, 0);
		m.receive(msg(EjectorMenu.MSG_MODE, EjectorMode.CONTINUOUS.ordinal()));
		h.assertValueEqual(e.getMode(), EjectorMode.CONTINUOUS, "back in reach");
		// A client side menu (no block entity) ignores everything
		new EjectorMenu(2, p.getInventory()).receive(msg(EjectorMenu.MSG_MODE, 1));

		// Exporter
		ExporterBlockEntity x = block(h, Content.exporter.get());
		ExporterMenu xm = new ExporterMenu(3, p.getInventory(), x);
		xm.receive(msg(ExporterMenu.MSG_AMOUNT, FilterAmount.ITEM.ordinal()));
		h.assertValueEqual(x.getAmount(), FilterAmount.ITEM, "exporter amount message");
		xm.receive(msg(ExporterMenu.MSG_AMOUNT, 99));
		h.assertValueEqual(x.getAmount(), FilterAmount.ITEM, "an amount out of range must be ignored");

		// Disposal: pause and reset
		DisposalBlockEntity d = block(h, Content.disposal.get());
		DisposalMenu dm = new DisposalMenu(4, p.getInventory(), d);
		d.getDestroyed().add(id(Items.DIRT), 12);
		dm.receive(flag(DisposalMenu.MSG_PAUSED, false));
		h.assertFalse(d.isPaused(), "pause message (start)");
		h.assertValueEqual(d.getDestroyed().getTotal(), 12L, "starting must not reset the counter");
		dm.receive(flag(DisposalMenu.MSG_RESET, false));
		h.assertValueEqual(d.getDestroyed().getTotal(), 12L, "a reset message without the flag must not reset");
		p.setGameMode(GameType.SPECTATOR);
		dm.receive(flag(DisposalMenu.MSG_RESET, true));
		dm.receive(flag(DisposalMenu.MSG_PAUSED, true));
		h.assertTrue(d.getDestroyed().getTotal() == 12L && !d.isPaused(), "messages of a spectator must be ignored");
		p.setGameMode(GameType.SURVIVAL);
		dm.receive(flag(DisposalMenu.MSG_RESET, true));
		h.assertTrue(d.getDestroyed().isEmpty(), "reset message");
		dm.receive(flag(DisposalMenu.MSG_PAUSED, true));
		h.assertTrue(d.isPaused(), "pause message (pause)");
		h.succeed();
	}

	// ---- loader specific ----

	static ServerPlayer fakePlayer(GameTestHelper h) {
		return FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "filter-test"));
	}
}
