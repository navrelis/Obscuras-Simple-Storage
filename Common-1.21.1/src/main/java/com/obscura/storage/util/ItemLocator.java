package com.obscura.storage.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Config;
import com.obscura.storage.block.AbstractStorageTerminalBlock;
import com.obscura.storage.block.AbstractStorageTerminalBlock.TerminalPos;
import com.obscura.storage.block.entity.InventoryConnectorBlockEntity;
import com.obscura.storage.block.entity.StorageTerminalBlockEntity;
import com.obscura.storage.inventory.FrameLabel;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IProxy;
import com.obscura.storage.inventory.MultiInventoryAccess;
import com.obscura.storage.inventory.NetworkInventory;
import com.obscura.storage.inventory.PlatformFilteredInventoryAccess;
import com.obscura.storage.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.menu.StorageTerminalMenu;
import com.obscura.storage.network.LocateResultPacket;
import com.obscura.storage.network.NetworkHandler;

/**
 * Item Locator: finds the inventories on a terminal's network that hold a given item, or all of them
 * ("Highlight Connected Inventories", a search without an item).
 *
 * <p>Runs only when a player asks (Alt + click in a terminal, the highlight button or key), never per tick.
 * The search reads the per-inventory change trackers the terminal already keeps up to date
 * ({@code countItems} is a map lookup), so no inventory slots are iterated again.
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

		if (!tryStartRequest(player))return;

		IInventoryAccess access = getTerminalAccess(te, level);
		StoredItemStack key = new StoredItemStack(clicked.getStack(), 1);
		SearchResult res = collect(access, key, level, ItemLocator::locateBlock);
		Selection sel = select(res.located(), player.level().dimension(), player.blockPosition(), MAX_POSITIONS);

		NetworkHandler.sendLocateResult(player, new LocateResultPacket(clicked.getStack().copyWithCount(1), sel.positions(), sel.inDimension(), sel.otherDimensions(), res.unknown()));
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

	/** Every inventory of the network the terminal reads: {@link #collect} without an item. */
	public static SearchResult searchAll(StorageTerminalBlockEntity te, ServerLevel level) {
		return collect(getTerminalAccess(te, level), null, level, ItemLocator::locateBlock);
	}

	/** Every inventory of the network of {@code connector}. */
	public static SearchResult searchAll(InventoryConnectorBlockEntity connector) {
		return collect(connector.getMergedHandler(), null, connector.getLevel(), ItemLocator::locateBlock);
	}

	private static void replyAll(ServerPlayer player, SearchResult res) {
		Selection sel = select(res.located(), player.level().dimension(), player.blockPosition(), MAX_ALL_POSITIONS);
		NetworkHandler.sendLocateResult(player, new LocateResultPacket(ItemStack.EMPTY, sel.positions(), sel.inDimension(), sel.otherDimensions(), res.unknown()));
	}

	/**
	 * The terminal's inventory access, resolved the same way the terminal resolves its own
	 * (its cache is private). For a network this returns the connector's shared merged handler,
	 * so the trackers are the warm ones the terminal already uses.
	 */
	private static IInventoryAccess getTerminalAccess(StorageTerminalBlockEntity te, ServerLevel level) {
		BlockState st = te.getBlockState();
		Direction d = Direction.NORTH;
		if (st.hasProperty(AbstractStorageTerminalBlock.FACING))d = st.getValue(AbstractStorageTerminalBlock.FACING);
		if (st.hasProperty(AbstractStorageTerminalBlock.TERMINAL_POS)) {
			TerminalPos p = st.getValue(AbstractStorageTerminalBlock.TERMINAL_POS);
			if(p == TerminalPos.UP)d = Direction.UP;
			if(p == TerminalPos.DOWN)d = Direction.DOWN;
		}
		BlockPos pos = te.getBlockPos();
		NetworkInventory inv = new NetworkInventory();
		inv.onLoad(level, pos.relative(d), d.getOpposite(), te);
		return inv.getAccess(level, pos);
	}

	/**
	 * Walks the network below {@code top} and collects every inventory whose tracked contents
	 * include {@code key} (same item and components), or every inventory when {@code key} is null,
	 * empty ones too. Every access is visited at most once, merged handlers of linked networks are
	 * descended into, and positions are deduplicated.
	 *
	 * @param key the item looked for, null for every inventory
	 * @param level when non-null, the trackers of {@code top} and of every merged handler are
	 * refreshed first (a no-op when the terminal already did so this tick)
	 * @param locator position of a leaf access, or null when it has none
	 */
	public static SearchResult collect(IInventoryAccess top, StoredItemStack key, Level level, Function<IInventoryAccess, Location> locator) {
		Set<IInventoryAccess> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Location> located = new LinkedHashSet<>();
		int unknown = 0;
		ArrayDeque<IInventoryAccess> queue = new ArrayDeque<>();
		if (top != null)queue.add(top);
		while (!queue.isEmpty()) {
			IInventoryAccess a = queue.poll();
			if (!visited.add(a))continue;
			if (level != null && (a == top || a instanceof MultiInventoryAccess))a.tracker().getChangeTracker(level);
			if (a instanceof MultiInventoryAccess m) {
				queue.addAll(m.getConnected());
				continue;
			}
			if (key == null ? !holdsInventory(a) : a.tracker().countItems(key) <= 0)continue;
			IInventoryAccess root = a.getRootHandler(Collections.newSetFromMap(new IdentityHashMap<>()));
			if (root != a && root instanceof MultiInventoryAccess) {
				// A filter or proxy in front of a whole other network: find the actual storage there
				queue.add(root);
				continue;
			}
			Location l = locator.apply(a);
			if (l == null)unknown++;
			else located.add(l);
		}
		return new SearchResult(new ArrayList<>(located), unknown);
	}

	/**
	 * Searching for all inventories: false for an access whose block holds no inventory. A terminal
	 * that faces such a block still has one, and it has a position, but there is nothing to highlight.
	 */
	private static boolean holdsInventory(IInventoryAccess a) {
		return !(a instanceof BlockInventoryAccess b) || b.exists();
	}

	/**
	 * Splits {@code located} into the player's dimension and the others, and keeps the {@code cap}
	 * positions nearest to {@code origin}. A location given twice counts once, see {@link LocateSelection}.
	 */
	public static Selection select(Collection<Location> located, ResourceKey<Level> dimension, BlockPos origin, int cap) {
		LocateSelection.Result r = LocateSelection.select(located, Location::pos, Location::dimension, dimension, origin, cap);
		return new Selection(r.positions(), r.inDimension(), r.otherDimensions());
	}

	/**
	 * Position of the storage block behind {@code a}: the root of proxies (the block an Inventory
	 * Proxy points at) when that is a block, else the block a filter sits on. Null when unknown.
	 */
	public static Location locateBlock(IInventoryAccess a) {
		Set<IProxy> dejaVu = Collections.newSetFromMap(new IdentityHashMap<>());
		return locateBlock(a, a.getRootHandler(dejaVu));
	}

	/** {@link #locateBlock(IInventoryAccess)} for a caller that already resolved {@code a}'s root handler. */
	public static Location locateBlock(IInventoryAccess a, IInventoryAccess root) {
		Location l = blockLocation(root);
		if (l != null)return l;
		for (int i = 0; i < 8 && a != null; i++) {
			if (a instanceof BlockInventoryAccess)return blockLocation(a);
			if (a instanceof FrameLabel.Labelled lab)a = lab.getLabelledInventory();
			else a = a instanceof PlatformFilteredInventoryAccess f ? f.getActualInventory() : null;
		}
		return null;
	}

	private static Location blockLocation(IInventoryAccess a) {
		if (a instanceof BlockInventoryAccess b) {
			BlockPos p = b.getPos();
			Level l = b.getLevel();
			if (p != null && l != null)return new Location(p.immutable(), l.dimension());
		}
		return null;
	}
}
