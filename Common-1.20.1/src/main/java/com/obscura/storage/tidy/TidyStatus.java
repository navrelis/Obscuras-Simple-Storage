package com.obscura.storage.tidy;

/**
 * The Tidy button's state as the terminal menu syncs it (one int): the state in the low byte, a value
 * (the cooldown seconds) above it. Shared by server and client.
 */
public final class TidyStatus {
	public static final int IDLE = 0, PLANNING = 1, RUNNING = 2, DISABLED = 3, NOTHING = 4, COOLDOWN = 5, OTHER = 6, NO_NETWORK = 7;

	private TidyStatus() {}

	public static int encode(int state, int value) {
		return (state & 0xFF) | (Math.max(0, Math.min(value, 0xFFFFFF)) << 8);
	}

	public static int state(int status) {
		return status & 0xFF;
	}

	public static int value(int status) {
		return status >>> 8;
	}

	/** A job runs on the network: the button shows its progress and a click stops it. */
	public static boolean isRunning(int status) {
		int s = state(status);
		return s == PLANNING || s == RUNNING;
	}

	/** The button can be clicked (to start or to stop). */
	public static boolean isClickable(int status) {
		int s = state(status);
		return s == IDLE || isRunning(status);
	}
}
