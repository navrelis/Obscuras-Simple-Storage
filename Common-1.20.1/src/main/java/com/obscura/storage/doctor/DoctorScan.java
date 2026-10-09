package com.obscura.storage.doctor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import com.obscura.storage.Config;
import com.obscura.storage.StorageTags;
import com.obscura.storage.block.AbstractStorageTerminalBlock;
import com.obscura.storage.block.BasicInventoryHopperBlock;
import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.block.InventoryConnectorBlock;
import com.obscura.storage.block.InventoryInterfaceBlock;
import com.obscura.storage.block.ITrim;
import com.obscura.storage.block.LevelEmitterBlock;
import com.obscura.storage.doctor.NetworkReport.Hints;
import com.obscura.storage.doctor.NetworkReport.Reasons;
import com.obscura.storage.doctor.NetworkReport.Skipped;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.tile.AbstractInventoryCableConnectorBlockEntity;
import com.obscura.storage.tile.AbstractFilterBlockEntity;
import com.obscura.storage.tile.AbstractInventoryHopperBlockEntity;
import com.obscura.storage.tile.CraftingTerminalBlockEntity;
import com.obscura.storage.tile.InventoryCableConnectorBlockEntity;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.tile.InventoryConnectorBlockEntity.UsageInfo;
import com.obscura.storage.tile.InventoryInterfaceBlockEntity;
import com.obscura.storage.tile.InventoryProxyBlockEntity;
import com.obscura.storage.tile.LevelEmitterBlockEntity;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.BlockFilter;
import com.obscura.storage.util.InventoryConnectorConfigUtil;
import com.obscura.storage.util.ItemLocator.Location;
import com.obscura.storage.util.MergedStorage;
import com.obscura.storage.util.StorageSnapshot;

/**
 * The Network Doctor's analysis. Runs only when a command asks for it (or the Inventory Configurator
 * shows a summary), never per tick, and changes nothing: the connector's scan and the cable graph are
 * traced again read-only, with the same rules the network uses, to find out what is (not) connected
 * and why.
 */
public final class DoctorScan {
	/** Skipped blocks kept per network, so a huge broken network does not produce a huge export. */
	public static final int MAX_SKIPPED = 256;
	private static final Direction[] DIRS = Direction.values();

	private DoctorScan() {}

	/** Loaded networks, heaviest (average ms per tick) first. */
	public static List<InventoryConnectorBlockEntity> byCost(MinecraftServer server) {
		List<InventoryConnectorBlockEntity> list = NetworkDoctor.connectors(server);
		Map<InventoryConnectorBlockEntity, Double> cost = new HashMap<>();
		for (InventoryConnectorBlockEntity c : list)cost.put(c, c.getDoctorStats().avgNanosPerTick(c.getLevel().getGameTime()));
		list.sort(Comparator.<InventoryConnectorBlockEntity>comparingDouble(cost::get).reversed()
				.thenComparing(c -> c.getLevel().dimension().location().toString())
				.thenComparing(BlockEntity::getBlockPos));
		return list;
	}

	/** Counts and timings without the analysis, for {@code doctor top}. */
	public static NetworkReport quick(InventoryConnectorBlockEntity c, MinecraftServer server) {
		return report(c, server, null);
	}

	private static NetworkReport report(InventoryConnectorBlockEntity c, MinecraftServer server, NetworkReport.Details details) {
		NetworkStats s = c.getDoctorStats();
		long now = c.getLevel().getGameTime();
		UsageInfo u = c.getUsage();
		BlockPos p = c.getBlockPos();
		return new NetworkReport(c.getLevel().dimension().location().toString(), p.getX(), p.getY(), p.getZ(),
				u.blocks(), u.all(), u.free(),
				s.avgMsPerTick(now), s.peakMs(now), s.rescansPerMinute(now), s.rebuildsPerMinute(now), s.windowSeconds(now),
				NetworkDoctor.playersWatching(s, server), s.packetsPerSecond(now), s.bytesPerSecond(now), details);
	}

	/**
	 * Attached blocks of the network owning {@code stats}: terminals, crafting terminals, hoppers, level
	 * emitters, filter blocks (Ejector, Disposal and Exporter together).
	 */
	public static int[] attached(NetworkStats stats, MinecraftServer server) {
		int[] out = new int[5];
		for (BlockEntity be : NetworkDoctor.members(server)) {
			if (!(be instanceof INetworkMember m) || !m.isOnNetwork(stats))continue;
			if (be instanceof CraftingTerminalBlockEntity)out[1]++;
			else if (be instanceof StorageTerminalBlockEntity)out[0]++;
			else if (be instanceof AbstractInventoryHopperBlockEntity)out[2]++;
			else if (be instanceof LevelEmitterBlockEntity)out[3]++;
			else if (be instanceof AbstractFilterBlockEntity)out[4]++;
		}
		return out;
	}

	/** The Inventory Configurator's one line summary: no timings, cheap enough for every player. */
	public record Summary(int inventories, int slots, int slotsFree, int terminals, int duplicates) {}

	public static Summary summary(InventoryConnectorBlockEntity c, MinecraftServer server) {
		UsageInfo u = c.getUsage();
		int terminals = 0;
		if (c.getDoctorStats() != null) {
			int[] a = attached(c.getDoctorStats(), server);
			terminals = a[0] + a[1];
		}
		return new Summary(u.blocks(), u.all(), u.free(), terminals, c.getDoctorCounters().duplicates());
	}

	/** Everything {@code doctor at} shows about one network. */
	public static NetworkReport full(InventoryConnectorBlockEntity c, MinecraftServer server) {
		ServerLevel level = (ServerLevel) c.getLevel();
		String dim = level.dimension().location().toString();
		NetworkStats stats = c.getDoctorStats();
		long now = level.getGameTime();
		MergedStorage h = c.getNetworkStorage();
		ScanCounters sc = c.getDoctorCounters();
		Map<BlockPos, String> skipped = new LinkedHashMap<>();

		// Members the last scan left out (the cap)
		int cap = Config.get().doctorMaxInventories;
		for (BlockPos p : sc.overCapPositions)skip(skipped, p, Reasons.OVER_CAP);

		// The item totals are only read by terminals, emitters and hoppers: bring them up to date
		// first, without booking the doctor's read to the network
		StorageSnapshot snapshot = h.getSnapshot();
		NetworkDoctor.runUntimed(() -> {
			snapshot.invalidate();
			snapshot.refresh(h, now, 1);
		});
		int uniqueItems = snapshot.counts().size();

		// The connector's own scan for touching inventories, traced again with the same rules
		Set<BlockPos> interfaces = new HashSet<>();
		Set<BlockPos> proxies = new HashSet<>();
		touchingScan(level, c.getBlockPos(), skipped);

		// The cable graph, traced again to count cables and the network blocks on it
		CableTrace cables = traceCables(level, c.getBlockPos(), skipped);
		int cableConnectors = 0, links = 0, linkedConnectors = 0;
		for (BlockPos p : cables.attached) {
			BlockEntity be = level.getBlockEntity(p);
			if (be instanceof InventoryConnectorBlockEntity && be != c)linkedConnectors++;
			else if (be instanceof InventoryInterfaceBlockEntity)interfaces.add(p);
			else if (be instanceof AbstractInventoryCableConnectorBlockEntity cc) {
				cableConnectors++;
				if (cc instanceof InventoryCableConnectorBlockEntity link && link.isLinkMode())links++;
				else checkCableConnectorTarget(level, cc, skipped, proxies);
			}
		}

		int[] att = attached(stats, server);
		boolean blacklisted = skipped.containsValue(Reasons.SKIP_TAG) || skipped.containsValue(Reasons.CONFIG_BLOCK) || skipped.containsValue(Reasons.CONFIG_MOD);
		List<Skipped> skippedList = new ArrayList<>();
		for (Map.Entry<BlockPos, String> e : skipped.entrySet()) {
			BlockPos p = e.getKey();
			skippedList.add(new Skipped(dim, p.getX(), p.getY(), p.getZ(), e.getValue()));
		}

		List<String> hints = new ArrayList<>();
		// Rate based hints only once there are 10 s of data: a network that just loaded rescans and rebuilds
		boolean measured = stats.windowTicks(now) >= NetworkStats.WARN_MIN_WINDOW_TICKS;
		if (measured && stats.rescansPerMinute(now) > Hints.RESCANS_PER_MIN)hints.add(Hints.RESCANS);
		if (measured && stats.rebuildsPerMinute(now) > Hints.REBUILDS_PER_MIN)hints.add(Hints.REBUILDS);
		if (sc.overCap > 0)hints.add(Hints.OVER_CAP);
		if (skipped.containsValue(Reasons.UNLOADED))hints.add(Hints.UNLOADED);
		if (measured && stats.packetsPerSecond(now) > Hints.PACKETS_PER_SEC)hints.add(Hints.SYNC);
		if (blacklisted)hints.add(Hints.BLACKLISTED);
		if (cables.truncated)hints.add(Hints.CABLES_TRUNCATED);
		if (measured && hints.isEmpty() && stats.avgMsPerTick(now) >= Hints.SLOW_MS)hints.add(Hints.SLOW);

		NetworkReport.Details d = new NetworkReport.Details(sc.sameStorage, sc.reachedTwice, sc.network, uniqueItems,
				cables.cables, cableConnectors, interfaces.size(), proxies.size(), links, linkedConnectors,
				att[0], att[1], att[2], att[3], cap, sc.overCap, cables.truncated, List.copyOf(skippedList), List.copyOf(hints), h.countLabelled(), att[4]);
		return report(c, server, d);
	}

	private static void skip(Map<BlockPos, String> skipped, BlockPos p, String reason) {
		if (skipped.size() >= MAX_SKIPPED && !skipped.containsKey(p))return;
		skipped.putIfAbsent(p.immutable(), reason);
	}

	/** Why the Inventory Connector ignores a block with an inventory, see {@link InventoryConnectorConfigUtil#canConnect}. */
	public static String blacklistReason(BlockState state) {
		if (state.is(StorageTags.INV_CONNECTOR_SKIP))return Reasons.SKIP_TAG;
		if (Config.get().getBlockedBlocks().contains(state.getBlock()))return Reasons.CONFIG_BLOCK;
		return Reasons.CONFIG_MOD;
	}

	/** The position of the other half of the double chest {@code state} at {@code p} belongs to, null for a single chest. */
	private static @Nullable BlockPos otherChestHalf(Level level, BlockState state, BlockPos p) {
		if (!(state.getBlock() instanceof ChestBlock))return null;
		ChestType type = state.getValue(ChestBlock.TYPE);
		if (type == ChestType.SINGLE)return null;
		BlockPos opos = p.relative(ChestBlock.getConnectedDirection(state));
		BlockState ostate = level.getBlockState(opos);
		if (state.getBlock() != ostate.getBlock())return null;
		ChestType otype = ostate.getValue(ChestBlock.TYPE);
		if (otype != ChestType.SINGLE && type != otype && state.getValue(ChestBlock.FACING) == ostate.getValue(ChestBlock.FACING))return opos;
		return null;
	}

	/** Blocks the Inventory Connector's scan never takes as inventories: the network's own blocks. */
	private static boolean isNetworkBlockEntity(BlockEntity be) {
		return be instanceof InventoryConnectorBlockEntity || be instanceof InventoryProxyBlockEntity || be instanceof AbstractInventoryCableConnectorBlockEntity;
	}

	/**
	 * Read-only copy of the touching scan of {@code InventoryConnectorBlockEntity#updateServer}: the same
	 * walk through trims and inventories, recording what it does not take and why.
	 */
	private static void touchingScan(ServerLevel level, BlockPos origin, Map<BlockPos, String> skipped) {
		int range = Config.get().invConnectorScanRange;
		range *= range;
		boolean onlyTrims = Config.get().onlyTrims;
		ArrayDeque<BlockPos> toCheck = new ArrayDeque<>();
		Set<BlockPos> checked = new HashSet<>();
		Set<BlockPos> outside = new HashSet<>();
		toCheck.push(origin);
		checked.add(origin);
		while (!toCheck.isEmpty()) {
			BlockPos cp = toCheck.pop();
			for (Direction d : DIRS) {
				BlockPos p = cp.relative(d);
				if (checked.contains(p))continue;
				if (p.distSqr(origin) >= range) {
					// Just outside the range: only worth a mention when it would have connected
					if (outside.add(p) && level.isLoaded(p)) {
						BlockState st = level.getBlockState(p);
						BlockEntity be = level.getBlockEntity(p);
						if (be != null && !isNetworkBlockEntity(be) && !(st.getBlock() instanceof ITrim) && InventoryConnectorConfigUtil.canConnect(st.getBlock())
								&& !st.is(StorageTags.INV_CONNECTOR_SKIP) && Platform.hasItemInventory(level, p, st, d.getOpposite()))
							skip(skipped, p, Reasons.OUT_OF_RANGE);
					}
					continue;
				}
				checked.add(p);
				if (!level.isLoaded(p)) {
					skip(skipped, p, Reasons.UNLOADED);
					continue;
				}
				BlockState state = level.getBlockState(p);
				if (state.isAir())continue;
				if (state.getBlock() instanceof ITrim) {
					toCheck.push(p);
					continue;
				}
				BlockEntity te = level.getBlockEntity(p);
				if (te == null || isNetworkBlockEntity(te))continue;
				boolean inventory = Platform.hasItemInventory(level, p, state, d.getOpposite());
				if (state.is(StorageTags.INV_CONNECTOR_SKIP) || !InventoryConnectorConfigUtil.canConnect(state.getBlock())) {
					// Only blocks that would have been an inventory are worth a line
					if (inventory)skip(skipped, p, blacklistReason(state));
					continue;
				}
				if (onlyTrims)continue;
				BlockPos otherHalf = inventory ? otherChestHalf(level, state, p) : null;
				if (otherHalf != null) {
					toCheck.push(otherHalf);
					checked.add(otherHalf);
				}
				if (!inventory) {
					// Terminals, hoppers and emitters next to the connector are network blocks, not blocks that failed to connect
					if (!(state.getBlock() instanceof AbstractStorageTerminalBlock) && !(state.getBlock() instanceof IInventoryCable))skip(skipped, p, Reasons.NOT_INVENTORY);
					continue;
				}
				BlockFilter filter = BlockFilter.findExistingAt(level, p);
				toCheck.push(p);
				// Excluded with the Inventory Configurator: the block drops out, the scan goes on through it
				if (filter != null && filter.skip())skip(skipped, p, Reasons.EXCLUDED);
				else if (Config.getMultiblockInvs().contains(state.getBlock()))skipMultiblock(level, p, state.getBlock(), checked, toCheck);
			}
		}
	}

	/** The configured multiblock inventories (multiblockInv): the connected blocks of the same type go along, see the connector's skipBlocks. */
	private static void skipMultiblock(ServerLevel level, BlockPos pos, Block block, Set<BlockPos> checked, ArrayDeque<BlockPos> toCheck) {
		ArrayDeque<BlockPos> pending = new ArrayDeque<>();
		double rangeSq = (double) Config.get().invConnectorScanRange * Config.get().invConnectorScanRange;
		pending.push(pos);
		while (!pending.isEmpty()) {
			BlockPos cp = pending.pop();
			for (Direction d : DIRS) {
				BlockPos p = cp.relative(d);
				if (checked.contains(p) || p.distSqr(pos) >= rangeSq || !level.isLoaded(p))continue;
				if (level.getBlockState(p).getBlock() == block) {
					checked.add(p);
					toCheck.push(p);
					pending.push(p);
				}
			}
		}
	}

	private record CableTrace(int cables, List<BlockPos> attached, boolean truncated) {}

	/** Whether a block on the cable graph is a plain cable (as opposed to a connector, interface, hopper or emitter). */
	private static boolean isPlainCable(Block b) {
		return !(b instanceof InventoryConnectorBlock || b instanceof InventoryCableConnectorBlock || b instanceof InventoryInterfaceBlock
				|| b instanceof BasicInventoryHopperBlock || b instanceof LevelEmitterBlock);
	}

	/** Read-only copy of the cable graph walk the cable connectors, hoppers and emitters do to find their connector. */
	private static CableTrace traceCables(ServerLevel level, BlockPos origin, Map<BlockPos, String> skipped) {
		int max = Config.get().invConnectorMaxCables;
		ArrayDeque<BlockPos> next = new ArrayDeque<>();
		Set<BlockPos> seen = new HashSet<>();
		List<BlockPos> attached = new ArrayList<>();
		int cables = 0;
		boolean truncated = false;
		seen.add(origin);
		for (Direction d : DIRS) {
			BlockPos p = origin.relative(d);
			if (!level.isLoaded(p)) {
				skip(skipped, p, Reasons.UNLOADED);
				continue;
			}
			if (IInventoryCable.canConnect(level.getBlockState(p), d))next.push(p);
		}
		while (!next.isEmpty()) {
			if (seen.size() > max) {
				truncated = true;
				break;
			}
			BlockPos p = next.pop();
			if (!seen.add(p))continue;
			if (!level.isLoaded(p)) {
				skip(skipped, p, Reasons.UNLOADED);
				continue;
			}
			BlockState st = level.getBlockState(p);
			if (st.getBlock() instanceof IInventoryCable cab) {
				if (isPlainCable(st.getBlock()))cables++;
				else attached.add(p.immutable());
				next.addAll(cab.next(level, st, p));
			}
		}
		return new CableTrace(cables, attached, truncated);
	}

	/** What a (non link) Inventory Cable Connector points at, when that does not end up in the network. */
	private static void checkCableConnectorTarget(ServerLevel level, AbstractInventoryCableConnectorBlockEntity cc, Map<BlockPos, String> skipped, Set<BlockPos> proxies) {
		BlockPos t = cc.getTargetPos();
		if (t.equals(cc.getBlockPos()))return;
		if (!level.isLoaded(t)) {
			skip(skipped, t, Reasons.UNLOADED);
			return;
		}
		BlockState st = level.getBlockState(t);
		if (st.isAir() || st.getBlock() instanceof IInventoryCable)return;
		BlockEntity be = level.getBlockEntity(t);
		// Another network's block in front: linked as a network, not an inventory
		if (be != null && (isNetworkBlockEntity(be) || be instanceof InventoryInterfaceBlockEntity) && !(be instanceof InventoryProxyBlockEntity))return;
		Direction facing = cc.getBlockState().getValue(InventoryCableConnectorBlock.FACING);
		if (!Platform.hasItemInventoryAt(level, t, facing.getOpposite())) {
			skip(skipped, t, Reasons.NOT_INVENTORY);
			return;
		}
		if (be instanceof InventoryProxyBlockEntity)proxies.add(t.immutable());
		BlockFilter own = BlockFilter.findExistingAt(level, cc.getBlockPos());
		BlockFilter tgt = BlockFilter.findExistingAt(level, t);
		if (own != null && own.skip() || tgt != null && tgt.skip())skip(skipped, t, Reasons.EXCLUDED);
	}

	/**
	 * The loaded network {@code pos} belongs to: the connector itself, a cable, cable connector,
	 * interface, terminal, hopper or level emitter on it, or one of its inventories. Null when none.
	 */
	public static @Nullable InventoryConnectorBlockEntity find(ServerLevel level, BlockPos pos) {
		MinecraftServer server = level.getServer();
		if (!level.isLoaded(pos))return null;
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof InventoryConnectorBlockEntity c)return c.getDoctorStats() != null ? c : null;
		List<InventoryConnectorBlockEntity> all = new ArrayList<>();
		for (InventoryConnectorBlockEntity c : NetworkDoctor.connectors(server)) {
			if (c.getLevel() == level)all.add(c);
		}
		if (be instanceof AbstractInventoryCableConnectorBlockEntity cc) {
			InventoryConnectorBlockEntity m = cc.getMaster();
			if (m != null && m.getDoctorStats() != null)return m;
		}
		if (be instanceof INetworkMember m) {
			for (InventoryConnectorBlockEntity c : all) {
				if (m.isOnNetwork(c.getDoctorStats()))return c;
			}
		}
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof IInventoryCable) {
			InventoryConnectorBlockEntity viaCable = findViaCables(level, pos);
			if (viaCable != null)return viaCable;
		}
		// An inventory: touching the connector (directly or through trims), or a member elsewhere
		for (InventoryConnectorBlockEntity c : all) {
			if (c.getConnectedBlocks().contains(pos))return c;
		}
		for (InventoryConnectorBlockEntity c : all) {
			MergedStorage ms = c.getNetworkStorage();
			for (var part : ms.getStorages()) {
				BlockPos at = ms.getPosition(part);
				if (at != null && at.equals(pos))return c;
			}
		}
		return null;
	}

	/** The connector at the end of the cables {@code pos} is on (the lowest position when there are several), null when none. */
	private static @Nullable InventoryConnectorBlockEntity findViaCables(ServerLevel level, BlockPos pos) {
		int max = Config.get().invConnectorMaxCables;
		ArrayDeque<BlockPos> next = new ArrayDeque<>();
		Set<BlockPos> seen = new HashSet<>();
		InventoryConnectorBlockEntity best = null;
		next.push(pos);
		while (!next.isEmpty() && seen.size() <= max) {
			BlockPos p = next.pop();
			if (!seen.add(p) || !level.isLoaded(p))continue;
			BlockState st = level.getBlockState(p);
			if (st.getBlock() instanceof InventoryConnectorBlock) {
				if (level.getBlockEntity(p) instanceof InventoryConnectorBlockEntity c && c.getDoctorStats() != null && (best == null || p.compareTo(best.getBlockPos()) < 0))best = c;
				continue;
			}
			if (st.getBlock() instanceof IInventoryCable cab)next.addAll(cab.next(level, st, p));
		}
		return best;
	}

	// ------------------------------------------------------------------ doctor block

	/** How {@code doctor block} judges a block. */
	public enum Verdict {
		/** A cable, connector, terminal...: part of the network, not an inventory in it. */
		NETWORK_BLOCK,
		/** Carries the connector's scan, holds no items. */
		TRIM,
		/** Joins through an Inventory Connector or a Cable Connector. */
		JOINS,
		/** Blacklisted for the Inventory Connector, but a Cable Connector facing it still connects it. */
		CABLE_CONNECTOR_ONLY,
		/** Excluded with the Inventory Configurator. */
		EXCLUDED,
		/** Has no item inventory. */
		CANNOT_JOIN,
		/** Has a block entity that is not a known vanilla container: unknown until a placed one is checked. */
		CHECK_PLACED
	}

	/** One line of {@code doctor block}. {@code code} names the lang key, the args are Strings, numbers or {@link Location}s. */
	public record Finding(String code, List<Object> args) {
		public static Finding of(String code, Object... args) {
			return new Finding(code, List.of(args));
		}
	}

	public record BlockCheck(String blockId, @Nullable Location at, Verdict verdict, List<Finding> findings) {
		public boolean has(String code) {
			for (Finding f : findings) {
				if (f.code().equals(code))return true;
			}
			return false;
		}
	}

	/**
	 * Explains whether {@code block} can join a network. With a position the placed block is checked
	 * (its inventory on every side, filters, multiblock, current network), without one only what the
	 * block type decides.
	 */
	public static BlockCheck checkBlock(Block block, @Nullable ServerLevel level, @Nullable BlockPos pos) {
		boolean placed = level != null && pos != null;
		BlockState state = placed ? level.getBlockState(pos) : block.defaultBlockState();
		if (placed)block = state.getBlock();
		ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
		Location at = placed ? new Location(pos.immutable(), level.dimension()) : null;
		List<Finding> f = new ArrayList<>();
		if (block instanceof IInventoryCable || block instanceof AbstractStorageTerminalBlock) {
			f.add(Finding.of("network_block"));
			if (placed)addNetwork(f, level, pos);
			return new BlockCheck(id.toString(), at, Verdict.NETWORK_BLOCK, f);
		}
		Config cfg = Config.get();
		boolean trim = block instanceof ITrim;
		if (trim)f.add(Finding.of("trim"));
		boolean skipTag = state.is(StorageTags.INV_CONNECTOR_SKIP);
		boolean cfgBlock = cfg.getBlockedBlocks().contains(block);
		boolean cfgMod = cfg.getBlockedMods().contains(id.getNamespace());
		if (skipTag)f.add(Finding.of("skip_tag"));
		if (cfgBlock)f.add(Finding.of("config_block"));
		if (cfgMod)f.add(Finding.of("config_mod", id.getNamespace()));
		if (state.is(StorageTags.INV_CONFIG_SKIP))f.add(Finding.of("configurator_skip"));

		Boolean inventory;
		if (placed) {
			List<String> faces = new ArrayList<>();
			for (Direction d : DIRS) {
				if (Platform.hasItemInventory(level, pos, state, d))faces.add(d.getName());
			}
			inventory = !faces.isEmpty();
			if (inventory)f.add(Finding.of(faces.size() == DIRS.length ? "inventory_all" : "inventory_faces", String.join(", ", faces), Platform.INVENTORY_API));
			else f.add(Finding.of("no_inventory", Platform.INVENTORY_API));
		} else if (isVanillaContainer(block)) {
			// Decided by the block class: no block entity is created, the command has no side effects
			inventory = true;
			f.add(Finding.of("container"));
		} else if (block instanceof EntityBlock) {
			// Whether a block entity exposes items is only known from a real one in the world
			inventory = null;
			f.add(Finding.of("block_entity"));
		} else {
			inventory = null;
			f.add(Finding.of("no_block_entity"));
		}
		if (block instanceof ChestBlock)f.add(Finding.of("multiblock_chest"));
		boolean excluded = false;
		if (placed) {
			BlockPos other = otherChestHalf(level, state, pos);
			if (other != null)f.add(Finding.of("multiblock", 1));
			BlockFilter filter = BlockFilter.findExistingAt(level, pos);
			if (filter != null) {
				excluded = filter.skip();
				f.add(excluded ? Finding.of("filter_excluded") : Finding.of("filter", filter.getPriority().name().toLowerCase(java.util.Locale.ROOT)));
			}
		}
		f.add(Finding.of("multiblock_handlers", Config.getMultiblockInvs().size()));
		if (cfg.onlyTrims && !trim)f.add(Finding.of("only_trims"));

		Verdict v;
		boolean blacklisted = skipTag || cfgBlock || cfgMod;
		if (trim && !Boolean.TRUE.equals(inventory))v = Verdict.TRIM;
		else if (Boolean.FALSE.equals(inventory))v = Verdict.CANNOT_JOIN;
		else if (excluded)v = Verdict.EXCLUDED;
		else if (blacklisted)v = Verdict.CABLE_CONNECTOR_ONLY;
		else if (inventory == null)v = Verdict.CHECK_PLACED;
		else v = Verdict.JOINS;
		if (v == Verdict.JOINS)f.add(Finding.of("range", cfg.invConnectorScanRange));
		if (v == Verdict.JOINS || v == Verdict.CHECK_PLACED)f.add(Finding.of("cable_connector"));
		if (v == Verdict.CABLE_CONNECTOR_ONLY)f.add(Finding.of("cable_connector_only"));
		if (placed)addNetwork(f, level, pos);
		return new BlockCheck(id.toString(), at, v, f);
	}

	/**
	 * Vanilla blocks whose block entity is a vanilla container, which Fabric's Transfer API and
	 * Forge's item handler capability both expose as an item inventory. Judged by class only.
	 */
	public static boolean isVanillaContainer(Block block) {
		return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock
				|| block instanceof AbstractFurnaceBlock || block instanceof HopperBlock || block instanceof DispenserBlock
				|| block instanceof BrewingStandBlock;
	}

	private static void addNetwork(List<Finding> f, ServerLevel level, BlockPos pos) {
		InventoryConnectorBlockEntity c = find(level, pos);
		if (c != null)f.add(Finding.of("in_network", new Location(c.getBlockPos(), level.dimension())));
		else f.add(Finding.of("not_in_network"));
	}

	/** Dimension key of a report, for position components. */
	public static ResourceKey<Level> dimensionOf(NetworkReport r) {
		return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(r.dimension()));
	}
}
