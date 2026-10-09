package com.obscura.storage.doctor;

/**
 * A block that works on a storage network without being part of its inventory (terminals, hoppers,
 * level emitters, Ejectors, Disposals, Exporters). Lets the Network Doctor count what is attached to
 * a network.
 */
public interface INetworkMember {
	/**
	 * Whether this block currently reads from or writes to the network owning {@code network}. Only
	 * called by the Network Doctor's commands, not per tick, so it may resolve its network afresh.
	 */
	boolean isOnNetwork(NetworkStats network);
}
