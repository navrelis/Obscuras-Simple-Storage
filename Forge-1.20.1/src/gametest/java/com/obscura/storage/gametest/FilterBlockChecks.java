package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.StorageTags;
import com.obscura.storage.block.AbstractFilterBlock;
import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.block.ITrim;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.doctor.DoctorScan;
import com.obscura.storage.doctor.NetworkReport;
import com.obscura.storage.gui.AbstractFilterBlockMenu;
import com.obscura.storage.gui.DisposalMenu;
import com.obscura.storage.gui.EjectorMenu;
import com.obscura.storage.gui.ExporterMenu;
import com.obscura.storage.tile.AbstractFilterBlockEntity;
import com.obscura.storage.tile.DisposalBlockEntity;
import com.obscura.storage.tile.EjectorBlockEntity;
import com.obscura.storage.tile.ExporterBlockEntity;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.tile.ShulkerDockBlockEntity;
import com.obscura.storage.util.DestroyedCounter;
import com.obscura.storage.util.EjectorMode;
import com.obscura.storage.util.FilterAmount;
import com.obscura.storage.util.FilterBlockLogic;
import com.obscura.storage.util.FilterBlockStatus;
import com.obscura.storage.util.GhostFilter;
import com.obscura.storage.util.ItemLocator;
import com.obscura.storage.util.TargetExclusion;

/**
 * The Ejector, Disposal and Exporter GameTests of both 1.20.1 loaders (identical copies in
 * Fabric-1.20.1 and Forge-1.20.1, like {@link ShulkerDockChecks}); the loader specific part, the fake
 * player, comes in through {@link Platform}. Ported from the 1.21.1 tests.
 *
 * <p>Every block sits at {@link #BLOCK} facing east: its source is the block west of it (a chest, or
 * a cable leading to a connector with two chests), its front is {@link #FRONT}.
 */
public final class FilterBlockChecks {
	static final BlockPos BLOCK = new BlockPos(3, 3, 3);
	/** The block behind: a chest (plain source) or a cable (network source). */
	static final BlockPos BACK = new BlockPos(2, 3, 3);
	static final BlockPos FRONT = new BlockPos(4, 3, 3);
	static final BlockPos REDSTONE = new BlockPos(3, 4, 3);
	// Network source: connector with a chest north and south of it, a cable from it to the block's back
	static final BlockPos CONNECTOR = new BlockPos(1, 3, 3);
	static final BlockPos NET_A = new BlockPos(1, 3, 2);
	static final BlockPos NET_B = new BlockPos(1, 3, 4);

	private FilterBlockChecks() {}

	/** What differs between the loaders. */
	public interface Platform {
		ServerPlayer fakePlayer(GameTestHelper h);
	}

	// ---- helpers ----

	private static int cooldown() {
		return Math.max(1, Config.get().filterBlockCooldown);
	}

	private static <T extends AbstractFilterBlockEntity> T block(GameTestHelper h, Block block, ItemStack... filter) {
		h.setBlock(BLOCK, facing(block, Direction.EAST));
		T be = be(h, BLOCK);
		for (int i = 0; i < filter.length; i++)be.setFilterStack(i, filter[i]);
		return be;
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

	/**
	 * Items of {@code items} lying in front of the block as item entities. Always asked for the items
	 * a test put into its source, filter or target, never for "any item": the Forge test world is a
	 * normal one, and what falls out of a cave above the test area (gravel, azaleas, moss carpets)
	 * is no drop of the block.
	 */
	private static int dropped(GameTestHelper h, Item... items) {
		int n = 0;
		for (ItemEntity e : drops(h)) {
			for (Item i : items) {
				if (e.getItem().is(i))n += e.getItem().getCount();
			}
		}
		return n;
	}

	/** The stack of the item entity in front of the block that holds {@code item}, EMPTY when there is none. */
	private static ItemStack droppedStack(GameTestHelper h, Item item) {
		for (ItemEntity e : drops(h)) {
			if (e.getItem().is(item))return e.getItem();
		}
		return ItemStack.EMPTY;
	}

	private static void power(GameTestHelper h, boolean on) {
		h.setBlock(REDSTONE, on ? Blocks.REDSTONE_BLOCK : Blocks.AIR);
	}

	private static ItemStack damagedPickaxe() {
		ItemStack s = new ItemStack(Items.IRON_PICKAXE);
		s.setDamageValue(25);
		return s;
	}

	private static ItemStack named(ItemStack s, String name) {
		s.setHoverName(Component.literal(name));
		return s;
	}

	/** Every slot of a container as text, to compare two moments. */
	private static String snapshot(Container c) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty())sb.append(i).append(':').append(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()).append('x').append(s.getCount()).append(s.hasCustomHoverName() ? "* " : " ");
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
		BlockEntity loaded = BlockEntity.loadStatic(abs, state, tag);
		h.assertTrue(loaded instanceof AbstractFilterBlockEntity, "the saved tag must load into the block entity, got " + loaded);
		h.getLevel().setBlockEntity(loaded);
		// The neighbour update a loaded block gets
		((AbstractFilterBlock) state.getBlock()).updateRedstone(h.getLevel(), abs);
		return (T) loaded;
	}

	private static List<ItemEntity> itemsAround(GameTestHelper h) {
		return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(BLOCK)).inflate(3));
	}

	/**
	 * Items of {@code items} lying around the block as item entities: {@link #dropped} for the blocks
	 * that must not drop anything, with the same rule about which items to ask for.
	 */
	private static int lyingAround(GameTestHelper h, Item... items) {
		int n = 0;
		for (ItemEntity e : itemsAround(h)) {
			for (Item i : items) {
				if (e.getItem().is(i))n += e.getItem().getCount();
			}
		}
		return n;
	}

	// ---- Ejector ----

	static void ejectorContinuousDropsMatchingItemsWhilePowered(GameTestHelper h) {
		int cd = cooldown();
		cell(h);
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), new ItemStack(Items.COBBLESTONE, 32), new ItemStack(Items.STONE, 10));
		e.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		eq(e.getMode(), EjectorMode.CONTINUOUS, "default mode");
		eq(e.getAmount(), FilterAmount.ITEM, "default amount");
		List<Long> moves = new ArrayList<>();
		int[] last = {32};
		h.onEachTick(() -> {
			int now = count(h, BACK, Items.COBBLESTONE);
			for (int i = now; i < last[0]; i++)moves.add(h.getTick());
			last[0] = now;
			eq(now + dropped(h, Items.COBBLESTONE), 32, "conservation: source + item entities");
		});
		int[] atStop = new int[1];
		h.startSequence()
		.thenIdle(30)
		.thenExecute(() -> {
			eq(count(h, BACK, Items.COBBLESTONE), 32, "an unpowered Ejector must not take anything");
			eq(dropped(h, Items.COBBLESTONE, Items.STONE), 0, "an unpowered Ejector must not drop anything: " + drops(h));
			eq(e.getStatus(), FilterBlockStatus.NEEDS_REDSTONE, "status while unpowered");
			power(h, true);
		})
		.thenIdle(8 * cd + 2)
		.thenExecute(() -> {
			h.assertTrue(moves.size() >= 8, "too few drops while powered: " + moves);
			for (int i = 1; i < moves.size(); i++) {
				long d = moves.get(i) - moves.get(i - 1);
				eq(d, (long) cd, "ticks between two drops (config filterBlockCooldown), drops at " + moves);
			}
			eq(dropped(h, Items.COBBLESTONE), moves.size(), "one item entity item per drop");
			eq(count(h, BACK, Items.STONE), 10, "stone does not match and must stay");
			eq(dropped(h, Items.STONE), 0, "stone dropped");
			eq(e.getStatus(), FilterBlockStatus.RUNNING, "status while powered");
			power(h, false);
			atStop[0] = count(h, BACK, Items.COBBLESTONE);
		})
		.thenIdle(3 * cd)
		.thenExecute(() -> eq(count(h, BACK, Items.COBBLESTONE), atStop[0], "nothing may be taken after the signal is gone"))
		.thenSucceed();
	}

	static void ejectorExactDecidesAboutDamagedTools(GameTestHelper h) {
		cell(h);
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), damagedPickaxe());
		// A pristine pickaxe as the ghost entry
		e.setFilterStack(4, new ItemStack(Items.IRON_PICKAXE));
		e.setExact(true);
		power(h, true);
		h.startSequence()
		.thenIdle(40)
		.thenExecute(() -> {
			eq(count(h, BACK, Items.IRON_PICKAXE), 1, "Exact on: a damaged tool does not match a pristine entry");
			eq(dropped(h, Items.IRON_PICKAXE), 0, "nothing may be dropped with Exact on: " + drops(h));
			e.setExact(false);
		})
		.thenWaitUntil(() -> eq(dropped(h, Items.IRON_PICKAXE), 1, "Exact off: the damaged tool matches"))
		.thenExecute(() -> {
			eq(count(h, BACK, Items.IRON_PICKAXE), 0, "pickaxe left in the source");
			ItemStack out = droppedStack(h, Items.IRON_PICKAXE);
			h.assertTrue(ItemStack.isSameItemSameTags(out, damagedPickaxe()), "the dropped pickaxe must be the damaged one, unchanged: " + out + " " + out.getTag());
		})
		.thenSucceed();
	}

	static void ejectorExactMatchesTheSameNbt(GameTestHelper h) {
		cell(h);
		// Exact on with the damaged pickaxe itself as the entry: that one matches, the pristine one stays
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), new ItemStack(Items.IRON_PICKAXE), damagedPickaxe(), named(new ItemStack(Items.PAPER, 3), "Note"), new ItemStack(Items.PAPER, 5));
		e.setFilterStack(0, damagedPickaxe());
		e.setFilterStack(1, new ItemStack(Items.PAPER));
		e.setExact(true);
		e.setAmount(FilterAmount.STACK);
		power(h, true);
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(dropped(h, Items.IRON_PICKAXE), 1, "the damaged pickaxe (same NBT as the entry)");
			eq(dropped(h, Items.PAPER), 5, "the plain paper (same NBT as the entry: none)");
		})
		.thenIdle(30)
		.thenExecute(() -> {
			Container c = container(h, BACK);
			h.assertTrue(c.getItem(0).is(Items.IRON_PICKAXE) && c.getItem(0).getDamageValue() == 0, "the pristine pickaxe must stay: " + c.getItem(0));
			h.assertTrue(c.getItem(2).is(Items.PAPER) && c.getItem(2).getCount() == 3 && c.getItem(2).hasCustomHoverName(), "the renamed paper must stay: " + c.getItem(2));
			eq(dropped(h, Items.IRON_PICKAXE), 1, "pickaxes dropped");
			eq(dropped(h, Items.PAPER), 5, "paper dropped");
			h.assertTrue(e.isIdle(), "nothing matches any more: the Ejector must be idle");
		})
		.thenSucceed();
	}

	static void ejectorEmptyFilterDoesNothing(GameTestHelper h) {
		cell(h);
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), new ItemStack(Items.COBBLESTONE, 32), damagedPickaxe());
		power(h, true);
		h.startSequence()
		.thenIdle(40)
		.thenExecute(() -> {
			h.assertTrue(e.getFilter().isEmpty(), "filter");
			eq(e.getStatus(), FilterBlockStatus.NO_FILTER, "status");
			eq(count(h, BACK, Items.COBBLESTONE), 32, "an empty filter must not match anything");
			eq(count(h, BACK, Items.IRON_PICKAXE), 1, "an empty filter must not match anything");
			eq(dropped(h, Items.COBBLESTONE, Items.IRON_PICKAXE), 0, "nothing may be dropped with an empty filter: " + drops(h));
			// The same in Pulse mode, with a fresh rising edge
			e.setMode(EjectorMode.PULSE);
			power(h, false);
		})
		.thenIdle(5)
		.thenExecute(() -> power(h, true))
		.thenIdle(20)
		.thenExecute(() -> {
			eq(dropped(h, Items.COBBLESTONE, Items.IRON_PICKAXE), 0, "a pulse with an empty filter dropped something: " + drops(h));
			// The pulse was used up: setting a filter afterwards must not drop anything without a new edge
			e.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		})
		.thenIdle(20)
		.thenExecute(() -> eq(dropped(h, Items.COBBLESTONE, Items.IRON_PICKAXE), 0, "an old pulse fired after the filter was set: " + drops(h)))
		.thenSucceed();
	}

	static void ejectorPulseGivesOneBatchPerRisingEdge(GameTestHelper h) {
		int cd = cooldown();
		cell(h);
		// Two slots: a stack batch has to gather its 64 items from both
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), new ItemStack(Items.COBBLESTONE, 40), new ItemStack(Items.COBBLESTONE, 40), new ItemStack(Items.STONE, 5));
		e.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		e.setMode(EjectorMode.PULSE);
		h.onEachTick(() -> eq(count(h, BACK, Items.COBBLESTONE) + dropped(h, Items.COBBLESTONE), 80, "conservation: source + item entities"));
		h.startSequence()
		.thenIdle(20)
		.thenExecute(() -> {
			eq(dropped(h, Items.COBBLESTONE), 0, "nothing may drop without a pulse");
			power(h, true);
		})
		.thenIdle(3)
		.thenExecute(() -> eq(dropped(h, Items.COBBLESTONE), 1, "Item amount: one item per rising edge"))
		.thenIdle(4 * cd)
		.thenExecute(() -> {
			eq(dropped(h, Items.COBBLESTONE), 1, "a held signal must not drop more");
			power(h, false);
		})
		.thenIdle(cd + 2)
		.thenExecute(() -> {
			eq(dropped(h, Items.COBBLESTONE), 1, "a falling edge must not drop anything");
			power(h, true);
		})
		.thenIdle(3)
		.thenExecute(() -> {
			eq(dropped(h, Items.COBBLESTONE), 2, "second rising edge");
			power(h, false);
			e.setAmount(FilterAmount.STACK);
		})
		.thenIdle(cd + 2)
		.thenExecute(() -> {
			eq(dropped(h, Items.COBBLESTONE), 2, "switching the amount must not drop anything");
			power(h, true);
		})
		.thenIdle(3)
		.thenExecute(() -> {
			eq(dropped(h, Items.COBBLESTONE), 66, "Stack amount: one stack of 64 per rising edge, gathered from two slots");
			eq(count(h, BACK, Items.COBBLESTONE), 14, "cobblestone left in the source");
		})
		.thenIdle(4 * cd)
		.thenExecute(() -> {
			eq(dropped(h, Items.COBBLESTONE), 66, "a held signal must not drop a second stack");
			power(h, false);
		})
		.thenIdle(cd + 2)
		.thenExecute(() -> power(h, true))
		.thenIdle(3)
		.thenExecute(() -> {
			eq(dropped(h, Items.COBBLESTONE), 80, "the last pulse drops the rest");
			eq(count(h, BACK, Items.STONE), 5, "stone does not match and must stay");
			eq(dropped(h, Items.STONE), 0, "stone dropped");
		})
		.thenSucceed();
	}

	static void ejectorPulseFollowsClockFasterThanCooldown(GameTestHelper h) {
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
			eq(now + dropped(h, Items.COBBLESTONE), 5, "conservation: source + item entities");
		});
		GameTestSequence seq = h.startSequence().thenIdle(10);
		for (int i = 1; i <= 5; i++) {
			int edges = i;
			seq = seq.thenExecute(() -> power(h, true))
			.thenIdle(2)
			.thenExecute(() -> {
				eq(dropped(h, Items.COBBLESTONE), edges, "one batch per rising edge, also inside the cooldown (edge " + edges + ")");
				power(h, false);
			})
			.thenIdle(2);
		}
		seq.thenExecute(() -> {
			eq(drops.size(), 5, "batches for 5 rising edges: " + drops);
			for (int i = 1; i < drops.size(); i++) {
				eq(drops.get(i) - drops.get(i - 1), 4L, "every batch comes right after its edge, 4 ticks apart: " + drops);
			}
			eq(count(h, BACK, Items.COBBLESTONE), 0, "cobblestone left in the source");
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
			eq(dropped(h, Items.COBBLESTONE), 5, "nothing more to drop");
			eq(count(h, BACK, Items.STONE), 3, "stone does not match and must stay");
			eq(dropped(h, Items.STONE), 0, "stone dropped");
		})
		.thenSucceed();
	}

	static void ejectorSavedSignalPreventsPulseAfterReload(GameTestHelper h) {
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
			eq(dropped(h, Items.COBBLESTONE), 1, "first pulse");
			CompoundTag tag = e.saveWithFullMetadata();
			h.assertTrue(tag.getBoolean("Powered"), "the last seen redstone signal must be saved, tag: " + tag);
			eq((int) tag.getTagType("Powered"), (int) Tag.TAG_BYTE, "Powered is a byte, tag: " + tag);
			eq((int) tag.getTagType("Mode"), (int) Tag.TAG_BYTE, "Mode is a byte, tag: " + tag);
			eq((int) tag.getByte("Mode"), EjectorMode.PULSE.ordinal(), "Mode, tag: " + tag);
			eq((int) tag.getTagType("Amount"), (int) Tag.TAG_BYTE, "Amount is a byte, tag: " + tag);
			eq((int) tag.getByte("Amount"), FilterAmount.ITEM.ordinal(), "Amount, tag: " + tag);
			h.assertTrue(tag.getBoolean("Exact"), "Exact, tag: " + tag);
			ListTag filter = tag.getList("Filter", Tag.TAG_COMPOUND);
			eq(filter.size(), 2, "Filter entries, tag: " + tag);
			eq((int) filter.getCompound(0).getTagType("Slot"), (int) Tag.TAG_BYTE, "Slot is a byte");
			eq((int) filter.getCompound(0).getByte("Slot"), 4, "Slot of the first entry");
			eq(filter.getCompound(0).getCompound("tag").getInt("Damage"), 25, "the entry keeps its NBT (1.20.1 stack format)");
			eq(filter.getCompound(1).getString("id"), "minecraft:cobblestone", "id of the second entry");
			eq((int) filter.getCompound(1).getByte("Count"), 1, "a ghost entry is saved with count 1 (1.20.1 stack format)");

			EjectorBlockEntity re = reload(h, tag);
			eq(re.getMode(), EjectorMode.PULSE, "mode after reload");
			eq(re.getAmount(), FilterAmount.ITEM, "amount after reload");
			h.assertTrue(re.getFilter().isExact(), "Exact after reload");
			h.assertTrue(re.isPowered(), "stored signal after reload");
			h.assertTrue(ItemStack.isSameItemSameTags(re.getFilter().getStack(4), damagedPickaxe()), "ghost entry 4 after reload: " + re.getFilter().getStack(4));
			h.assertTrue(re.getFilter().getStack(7).is(Items.COBBLESTONE) && re.getFilter().getStack(7).getCount() == 1, "ghost entry 7 after reload");
			h.assertTrue(re.getFilter().getStack(0).isEmpty(), "ghost entry 0 after reload");
		})
		.thenIdle(3 * cd)
		.thenExecute(() -> {
			eq(dropped(h, Items.COBBLESTONE), 1, "reloading while powered must not pulse");
			power(h, false);
		})
		.thenIdle(cd + 2)
		.thenExecute(() -> power(h, true))
		.thenIdle(3)
		.thenExecute(() -> {
			eq(dropped(h, Items.COBBLESTONE), 2, "the reloaded Ejector must pulse on the next rising edge");
			eq(count(h, BACK, Items.COBBLESTONE), 14, "cobblestone left in the source");
		})
		.thenSucceed();
	}

	static void ejectorFilterItemsWorkAsEntries(GameTestHelper h) {
		cell(h);
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.GOLD_INGOT, 3),
				new ItemStack(Items.OAK_PLANKS, 2), new ItemStack(Items.BIRCH_PLANKS, 2), new ItemStack(Items.STONE, 4));
		// An Item Filter (iron ingots) and a Tag Filter (#minecraft:planks) as ghost entries
		ItemStack tagFilter = new ItemStack(Content.tagItemFliter.get());
		ListTag tags = new ListTag();
		tags.add(StringTag.valueOf("minecraft:planks"));
		tagFilter.getOrCreateTag().put("tags", tags);
		tagFilter.getOrCreateTag().putBoolean("allowlist", true);
		e.setFilterStack(0, itemFilter(Items.IRON_INGOT));
		e.setFilterStack(1, tagFilter);
		e.setAmount(FilterAmount.STACK);
		h.assertFalse(e.getFilter().isPlainEntry(0), "a filter item is no plain entry");
		power(h, true);
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(dropped(h, Items.IRON_INGOT), 3, "iron ingots (Item Filter entry)");
			eq(dropped(h, Items.OAK_PLANKS), 2, "oak planks (Tag Filter entry)");
			eq(dropped(h, Items.BIRCH_PLANKS), 2, "birch planks (Tag Filter entry)");
		})
		.thenIdle(30)
		.thenExecute(() -> {
			eq(count(h, BACK, Items.GOLD_INGOT), 3, "gold matches no entry");
			eq(count(h, BACK, Items.STONE), 4, "stone matches no entry");
			eq(dropped(h, Items.GOLD_INGOT) + dropped(h, Items.STONE), 0, "non matching items dropped");
			// The filter items themselves are references: none of them may ever come out
			eq(dropped(h, Content.itemFilter.get()) + dropped(h, Content.tagItemFliter.get()), 0, "a ghost entry was dropped");
			h.assertTrue(e.isIdle(), "nothing matches any more: the Ejector must be idle");
		})
		.thenSucceed();
	}

	static void ejectorWorksFromNetwork(GameTestHelper h) {
		cell(h);
		EjectorBlockEntity e = withNetwork(h, Content.ejector.get(),
				new ItemStack[] {new ItemStack(Items.COBBLESTONE, 3), new ItemStack(Items.DIRT, 4)},
				new ItemStack[] {new ItemStack(Items.COBBLESTONE, 2)});
		e.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		power(h, true);
		// Cobblestone the test itself puts into the network later
		int[] added = new int[1];
		h.onEachTick(() -> eq(count(h, NET_A, Items.COBBLESTONE) + count(h, NET_B, Items.COBBLESTONE) + dropped(h, Items.COBBLESTONE), 5 + added[0], "conservation: network + item entities"));
		h.startSequence()
		.thenWaitUntil(() -> eq(dropped(h, Items.COBBLESTONE), 5, "cobblestone from both chests of the network"))
		.thenIdle(30)
		.thenExecute(() -> {
			eq(count(h, NET_A, Items.DIRT), 4, "dirt does not match and must stay");
			eq(dropped(h, Items.DIRT), 0, "dirt dropped");
			h.assertTrue(e.isIdle(), "nothing left to do: the Ejector must be idle");
			// Items the filter does not match come and go: the Ejector stays idle, a matching one wakes it
			fill(h, NET_B, new ItemStack(Items.DIRT, 7));
		})
		.thenIdle(3 * cooldown())
		.thenExecute(() -> {
			h.assertTrue(e.isIdle(), "dirt arrived, which the filter does not match: the Ejector must stay idle");
			eq(count(h, NET_B, Items.DIRT), 7, "dirt does not match and must stay");
			container(h, NET_B).setItem(5, new ItemStack(Items.COBBLESTONE, 2));
			added[0] = 2;
		})
		.thenWaitUntil(() -> eq(dropped(h, Items.COBBLESTONE), 7, "cobblestone that arrived later"))
		.thenSucceed();
	}

	/** Pulse mode reads its source afresh: an item that arrived a tick or two before the pulse is in its batch. */
	static void ejectorPulseRightAfterNewItemGivesItsBatch(GameTestHelper h) {
		cell(h);
		EjectorBlockEntity e = withChest(h, Content.ejector.get(), new ItemStack(Items.STONE, 3));
		e.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		e.setMode(EjectorMode.PULSE);
		// Cobblestone the test itself puts into the source
		int[] added = new int[1];
		h.onEachTick(() -> eq(count(h, BACK, Items.COBBLESTONE) + dropped(h, Items.COBBLESTONE), added[0], "conservation: source + item entities"));
		GameTestSequence seq = h.startSequence().thenIdle(10);
		// The item arrives 1 tick, then 2 ticks before the rising edge
		for (int i = 1; i <= 2; i++) {
			int gap = i;
			seq = seq.thenExecute(() -> power(h, true))
			.thenIdle(1)
			.thenExecute(() -> {
				// This pulse found nothing: the Ejector has just read its source and waits on what it saw
				h.assertTrue(e.isIdle(), "nothing matches: the Ejector must be idle");
				eq(dropped(h, Items.COBBLESTONE), gap - 1, "nothing to drop for a pulse on a source without matching items");
				power(h, false);
				container(h, BACK).setItem(8 + gap, new ItemStack(Items.COBBLESTONE));
				added[0]++;
			})
			.thenIdle(gap)
			.thenExecute(() -> power(h, true))
			.thenIdle(2)
			.thenExecute(() -> {
				eq(dropped(h, Items.COBBLESTONE), gap, "a pulse " + gap + " tick(s) after a matching item arrived must drop it");
				eq(count(h, BACK, Items.COBBLESTONE), 0, "cobblestone left in the source");
				power(h, false);
			})
			.thenIdle(4);
		}
		seq.thenExecute(() -> {
			eq(count(h, BACK, Items.STONE), 3, "stone does not match and must stay");
			eq(dropped(h, Items.STONE), 0, "stone dropped");
		})
		.thenSucceed();
	}

	// ---- Disposal ----

	static void disposalStartsPausedThenDestroysMatchingOnly(GameTestHelper h) {
		DisposalBlockEntity d = withChest(h, Content.disposal.get(), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 6),
				new ItemStack(Items.DIRT, 5), new ItemStack(Items.STONE, 9), damagedPickaxe());
		d.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		d.setFilterStack(1, new ItemStack(Items.DIRT));
		d.setFilterStack(2, new ItemStack(Items.IRON_PICKAXE));
		h.startSequence()
		.thenIdle(40)
		.thenExecute(() -> {
			h.assertTrue(d.isPaused(), "a newly placed Disposal must be paused");
			eq(d.getStatus(), FilterBlockStatus.PAUSED, "status");
			eq(count(h, BACK, Items.COBBLESTONE), 70, "a paused Disposal must not destroy anything");
			eq(d.getDestroyed().getTotal(), 0L, "total while paused");
			d.setPaused(false);
		})
		.thenWaitUntil(() -> {
			eq(count(h, BACK, Items.COBBLESTONE), 0, "cobblestone left");
			eq(count(h, BACK, Items.DIRT), 0, "dirt left");
			eq(count(h, BACK, Items.IRON_PICKAXE), 0, "pickaxe left (a damaged tool matches a pristine entry)");
		})
		.thenIdle(30)
		.thenExecute(() -> {
			eq(count(h, BACK, Items.STONE), 9, "stone does not match and must stay");
			DestroyedCounter c = d.getDestroyed();
			eq(c.getTotal(), 76L, "total = exactly what disappeared");
			eq(c.get(id(Items.COBBLESTONE)), 70L, "cobblestone destroyed");
			eq(c.get(id(Items.DIRT)), 5L, "dirt destroyed");
			eq(c.get(id(Items.IRON_PICKAXE)), 1L, "pickaxes destroyed");
			eq(c.get(id(Items.STONE)), 0L, "stone destroyed");
			eq(c.top(1).get(0), new DestroyedCounter.Entry(id(Items.COBBLESTONE), 70), "most destroyed item");
			eq(d.getStatus(), FilterBlockStatus.RUNNING, "status");
			h.assertTrue(d.isIdle(), "nothing left to do: the Disposal must be idle");
			eq(lyingAround(h, Items.COBBLESTONE, Items.DIRT, Items.STONE, Items.IRON_PICKAXE), 0, "a Disposal must not drop anything: " + itemsAround(h));
		})
		.thenSucceed();
	}

	static void disposalDestroysAtMostOneStackPerCooldown(GameTestHelper h) {
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
			eq(count(h, BACK, Items.COBBLESTONE) + count(h, BACK, Items.ENDER_PEARL) + (int) now, 224, "counted = disappeared");
		});
		d.setPaused(false);
		h.startSequence()
		.thenWaitUntil(() -> eq(d.getDestroyed().getTotal(), 224L, "everything destroyed"))
		.thenExecute(() -> {
			// 3 stacks of cobblestone, then the ender pearls one stack (16) at a time
			eq(ops.size(), 5, "operations: " + ops);
			for (int i = 1; i < ops.size(); i++) {
				eq(ops.get(i) - ops.get(i - 1), (long) cd, "ticks between two operations (config filterBlockCooldown), at " + ops);
			}
		})
		.thenSucceed();
	}

	static void disposalCountsAndPauseSurviveReload(GameTestHelper h) {
		DisposalBlockEntity d = withChest(h, Content.disposal.get(), new ItemStack(Items.COBBLESTONE, 40), new ItemStack(Items.DIRT, 5));
		d.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		d.setFilterStack(1, new ItemStack(Items.DIRT));
		d.setPaused(false);
		DisposalBlockEntity[] re = new DisposalBlockEntity[1];
		h.startSequence()
		.thenWaitUntil(() -> eq(d.getDestroyed().getTotal(), 45L, "destroyed"))
		.thenExecute(() -> {
			// A count beyond 32 bit, as a long running Disposal would have
			d.getDestroyed().add(id(Items.COBBLESTONE), 5_000_000_000L);
			CompoundTag tag = d.saveWithFullMetadata();
			h.assertFalse(tag.getBoolean("Paused"), "Paused, tag: " + tag);
			eq((int) tag.getTagType("Paused"), (int) Tag.TAG_BYTE, "Paused is a byte, tag: " + tag);
			eq((int) tag.getTagType("Destroyed"), (int) Tag.TAG_LONG, "Destroyed is a long, tag: " + tag);
			eq(tag.getLong("Destroyed"), 5_000_000_045L, "Destroyed, tag: " + tag);
			ListTag items = tag.getList("DestroyedItems", Tag.TAG_COMPOUND);
			eq(items.size(), 2, "DestroyedItems, tag: " + tag);
			eq(items.getCompound(0).getString("id"), "minecraft:cobblestone", "id of the first counted item");
			eq((int) items.getCompound(0).getTagType("n"), (int) Tag.TAG_LONG, "n is a long");
			eq(items.getCompound(0).getLong("n"), 5_000_000_040L, "n of the first counted item");
			re[0] = reload(h, tag);
			DestroyedCounter c = re[0].getDestroyed();
			h.assertFalse(re[0].isPaused(), "a running Disposal stays running after a reload");
			eq(c.getTotal(), 5_000_000_045L, "total after reload");
			eq(c.get(id(Items.COBBLESTONE)), 5_000_000_040L, "cobblestone after reload");
			eq(c.get(id(Items.DIRT)), 5L, "dirt after reload");
			// It keeps working and counting
			fill(h, BACK, new ItemStack(Items.DIRT, 3));
		})
		.thenWaitUntil(() -> eq(re[0].getDestroyed().get(id(Items.DIRT)), 8L, "dirt destroyed after the reload"))
		.thenExecute(() -> {
			re[0].setPaused(true);
			CompoundTag tag = re[0].saveWithFullMetadata();
			h.assertTrue(tag.getBoolean("Paused"), "Paused, tag: " + tag);
			DisposalBlockEntity again = reload(h, tag);
			h.assertTrue(again.isPaused(), "a paused Disposal stays paused after a reload");
			eq(again.getDestroyed().getTotal(), 5_000_000_048L, "total after the second reload");
			// A block entity without any saved data (placed from an item) is paused
			tag.remove("Paused");
			h.assertTrue(FilterBlockChecks.<DisposalBlockEntity>reload(h, tag).isPaused(), "missing pause state must read as paused");
		})
		.thenSucceed();
	}

	static void disposalRedstoneStopsIt(GameTestHelper h) {
		int cd = cooldown();
		DisposalBlockEntity d = withChest(h, Content.disposal.get(), new ItemStack(Items.COBBLESTONE, 10));
		d.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		power(h, true);
		d.setPaused(false);
		h.startSequence()
		.thenIdle(5 * cd)
		.thenExecute(() -> {
			eq(count(h, BACK, Items.COBBLESTONE), 10, "a powered Disposal must not destroy anything");
			eq(d.getDestroyed().getTotal(), 0L, "total while powered");
			eq(d.getStatus(), FilterBlockStatus.STOPPED_BY_REDSTONE, "status");
			power(h, false);
		})
		.thenWaitUntil(() -> eq(count(h, BACK, Items.COBBLESTONE), 0, "cobblestone left after the signal is gone"))
		.thenExecute(() -> eq(d.getDestroyed().getTotal(), 10L, "total"))
		.thenSucceed();
	}

	static void disposalEmptyFilterDestroysNothing(GameTestHelper h) {
		int cd = cooldown();
		DisposalBlockEntity d = withChest(h, Content.disposal.get(), new ItemStack(Items.COBBLESTONE, 10), new ItemStack(Items.DIAMOND, 3), damagedPickaxe());
		d.setPaused(false);
		Container c = container(h, BACK);
		String before = snapshot(c);
		h.startSequence()
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			eq(snapshot(c), before, "a running Disposal with an empty filter must not touch anything");
			eq(d.getDestroyed().getTotal(), 0L, "total");
			eq(d.getStatus(), FilterBlockStatus.NO_FILTER, "status");
			// Exact on changes nothing about that
			d.setExact(true);
			// An entry that was set and cleared again leaves the filter empty
			d.setFilterStack(3, new ItemStack(Items.DIAMOND));
			d.setFilterStack(3, ItemStack.EMPTY);
		})
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			eq(snapshot(c), before, "empty filter, Exact on");
			eq(d.getDestroyed().getTotal(), 0L, "total");
		})
		.thenSucceed();
	}

	static void disposalWorksFromNetworkAndResets(GameTestHelper h) {
		DisposalBlockEntity d = withNetwork(h, Content.disposal.get(),
				new ItemStack[] {new ItemStack(Items.ROTTEN_FLESH, 20), new ItemStack(Items.DIAMOND, 2)},
				new ItemStack[] {new ItemStack(Items.ROTTEN_FLESH, 12)});
		d.setFilterStack(8, new ItemStack(Items.ROTTEN_FLESH));
		d.setPaused(false);
		h.startSequence()
		.thenWaitUntil(() -> eq(d.getDestroyed().getTotal(), 32L, "rotten flesh from both chests of the network"))
		.thenExecute(() -> {
			eq(count(h, NET_A, Items.ROTTEN_FLESH) + count(h, NET_B, Items.ROTTEN_FLESH), 0, "rotten flesh left");
			eq(count(h, NET_A, Items.DIAMOND), 2, "diamonds do not match and must stay");
			d.resetDestroyed();
			eq(d.getDestroyed().getTotal(), 0L, "total after the reset");
			eq(d.getDestroyed().get(id(Items.ROTTEN_FLESH)), 0L, "per item count after the reset");
			h.assertTrue(d.getDestroyed().isEmpty(), "counter after the reset");
			fill(h, NET_B, new ItemStack(Items.ROTTEN_FLESH, 4));
		})
		.thenWaitUntil(() -> eq(d.getDestroyed().getTotal(), 4L, "counting starts again from zero"))
		.thenSucceed();
	}

	// ---- Exporter ----

	static void exporterMovesItemsThenStacksIntoChest(GameTestHelper h) {
		int cd = cooldown();
		chest(h, FRONT);
		ExporterBlockEntity x = withChest(h, Content.exporter.get(), new ItemStack(Items.COBBLESTONE, 40), new ItemStack(Items.COBBLESTONE, 64),
				new ItemStack(Items.DIRT, 7), new ItemStack(Items.COBBLESTONE, 30));
		eq(x.getAmount(), FilterAmount.STACK, "default amount");
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
			eq(count(h, BACK, Items.COBBLESTONE) + now, 134, "conservation: source + target");
		});
		int[] itemOps = new int[1];
		h.startSequence()
		.thenIdle(6 * cd + 2)
		.thenExecute(() -> {
			h.assertTrue(ops.size() >= 6, "too few moves: " + ops);
			for (int s : sizes)eq(s, 1, "Item amount: one item per operation, sizes " + sizes);
			itemOps[0] = ops.size();
			x.setAmount(FilterAmount.STACK);
		})
		.thenWaitUntil(() -> eq(count(h, FRONT, Items.COBBLESTONE), 134, "all cobblestone moved"))
		.thenIdle(3 * cd)
		.thenExecute(() -> {
			for (int i = 1; i < ops.size(); i++) {
				// Changing a setting starts the cooldown anew at most, it never shortens it
				long d = ops.get(i) - ops.get(i - 1);
				h.assertTrue(i == itemOps[0] ? d >= 1 && d <= cd : d == cd, "ticks between two operations must be the cooldown, at " + ops);
			}
			List<Integer> stacks = sizes.subList(itemOps[0], sizes.size());
			for (int s : stacks)h.assertTrue(s >= 1 && s <= 64, "Stack amount: at most 64 items per operation, sizes " + stacks);
			eq(stacks.get(0), 64, "Stack amount: a full stack gathered from several source slots, sizes " + stacks);
			eq(stacks.size(), (134 - itemOps[0] + 63) / 64, "operations for the rest, sizes " + stacks);
			eq(count(h, BACK, Items.DIRT), 7, "dirt does not match and must stay");
			eq(count(h, FRONT, Items.DIRT), 0, "dirt moved");
			h.assertTrue(x.isIdle(), "nothing left to do: the Exporter must be idle");
			eq(x.getStatus(), FilterBlockStatus.RUNNING, "status");
			eq(x.saveWithFullMetadata().getByte("Amount"), (byte) FilterAmount.STACK.ordinal(), "Amount in the saved tag");
		})
		.thenSucceed();
	}

	static void exporterFillsDoubleChestAndBarrel(GameTestHelper h) {
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
		h.onEachTick(() -> eq(count(h, BACK, Items.WOODEN_SWORD) + count(left, Items.WOODEN_SWORD) + count(right, Items.WOODEN_SWORD), 27, "conservation"));
		h.startSequence()
		.thenWaitUntil(() -> eq(count(right, Items.WOODEN_SWORD), 24, "swords in the far half (24 free slots)"))
		.thenIdle(40)
		.thenExecute(() -> {
			eq(count(h, BACK, Items.WOODEN_SWORD), 3, "swords that do not fit stay in the barrel");
			eq(count(left, Items.WOODEN_SWORD), 0, "the near half is full");
			h.assertTrue(x.isIdle(), "target full: the Exporter must be idle");
			eq(lyingAround(h, Items.WOODEN_SWORD, Items.DIRT), 0, "nothing may be dropped: " + itemsAround(h));
		})
		.thenSucceed();
	}

	static void exporterIntoShulkerDock(GameTestHelper h) {
		int cd = cooldown();
		h.setBlock(FRONT, Content.shulkerDock.get());
		ShulkerDockBlockEntity dock = be(h, FRONT);
		ExporterBlockEntity x = withChest(h, Content.exporter.get(), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 20), new ItemStack(Items.DIRT, 5));
		x.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		h.startSequence()
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			eq(dock.getInv().getContainerSize(), 0, "slots of a dock without a box");
			eq(count(h, BACK, Items.COBBLESTONE), 84, "nothing may move into a dock without a box");
			eq(lyingAround(h, Items.COBBLESTONE, Items.DIRT, Items.SHULKER_BOX), 0, "nothing may be dropped: " + itemsAround(h));
			h.assertTrue(dock.dock(new ItemStack(Items.SHULKER_BOX)), "box not docked");
		})
		.thenWaitUntil(() -> eq(count(dock.getInv(), Items.COBBLESTONE), 84, "cobblestone in the docked box"))
		.thenExecute(() -> {
			eq(count(h, BACK, Items.COBBLESTONE), 0, "cobblestone left in the source");
			eq(count(h, BACK, Items.DIRT), 5, "dirt does not match and must stay");
			ItemStack box = dock.undock();
			eq(ShulkerDockChecks.inBox(box, Items.COBBLESTONE), 84, "cobblestone in the box that was taken out");
			fill(h, BACK, new ItemStack(Items.COBBLESTONE, 9));
		})
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			eq(count(h, BACK, Items.COBBLESTONE), 9, "nothing may move after the box was taken out");
			eq(lyingAround(h, Items.COBBLESTONE, Items.DIRT, Items.SHULKER_BOX), 0, "nothing may be dropped: " + itemsAround(h));
		})
		.thenSucceed();
	}

	static void exporterFullTargetLosesNothing(GameTestHelper h) {
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
			eq(count(h, BACK, Items.COBBLESTONE) + count(target, Items.COBBLESTONE), 30 + added[0], "conservation: cobblestone");
			eq(count(h, BACK, Items.STONE) + count(target, Items.STONE), 12, "conservation: stone");
		});
		h.startSequence()
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			eq(count(h, BACK, Items.COBBLESTONE), 30, "a full target: the items stay in the source");
			h.assertTrue(x.isIdle(), "full target: the Exporter must be idle");
			eq(lyingAround(h, Items.COBBLESTONE, Items.STONE, Items.DIRT), 0, "nothing may be dropped: " + itemsAround(h));
			// Room for exactly 5 cobblestone, and nothing for stone
			target.setItem(13, new ItemStack(Items.COBBLESTONE, 59));
			added[0] = 59;
			target.setChanged();
		})
		.thenWaitUntil(() -> eq(target.getItem(13).getCount(), 64, "the partial stack in the target is filled up"))
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			eq(count(h, BACK, Items.COBBLESTONE), 25, "only what fits moves");
			eq(count(h, BACK, Items.STONE), 12, "no room for stone");
			// One free slot: one item type takes it, the other stays where it is
			target.setItem(20, ItemStack.EMPTY);
			target.setChanged();
		})
		.thenWaitUntil(() -> h.assertTrue(!target.getItem(20).isEmpty(), "the free slot is used"))
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			eq(target.getItem(20).getCount(), target.getItem(20).is(Items.COBBLESTONE) ? 25 : 12, "one item type per operation fills the slot");
			h.assertTrue(x.isIdle(), "full target again: the Exporter must be idle");
		})
		.thenSucceed();
	}

	static void exporterRedstoneStopsIt(GameTestHelper h) {
		int cd = cooldown();
		chest(h, FRONT);
		ExporterBlockEntity x = withChest(h, Content.exporter.get(), new ItemStack(Items.COBBLESTONE, 10));
		x.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		power(h, true);
		h.startSequence()
		.thenIdle(5 * cd)
		.thenExecute(() -> {
			eq(count(h, FRONT, Items.COBBLESTONE), 0, "a powered Exporter must not move anything");
			eq(x.getStatus(), FilterBlockStatus.STOPPED_BY_REDSTONE, "status");
			power(h, false);
		})
		.thenWaitUntil(() -> eq(count(h, FRONT, Items.COBBLESTONE), 10, "cobblestone moved after the signal is gone"))
		.thenExecute(() -> {
			// No inventory in front: nothing moves, nothing is lost
			h.setBlock(FRONT, Blocks.AIR);
			fill(h, BACK, new ItemStack(Items.COBBLESTONE, 6));
		})
		.thenIdle(5 * cd)
		.thenExecute(() -> {
			eq(count(h, BACK, Items.COBBLESTONE), 6, "without a target the items stay in the source");
			eq(x.getStatus(), FilterBlockStatus.NO_TARGET, "status without a target");
			// Without a source there is nothing to move either
			h.setBlock(BACK, Blocks.AIR);
		})
		.thenIdle(6)
		.thenExecute(() -> eq(x.getStatus(), FilterBlockStatus.NO_SOURCE, "status without a source"))
		.thenSucceed();
	}

	static void exporterWorksFromNetworkWithFilterItem(GameTestHelper h) {
		chest(h, FRONT);
		ExporterBlockEntity x = withNetwork(h, Content.exporter.get(),
				new ItemStack[] {new ItemStack(Items.IRON_INGOT, 40), new ItemStack(Items.GOLD_INGOT, 8)},
				new ItemStack[] {new ItemStack(Items.IRON_INGOT, 50), new ItemStack(Items.DIAMOND, 3)});
		x.setFilterStack(0, itemFilter(Items.IRON_INGOT, Items.DIAMOND));
		h.onEachTick(() -> eq(count(h, NET_A, Items.IRON_INGOT) + count(h, NET_B, Items.IRON_INGOT) + count(h, FRONT, Items.IRON_INGOT), 90, "conservation: iron"));
		h.startSequence()
		.thenWaitUntil(() -> {
			eq(count(h, FRONT, Items.IRON_INGOT), 90, "iron from both chests of the network");
			eq(count(h, FRONT, Items.DIAMOND), 3, "diamonds");
		})
		.thenIdle(30)
		.thenExecute(() -> {
			eq(count(h, NET_A, Items.GOLD_INGOT), 8, "gold matches no entry and must stay");
			eq(count(h, FRONT, Items.GOLD_INGOT), 0, "gold moved");
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
	static void targetOnSourceNetwork(GameTestHelper h, boolean doubleChest) {
		int cd = cooldown();
		if (doubleChest)doubleChest(h, FRONT);
		else chest(h, FRONT);
		// The Exporter fills the near half, the network is attached to the far half: one inventory
		BlockPos attached = doubleChest ? FRONT.east() : FRONT;
		Container marked = container(h, attached);
		chest(h, NET_A, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 36), new ItemStack(Items.DIRT, 9));
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
		InventoryConnectorBlockEntity c = be(h, CONNECTOR);
		// Cobblestone the test itself puts into the source chest later
		int[] added = new int[1];
		h.onEachTick(() -> {
			int inTarget = count(near, Items.COBBLESTONE) + (marked != near ? count(marked, Items.COBBLESTONE) : 0);
			eq(count(h, NET_A, Items.COBBLESTONE) + inTarget, 110 + added[0], "conservation: source chest + target");
			ItemStack mark = marked.getItem(markSlot);
			h.assertTrue(mark.is(Items.COBBLESTONE) && mark.getCount() == 10 && mark.hasCustomHoverName(), "the Exporter took items out of its own target: slot " + markSlot + " holds " + mark);
		});
		String[] steady = new String[2];
		h.startSequence()
		.thenWaitUntil(() -> eq(c.getInventoryCount(), 3, "inventories on the network (two chests at the connector + the target)"))
		.thenWaitUntil(() -> eq(count(h, NET_A, Items.COBBLESTONE), 0, "cobblestone left in the source chest"))
		.thenIdle(2 * cd)
		.thenExecute(() -> {
			h.assertTrue(x.isIdle(), "everything is in the target: the Exporter must be idle, not keep moving items inside it");
			steady[0] = snapshot(near);
			steady[1] = snapshot(marked);
			// The exclusion itself: the network is the source, exactly its one member that is the target is left out
			TargetExclusion ex = x.exclusion();
			h.assertTrue(ex != null, "the Exporter must have a source and a target");
			h.assertFalse(ex.isAll(), "the source chest is not the target");
			eq(ex.excludedMembers(c.getNetworkStorage()), 1, "source members that are the target");
			int[] slots = x.countSourceSlots();
			h.assertTrue(slots[0] >= 2, "slots of the target the source offers, all excluded: " + slots[0]);
			eq(slots[1], 1, "slots outside the target (the dirt in the source chest)");
		})
		.thenIdle(10 * cd)
		.thenExecute(() -> {
			h.assertTrue(x.isIdle(), "steady state: the Exporter must stay idle");
			eq(snapshot(near), steady[0], "steady state: nothing may move any more");
			eq(snapshot(marked), steady[1], "steady state: nothing may move any more");
			eq(count(h, NET_A, Items.DIRT), 9, "dirt does not match and must stay");
			// New items in the source chest still go to the target
			container(h, NET_A).setItem(5, new ItemStack(Items.COBBLESTONE, 3));
			added[0] = 3;
		})
		.thenWaitUntil(() -> eq(count(h, NET_A, Items.COBBLESTONE), 0, "cobblestone that arrived later"))
		.thenExecute(() -> {
			int inTarget = count(near, Items.COBBLESTONE) + (marked != near ? count(marked, Items.COBBLESTONE) : 0);
			eq(inTarget, 113, "cobblestone in the target");
		})
		.thenSucceed();
	}

	/** Back and front lead to the same network: the source is its own target, there is nothing to move. */
	static void exporterSourceIsItsOwnTarget(GameTestHelper h) {
		int cd = cooldown();
		chest(h, NET_A, new ItemStack(Items.COBBLESTONE, 20));
		chest(h, NET_B, new ItemStack(Items.COBBLESTONE, 5));
		h.setBlock(CONNECTOR, Content.connector.get());
		ExporterBlockEntity x = block(h, Content.exporter.get(), new ItemStack(Items.COBBLESTONE));
		// A cable loop from the back over the block to its front
		List<BlockPos> loop = List.of(BACK, BACK.above(), BLOCK.above(), FRONT.above(), FRONT);
		for (BlockPos p : loop)h.setBlock(p, Content.invCable.get());
		for (BlockPos p : loop)refreshShape(h, p);
		String a = snapshot(container(h, NET_A)), b = snapshot(container(h, NET_B));
		h.onEachTick(() -> {
			eq(snapshot(container(h, NET_A)), a, "nothing may move inside the network");
			eq(snapshot(container(h, NET_B)), b, "nothing may move inside the network");
		});
		InventoryConnectorBlockEntity c = be(h, CONNECTOR);
		h.startSequence()
		.thenWaitUntil(() -> eq(c.getInventoryCount(), 2, "inventories on the network"))
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			TargetExclusion ex = x.exclusion();
			h.assertTrue(ex != null && ex.isAll(), "a source that is its own target has nothing to move");
			eq(x.countSourceSlots()[1], 0, "slots outside the target");
			h.assertTrue(x.isIdle(), "nothing to do: the Exporter must be idle");
			eq(lyingAround(h, Items.COBBLESTONE), 0, "nothing may be dropped: " + itemsAround(h));
		})
		.thenSucceed();
	}

	// ---- Idle checks and the search limit ----

	/**
	 * An idle block that may run notices a matching item in its source within one cooldown plus the 4
	 * ticks the item totals of its idle check may be old, at whatever point of the cooldown it arrives.
	 */
	static void idleBlockWakesWithinCooldown(GameTestHelper h, boolean disposal) {
		int cd = cooldown();
		AbstractFilterBlockEntity b;
		IntSupplier done;
		if (disposal) {
			DisposalBlockEntity d = withChest(h, Content.disposal.get(), new ItemStack(Items.STONE, 3));
			d.setPaused(false);
			done = () -> (int) d.getDestroyed().getTotal();
			b = d;
		} else {
			chest(h, FRONT);
			b = withChest(h, Content.exporter.get(), new ItemStack(Items.STONE, 3));
			done = () -> count(h, FRONT, Items.COBBLESTONE);
		}
		b.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		// Cobblestone the test itself puts into the source
		int[] added = new int[1];
		h.onEachTick(() -> eq(count(h, BACK, Items.COBBLESTONE) + done.getAsInt(), added[0], "conservation: source + worked off"));
		GameTestSequence seq = h.startSequence().thenIdle(2 * cd + 2);
		for (int i = 1; i <= 4; i++) {
			int round = i;
			seq = seq.thenExecute(() -> {
				h.assertTrue(b.isIdle(), "nothing matches: the block must be idle (round " + round + ")");
				eq(b.getStatus(), FilterBlockStatus.RUNNING, "an idle block that may run");
				container(h, BACK).setItem(10, new ItemStack(Items.COBBLESTONE, 3));
				added[0] += 3;
			})
			.thenIdle(cd + 4)
			.thenExecute(() -> eq(done.getAsInt(), 3 * round, "matching items worked off within the cooldown + 4 ticks (round " + round + ")"))
			// Idle again, and the next item arrives at another point of the cooldown
			.thenIdle(2 * cd + 1);
		}
		seq.thenExecute(() -> {
			eq(count(h, BACK, Items.STONE), 3, "stone does not match and must stay");
			eq(lyingAround(h, Items.COBBLESTONE, Items.STONE), 0, "items dropped: " + itemsAround(h));
		})
		.thenSucceed();
	}

	/** Two idle Exporters on one network share its item totals: a matching item still wakes both in time. */
	static void idleExportersOnOneNetworkBothWake(GameTestHelper h) {
		int cd = cooldown();
		// The second Exporter sits on top of the connector, its back to it, with a chest of its own above
		BlockPos second = CONNECTOR.above(), secondTarget = second.above();
		chest(h, FRONT);
		chest(h, secondTarget);
		ExporterBlockEntity x1 = withNetwork(h, Content.exporter.get(), new ItemStack[] {new ItemStack(Items.DIRT, 4)}, new ItemStack[0]);
		x1.setFilterStack(0, new ItemStack(Items.COBBLESTONE));
		ExporterBlockEntity[] x2 = new ExporterBlockEntity[1];
		// Rounds of 5 cobblestone and 6 stone the test itself puts into the network
		int[] rounds = new int[1];
		h.onEachTick(() -> {
			eq(count(h, NET_A, Items.COBBLESTONE) + count(h, NET_B, Items.COBBLESTONE) + count(h, FRONT, Items.COBBLESTONE), 5 * rounds[0], "conservation: cobblestone");
			eq(count(h, NET_A, Items.STONE) + count(h, NET_B, Items.STONE) + count(h, secondTarget, Items.STONE), 6 * rounds[0], "conservation: stone");
		});
		GameTestSequence seq = h.startSequence()
		// Placed a few ticks after the first: their idle checks come at different points of the cooldown
		.thenIdle(3)
		.thenExecute(() -> {
			h.setBlock(second, facing(Content.exporter.get(), Direction.UP));
			x2[0] = be(h, second);
			x2[0].setFilterStack(0, new ItemStack(Items.STONE));
		})
		.thenIdle(3 * cd);
		for (int i = 1; i <= 4; i++) {
			int round = i;
			seq = seq.thenExecute(() -> {
				h.assertTrue(x1.isIdle() && x2[0].isIdle(), "nothing matches: both Exporters must be idle (round " + round + ")");
				Container c = container(h, NET_B);
				c.setItem(3, new ItemStack(Items.COBBLESTONE, 5));
				c.setItem(4, new ItemStack(Items.STONE, 6));
				rounds[0] = round;
			})
			.thenIdle(cd + 4)
			.thenExecute(() -> {
				eq(count(h, FRONT, Items.COBBLESTONE), 5 * round, "cobblestone moved by the first Exporter within the cooldown + 4 ticks (round " + round + ")");
				eq(count(h, secondTarget, Items.STONE), 6 * round, "stone moved by the second Exporter within the cooldown + 4 ticks (round " + round + ")");
			})
			// Idle again, and the next items arrive at another point of the cooldown
			.thenIdle(2 * cd + 1);
		}
		seq.thenExecute(() -> {
			eq(count(h, NET_A, Items.DIRT), 4, "dirt matches no filter and must stay");
			eq(count(h, FRONT, Items.DIRT) + count(h, secondTarget, Items.DIRT), 0, "dirt moved");
			eq(count(h, FRONT, Items.STONE) + count(h, secondTarget, Items.COBBLESTONE), 0, "an item in the target of the other Exporter");
		})
		.thenSucceed();
	}

	// The source of the search limit test: barrels around the connector and chained to each other, 11 x 27 = 297 slots
	private static final List<BlockPos> BARRELS = List.of(NET_A, NET_B, CONNECTOR.above(), CONNECTOR.below(), NET_A.above(), NET_B.above(),
			NET_A.below(), NET_B.below(), NET_A.north(), NET_B.south(), CONNECTOR.above(2));
	private static final int BARREL_SLOTS = 27;

	private static void put(List<Container> order, int slot, ItemStack stack) {
		order.get(slot / BARREL_SLOTS).setItem(slot % BARREL_SLOTS, stack);
	}

	/**
	 * A matching item spread thinly over more stocked slots than one operation looks at after its
	 * first hit ({@link FilterBlockLogic#MAX_SLOT_VISITS}): the operation takes what lies within the
	 * limit, the next one the rest. Nothing is lost and nothing that does not match moves.
	 *
	 * @param disposal a Disposal (the batch search), else an Exporter (the slot to slot move)
	 */
	static void slotWalkStopsAfterTheVisitLimit(GameTestHelper h, boolean disposal) {
		int cd = cooldown();
		int slots = BARRELS.size() * BARREL_SLOTS, limit = FilterBlockLogic.MAX_SLOT_VISITS;
		h.assertTrue(limit > 100 && limit + 1 < slots - 1, "this test needs more slots than the limit: " + limit + " of " + slots);
		for (BlockPos p : BARRELS)h.setBlock(p, Blocks.BARREL);
		h.setBlock(CONNECTOR, Content.connector.get());
		AbstractFilterBlockEntity b;
		IntSupplier done;
		if (disposal) {
			// Paused, as placed, until the source is filled
			DisposalBlockEntity d = block(h, Content.disposal.get(), new ItemStack(Items.COBBLESTONE));
			done = () -> (int) d.getDestroyed().getTotal();
			b = d;
		} else {
			chest(h, FRONT);
			b = block(h, Content.exporter.get(), new ItemStack(Items.COBBLESTONE));
			// Stopped by redstone until the source is filled
			power(h, true);
			done = () -> count(h, FRONT, Items.COBBLESTONE);
		}
		place(h, BACK, Content.invCable.get());
		InventoryConnectorBlockEntity c = be(h, CONNECTOR);
		// What the test itself puts into the source
		int[] cobblestone = new int[1], dirt = new int[1];
		List<Long> ops = new ArrayList<>();
		List<Integer> sizes = new ArrayList<>();
		int[] last = {0};
		h.onEachTick(() -> {
			int now = done.getAsInt();
			if (now != last[0]) {
				ops.add(h.getTick());
				sizes.add(now - last[0]);
				last[0] = now;
			}
			int inSource = 0, dirtInSource = 0;
			for (BlockPos p : BARRELS) {
				inSource += count(h, p, Items.COBBLESTONE);
				dirtInSource += count(h, p, Items.DIRT);
			}
			eq(inSource + now, cobblestone[0], "conservation: cobblestone in the source + worked off");
			eq(dirtInSource, dirt[0], "dirt does not match and must stay in the source");
		});
		h.startSequence()
		.thenWaitUntil(() -> eq(c.getInventoryCount(), BARRELS.size(), "inventories on the network"))
		.thenExecute(() -> {
			// The barrels in the order the network lists them, which is the order a search walks them in
			List<Container> order = new ArrayList<>();
			for (Object part : c.getNetworkStorage().getStorages()) {
				BlockPos p = c.getNetworkStorage().positionOf(part);
				h.assertTrue(p != null && h.getLevel().getBlockEntity(p) instanceof Container, "a member of the network that is no barrel, at " + p);
				order.add((Container) h.getLevel().getBlockEntity(p));
			}
			eq(order.size(), BARRELS.size(), "members of the network");
			// Every slot is stocked, the matching item lies thin
			for (int i = 0; i < slots; i++)put(order, i, new ItemStack(Items.DIRT));
			put(order, 0, new ItemStack(Items.COBBLESTONE, 1));
			put(order, 100, new ItemStack(Items.COBBLESTONE, 2));
			// The last slot the first operation still looks at, and the first one it does not
			put(order, limit, new ItemStack(Items.COBBLESTONE, 3));
			put(order, limit + 1, new ItemStack(Items.COBBLESTONE, 5));
			put(order, slots - 1, new ItemStack(Items.COBBLESTONE, 7));
			cobblestone[0] = 18;
			dirt[0] = slots - 5;
			if (disposal)((DisposalBlockEntity) b).setPaused(false);
			else power(h, false);
		})
		.thenWaitUntil(() -> eq(done.getAsInt(), 18, "all cobblestone worked off"))
		.thenIdle(3 * cd)
		.thenExecute(() -> {
			eq(sizes, List.of(6, 12), "the first operation takes what lies within " + limit + " slots after its first hit, the second the rest");
			eq(ops.get(1) - ops.get(0), (long) cd, "the rest goes with the next operation, one cooldown later, at " + ops);
			if (!disposal)eq(count(h, FRONT, Items.DIRT), 0, "dirt moved");
			h.assertTrue(b.isIdle(), "nothing left to do: the block must be idle");
			eq(lyingAround(h, Items.COBBLESTONE, Items.DIRT), 0, "items dropped: " + itemsAround(h));
		})
		.thenSucceed();
	}

	/** A Polymorphic Filter entry answers by what the inventory in front holds: nothing is remembered, the block follows it. */
	static void exporterPolymorphicFilterEntryFollowsTheTarget(GameTestHelper h) {
		int cd = cooldown();
		chest(h, FRONT, new ItemStack(Items.COBBLESTONE));
		ExporterBlockEntity x = withChest(h, Content.exporter.get(), new ItemStack(Items.COBBLESTONE, 20), new ItemStack(Items.DIRT, 5), new ItemStack(Items.STONE, 4));
		x.setFilterStack(0, new ItemStack(Content.polyItemFliter.get()));
		h.assertFalse(x.getFilter().isPlainEntry(0), "a filter item is no plain entry");
		// Dirt the test itself puts into the target later
		int[] added = new int[1];
		h.onEachTick(() -> {
			eq(count(h, BACK, Items.COBBLESTONE) + count(h, FRONT, Items.COBBLESTONE), 21, "conservation: cobblestone");
			eq(count(h, BACK, Items.DIRT) + count(h, FRONT, Items.DIRT), 5 + added[0], "conservation: dirt");
			eq(count(h, BACK, Items.STONE), 4, "stone is not in the target, it does not match and must stay");
		});
		h.startSequence()
		.thenWaitUntil(() -> eq(count(h, FRONT, Items.COBBLESTONE), 21, "cobblestone, which the target already holds"))
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			eq(count(h, BACK, Items.DIRT), 5, "dirt is not in the target, it does not match and must stay");
			eq(count(h, FRONT, Items.DIRT) + count(h, FRONT, Items.STONE), 0, "non matching items moved");
			h.assertFalse(x.getFilter().isStable(), "a Polymorphic Filter entry does not always answer the same");
			eq(x.getStatus(), FilterBlockStatus.RUNNING, "status with nothing left to move");
			// The filter's answer changes without an edit and without a change in the source
			container(h, FRONT).setItem(20, new ItemStack(Items.DIRT));
			added[0] = 1;
		})
		.thenWaitUntil(() -> eq(count(h, FRONT, Items.DIRT), 6, "dirt, once the target holds some"))
		.thenIdle(6 * cd)
		.thenExecute(() -> {
			eq(count(h, BACK, Items.COBBLESTONE) + count(h, BACK, Items.DIRT), 0, "matching items left in the source");
			eq(count(h, FRONT, Items.STONE), 0, "stone moved");
			eq(lyingAround(h, Items.COBBLESTONE, Items.DIRT, Items.STONE, Content.polyItemFliter.get()), 0, "items dropped: " + itemsAround(h));
		})
		.thenSucceed();
	}

	// ---- Network ----

	static void cablesConnectToTheBackOnly(GameTestHelper h) {
		for (Block b : List.of(Content.ejector.get(), Content.disposal.get(), Content.exporter.get())) {
			h.setBlock(BLOCK, facing(b, Direction.EAST));
			for (Direction d : Direction.values())place(h, BLOCK.relative(d), Content.invCable.get());
			for (Direction d : Direction.values()) {
				BlockState cable = h.getBlockState(BLOCK.relative(d));
				boolean arm = cable.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d.getOpposite()));
				eq(arm, d == Direction.WEST, b + " facing east: cable arm from the " + d + " side");
				eq(IInventoryCable.canConnect(h.getBlockState(BLOCK), d.getOpposite()), d == Direction.WEST, b + ": canConnect from " + d);
			}
			// The cable search of the network must not walk through the block
			BlockState state = h.getBlockState(BLOCK);
			h.assertTrue(((IInventoryCable) b).next(h.getLevel(), state, h.absolutePos(BLOCK)).isEmpty(), b + " must be a leaf of the cable graph");
			// Rotated in place: the arms follow the new back
			eq(state.rotate(Rotation.CLOCKWISE_90).getValue(AbstractFilterBlock.FACING), Direction.SOUTH, "rotate");
			eq(state.mirror(Mirror.FRONT_BACK).getValue(AbstractFilterBlock.FACING), Direction.WEST, "mirror");
			eq(state.getProperties().size(), 1, "FACING is the only block state property");
			h.setBlock(BLOCK, facing(b, Direction.UP));
			for (Direction d : Direction.values()) {
				BlockState cable = h.getBlockState(BLOCK.relative(d));
				boolean arm = cable.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d.getOpposite()));
				eq(arm, d == Direction.DOWN, b + " facing up: cable arm from the " + d + " side");
			}
			for (Direction d : Direction.values())h.setBlock(BLOCK.relative(d), Blocks.AIR);
		}
		h.succeed();
	}

	static void neverAnInventoryOfAConnector(GameTestHelper h) {
		// A connector with one chest and all three blocks touching it, their backs to the connector
		BlockPos connector = new BlockPos(3, 3, 3);
		chest(h, connector.west(), new ItemStack(Items.COBBLESTONE, 5));
		h.setBlock(connector, Content.connector.get());
		h.setBlock(connector.east(), facing(Content.ejector.get(), Direction.EAST));
		h.setBlock(connector.above(), facing(Content.disposal.get(), Direction.UP));
		h.setBlock(connector.south(), facing(Content.exporter.get(), Direction.SOUTH));
		List<BlockPos> blocks = List.of(connector.east(), connector.above(), connector.south());
		InventoryConnectorBlockEntity c = be(h, connector);
		MinecraftServer server = h.getLevel().getServer();
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(c.getDoctorStats() != null, "connector has not ticked yet");
			eq(c.getInventoryCount(), 1, "inventories of the connector (the chest only)");
		})
		.thenIdle(40)
		.thenExecute(() -> {
			eq(c.getInventoryCount(), 1, "inventories of the connector (the chest only)");
			eq(c.getSlotCount(), 27, "slots of the network");
			for (BlockPos rel : blocks) {
				BlockPos abs = h.absolutePos(rel);
				BlockState state = h.getLevel().getBlockState(abs);
				h.assertFalse(c.getConnectedBlocks().contains(abs), state.getBlock() + " is listed as an inventory of the connector");
				for (Direction d : Direction.values()) {
					h.assertFalse(com.obscura.storage.platform.Platform.hasItemInventory(h.getLevel(), abs, state, d), state.getBlock() + " exposes an inventory on its " + d + " side");
					h.assertFalse(com.obscura.storage.platform.Platform.hasItemInventoryAt(h.getLevel(), abs, d), state.getBlock() + " exposes an inventory on its " + d + " side");
				}
				DoctorScan.BlockCheck check = DoctorScan.checkBlock(state.getBlock(), h.getLevel(), abs);
				eq(check.verdict(), DoctorScan.Verdict.NETWORK_BLOCK, "Network Doctor verdict for " + state.getBlock());
				h.assertTrue(state.is(StorageTags.INV_CONFIG_SKIP), state.getBlock() + " must be skipped by the Inventory Configurator");
				h.assertTrue(DoctorScan.find(h.getLevel(), abs) == c, "the block must lead to the connector of the network it works on");
			}
			// "Highlight Connected Inventories" lists the chest and none of the three
			Set<BlockPos> located = new HashSet<>();
			for (ItemLocator.Location l : ItemLocator.searchAll(c).located())located.add(l.pos());
			eq(located, Set.of(h.absolutePos(connector.west())), "inventories highlighted on the network");
			// The Network Doctor counts the three together
			NetworkReport.Details d = DoctorScan.full(c, server).details();
			eq(d.filterBlocks(), 3, "filter blocks attached to the network");
			eq(d.hoppers(), 0, "hoppers");
			eq(d.emitters(), 0, "level emitters");
			eq(DoctorScan.attached(c.getDoctorStats(), server)[4], 3, "DoctorScan.attached, filter blocks");
			// Turned away from the connector the Ejector is no longer on its network
			h.setBlock(connector.east(), facing(Content.ejector.get(), Direction.WEST));
		})
		.thenIdle(5)
		.thenExecute(() -> eq(DoctorScan.full(c, server).details().filterBlocks(), 2, "filter blocks after one was turned away"))
		.thenSucceed();
	}

	// ---- Placement and menus ----

	private static ServerPlayer player(GameTestHelper h, Platform pf) {
		ServerPlayer p = pf.fakePlayer(h);
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

	static void placementFollowsNetworkBlocksInventoriesAndThePlayer(GameTestHelper h, Platform pf) {
		// The player stands south of the blocks and looks north: a dropper placed by it would face south
		ServerPlayer p = player(h, pf);
		BlockPos base = new BlockPos(3, 2, 3), pos = base.above();
		for (Block b : List.of(Content.ejector.get(), Content.disposal.get(), Content.exporter.get())) {
			// 1. Against a network block: the back faces it, the front points away. Sneaking changes nothing.
			h.setBlock(base, Content.invCable.get());
			eq(placeAgainst(h, p, b, base, Direction.UP, false), Direction.UP, b + " on a cable");
			eq(placeAgainst(h, p, b, base, Direction.UP, true), Direction.UP, b + " on a cable, sneaking");
			eq(placeAgainst(h, p, b, base, Direction.EAST, false), Direction.EAST, b + " next to a cable");
			clearAround(h, base);
			// A connector answers a right click itself, so a block is placed against it while sneaking
			h.setBlock(base, Content.connector.get());
			eq(placeAgainst(h, p, b, base, Direction.NORTH, true), Direction.NORTH, b + " next to a connector");
			clearAround(h, base);
			h.setBlock(base, Content.invInterface.get());
			eq(placeAgainst(h, p, b, base, Direction.WEST, false), Direction.WEST, b + " next to an interface");
			clearAround(h, base);

			// 2. Against any other block with a block entity: the front points at it
			h.setBlock(base, Blocks.CHEST);
			eq(placeAgainst(h, p, b, base, Direction.SOUTH, true), Direction.NORTH, b + " next to a chest");
			eq(placeAgainst(h, p, b, base, Direction.UP, true), Direction.DOWN, b + " on a chest");
			clearAround(h, base);
			h.setBlock(base, Content.shulkerDock.get());
			eq(placeAgainst(h, p, b, base, Direction.WEST, true), Direction.EAST, b + " next to a Shulker Dock");
			clearAround(h, base);

			// 4. Against a plain block with no network block around: like a dropper, the front faces the player
			h.setBlock(base, Blocks.STONE);
			eq(placeAgainst(h, p, b, base, Direction.UP, false), Direction.SOUTH, b + " on stone");
			eq(placeAgainst(h, p, b, base, Direction.WEST, false), Direction.SOUTH, b + " next to stone");
			eq(placeAgainst(h, p, b, base, Direction.WEST, true), Direction.SOUTH, b + " next to stone, sneaking");
			p.setXRot(90);
			eq(placeAgainst(h, p, b, base, Direction.UP, false), Direction.UP, b + " on stone, looking down");
			p.setXRot(0);
			clearAround(h, base);

			// 3. Against a plain block, a network block next to the new block: the back faces it
			h.setBlock(pos.east(), Content.invCable.get());
			eq(placeAgainst(h, p, b, base, Direction.UP, false), Direction.WEST, b + " on stone, cable to the east");
			// Several: the first in the order down, up, north, south, west, east
			h.setBlock(pos.west(), Content.invCable.get());
			eq(placeAgainst(h, p, b, base, Direction.UP, false), Direction.EAST, b + " on stone, cables to the west and east");
			// A clicked inventory still comes first
			h.setBlock(base, Blocks.BARREL);
			eq(placeAgainst(h, p, b, base, Direction.UP, true), Direction.DOWN, b + " on a barrel, cables to the west and east");
			h.setBlock(base, Blocks.STONE);
			// ...and the network block opposite the clicked block wins over the fixed order
			h.setBlock(pos.above(), Content.invCable.get());
			eq(placeAgainst(h, p, b, base, Direction.UP, false), Direction.DOWN, b + " on stone, cables above, to the west and east");
			clearAround(h, base);
		}
		// A Disposal placed by hand is paused
		placeAgainst(h, p, Content.disposal.get(), base, Direction.UP, false);
		h.assertTrue(GameTestUtil.<DisposalBlockEntity>be(h, pos).isPaused(), "a Disposal placed by hand must be paused");
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

	static void menusValidateClientMessages(GameTestHelper h, Platform pf) {
		ServerPlayer p = player(h, pf);
		EjectorBlockEntity e = block(h, Content.ejector.get());
		EjectorMenu m = new EjectorMenu(1, p.getInventory(), e);
		h.assertTrue(m.stillValid(p), "the player stands next to the block");
		eq(m.slots.size(), GhostFilter.SLOTS + 36, "slots: 9 ghost slots + the player inventory");
		eq(m.slots.get(0).x + "," + m.slots.get(0).y + " " + m.slots.get(8).x + "," + m.slots.get(8).y, "62,17 98,53", "ghost slot grid of the Ejector");
		eq(m.slots.get(9).x + "," + m.slots.get(9).y + " " + m.slots.get(36).x + "," + m.slots.get(36).y, "8,84 8,142", "player inventory and hotbar");

		// Settings
		m.receive(msg(EjectorMenu.MSG_MODE, EjectorMode.PULSE.ordinal()));
		eq(e.getMode(), EjectorMode.PULSE, "mode message");
		m.receive(msg(EjectorMenu.MSG_MODE, 7));
		m.receive(msg(EjectorMenu.MSG_MODE, -1));
		eq(e.getMode(), EjectorMode.PULSE, "a mode out of range must be ignored");
		m.receive(msg(EjectorMenu.MSG_AMOUNT, FilterAmount.STACK.ordinal()));
		eq(e.getAmount(), FilterAmount.STACK, "amount message");
		m.receive(msg(EjectorMenu.MSG_AMOUNT, 2));
		eq(e.getAmount(), FilterAmount.STACK, "an amount out of range must be ignored");
		m.receive(flag(AbstractFilterBlockMenu.MSG_EXACT, true));
		h.assertTrue(e.getFilter().isExact(), "exact message");
		m.receive(new CompoundTag());
		h.assertTrue(e.getFilter().isExact() && e.getMode() == EjectorMode.PULSE && e.getAmount() == FilterAmount.STACK, "an empty message must change nothing");

		// Ghost slots: a click leaves a reference of 1 and takes nothing from the cursor
		m.setCarried(new ItemStack(Items.DIAMOND, 5));
		m.clicked(0, 0, ClickType.PICKUP, p);
		h.assertTrue(e.getFilter().getStack(0).is(Items.DIAMOND) && e.getFilter().getStack(0).getCount() == 1, "ghost slot after a click: " + e.getFilter().getStack(0));
		eq(m.getCarried().getCount(), 5, "the cursor stack must not be used up");
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
		eq(p.getInventory().getItem(9).getCount(), 12, "the inventory stack must not be used up");
		// The recipe viewer drag (setPhantom)
		CompoundTag phantom = new CompoundTag(), inner = new CompoundTag();
		inner.putInt("id", 5);
		inner.put("item", new ItemStack(Items.GOLD_INGOT, 40).save(new CompoundTag()));
		phantom.put("setPhantom", inner);
		m.receive(phantom);
		h.assertTrue(e.getFilter().getStack(5).is(Items.GOLD_INGOT) && e.getFilter().getStack(5).getCount() == 1, "ghost slot after a recipe viewer drag: " + e.getFilter().getStack(5));
		// ...which must not reach a slot of the player inventory
		inner.putInt("id", GhostFilter.SLOTS);
		m.receive(phantom.copy());
		eq(p.getInventory().getItem(9).getCount(), 12, "a recipe viewer drag onto an inventory slot must be ignored");
		h.assertTrue(p.getInventory().getItem(9).is(Items.EMERALD), "a recipe viewer drag onto an inventory slot must be ignored");
		inner.putInt("id", 5);
		// An empty hand clears the entry
		m.clicked(0, 0, ClickType.PICKUP, p);
		h.assertTrue(e.getFilter().getStack(0).isEmpty(), "ghost slot after a click with an empty cursor");

		// A spectator changes nothing
		p.setGameMode(GameType.SPECTATOR);
		m.receive(msg(EjectorMenu.MSG_MODE, EjectorMode.CONTINUOUS.ordinal()));
		m.receive(flag(AbstractFilterBlockMenu.MSG_EXACT, false));
		e.setFilterStack(5, ItemStack.EMPTY);
		m.receive(phantom.copy());
		h.assertTrue(e.getMode() == EjectorMode.PULSE && e.getFilter().isExact() && e.getFilter().getStack(5).isEmpty(), "messages of a spectator must be ignored");
		p.setGameMode(GameType.SURVIVAL);
		// Neither does a player who is too far away
		BlockPos a = h.absolutePos(BLOCK);
		p.moveTo(a.getX() + 30.5, a.getY(), a.getZ() + 0.5, 0, 0);
		h.assertFalse(m.stillValid(p), "30 blocks away");
		m.receive(msg(EjectorMenu.MSG_MODE, EjectorMode.CONTINUOUS.ordinal()));
		m.receive(phantom.copy());
		h.assertTrue(e.getMode() == EjectorMode.PULSE && e.getFilter().getStack(5).isEmpty(), "messages from too far away must be ignored");
		p.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 2.5, 180, 0);
		m.receive(msg(EjectorMenu.MSG_MODE, EjectorMode.CONTINUOUS.ordinal()));
		eq(e.getMode(), EjectorMode.CONTINUOUS, "back in reach");
		// A client side menu (no block entity) ignores everything
		new EjectorMenu(2, p.getInventory()).receive(msg(EjectorMenu.MSG_MODE, 1));

		// Exporter
		ExporterBlockEntity x = block(h, Content.exporter.get());
		ExporterMenu xm = new ExporterMenu(3, p.getInventory(), x);
		xm.receive(msg(ExporterMenu.MSG_AMOUNT, FilterAmount.ITEM.ordinal()));
		eq(x.getAmount(), FilterAmount.ITEM, "exporter amount message");
		xm.receive(msg(ExporterMenu.MSG_AMOUNT, 99));
		eq(x.getAmount(), FilterAmount.ITEM, "an amount out of range must be ignored");
		// A block entity that is gone (replaced by the Disposal below) takes no messages any more
		DisposalBlockEntity d = block(h, Content.disposal.get());
		h.assertFalse(xm.stillValid(p), "the Exporter was replaced");
		xm.receive(msg(ExporterMenu.MSG_AMOUNT, FilterAmount.STACK.ordinal()));
		eq(x.getAmount(), FilterAmount.ITEM, "a message for a removed block must be ignored");

		// Disposal: pause and reset
		DisposalMenu dm = new DisposalMenu(4, p.getInventory(), d);
		eq(dm.slots.get(0).x + "," + dm.slots.get(0).y + " " + dm.slots.get(8).x + "," + dm.slots.get(8).y, "8,17 44,53", "ghost slot grid of the Disposal");
		d.getDestroyed().add(id(Items.DIRT), 12);
		dm.receive(flag(DisposalMenu.MSG_PAUSED, false));
		h.assertFalse(d.isPaused(), "pause message (start)");
		eq(d.getDestroyed().getTotal(), 12L, "starting must not reset the counter");
		dm.receive(flag(DisposalMenu.MSG_RESET, false));
		eq(d.getDestroyed().getTotal(), 12L, "a reset message without the flag must not reset");
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

	// ---- Registration and data ----

	static void blocksRecipesTagsAndLoot(GameTestHelper h) {
		MinecraftServer server = h.getLevel().getServer();
		for (Block b : List.of(Content.exporter.get(), Content.ejector.get(), Content.disposal.get())) {
			String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
			ResourceLocation id = new ResourceLocation("obscuras_storage", path);
			BlockState state = b.defaultBlockState();
			eq(b.asItem().getDescriptionId(), "block.obscuras_storage." + path, "description id");
			h.assertTrue(BuiltInRegistries.ITEM.getKey(b.asItem()).equals(id), "block item of " + path);
			h.assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id) != null && BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id).isValid(state), "block entity type of " + path);
			h.assertTrue(BuiltInRegistries.MENU.get(id) != null, "menu type of " + path);
			// Full opaque wooden cubes
			eq(state.getMapColor(h.getLevel(), BlockPos.ZERO), MapColor.WOOD, path + " map colour");
			eq(state.getSoundType(), SoundType.WOOD, path + " sound");
			eq(b.defaultDestroyTime(), 3.0F, path + " strength");
			h.assertTrue(state.canOcclude() && state.isSolidRender(h.getLevel(), BlockPos.ZERO), path + " must be a full opaque cube");
			h.assertTrue(state.is(BlockTags.MINEABLE_WITH_AXE), path + " is not mineable with an axe");
			h.assertTrue(state.is(StorageTags.INV_CONFIG_SKIP), path + " must be skipped by the Inventory Configurator");
			h.assertFalse(state.is(StorageTags.INV_CONNECTOR_SKIP) || b instanceof ITrim, path + " must be neither a trim nor skipped by the connector");
			// Recipe, the advancement that unlocks it and the loot table
			Recipe<?> r = server.getRecipeManager().byKey(id).orElse(null);
			h.assertTrue(r != null, "recipe " + id + " not loaded");
			h.assertTrue(r.getResultItem(h.getLevel().registryAccess()).is(b.asItem()), "recipe result of " + path);
			h.assertTrue(r.getIngredients().stream().anyMatch(i -> i.test(new ItemStack(Content.invCable.get()))), path + ": cable ingredient");
			h.assertTrue(server.getAdvancements().getAdvancement(new ResourceLocation("obscuras_storage", "recipes/redstone/" + path)) != null, "recipe advancement of " + path);
			eq(b.getLootTable(), new ResourceLocation("obscuras_storage", "blocks/" + path), "loot table id");
			h.assertTrue(server.getLootData().getLootTable(b.getLootTable()) != LootTable.EMPTY, "loot table of " + path);
		}
		h.succeed();
	}
}
