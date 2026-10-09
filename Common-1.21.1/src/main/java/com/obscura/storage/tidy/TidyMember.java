package com.obscura.storage.tidy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import com.obscura.storage.StorageTags;
import com.obscura.storage.block.entity.FilingCabinetBlockEntity;
import com.obscura.storage.block.entity.OpenCrateBlockEntity;
import com.obscura.storage.inventory.FrameLabel;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IProxy;
import com.obscura.storage.inventory.ITidyExempt;
import com.obscura.storage.inventory.MultiInventoryAccess;
import com.obscura.storage.inventory.PlatformFilteredInventoryAccess;
import com.obscura.storage.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.obscura.storage.inventory.filter.IFilter;
import com.obscura.storage.inventory.filter.IFilter.MultiFilter;
import com.obscura.storage.inventory.filter.ItemFilter;
import com.obscura.storage.inventory.filter.ItemPredicate;
import com.obscura.storage.inventory.filter.PolyFilter;
import com.obscura.storage.inventory.filter.SimpleItemFilter;
import com.obscura.storage.inventory.filter.TagFilter;
import com.obscura.storage.inventory.filter.ConditionFilter;
import com.obscura.storage.util.Priority;

/**
 * One network member as Tidy sees it: the member access (block filters and Frame Label on top of the
 * inventory), the inventory itself and the rules read from the wrappers.
 *
 * <p>Only plain inventories take part: the wrapper chain may hold Configurator filters and a Frame
 * Label and must end in the block's own inventory. Proxies, interfaces and anything leading into a
 * network are left out, and so are the Filing Cabinet, the Open Crate, docked shulker boxes (unless
 * {@code tidyIncludeDocks}), machines (sided inventories other than shulker boxes, the Crafter) and
 * blocks in the {@code obscuras_storage:tidy_skip} tag.
 */
public final class TidyMember {
	public final IInventoryAccess access;
	public final BlockInventoryAccess base;
	public final BlockPos pos;
	/** Priority of the member for Tidy: its block filters' priorities, without the Frame Label boost. */
	public final int priority;
	public final boolean keepLast;
	private final List<ItemPredicate> filters;
	private final List<ItemPredicate> allowFilters;
	private final List<FrameLabel> labels;

	private TidyMember(IInventoryAccess access, BlockInventoryAccess base, BlockPos pos, int priority, boolean keepLast,
			List<ItemPredicate> filters, List<ItemPredicate> allowFilters, List<FrameLabel> labels) {
		this.access = access;
		this.base = base;
		this.pos = pos;
		this.priority = priority;
		this.keepLast = keepLast;
		this.filters = filters;
		this.allowFilters = allowFilters;
		this.labels = labels;
	}

	/** Whether the network may put {@code stack}'s item here (block filters and Frame Label). */
	public boolean canInsert(ItemStack stack) {
		for (int i = 0; i < filters.size(); i++)if (!filters.get(i).test(stack))return false;
		for (int i = 0; i < labels.size(); i++)if (!labels.get(i).test(stack))return false;
		return true;
	}

	/** Whether Tidy may take {@code stack}'s item out (block filters; never from a keep-last inventory). */
	public boolean canExtract(ItemStack stack) {
		if (keepLast)return false;
		for (int i = 0; i < filters.size(); i++)if (!filters.get(i).test(stack))return false;
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
		for (int i = 0; i < allowFilters.size(); i++)if (allowFilters.get(i).test(stack))return true;
		return false;
	}

	/**
	 * Whether the block at {@code pos} is out of Tidy's reach by its kind (see the class comment), with
	 * {@code includeDocks} from the config.
	 */
	public static boolean isExcludedBlock(Level level, BlockPos pos, boolean includeDocks) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be == null || be.isRemoved())return true;
		if (be instanceof FilingCabinetBlockEntity || be instanceof OpenCrateBlockEntity || be instanceof CrafterBlockEntity)return true;
		if (be instanceof ITidyExempt && !includeDocks)return true;
		if (be instanceof WorldlyContainer && !(be instanceof ShulkerBoxBlockEntity))return true;
		return be.getBlockState().is(StorageTags.TIDY_SKIP);
	}

	/**
	 * Tidy's view of a network member, or null when it does not take part.
	 *
	 * @param seen base inventories already taken, by identity: the same inventory reached twice is
	 *        only tidied through the first member
	 */
	@SuppressWarnings("deprecation")
	public static TidyMember resolve(Level level, IInventoryAccess member, boolean includeDocks, Set<Object> seen) {
		IInventoryAccess root = member.getRootHandler(Collections.newSetFromMap(new IdentityHashMap<>()));
		if (root instanceof MultiInventoryAccess)return null;
		List<IFilter> filterObjs = new ArrayList<>(1);
		List<FrameLabel> labels = new ArrayList<>(1);
		IInventoryAccess a = member;
		for (int depth = 0; depth < 16; depth++) {
			if (a instanceof PlatformFilteredInventoryAccess f) {
				filterObjs.add(f.getFilter());
				a = f.getActualInventory();
			} else if (a instanceof FrameLabel.Labelled l) {
				labels.add(l.getLabel());
				a = l.getLabelledInventory();
			} else break;
		}
		if (!(a instanceof BlockInventoryAccess base) || root != base)return null;
		Object h = base.get();
		if (h == null || h instanceof IProxy || h instanceof MultiInventoryAccess)return null;
		BlockPos pos = base.getPos();
		if (pos == null || base.getLevel() != level || !level.isLoaded(pos))return null;
		if (isExcludedBlock(level, pos, includeDocks))return null;
		if (!seen.add(pos.immutable()))return null;

		Priority prio = Priority.NORMAL;
		boolean keepLast = false;
		List<ItemPredicate> filters = new ArrayList<>(filterObjs.size());
		List<ItemPredicate> allow = new ArrayList<>(1);
		// innermost filter first, as the wrappers add their priorities
		for (int i = filterObjs.size() - 1; i >= 0; i--) {
			IFilter f = filterObjs.get(i);
			ItemPredicate p = f.getItemPred();
			p.updateState();
			filters.add(p);
			if (isAllowList(p))allow.add(p);
			prio = prio.add(f.getPriority());
			keepLast |= f.isKeepLast();
		}
		for (FrameLabel l : labels)l.updateState();
		return new TidyMember(member, base, pos.immutable(), prio.getSorting(), keepLast, filters, allow, labels);
	}

	/**
	 * Whether a Configurator filter names the items it lets in: an allow-list Item or Tag Filter, a
	 * Polymorphic Filter (it lists what an inventory holds) or a Condition Filter. A deny-list or no
	 * filter item at all does not make an inventory a home.
	 */
	public static boolean isAllowList(ItemPredicate p) {
		if (p == null || p == ItemFilter.TRUE)return false;
		if (p instanceof SimpleItemFilter f)return f.isAllowList();
		if (p instanceof TagFilter f)return f.isAllowList();
		if (p instanceof PolyFilter || p instanceof ConditionFilter)return true;
		if (p instanceof MultiFilter m) {
			for (IFilter f : m.getFilters())if (isAllowList(f.getItemPred()))return true;
		}
		return false;
	}

	@Override
	public String toString() {
		return "TidyMember@" + pos;
	}
}
