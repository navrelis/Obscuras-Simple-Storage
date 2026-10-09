package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraftforge.items.IItemHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.obscura.storage.Config;
import com.obscura.storage.gui.StorageTerminalMenu;
import com.obscura.storage.network.LocateResultPacket;
import com.obscura.storage.network.NetworkHandler;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.tile.InventoryProxyBlockEntity;
import com.obscura.storage.tile.StorageTerminalBlockEntity;

/**
 * Item Locator: finds the inventories on a terminal's network that hold a given item, or all of them
 * ("Highlight Connected Inventories", a search without an item).
 *
 * <p>Runs only when a player asks (Alt + click in a terminal, the highlight button or key), never per tick. The terminal's
 * storage is a {@link MergedStorage} of the connected storages, each remembering the block it came
 * from ({@link MergedStorage#getPosition}), so every part is iterated once and only until its first
 * match. Networks reached through a proxy or a cable link are descended into, the dimension follows
 * the block entity the link goes through.
 */
public class ItemLocator {
	/** Most positions sent to the client, nearest first. */
	public static final int MAX_POSITIONS = 64;
	/** Most positions sent for "all connected inventories", nearest first. */
	public static final int MAX_ALL_POSITIONS = 256;
	/** Minimum server ticks between two locate requests of one player. */
	public static final int COOLDOWN_TICKS = 20;

	private static final Map<ServerPlayer, Integer> lastRequest = new WeakHashMap<>();

	/** Where one matching inventory is. */
	public static record Location(BlockPos pos, ResourceKey<Level> dimension) {}

	/** Distinct located inventories plus the number of matching inventories without a known position. */
	public static record SearchResult(List<Location> located, int unknown) {}

	/** Positions to highlight in the player's dimension (nearest first, capped) and the counts. */
	public static record Selection(List<BlockPos> positions, int inDimension, int otherDimensions) {}

	/** Config switch; on the client this is the server's value (login config sync). */
	public static boolean isEnabled() {
		return Config.get().itemLocatorEnabled;
	}

	/** Server: handles a locate request coming from {@code menu}. The clicked stack was resolved from the menu's own sync ids. */
	public static void locate(ServerPlayer player, StorageTerminalMenu menu, StoredItemStack clicked) {
		if (!isEnabled() || clicked == null || player.containerMenu != menu)return;
		StorageTerminalBlockEntity te = menu.getTerminal();
		if (te == null || te.isRemoved() || !(te.getLevel() instanceof ServerLevel level) || !menu.stillValid(player))return;
		ItemStack stack = clicked.getStack();
		if (stack.isEmpty())return;

		if (!tryStartRequest(player))return;

		SearchResult res = search(te, level, ItemKey.of(stack));
		Selection sel = select(res.located(), player.level().dimension(), player.blockPosition(), MAX_POSITIONS);

		NetworkHandler.sendLocateResult(player, new LocateResultPacket(stack.copyWithCount(1), sel.positions(), sel.inDimension(), sel.otherDimensions(), res.unknown()));
	}

	/** Server: true (and starts the cooldown) when {@code player} may send a locate request now. Item locate and highlight share it. */
	public static boolean tryStartRequest(ServerPlayer player) {
		int now = player.server.getTickCount();
		Integer last = lastRequest.get(player);
		if (last != null && now - last < COOLDOWN_TICKS && now >= last)return false;
		lastRequest.put(player, now);
		return true;
	}

	/** Server: the highlight button in the terminal screen of {@code menu}. Same guards as {@link #locate}. */
	public static void locateAll(ServerPlayer player, StorageTerminalMenu menu) {
		if (!isEnabled() || player.containerMenu != menu)return;
		StorageTerminalBlockEntity te = menu.getTerminal();
		if (te == null || !menu.stillValid(player) || !tryStartRequest(player))return;
		locateAll(player, te);
	}

	/** Server: answers with every inventory of the network {@code te} reads. The caller checked the request (switch, cooldown, reach). */
	public static void locateAll(ServerPlayer player, StorageTerminalBlockEntity te) {
		if (te.isRemoved() || !(te.getLevel() instanceof ServerLevel level))return;
		replyAll(player, searchAll(te, level));
	}

	/** Server: answers with every inventory of the network of {@code connector}. The caller checked the request. */
	public static void locateAll(ServerPlayer player, InventoryConnectorBlockEntity connector) {
		if (connector.isRemoved() || connector.getLevel() == null)return;
		replyAll(player, searchAll(connector));
	}

	/** Every inventory of the network the terminal reads: {@link #search} without an item. */
	public static SearchResult searchAll(StorageTerminalBlockEntity te, ServerLevel level) {
		return search(te, level, null);
	}

	/** Every inventory of the network of {@code connector}. */
	public static SearchResult searchAll(InventoryConnectorBlockEntity connector) {
		Search s = new Search(null);
		s.scanMerged(connector.getNetworkStorage(), connector.getLevel().dimension());
		return s.result();
	}

	private static void replyAll(ServerPlayer player, SearchResult res) {
		Selection sel = select(res.located(), player.level().dimension(), player.blockPosition(), MAX_ALL_POSITIONS);
		NetworkHandler.sendLocateResult(player, new LocateResultPacket(ItemStack.EMPTY, sel.positions(), sel.inDimension(), sel.otherDimensions(), res.unknown()));
	}

	/** Searches the storage the terminal is attached to. A null {@code variant} matches every inventory. */
	public static SearchResult search(StorageTerminalBlockEntity te, ServerLevel level, ItemKey variant) {
		Search s = new Search(variant);
		IItemHandler handler = te.getItemHandler();
		if (handler != null) {
			ResourceKey<Level> dim = level.dimension();
			IItemHandler root = IProxy.resolve(handler);
			if (root instanceof MergedStorage m) {
				s.scanMerged(m, entityDimension(handler, dim));
			} else if (root != null) {
				// A single storage next to the terminal, no merged network in between
				s.scanLeaf(handler, te.getAttachedPos(), entityDimension(handler, dim));
			}
		}
		return s.result();
	}

	/**
	 * Splits {@code located} into the player's dimension and the others, and keeps the {@code cap}
	 * positions nearest to {@code origin}. A location given twice counts once, see {@link LocateSelection}.
	 */
	public static Selection select(Collection<Location> located, ResourceKey<Level> dimension, BlockPos origin, int cap) {
		LocateSelection.Result r = LocateSelection.select(located, Location::pos, Location::dimension, dimension, origin, cap);
		return new Selection(r.positions(), r.inDimension(), r.otherDimensions());
	}

	private static ResourceKey<Level> entityDimension(Object part, ResourceKey<Level> def) {
		if (part instanceof InventoryConnectorBlockEntity.LocalView v)part = v.getOwner();
		if (part instanceof BlockEntity be && be.getLevel() != null)return be.getLevel().dimension();
		return def;
	}

	/** One search: the variant looked for (null: every inventory), the parts already visited and the matches so far. */
	private static class Search {
		private final ItemKey variant;
		private final Set<MergedStorage> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		private final Set<Location> located = new LinkedHashSet<>();
		private int unknown;

		private Search(ItemKey variant) {
			this.variant = variant;
		}

		private SearchResult result() {
			return new SearchResult(new ArrayList<>(located), unknown);
		}

		/**
		 * Walks the parts of {@code m}. A part that leads to another merged storage (a cable link, or
		 * a proxy in front of a whole network) is descended into, every merged storage at most once.
		 */
		private void scanMerged(MergedStorage m, ResourceKey<Level> dim) {
			if (!visited.add(m))return;
			for (IItemHandler part : new ArrayList<>(m.getStorages())) {
				if (part == InfoHandler.INSTANCE)continue;
				ResourceKey<Level> partDim = entityDimension(part, dim);
				IItemHandler root = IProxy.resolve(part);
				if (root == null)continue;
				if (root instanceof MergedStorage nested) {
					scanMerged(nested, partDim);
				} else {
					scanLeaf(part, m.getPosition(part), partDim);
				}
			}
		}

		private void scanLeaf(IItemHandler part, BlockPos pos, ResourceKey<Level> dim) {
			if (!contains(part))return;
			if (part instanceof InventoryProxyBlockEntity proxy) {
				// A proxy stands for the block it points at
				BlockPos target = proxy.getPointedAtPos();
				if (target != null)pos = target;
			}
			if (pos == null)unknown++;
			else located.add(new Location(pos.immutable(), dim));
		}

		/** True when {@code part} shows {@link #variant} (always when searching for all). Same view the terminal gets: filters and keep-last apply. */
		private boolean contains(IItemHandler part) {
			if (variant == null)return true;
			return ItemHandlers.count(part, variant, 1) > 0;
		}
	}
}
