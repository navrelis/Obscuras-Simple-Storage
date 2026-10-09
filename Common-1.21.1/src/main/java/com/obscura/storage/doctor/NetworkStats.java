package com.obscura.storage.doctor;

import java.util.Arrays;

/**
 * Rolling 30 second timing window of one storage network, for the Network Doctor.
 *
 * <p>The window is kept as 30 one-second buckets indexed by game time, so recording is a couple of
 * array writes: no allocation, no locking (only the server thread records). Each bucket holds the
 * nanoseconds spent on the network in that second, the most expensive single tick of that second,
 * and the rescan, rebuild and sync packet counters. Queries sum the buckets that still lie inside
 * the window, so old data falls out by itself and a network that went idle reads 0.
 *
 * <p>Plain Java on purpose (no game classes): the window math is unit tested on its own.
 */
public final class NetworkStats {
	public static final int TICKS_PER_SECOND = 20;
	public static final int WINDOW_SECONDS = 30;
	public static final int WINDOW_TICKS = WINDOW_SECONDS * TICKS_PER_SECOND;
	/** A network is logged at most once per this many ticks (10 minutes). */
	public static final long WARN_INTERVAL_TICKS = 10 * 60 * TICKS_PER_SECOND;
	/** A slow-network warning needs at least this much measured time, so one heavy first scan does not trigger it. */
	public static final long WARN_MIN_WINDOW_TICKS = 10 * TICKS_PER_SECOND;
	private static final long NONE = Long.MIN_VALUE;

	private final long[] bucketSecond = new long[WINDOW_SECONDS];
	private final long[] workNanos = new long[WINDOW_SECONDS];
	private final long[] peakTickNanos = new long[WINDOW_SECONDS];
	private final int[] samples = new int[WINDOW_SECONDS];
	private final int[] rescans = new int[WINDOW_SECONDS];
	private final int[] rebuilds = new int[WINDOW_SECONDS];
	private final int[] packets = new int[WINDOW_SECONDS];
	private final long[] bytes = new long[WINDOW_SECONDS];
	/** The tick the current per-tick sum belongs to, see {@link #addWork}. */
	private long curTick = NONE, curTickNanos;
	/** First tick recorded since creation or {@link #reset}; the window never reaches back before it. */
	private long startTick = NONE;
	private long lastWarnTick = NONE;

	public NetworkStats() {
		Arrays.fill(bucketSecond, NONE);
	}

	/** Records {@code nanos} of work done for this network in game tick {@code tick}. */
	public void addWork(long tick, long nanos) {
		if (nanos < 0)nanos = 0;
		if (tick != curTick) {
			foldCurrentTick();
			curTick = tick;
			curTickNanos = 0;
		}
		curTickNanos += nanos;
		int b = bucket(tick);
		workNanos[b] += nanos;
		samples[b]++;
	}

	/** A triggered block scan (neighbour update or configuration change, not the periodic heartbeat). */
	public void addRescan(long tick) {
		rescans[bucket(tick)]++;
	}

	/** A rebuild that changed the network's member list (every open terminal regroups after one). */
	public void addRebuild(long tick) {
		rebuilds[bucket(tick)]++;
	}

	/** Terminal sync packets sent to players watching this network. */
	public void addPackets(long tick, int count, long byteCount) {
		int b = bucket(tick);
		packets[b] += count;
		bytes[b] += byteCount;
	}

	/** Drops everything measured so far. */
	public void reset() {
		Arrays.fill(bucketSecond, NONE);
		Arrays.fill(workNanos, 0);
		Arrays.fill(peakTickNanos, 0);
		Arrays.fill(samples, 0);
		Arrays.fill(rescans, 0);
		Arrays.fill(rebuilds, 0);
		Arrays.fill(packets, 0);
		Arrays.fill(bytes, 0);
		curTick = NONE;
		curTickNanos = 0;
		startTick = NONE;
	}

	private int bucket(long tick) {
		if (startTick == NONE || tick < startTick)startTick = tick;
		long sec = Math.floorDiv(tick, TICKS_PER_SECOND);
		int i = (int) Math.floorMod(sec, (long) WINDOW_SECONDS);
		if (bucketSecond[i] != sec) {
			bucketSecond[i] = sec;
			workNanos[i] = 0;
			peakTickNanos[i] = 0;
			samples[i] = 0;
			rescans[i] = 0;
			rebuilds[i] = 0;
			packets[i] = 0;
			bytes[i] = 0;
		}
		return i;
	}

	/** Moves the finished tick's total into its bucket's peak. */
	private void foldCurrentTick() {
		if (curTick == NONE)return;
		long sec = Math.floorDiv(curTick, TICKS_PER_SECOND);
		int i = (int) Math.floorMod(sec, (long) WINDOW_SECONDS);
		if (bucketSecond[i] == sec && curTickNanos > peakTickNanos[i])peakTickNanos[i] = curTickNanos;
	}

	private boolean inWindow(int i, long nowSec) {
		long s = bucketSecond[i];
		return s != NONE && s <= nowSec && s > nowSec - WINDOW_SECONDS;
	}

	/**
	 * Game ticks the window covers at {@code now}: from the start of the oldest second in the window
	 * (or the first recorded tick, when that is later) up to and including {@code now}. 0 without data.
	 */
	public long windowTicks(long now) {
		if (startTick == NONE || now < startTick)return 0;
		long nowSec = Math.floorDiv(now, TICKS_PER_SECOND);
		long from = Math.max(startTick, (nowSec - WINDOW_SECONDS + 1) * TICKS_PER_SECOND);
		return Math.min(WINDOW_TICKS, Math.max(1, now - from + 1));
	}

	public double windowSeconds(long now) {
		return windowTicks(now) / (double) TICKS_PER_SECOND;
	}

	public long totalNanos(long now) {
		long nowSec = Math.floorDiv(now, TICKS_PER_SECOND);
		long sum = 0;
		for (int i = 0; i < WINDOW_SECONDS; i++) {
			if (inWindow(i, nowSec))sum += workNanos[i];
		}
		return sum;
	}

	/** Number of timed work units in the window (how many times something was measured). */
	public int samples(long now) {
		long nowSec = Math.floorDiv(now, TICKS_PER_SECOND);
		int sum = 0;
		for (int i = 0; i < WINDOW_SECONDS; i++) {
			if (inWindow(i, nowSec))sum += samples[i];
		}
		return sum;
	}

	/** Average time per game tick over the window, idle ticks included. */
	public double avgNanosPerTick(long now) {
		long t = windowTicks(now);
		return t == 0 ? 0 : totalNanos(now) / (double) t;
	}

	public double avgMsPerTick(long now) {
		return avgNanosPerTick(now) / 1_000_000.0;
	}

	/** The most expensive single tick in the window. */
	public long peakNanos(long now) {
		long nowSec = Math.floorDiv(now, TICKS_PER_SECOND);
		long max = 0;
		for (int i = 0; i < WINDOW_SECONDS; i++) {
			if (inWindow(i, nowSec) && peakTickNanos[i] > max)max = peakTickNanos[i];
		}
		// The tick in progress has not been folded into its bucket yet
		if (curTick != NONE && curTick <= now && Math.floorDiv(curTick, TICKS_PER_SECOND) > nowSec - WINDOW_SECONDS && curTickNanos > max)max = curTickNanos;
		return max;
	}

	public double peakMs(long now) {
		return peakNanos(now) / 1_000_000.0;
	}

	private long sum(int[] a, long now) {
		long nowSec = Math.floorDiv(now, TICKS_PER_SECOND);
		long sum = 0;
		for (int i = 0; i < WINDOW_SECONDS; i++) {
			if (inWindow(i, nowSec))sum += a[i];
		}
		return sum;
	}

	/**
	 * Ticks to divide counts by for a rate: the window, but at least {@link #WARN_MIN_WINDOW_TICKS}, so
	 * the one scan a network does when it loads does not read as 1,200 rescans a minute.
	 */
	private long rateTicks(long now) {
		long t = windowTicks(now);
		return t == 0 ? 0 : Math.max(t, WARN_MIN_WINDOW_TICKS);
	}

	private double perMinute(long count, long now) {
		long t = rateTicks(now);
		return t == 0 ? 0 : count * (60.0 * TICKS_PER_SECOND) / t;
	}

	private double perSecond(long count, long now) {
		long t = rateTicks(now);
		return t == 0 ? 0 : count * (double) TICKS_PER_SECOND / t;
	}

	public double rescansPerMinute(long now) {
		return perMinute(sum(rescans, now), now);
	}

	public double rebuildsPerMinute(long now) {
		return perMinute(sum(rebuilds, now), now);
	}

	public double packetsPerSecond(long now) {
		return perSecond(sum(packets, now), now);
	}

	public double bytesPerSecond(long now) {
		long nowSec = Math.floorDiv(now, TICKS_PER_SECOND);
		long b = 0;
		for (int i = 0; i < WINDOW_SECONDS; i++) {
			if (inWindow(i, nowSec))b += bytes[i];
		}
		return perSecond(b, now);
	}

	/**
	 * Whether the server log should get a warning about this network now, and if so remembers that it
	 * got one: at most once per {@link #WARN_INTERVAL_TICKS}.
	 *
	 * @param warnMs average ms per tick at or above which the network is slow, 0 or less = off
	 * @param warnInventories inventory count above which the network is too big, 0 or less = off
	 * @param inventories the network's current inventory count
	 */
	public boolean shouldWarn(long now, double warnMs, int warnInventories, int inventories) {
		boolean slow = warnMs > 0 && windowTicks(now) >= WARN_MIN_WINDOW_TICKS && avgMsPerTick(now) >= warnMs;
		boolean big = warnInventories > 0 && inventories > warnInventories;
		if (!slow && !big)return false;
		if (lastWarnTick != NONE && now - lastWarnTick < WARN_INTERVAL_TICKS && now >= lastWarnTick)return false;
		lastWarnTick = now;
		return true;
	}
}
