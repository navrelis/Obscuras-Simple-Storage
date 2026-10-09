package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.minecraftforge.items.IItemHandler;

import com.obscura.storage.tile.ExporterBlockEntity;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.tile.InventoryProxyBlockEntity;
import com.obscura.storage.util.ItemLocator.Location;

/**
 * Which slots of a source physically belong to a target inventory (Forge twin). The Exporter must
 * never pull from the inventory it inserts into: when its target chest (or Shulker Dock) is also
 * connected to the source network, the items it just moved would be found again and moved forever.
 *
 * <p>A source inventory counts as the target when any of these is the same:
 * <ul>
 * <li>the handler behind proxies, filters and frame labels;</li>
 * <li>the block it stands for, as the Item Locator knows it (the other half of a double chest, a proxy
 * pointing at the target, another face of the same block), also inside a nested network;</li>
 * <li>the slot itself: the stack objects of the target's slots, which vanilla style handlers hand
 * out whatever route leads to them.</li>
 * </ul>
 * Built for one transfer attempt, nothing is cached: the members of a network and what a block face
 * resolves to can change at any time.
 */
public final class TargetExclusion {
	private static final TargetExclusion NONE = new TargetExclusion(false, null);
	private static final TargetExclusion ALL = new TargetExclusion(true, null);
	/** Networks nested in the target that are still resolved to their members. */
	private static final int MAX_DEPTH = 4;

	private final boolean all;
	/** Dimension of the source. */
	private final ResourceKey<Level> dimension;
	/** The handlers of the target, the handlers they resolve to and the stacks in their slots, compared by identity. */
	private final Set<Object> identity = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<Location> blocks = new HashSet<>();

	private TargetExclusion(boolean all, ResourceKey<Level> dimension) {
		this.all = all;
		this.dimension = dimension;
	}

	/**
	 * @param level the level of the block that moves the items, source and target are looked up in it
	 * @param targetPos the block {@code target} was found at
	 */
	public static TargetExclusion of(IItemHandler source, IItemHandler target, Level level, BlockPos targetPos) {
		if (source == null || target == null)return NONE;
		IItemHandler sourceRoot = IProxy.resolve(source);
		if (source == target || sourceRoot != null && sourceRoot == IProxy.resolve(target))return ALL;
		TargetExclusion ex = new TargetExclusion(false, level.dimension());
		ex.addTarget(target, targetPos, level, level.dimension(), 0);
		// The whole source is one of the inventories of the target
		if (ex.identity.contains(source) || sourceRoot != null && ex.identity.contains(sourceRoot))return ALL;
		return ex;
	}

	private void addTarget(IItemHandler handler, BlockPos pos, Level level, ResourceKey<Level> dim, int depth) {
		if (!identity.add(handler))return;
		IItemHandler root = IProxy.resolve(handler);
		if (root == null)return;
		identity.add(root);
		if (root instanceof MergedStorage m) {
			// The target is (or leads into) a network: every inventory of it is the target
			if (depth < MAX_DEPTH) {
				for (IItemHandler part : new ArrayList<>(m.getStorages())) {
					if (part != InfoHandler.INSTANCE)addTarget(part, m.getPosition(part), level, dimensionOf(part, dim), depth + 1);
				}
			}
			return;
		}
		pos = blockOf(handler, pos);
		if (pos != null) {
			// The other half of a double chest is the same inventory
			if (dim == level.dimension() && level.isLoaded(pos)) {
				for (BlockPos p : ExporterBlockEntity.blocksOf(level, pos))blocks.add(new Location(p, dim));
			} else {
				blocks.add(new Location(pos.immutable(), dim));
			}
		}
		int n = root.getSlots();
		for (int i = 0; i < n; i++) {
			ItemStack s = root.getStackInSlot(i);
			if (!s.isEmpty())identity.add(s);
		}
	}

	/** A proxy stands for the block it points at. */
	private static BlockPos blockOf(IItemHandler handler, BlockPos pos) {
		if (handler instanceof InventoryProxyBlockEntity proxy) {
			BlockPos pointedAt = proxy.getPointedAtPos();
			if (pointedAt != null)return pointedAt;
		}
		return pos;
	}

	/** The dimension the block of {@code part} is in: the one of the block entity a link or proxy goes through. */
	static ResourceKey<Level> dimensionOf(Object part, ResourceKey<Level> def) {
		if (part instanceof InventoryConnectorBlockEntity.LocalView v)part = v.getOwner();
		if (part instanceof BlockEntity be && be.getLevel() != null)return be.getLevel().dimension();
		return def;
	}

	/** The whole source is the target: there is nothing to move. */
	public boolean isAll() {
		return all;
	}

	/**
	 * Whether {@code part}, an inventory of the network {@code owner}, is the target.
	 *
	 * @param owner null for a source that is a single inventory
	 * @param dim the dimension {@code part} is in
	 */
	public boolean excludes(MergedStorage owner, IItemHandler part, ResourceKey<Level> dim) {
		if (all)return true;
		if (owner == null)return false;
		if (identity.contains(part))return true;
		IItemHandler root = IProxy.resolve(part);
		if (root == null)return false;
		if (identity.contains(root))return true;
		// A nested network is no single block, its inventories are told apart one by one
		if (root instanceof MergedStorage)return false;
		BlockPos pos = blockOf(part, owner.getPosition(part));
		return pos != null && blocks.contains(new Location(pos, dimensionOf(part, dim)));
	}

	/** Whether {@code stack}, the stack a slot of the source shows, sits in a slot of the target. */
	public boolean excludes(ItemStack stack) {
		return all || identity.contains(stack);
	}

	/** The inventories of {@code source} that are the target, for tests and diagnostics. */
	public int excludedMembers(MergedStorage source) {
		int n = 0;
		for (IItemHandler part : new ArrayList<>(source.getStorages())) {
			if (excludes(source, part, dimension))n++;
		}
		return n;
	}
}
