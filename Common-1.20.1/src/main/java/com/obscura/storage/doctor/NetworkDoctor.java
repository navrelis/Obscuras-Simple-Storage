package com.obscura.storage.doctor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.obscura.storage.Config;
import com.obscura.storage.StorageMod;
import com.obscura.storage.gui.StorageTerminalMenu;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.IProxy;
import com.obscura.storage.util.MergedStorage;

/**
 * Network Doctor bookkeeping: which networks are loaded, which blocks work on them, and the timing
 * hooks the network code calls.
 *
 * <p><b>Timing.</b> Each network (keyed by its Inventory Connector) has a {@link NetworkStats} window.
 * The network's own work brackets itself with {@link #begin}/{@link #end}: two {@code System.nanoTime}
 * reads around a unit of real work (a connector scan, a cable connector check, a terminal list read or
 * sync, a hopper transfer attempt, a level emitter check, an Ejector, Disposal or Exporter attempt),
 * nothing on idle ticks and no allocation.
 * Timers do not nest: work that runs inside an already timed unit (the network read a hopper's Keep
 * Stock count triggers) is part of the outer unit, so nothing is counted twice. Sub-blocks find their
 * network through the storage they already resolved, which carries its owner's window
 * ({@link #statsOf}), so attributing work costs a couple of type checks.
 *
 * <p>Everything here runs on the server thread only; timers started on any other thread are ignored.
 */
public final class NetworkDoctor {
	/** {@link #begin} result: not timed, {@link #end} does nothing. */
	public static final long OFF = Long.MIN_VALUE;
	/** {@link #begin} result: inside another timed unit, counted there. */
	public static final long NESTED = Long.MIN_VALUE + 1;

	private static int depth;
	private static volatile Thread serverThread;
	/** Loaded Inventory Connectors (the networks). Weak: an unloaded block entity just drops out. */
	private static final Set<InventoryConnectorBlockEntity> CONNECTORS = Collections.newSetFromMap(new WeakHashMap<>());
	/** Loaded terminals, hoppers, level emitters and interfaces ({@link INetworkMember}), for the attached counts. */
	private static final Set<BlockEntity> MEMBERS = Collections.newSetFromMap(new WeakHashMap<>());

	private NetworkDoctor() {}

	/**
	 * Starts timing a unit of work for {@code stats}' network.
	 *
	 * @return the value to hand to {@link #end}
	 */
	public static long begin(NetworkStats stats) {
		if (stats == null || Thread.currentThread() != serverThread)return OFF;
		if (depth++ > 0)return NESTED;
		return System.nanoTime();
	}

	/** Ends a unit started with {@link #begin}; {@code stats} must be the same object. */
	public static void end(NetworkStats stats, long gameTime, long t0) {
		if (t0 == OFF)return;
		depth--;
		if (t0 != NESTED)stats.addWork(gameTime, System.nanoTime() - t0);
	}

	/** Runs {@code r} without booking any of it to a network (the doctor's own reads). */
	public static void runUntimed(Runnable r) {
		depth++;
		try {
			r.run();
		} finally {
			depth--;
		}
	}

	/**
	 * The timing window of the network {@code access} belongs to, null when it is not a network. Follows
	 * proxies (cable connectors, filters), so a block reading through one is booked to the right network.
	 *
	 * @param access a {@code Storage<ItemVariant>} (Fabric) or an {@code IItemHandler} (Forge), or null
	 */
	public static NetworkStats statsOf(Object access) {
		for (int i = 0; i < 8 && access != null; i++) {
			if (access instanceof MergedStorage m)return m.getDoctorStats();
			if (!(access instanceof IProxy p))return null;
			access = p.get();
		}
		return null;
	}

	/** A connector started ticking on the server. */
	public static void register(InventoryConnectorBlockEntity connector) {
		serverThread = Thread.currentThread();
		CONNECTORS.add(connector);
	}

	/** A terminal, hopper, level emitter or interface ticked on the server for the first time. */
	public static <T extends BlockEntity & INetworkMember> void track(T member) {
		MEMBERS.add(member);
	}

	/** Server stopping: nothing of a closed world may survive into the next one. */
	public static void clear() {
		CONNECTORS.clear();
		MEMBERS.clear();
		serverThread = null;
		depth = 0;
	}

	private static boolean isLive(BlockEntity be, MinecraftServer server) {
		Level l = be.getLevel();
		return !be.isRemoved() && l != null && !l.isClientSide && l.getServer() == server;
	}

	/** Loaded networks of {@code server}. */
	public static List<InventoryConnectorBlockEntity> connectors(MinecraftServer server) {
		List<InventoryConnectorBlockEntity> out = new ArrayList<>();
		for (InventoryConnectorBlockEntity c : CONNECTORS) {
			if (c.getDoctorStats() != null && isLive(c, server))out.add(c);
		}
		return out;
	}

	/** Loaded terminals, hoppers, level emitters and interfaces of {@code server}. */
	public static List<BlockEntity> members(MinecraftServer server) {
		List<BlockEntity> out = new ArrayList<>();
		for (BlockEntity be : MEMBERS) {
			if (isLive(be, server))out.add(be);
		}
		return out;
	}

	/** The loaded connector owning {@code stats}, or null. */
	public static InventoryConnectorBlockEntity connectorOf(NetworkStats stats, MinecraftServer server) {
		if (stats == null)return null;
		for (InventoryConnectorBlockEntity c : connectors(server)) {
			if (c.getDoctorStats() == stats)return c;
		}
		return null;
	}

	/** Players with a terminal of this network open (wireless terminals included). */
	public static int playersWatching(NetworkStats stats, MinecraftServer server) {
		int n = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.containerMenu instanceof StorageTerminalMenu m) {
				StorageTerminalBlockEntity te = m.getTerminal();
				if (te != null && te.isOnNetwork(stats))n++;
			}
		}
		return n;
	}

	/**
	 * Logs a warning when a network is slow ({@code doctorWarnMs}) or big ({@code doctorWarnInventories}),
	 * at most once per network per 10 minutes. Called by the connector every 5 seconds.
	 */
	public static void checkWarning(InventoryConnectorBlockEntity connector, NetworkStats stats, long now) {
		Config c = Config.get();
		double warnMs = c.doctorWarnMs;
		int warnInv = c.doctorWarnInventories;
		if (warnMs <= 0 && warnInv <= 0)return;
		int inv = connector.getInventoryCount();
		if (!stats.shouldWarn(now, warnMs, warnInv, inv))return;
		Level l = connector.getLevel();
		StorageMod.LOGGER.warn("[Network Doctor] Storage network at {} {} {} in {}: {} ms/tick average (peak {} ms) over the last {} s, {} inventories, {} slots, {} rescans/min (warn at doctorWarnMs = {}, doctorWarnInventories = {}). Run /{} doctor at {} {} {} for details.",
				connector.getBlockPos().getX(), connector.getBlockPos().getY(), connector.getBlockPos().getZ(),
				l != null ? l.dimension().location() : "?",
				DoctorFormat.ms(stats.avgMsPerTick(now)), DoctorFormat.ms(stats.peakMs(now)), DoctorFormat.rate(stats.windowSeconds(now)),
				DoctorFormat.count(inv), DoctorFormat.count(connector.getSlotCount()), DoctorFormat.rate(stats.rescansPerMinute(now)),
				warnMs, warnInv, StorageMod.modid,
				connector.getBlockPos().getX(), connector.getBlockPos().getY(), connector.getBlockPos().getZ());
	}
}
