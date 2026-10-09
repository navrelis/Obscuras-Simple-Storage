package com.obscura.storage.tidy;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import com.obscura.storage.Config;
import com.obscura.storage.StorageMod;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.gui.StorageTerminalMenu;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.MergedStorage;
import com.obscura.storage.util.NetworkChanges;
import com.obscura.storage.util.StorageSnapshot;

/**
 * The Tidy button's server side: one {@link TidyJob} per network (keyed by its Inventory Connector's
 * Network Doctor window), the cooldown and "nothing to tidy" memory per network, the click handling and
 * the state every open terminal shows.
 *
 * <p>Everything runs on the server thread. Nothing is saved: jobs, cooldowns and memories end with the
 * server.
 */
public final class TidyManager {
	/** Ticks between two clicks of one player that are taken into account (double clicks, spam). */
	private static final int CLICK_INTERVAL = 5;
	/** How often (ticks) a "nothing to tidy" result is checked against the network's change signature. */
	private static final int NOTHING_RECHECK = 20;
	/**
	 * A "nothing to tidy" result is forgotten after this many ticks. The change signature (see
	 * {@link #signature}) sees item totals, free slots and network changes, but not a stack moved by hand
	 * from one chest to another, so the memory must not stick.
	 */
	private static final int NOTHING_EXPIRE = 30 * 20;

	private static final Map<NetworkStats, TidyJob> JOBS = new LinkedHashMap<>();
	/** Per network memory. Weak: a network that unloads takes its memory with it. */
	private static final Map<NetworkStats, Memo> MEMO = new WeakHashMap<>();
	private static final Map<UUID, Integer> LAST_CLICK = new HashMap<>();

	private TidyManager() {}

	public enum Result { STARTED, STOPPED, DISABLED, NOT_ALLOWED, COOLDOWN, NOTHING, OTHER_RUNNING, NO_NETWORK, ALREADY_RUNNING, NOT_RUNNING }

	static final class Memo {
		int cooldownUntil = Integer.MIN_VALUE;
		/** The network's change signature when a run found nothing to do; Long.MIN_VALUE when not set. */
		long nothingTracker = Long.MIN_VALUE;
		int nothingCheckedAt, nothingSetAt;
		WeakReference<InventoryConnectorBlockEntity> connector;
		/** The run (of an overlapping network) that kept this network from starting. Weak: never keeps a job alive. */
		WeakReference<TidyJob> blockedBy;
		Summary last;
	}

	/** How a run ended, kept per network after the job itself is gone (no references into the world). */
	public record Summary(TidyJob.End end, int done, int processed, int planned, int freed, long noRoom, int maxMovesInTick) {
		static Summary of(TidyJob j) {
			return new Summary(j.end(), j.done(), j.processed(), j.planned(), j.freed(), j.noRoom(), j.maxMovesInTick());
		}
	}

	private static Memo memo(NetworkStats s) {
		return MEMO.computeIfAbsent(s, k -> new Memo());
	}

	// ------------------------------------------------------------------ ticking

	/** End of every server tick. */
	public static void tick(MinecraftServer server) {
		if (JOBS.isEmpty())return;
		int now = server.getTickCount();
		List<TidyJob> ended = null;
		for (Iterator<TidyJob> it = JOBS.values().iterator(); it.hasNext();) {
			TidyJob j = it.next();
			if (j.level.getServer() != server) {
				it.remove();
				continue;
			}
			long t0 = NetworkDoctor.begin(j.stats);
			try {
				j.tick(now);
			} catch (RuntimeException e) {
				StorageMod.LOGGER.error("Tidy on the network at {} failed, stopping it", j.connector.getBlockPos(), e);
				j.finish(TidyJob.End.GONE);
			} finally {
				NetworkDoctor.end(j.stats, j.level.getGameTime(), t0);
			}
			if (j.phase() == TidyJob.Phase.DONE) {
				it.remove();
				if (ended == null)ended = new ArrayList<>(1);
				ended.add(j);
			}
		}
		if (ended != null)for (TidyJob j : ended)ended(j, server, now);
	}

	/** Server stopping: nothing of a closed world may survive into the next one. */
	public static void clear() {
		JOBS.clear();
		MEMO.clear();
		LAST_CLICK.clear();
	}

	private static void ended(TidyJob j, MinecraftServer server, int now) {
		Memo m = memo(j.stats);
		m.last = Summary.of(j);
		m.connector = new WeakReference<>(j.connector);
		if (j.end() == TidyJob.End.NOTHING) {
			m.nothingTracker = signature(j.connector, true);
			m.nothingCheckedAt = now;
			m.nothingSetAt = now;
		} else {
			m.nothingTracker = Long.MIN_VALUE;
			m.cooldownUntil = now + Math.max(0, Config.get().tidyCooldownSeconds) * 20;
		}
		Component msg = endMessage(j);
		for (ServerPlayer p : audience(j, server))p.displayClientMessage(msg, true);
	}

	static Component endMessage(TidyJob j) {
		String k = "chat.obscuras_storage.tidy.";
		MutableComponent c = switch (j.end()) {
		case FINISHED -> Component.translatable(k + "finished", j.done(), Math.max(0, j.freed()));
		case STOPPED -> Component.translatable(k + "stopped", j.done(), Math.max(0, j.freed()));
		case TIMEOUT -> Component.translatable(k + "timeout", j.maxTicks / 20, j.done(), Math.max(0, j.freed()));
		case NOTHING -> Component.translatable(k + "nothing");
		case GONE -> Component.translatable(k + "gone");
		};
		c.withStyle(j.end() == TidyJob.End.GONE ? ChatFormatting.RED : ChatFormatting.GREEN);
		if (j.noRoom() > 0 && j.end() != TidyJob.End.NOTHING && j.end() != TidyJob.End.GONE) {
			c.append(" ").append(Component.translatable(k + "no_room", j.noRoom()).withStyle(ChatFormatting.YELLOW));
		}
		return c;
	}

	/** The player who started the run and everyone with a terminal of the network open. */
	private static List<ServerPlayer> audience(TidyJob j, MinecraftServer server) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (j.starter != null && j.starter.equals(p.getUUID())) {
				out.add(p);
			} else if (p.containerMenu instanceof StorageTerminalMenu menu) {
				StorageTerminalBlockEntity te = menu.getTerminal();
				if (te != null && (te.getDoctorStats() == j.stats || te.isOnNetwork(j.stats)))out.add(p);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ start / stop

	/**
	 * Starts a run on the network of {@code connector}.
	 *
	 * @param player who started it (permission check, end message), null for none (tests, automation)
	 */
	public static Result start(InventoryConnectorBlockEntity connector, ServerPlayer player) {
		Config cfg = Config.get();
		if (!cfg.tidyEnabled)return Result.DISABLED;
		if (player != null && !mayUse(player))return Result.NOT_ALLOWED;
		NetworkStats st = connector == null || connector.isRemoved() ? null : connector.getDoctorStats();
		if (st == null || !(connector.getLevel() instanceof ServerLevel level))return Result.NO_NETWORK;
		if (JOBS.containsKey(st))return Result.ALREADY_RUNNING;
		MinecraftServer server = level.getServer();
		int now = server.getTickCount();
		Memo m = MEMO.get(st);
		if (m != null) {
			if (now < m.cooldownUntil)return Result.COOLDOWN;
			if (nothingStill(m, now, true))return Result.NOTHING;
		}
		Set<Object> seen = new HashSet<>();
		List<TidyMember> members = new ArrayList<>();
		MergedStorage network = connector.getNetworkStorage();
		for (Object a : network.getStorages()) {
			TidyMember tm = TidyMember.resolve(level, a, network.positionOf(a), cfg.tidyIncludeDocks, seen);
			if (tm != null)members.add(tm);
		}
		for (TidyJob o : JOBS.values()) {
			if (o.level == level && o.overlaps(seen)) {
				memo(st).blockedBy = new WeakReference<>(o);
				return Result.OTHER_RUNNING;
			}
		}
		Memo mm = memo(st);
		mm.blockedBy = null;
		mm.connector = new WeakReference<>(connector);
		TidyJob j = new TidyJob(connector, st, level, player != null ? player.getUUID() : null, now,
				cfg.tidyMovesPerTick, cfg.tidyMaxSeconds, members, seen);
		JOBS.put(st, j);
		return Result.STARTED;
	}

	/** Stops the run on the network {@code stats}; the moves made stay. */
	public static Result stop(NetworkStats stats, ServerPlayer player) {
		TidyJob j = stats == null ? null : JOBS.get(stats);
		if (j == null)return Result.NOT_RUNNING;
		if (player != null && !mayUse(player))return Result.NOT_ALLOWED;
		JOBS.remove(stats);
		j.finish(TidyJob.End.STOPPED);
		ended(j, j.level.getServer(), j.level.getServer().getTickCount());
		return Result.STOPPED;
	}

	/** Whether {@code player} may start and stop runs (tidyOpsOnly). */
	public static boolean mayUse(ServerPlayer player) {
		return !Config.get().tidyOpsOnly || player.hasPermissions(2);
	}

	/**
	 * The Tidy button of an open terminal was clicked (DataPacket key {@code tidy}: true = start, false =
	 * stop, as the client saw the button). Validated here: the menu must still be usable, the player
	 * allowed, the server switch on, no cooldown.
	 */
	public static void onClick(ServerPlayer player, StorageTerminalMenu menu, boolean start) {
		StorageTerminalBlockEntity te = menu.getTerminal();
		if (te == null || player.isSpectator() || !menu.stillValid(player))return;
		int now = player.server.getTickCount();
		Integer last = LAST_CLICK.get(player.getUUID());
		if (last != null && now - last >= 0 && now - last < CLICK_INTERVAL)return;
		LAST_CLICK.put(player.getUUID(), now);
		NetworkStats st = te.getDoctorStats();
		Result r;
		if (start) {
			InventoryConnectorBlockEntity c = NetworkDoctor.connectorOf(st, player.server);
			r = c == null ? Result.NO_NETWORK : start(c, player);
		} else {
			r = stop(st, player);
		}
		Component feedback = switch (r) {
		case DISABLED, NOT_ALLOWED -> Component.translatable("tooltip.obscuras_storage.tidy.disabled.server");
		case COOLDOWN -> Component.translatable("tooltip.obscuras_storage.tidy.disabled.cooldown", cooldownSeconds(MEMO.get(st), now));
		case NOTHING -> Component.translatable("tooltip.obscuras_storage.tidy.disabled.nothing");
		case OTHER_RUNNING -> Component.translatable("tooltip.obscuras_storage.tidy.disabled.other");
		case NO_NETWORK -> Component.translatable("tooltip.obscuras_storage.tidy.disabled.no_network");
		default -> null;
		};
		if (feedback != null)player.displayClientMessage(feedback.copy().withStyle(ChatFormatting.YELLOW), true);
	}

	private static int cooldownSeconds(Memo m, int now) {
		return m == null ? 0 : Math.max(0, (m.cooldownUntil - now + 19) / 20);
	}

	/**
	 * What changes when a network does: the global network change generation (blocks, cables, filters), the
	 * item totals' version, and the slot and free slot count of the shared snapshot. With {@code refresh}
	 * the snapshot is read again first (at most once per tick); without it the last read by a terminal
	 * or emitter is used.
	 */
	private static long signature(InventoryConnectorBlockEntity c, boolean refresh) {
		MergedStorage st = c.getNetworkStorage();
		StorageSnapshot s = st.getSnapshot();
		if (refresh && c.getLevel() != null)s.refresh(st, c.getLevel().getGameTime(), 1);
		long h = NetworkChanges.get();
		h = h * 31 + s.getVersion();
		h = h * 31 + s.getSlotCount();
		h = h * 31 + s.getFreeSlotCount();
		return h == Long.MIN_VALUE ? 0 : h;
	}

	/**
	 * Whether the last run of the network found nothing to do and the network did not change since.
	 * Looks at the change signature at most every {@link #NOTHING_RECHECK} ticks, or right away when asked.
	 */
	private static boolean nothingStill(Memo m, int now, boolean force) {
		if (m.nothingTracker == Long.MIN_VALUE)return false;
		if (now < m.nothingSetAt || now - m.nothingSetAt >= NOTHING_EXPIRE) {
			m.nothingTracker = Long.MIN_VALUE;
			return false;
		}
		if (!force && now - m.nothingCheckedAt < NOTHING_RECHECK && now >= m.nothingCheckedAt)return true;
		m.nothingCheckedAt = now;
		InventoryConnectorBlockEntity c = m.connector != null ? m.connector.get() : null;
		if (c == null || c.isRemoved() || c.getLevel() == null) {
			m.nothingTracker = Long.MIN_VALUE;
			return false;
		}
		if (signature(c, force) != m.nothingTracker) {
			m.nothingTracker = Long.MIN_VALUE;
			return false;
		}
		return true;
	}

	// ------------------------------------------------------------------ what the terminals show

	/**
	 * The button state for a terminal menu, into {@code out}: status ({@link TidyStatus}), moves done,
	 * moves planned, moves per tick. Called once per tick per open terminal; map lookups only.
	 */
	public static void fillStatus(StorageTerminalBlockEntity te, ServerPlayer player, int[] out) {
		Config cfg = Config.get();
		out[3] = cfg.tidyMovesPerTick;
		NetworkStats st = te != null ? te.getDoctorStats() : null;
		TidyJob j = st != null ? JOBS.get(st) : null;
		if (j != null) {
			out[0] = TidyStatus.encode(j.publishedRunning() ? TidyStatus.RUNNING : TidyStatus.PLANNING, 0);
			out[1] = j.publishedDone();
			out[2] = j.publishedPlanned();
			return;
		}
		out[1] = 0;
		out[2] = 0;
		if (!cfg.tidyEnabled || !mayUse(player)) {
			out[0] = TidyStatus.encode(TidyStatus.DISABLED, 0);
			return;
		}
		if (st == null) {
			out[0] = TidyStatus.encode(TidyStatus.NO_NETWORK, 0);
			return;
		}
		Memo m = MEMO.get(st);
		if (m != null) {
			if (m.blockedBy != null) {
				TidyJob b = m.blockedBy.get();
				if (b != null && JOBS.containsValue(b)) {
					out[0] = TidyStatus.encode(TidyStatus.OTHER, 0);
					return;
				}
				m.blockedBy = null;
			}
			int now = player.server.getTickCount();
			if (now < m.cooldownUntil) {
				out[0] = TidyStatus.encode(TidyStatus.COOLDOWN, cooldownSeconds(m, now));
				return;
			}
			if (nothingStill(m, now, false)) {
				out[0] = TidyStatus.encode(TidyStatus.NOTHING, 0);
				return;
			}
		}
		out[0] = TidyStatus.encode(TidyStatus.IDLE, 0);
	}

	// ------------------------------------------------------------------ lookups (doctor, tests)

	/** The running job of a network, null when none. */
	public static TidyJob jobFor(NetworkStats stats) {
		return stats == null ? null : JOBS.get(stats);
	}

	/** How the last run of a network ended (tests), null when none. */
	public static Summary lastRun(NetworkStats stats) {
		Memo m = stats == null ? null : MEMO.get(stats);
		return m != null ? m.last : null;
	}

	/** The running job of the network whose connector is at {@code pos} in {@code dim}, null when none. */
	public static TidyJob jobAt(ResourceKey<Level> dim, BlockPos pos) {
		for (TidyJob j : JOBS.values()) {
			if (j.level.dimension() == dim && j.connector.getBlockPos().equals(pos))return j;
		}
		return null;
	}

	/** Forgets the cooldown and "nothing to tidy" of a network (tests). */
	public static void resetMemory(NetworkStats stats) {
		MEMO.remove(stats);
	}
}
