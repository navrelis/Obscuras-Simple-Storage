package com.obscura.storage.block.entity;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.StorageTags;
import com.obscura.storage.api.MultiblockInventoryAPI;
import com.obscura.storage.block.IInventoryNode;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.inventory.BlockFilter;
import com.obscura.storage.inventory.FrameLabels;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IInventoryAccess.IInventory;
import com.obscura.storage.inventory.IInventoryConnectorReference;
import com.obscura.storage.inventory.InventoryCableNetwork;
import com.obscura.storage.inventory.MultiInventoryAccess;
import com.obscura.storage.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.obscura.storage.inventory.PlatformMultiInventoryAccess;
import com.obscura.storage.platform.PlatformBlockEntity;
import com.obscura.storage.util.BlockFace;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.util.InventoryConnectorConfigUtil;
import com.obscura.storage.util.TickerUtil.TickableServer;

public class InventoryConnectorBlockEntity extends PlatformBlockEntity implements TickableServer, IInventoryConnector, IInventory, IInventoryConnectorReference, ComparatorOutput.Source {
	private MultiInventoryAccess handler = new PlatformMultiInventoryAccess();
	private Map<BlockFace, BlockInventoryAccess> invAccesses = new HashMap<>();
	private Set<IInventoryAccess> connectedInvs = new HashSet<>();
	private Set<IInventoryConnector> linkedConnectors = new HashSet<>();
	private Set<BlockFace> interfaces = new HashSet<>();

	// OPT-003: Pooled scan collections
	private final Map<BlockPos, Direction> poolConnected = new HashMap<>();
	private final Set<BlockFilter> poolBlockFilters = new HashSet<>();
	private final ArrayDeque<BlockPos> poolToCheck = new ArrayDeque<>();
	private final Set<BlockPos> poolCheckedBlocks = new HashSet<>();
	private Set<BlockFace> poolInterfaces = new HashSet<>();

	// OPT-002: Cable network generation tracking
	private long lastCableGeneration = -1;
	/** Set on load and by neighbour updates: rescan the touching inventories at the next check. */
	private boolean rescanRequested = true;
	private boolean announced;
	private int lastFilterEpoch;
	/** Network Doctor timing window of this network, created on the first server tick. */
	private NetworkStats doctorStats;
	private final ComparatorOutput comparator = new ComparatorOutput();

	public InventoryConnectorBlockEntity(BlockPos p_155229_, BlockState p_155230_) {
		super(Content.connectorBE.get(), p_155229_, p_155230_);
	}

	/** A block next to the connector changed; picked up at the next 5-tick check instead of the heartbeat. */
	public void requestRescan() {
		rescanRequested = true;
	}

	/**
	 * Every 5 ticks (as before) the connector checks whether anything it depends on changed, and only
	 * then does the expensive part:
	 * <ul>
	 * <li>the block scan for touching inventories on a neighbour update, a block filter change (or a
	 * frame label change, see FrameLabels), or
	 * the 20-tick heartbeat (which catches changes further away along trims or chests);</li>
	 * <li>the cable network lookup when the cable topology changed (or every 5 s);</li>
	 * <li>the network rebuild, which is a no-op unless the member list actually changed. It used to
	 * run unconditionally, and each run made every open terminal regroup and resync everything.</li>
	 * </ul>
	 */
	@Override
	public void updateServer() {
		long time = level.getGameTime();
		int phase = (worldPosition.hashCode() & 0x7FFFFFFF) % 20;
		if(time % 5 == phase % 5) {
			NetworkStats stats = doctorStats;
			if (stats == null) {
				stats = doctorStats = new NetworkStats();
				handler.setDoctorStats(stats);
				NetworkDoctor.register(this);
			}
			long t0 = NetworkDoctor.begin(stats);
			try {
				check(time, phase, stats);
			} finally {
				NetworkDoctor.end(stats, time, t0);
			}
			if (time % 100 == phase)NetworkDoctor.checkWarning(this, stats, time);
		}
	}

	/** The 5-tick check of {@link #updateServer}, timed for the Network Doctor. */
	private void check(long time, int phase, NetworkStats stats) {
		if (!announced) {
			// A connector that just loaded (or was placed) is not in any cached cable network yet:
			// make the connectors sharing its cables look again.
			announced = true;
			InventoryCableNetwork.getNetwork(level).markNodeInvalid(worldPosition);
		}
		int filterEpoch = BlockFilter.getConfigEpoch();
		if (rescanRequested || filterEpoch != lastFilterEpoch || time % 20 == phase) {
			// Only triggered scans count as rescans for the Network Doctor, not the heartbeat
			if (rescanRequested || filterEpoch != lastFilterEpoch)stats.addRescan(time);
			rescanRequested = false;
			lastFilterEpoch = filterEpoch;
			detectTouchingInventories();
		}

		long currentGen = InventoryCableNetwork.getNetwork(level).getGeneration();
		boolean cableDirty = currentGen != lastCableGeneration;
		if (cableDirty || time % 100 == phase) {
			detectCableNetwork();
			lastCableGeneration = currentGen;
		} else {
			// An unloaded connector must not keep feeding its stale inventories into this network.
			linkedConnectors.removeIf(c -> c instanceof BlockEntity be && be.isRemoved());
		}

		if (handler.build(this, linkedConnectors))stats.addRebuild(time);
		// A comparator next to the connector follows the fill level; at most every 5 ticks, and only when one is there
		comparator.tick(level, worldPosition, this::getComparatorSignal);
	}

	private void detectCableNetwork() {
		linkedConnectors.clear();
		Collection<BlockPos> netBlocks = InventoryCableNetwork.getNetwork(level).getNetworkNodes(worldPosition);

		for (BlockPos p : netBlocks) {
			if (!level.isLoaded(p))continue;

			BlockEntity be = level.getBlockEntity(p);
			if (be == this)continue;
			if (be instanceof IInventoryConnector te)
				linkedConnectors.add(te);
		}
	}

	private void detectTouchingInventories() {
		BlockFilter connFilter = BlockFilter.getFilterAt(level, worldPosition);
		UnaryOperator<IInventoryAccess> wrapper = connFilter != null ? i -> connFilter.wrap(level, i) : UnaryOperator.identity();

		connectedInvs.clear();
		poolConnected.clear();
		poolBlockFilters.clear();
		poolInterfaces.clear();
		poolToCheck.clear();
		poolCheckedBlocks.clear();
		poolToCheck.push(worldPosition);
		poolCheckedBlocks.add(worldPosition);
		int maxRange = Config.get().invConnectorScanRange;
		maxRange *= maxRange;
		boolean onlyTrims = Config.get().onlyTrims;

		Consumer<BlockPos> mbCheck = opos -> {
			BlockFilter f = BlockFilter.getFilterAt(level, opos);
			if (f != null)poolBlockFilters.add(f);
			if (!onlyTrims)poolToCheck.push(opos);
			poolCheckedBlocks.add(opos);
		};

		while (!poolToCheck.isEmpty()) {
			BlockPos cp = poolToCheck.pop();
			for (Direction d : Direction.values()) {
				BlockPos p = cp.relative(d);
				if(!poolCheckedBlocks.contains(p) && p.distSqr(worldPosition) < maxRange) {
					poolCheckedBlocks.add(p);
					BlockState state = level.getBlockState(p);
					if(state.is(StorageTags.TRIMS)) {
						poolToCheck.push(p);
					} else if(state.getBlock() instanceof IInventoryNode) {
						poolInterfaces.add(new BlockFace(p, Direction.DOWN));
						poolToCheck.push(p);
					} else if(InventoryConnectorConfigUtil.canConnect(state) && BlockInventoryAccess.hasInventoryAt(level, p, state, d.getOpposite())) {
						BlockFilter f = BlockFilter.getFilterAt(level, p);
						if (f != null)poolBlockFilters.add(f);
						poolConnected.put(p, d.getOpposite());
						if (!onlyTrims)poolToCheck.push(p);

						MultiblockInventoryAPI.EVENT.invoker().detectMultiblocks(level, p, state, mbCheck);
					}
				}
			}
		}

		poolBlockFilters.forEach(f -> f.getConnectedBlocks().forEach(poolConnected::remove));

		Map<BlockFace, BlockInventoryAccess> invA = new HashMap<>();
		poolConnected.forEach((p, d) -> {
			BlockFace s = new BlockFace(p, d);
			BlockInventoryAccess acc = invAccesses.remove(s);
			if (acc == null) {
				acc = new BlockInventoryAccess();
				acc.onLoad(level, p, d, this);
			}
			invA.put(s, acc);
			// Glow frames on the block (or the other half of its double chest) label it
			connectedInvs.add(wrapper.apply(FrameLabels.apply(level, p, acc)));
		});
		poolBlockFilters.forEach(f -> {
			if (f.skip())return;
			// A Configurator filter wins over frame labels on the blocks it covers
			FrameLabels.markOverridden(level, f.getConnectedBlocks());
			BlockFace s = new BlockFace(f.getMainPos(), f.getSide());
			BlockInventoryAccess acc = invAccesses.remove(s);
			if (acc == null) {
				acc = new BlockInventoryAccess();
				acc.onLoad(level, f.getMainPos(), f.getSide(), this);
			}
			invA.put(s, acc);
			connectedInvs.add(wrapper.apply(f.wrap(level, acc)));
		});
		invAccesses.values().forEach(IInventoryAccess::markInvalid);
		invAccesses.clear();
		invAccesses = invA;

		if (!this.interfaces.equals(poolInterfaces)) {
			var net = InventoryCableNetwork.getNetwork(level);
			this.interfaces.forEach(net::markNodeInvalid);
			Set<BlockFace> temp = this.interfaces;
			this.interfaces = poolInterfaces;
			poolInterfaces = temp;
			net.markNodeInvalid(worldPosition);
		}

		for (BlockFace blockFace : this.interfaces) {
			if (level.getBlockEntity(blockFace.pos()) instanceof InventoryInterfaceBlockEntity ii)
				ii.setConnectorAccess(this);
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		invAccesses.clear();
		handler.clear();
	}

	public UsageInfo getUsage() {
		return new UsageInfo(handler.getInventoryCount(), handler.getSlotCount(), handler.getFreeSlotCount());
	}

	public record UsageInfo(int blocks, int all, int free) {}

	/** Network Doctor timing window of this network, null until it ticked on the server. */
	@Override
	public NetworkStats doctorStats() {
		return doctorStats;
	}

	public NetworkStats getDoctorStats() {
		return doctorStats;
	}

	/** The merged network access (members, counts), for the Network Doctor. */
	public MultiInventoryAccess getNetworkHandler() {
		return handler;
	}

	/** How full the network is, 0 to 15. Cached in the network handler, see {@link MultiInventoryAccess#getFillSignal}. */
	@Override
	public int getComparatorSignal() {
		return level == null ? 0 : handler.getFillSignal(level);
	}

	public int getInventoryCount() {
		return handler.getInventoryCount();
	}

	public int getSlotCount() {
		return handler.getSlotCount();
	}

	@Override
	public IInventoryAccess getMergedHandler() {
		return handler;
	}

	@Override
	public Set<IInventoryAccess> getConnectedInventories() {
		return connectedInvs;
	}

	public Set<BlockFace> getInterfaces() {
		return interfaces;
	}

	@Override
	public boolean hasConnectedInventories() {
		return !isRemoved() && (!connectedInvs.isEmpty() || !linkedConnectors.isEmpty());
	}

	@Override
	public IInventoryAccess getInventoryAccess() {
		return handler;
	}

	public List<BlockPos> getConnectedBlocks() {
		return invAccesses.keySet().stream().map(b -> b.pos()).toList();
	}

	@Override
	public Collection<IInventoryConnector> getConnectedConnectors() {
		return linkedConnectors;
	}

	@Override
	public IInventoryConnector getConnectorRef() {
		return this;
	}
}
