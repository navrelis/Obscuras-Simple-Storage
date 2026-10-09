package com.obscura.storage.tidy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import com.obscura.storage.StorageTags;
import com.obscura.storage.tile.FilingCabinetBlockEntity;
import com.obscura.storage.tile.InventoryInterfaceBlockEntity;
import com.obscura.storage.tile.InventoryProxyBlockEntity;
import com.obscura.storage.tile.OpenCrateBlockEntity;
import com.obscura.storage.util.ConditionFilter;
import com.obscura.storage.util.FrameLabel;
import com.obscura.storage.util.ITidyExempt;
import com.obscura.storage.util.ItemPredicate;
import com.obscura.storage.util.PolyFilter;
import com.obscura.storage.util.Priority;
import com.obscura.storage.util.SimpleItemFilter;
import com.obscura.storage.util.TagFilter;

/**
 * One network member as Tidy sees it: the member as the last network scan listed it (block filters and
 * Frame Label on top of the block's own inventory) and the rules read from those wrappers.
 *
 * <p>Only plain inventories take part: the wrapper chain may hold block filters and a Frame Label and must
 * end in the block's own inventory. Proxies, interfaces and anything leading into a network are left
 * out, and so are the Filing Cabinet, the Open Crate, docked shulker boxes (unless
 * {@code tidyIncludeDocks}), machines (sided inventories other than shulker boxes) and blocks in the
 * {@code obscuras_storage:tidy_skip} tag.
 *
 * <p>The member remembers the block entities its inventory belongs to ({@link #isLive}). The network's
 * member list is only rebuilt every few ticks, so the stored inventory of a chest that was broken (or
 * replaced) a moment ago is still listed: moving items into it would void them, out of it would
 * duplicate them. Every move checks this first.
 */
public final class TidyMember {
	/** What {@link TidyIO#unwrap} found in the wrapper chain of a member. */
	public static final class Chain {
		public final List<ItemPredicate> filters = new ArrayList<>(2);
		public final List<FrameLabel> labels = new ArrayList<>(1);
		/** Priority of the outermost block filter (the one the network sorts the member by), null for none. */
		public Priority priority;
		public boolean keepLast;

		/** Adds a block filter, outermost first. */
		public void addFilter(ItemPredicate filter, boolean keepLast, Priority priority) {
			filters.add(filter);
			this.keepLast |= keepLast;
			if (this.priority == null)this.priority = priority;
		}
	}

	/** The member as the scan listed it when Tidy started. */
	public final Object part;
	public final BlockPos pos;
	/** Priority of the member for Tidy: its block filter's priority relative to normal, without the Frame Label boost. */
	public final int priority;
	public final boolean keepLast;
	private final BlockEntity[] blocks;
	private final List<ItemPredicate> filters;
	private final List<ItemPredicate> allowFilters;
	private final List<FrameLabel> labels;

	private TidyMember(Object part, BlockPos pos, BlockEntity[] blocks, int priority, boolean keepLast,
			List<ItemPredicate> filters, List<ItemPredicate> allowFilters, List<FrameLabel> labels) {
		this.part = part;
		this.pos = pos;
		this.blocks = blocks;
		this.priority = priority;
		this.keepLast = keepLast;
		this.filters = filters;
		this.allowFilters = allowFilters;
		this.labels = labels;
	}

	/** Whether the network may put {@code stack}'s item here (block filters and Frame Label). */
	public boolean canInsert(ItemStack stack) {
		for (int i = 0; i < filters.size(); i++)if (!filters.get(i).testStack(stack))return false;
		for (int i = 0; i < labels.size(); i++)if (!labels.get(i).test(stack))return false;
		return true;
	}

	/** Whether Tidy may take {@code stack}'s item out (block filters; never from a keep-last inventory). */
	public boolean canExtract(ItemStack stack) {
		if (keepLast)return false;
		for (int i = 0; i < filters.size(); i++)if (!filters.get(i).testStack(stack))return false;
		return true;
	}

	public boolean hasLabel() {
		return !labels.isEmpty();
	}

	public boolean hasAllowFilter() {
		return !allowFilters.isEmpty();
	}

	public boolean labelled(ItemStack stack) {
		for (int i = 0; i < labels.size(); i++)if (labels.get(i).test(stack))return true;
		return false;
	}

	public boolean filtered(ItemStack stack) {
		for (int i = 0; i < allowFilters.size(); i++)if (allowFilters.get(i).testStack(stack))return true;
		return false;
	}

	/**
	 * Whether the block entities behind this member are still the ones in the world, loaded and not
	 * removed (both halves of a double chest).
	 */
	public boolean isLive(Level level) {
		if (!level.isLoaded(pos))return false;
		for (BlockEntity be : blocks) {
			if (be.isRemoved() || be.getLevel() != level || !level.isLoaded(be.getBlockPos()) || level.getBlockEntity(be.getBlockPos()) != be)return false;
		}
		return true;
	}

	/** The level the member's block is in. */
	public Level level() {
		return blocks[0].getLevel();
	}

	/**
	 * Whether the block at {@code pos} is out of Tidy's reach by its kind (see the class comment), with
	 * {@code includeDocks} from the config.
	 */
	public static boolean isExcludedBlock(Level level, BlockPos pos, boolean includeDocks) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be == null || be.isRemoved())return true;
		if (be instanceof FilingCabinetBlockEntity || be instanceof OpenCrateBlockEntity)return true;
		if (be instanceof InventoryInterfaceBlockEntity || be instanceof InventoryProxyBlockEntity)return true;
		if (be instanceof ITidyExempt && !includeDocks)return true;
		if (be instanceof WorldlyContainer && !(be instanceof ShulkerBoxBlockEntity))return true;
		return be.getBlockState().is(StorageTags.TIDY_SKIP);
	}

	/**
	 * Tidy's view of a network member, or null when it does not take part.
	 *
	 * @param part the member as listed by the network storage (a {@code Storage} on Fabric, an
	 *        {@code IItemHandler} on Forge)
	 * @param pos the block the network read it from, null when it has none
	 * @param seen positions already taken: the same inventory reached twice is only tidied through the
	 *        first member
	 */
	public static TidyMember resolve(Level level, Object part, BlockPos pos, boolean includeDocks, Set<Object> seen) {
		if (part == null || pos == null || !level.isLoaded(pos))return null;
		Chain chain = new Chain();
		Object raw = TidyIO.unwrap(part, chain);
		if (raw == null)return null;
		if (isExcludedBlock(level, pos, includeDocks))return null;
		BlockEntity be = level.getBlockEntity(pos);
		BlockEntity[] blocks = {be};
		BlockPos other = null;
		BlockState state = be.getBlockState();
		if (be instanceof ChestBlockEntity && state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			// A double chest is one inventory over two block entities: both have to stay
			other = pos.relative(ChestBlock.getConnectedDirection(state));
			BlockEntity ob = level.isLoaded(other) ? level.getBlockEntity(other) : null;
			if (ob == null || ob.isRemoved() || isExcludedBlock(level, other, includeDocks))return null;
			blocks = new BlockEntity[] {be, ob};
		}
		if (seen.contains(pos) || (other != null && seen.contains(other)))return null;
		seen.add(pos.immutable());
		if (other != null)seen.add(other.immutable());

		List<ItemPredicate> allow = new ArrayList<>(1);
		// innermost filter first
		List<ItemPredicate> filters = new ArrayList<>(chain.filters.size());
		for (int i = chain.filters.size() - 1; i >= 0; i--) {
			ItemPredicate p = chain.filters.get(i);
			filters.add(p);
			if (isAllowList(p))allow.add(p);
		}
		Priority prio = chain.priority != null ? chain.priority : Priority.NORMAL;
		return new TidyMember(part, pos.immutable(), blocks, prio.ordinal() - Priority.NORMAL.ordinal(), chain.keepLast, filters, allow, List.copyOf(chain.labels));
	}

	/**
	 * Whether a Configurator filter names the items it lets in: an allow-list Item or Tag Filter, a
	 * Polymorphic Filter (it lists what an inventory holds) or a Condition Filter. A deny-list or no
	 * filter item at all does not make an inventory a home.
	 */
	public static boolean isAllowList(ItemPredicate p) {
		if (p == null)return false;
		if (p instanceof SimpleItemFilter f)return f.isAllowList();
		if (p instanceof TagFilter f)return f.isAllowList();
		return p instanceof PolyFilter || p instanceof ConditionFilter;
	}

	@Override
	public String toString() {
		return "TidyMember@" + pos;
	}
}
