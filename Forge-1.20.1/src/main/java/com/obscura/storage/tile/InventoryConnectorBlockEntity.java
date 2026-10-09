package com.obscura.storage.tile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.StorageTags;
import com.obscura.storage.block.ITrim;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.doctor.ScanCounters;
import com.obscura.storage.util.BlockFilter;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.util.IInventoryLink;
import com.obscura.storage.util.IProxy;
import com.obscura.storage.util.InfoHandler;
import com.obscura.storage.util.InventoryConnectorConfigUtil;
import com.obscura.storage.util.ItemHandlers;
import com.obscura.storage.util.LinkGraph;
import com.obscura.storage.util.BlockFilterData;
import com.obscura.storage.util.FilteredStorage;
import com.obscura.storage.util.FrameLabel;
import com.obscura.storage.util.FrameLabels;
import com.obscura.storage.util.LabelledStorage;
import com.obscura.storage.util.MergedStorage;
import com.obscura.storage.util.NetworkChanges;
import com.obscura.storage.util.Priority;
import com.obscura.storage.util.RemoteConnections;
import com.obscura.storage.util.RemoteConnections.Channel;
import com.obscura.storage.util.TickerUtil.TickableServer;

public class InventoryConnectorBlockEntity extends BlockEntity implements TickableServer, ComparatorOutput.Source {
	/** Ticks after which a network is rescanned even when nothing reported a change. */
	private static final int HEARTBEAT = 20;
	private static final Direction[] DIRECTIONS = Direction.values();
	private MergedStorage handlers = new MergedStorage();
	/** The inventories of this network without the ones reached over Inventory Links: what the links provide to other networks. */
	private MergedStorage localHandlers = new MergedStorage();
	private final LocalView localView = new LocalView();
	/** The Inventory Link ends that are part of this network. */
	private final List<IInventoryLink> links = new ArrayList<>();
	private List<LinkedInv> linkedInvs = new ArrayList<>();
	/** The positions of the inventories in this network, nothing else (no trims, cables or air). */
	private final Set<BlockPos> connectedBlocks = new HashSet<>();
	// Scratch collections of the scan, kept so a scan doesn't allocate them again
	private final Stack<BlockPos> toCheck = new Stack<>();
	private final Set<BlockPos> checkedBlocks = new HashSet<>();
	private final List<LocalInv> localInventories = new ArrayList<>();
	private final Set<LinkedInv> toRM = new HashSet<>();
	private final ArrayDeque<BlockPos> skipPending = new ArrayDeque<>();
	private long lastScan, lastGeneration;
	private boolean scanned, rescanRequested;
	/** Network Doctor timing window of this network, created on the first server tick. */
	private NetworkStats doctorStats;
	private final ScanCounters doctorCounters = new ScanCounters();
	/** The members before the running scan, to tell whether it changed them. */
	private final List<IItemHandler> prevParts = new ArrayList<>();
	/** Cached {@link #getInventoryCount()}, -1 after a scan until it is asked for again. */
	private int inventoryCount = -1;
	private final ComparatorOutput comparator = new ComparatorOutput();
	private final IntSupplier fillSignal = this::getComparatorSignal;

	public InventoryConnectorBlockEntity(BlockPos pos, BlockState state) {
		super(Content.connectorTile.get(), pos, state);
	}

	@Override
	public void updateServer() {
		long time = level.getGameTime();
		int hash = worldPosition.hashCode() & 0x7FFFFFFF;
		if((time + hash) % 5 == 0) {
			NetworkStats stats = doctorStats;
			if(stats == null) {
				stats = doctorStats = new NetworkStats();
				handlers.setDoctorStats(stats);
				localHandlers.setDoctorStats(stats);
				NetworkDoctor.register(this);
			}
			// Rescan when something reported a change (cable, trim, neighbour, filter), otherwise only as a heartbeat
			long generation = NetworkChanges.get();
			boolean triggered = rescanRequested || generation != lastGeneration;
			if(!scanned || triggered || time < lastScan || time - lastScan >= HEARTBEAT) {
				long t0 = NetworkDoctor.begin(stats);
				try {
					scan(time, generation, triggered, stats);
				} finally {
					NetworkDoctor.end(stats, time, t0);
				}
			}
			// A comparator next to the connector follows the fill level; at most every 5 ticks, and only when one is there
			comparator.tick(level, worldPosition, fillSignal);
			if((time + hash) % 100 == 0)NetworkDoctor.checkWarning(this, stats, time);
		}
	}

	/** The network scan of {@link #updateServer}, timed for the Network Doctor. */
	private void scan(long time, long generation, boolean triggered, NetworkStats stats) {
		// Only triggered scans count as rescans for the Network Doctor, not the heartbeat
		if(triggered)stats.addRescan(time);
		prevParts.clear();
		prevParts.addAll(handlers.getStorages());
		doctorCounters.reset();
		scanned = true;
		rescanRequested = false;
		lastScan = time;
		lastGeneration = generation;
		toCheck.clear();
		checkedBlocks.clear();
		localInventories.clear();
		connectedBlocks.clear();
		toRM.clear();
		BlockFilterData filterData = BlockFilterData.get(level);
		toCheck.add(worldPosition);
		checkedBlocks.add(worldPosition);
		handlers.clear();
		localHandlers.clear();
		links.clear();
		Collections.sort(linkedInvs);
		for (LinkedInv inv : linkedInvs) {
			if(inv.time + 40 < time) {
				toRM.add(inv);
				continue;
			}
			if(inv.link != null) {
				links.add(inv.link);
				continue;
			}
			IItemHandler w = inv.handler.get();
			if(w != null) {
				IItemHandler ihr = IProxy.resolve(w);
				if(ihr instanceof MergedStorage ih) {
					if(checkHandlers(ih, 0)) {
						addLoopWarning();
						continue;
					}
				}
				// Sorted together with the inventories this scan finds, so a block filter's priority on a
				// cable connector ranks it among the directly connected inventories. The sort is stable:
				// at equal priority the cable-connected ones stay first, as before.
				localInventories.add(new LocalInv(inv.priority, w, inv.pos));
			}
		}
		linkedInvs.removeAll(toRM);
		int range = StorageMod.CONFIG.invConnectorScanRange * StorageMod.CONFIG.invConnectorScanRange;
		double rangeSq = range;
		while(!toCheck.isEmpty()) {
			BlockPos cp = toCheck.pop();
			for (Direction d : DIRECTIONS) {
				BlockPos p = cp.relative(d);
				if(!checkedBlocks.contains(p) && p.distSqr(worldPosition) < range) {
					checkedBlocks.add(p);
					if(!level.isLoaded(p))continue;
					BlockState state = level.getBlockState(p);
					if(state.isAir()) {
						// A block filter whose block vanished (explosion, piston, other mods) is cleaned up here
						filterData.onBlockRemoved(level, p);
						continue;
					}
					if(state.getBlock() instanceof ITrim) {
						toCheck.add(p);
					} else {
						if (state.is(StorageTags.INV_CONNECTOR_SKIP))
							continue;
						if (!InventoryConnectorConfigUtil.canConnect(state.getBlock()))
							continue;
						BlockEntity te = level.getBlockEntity(p);
						if (te instanceof InventoryConnectorBlockEntity || te instanceof InventoryProxyBlockEntity || te instanceof AbstractInventoryCableConnectorBlockEntity) {
							continue;
						} else if(te != null && !StorageMod.CONFIG.onlyTrims) {
							IItemHandler inv = ItemHandlers.ofBlockEntity(te, d.getOpposite());
							BlockPos otherHalf = null;
							if(te instanceof ChestBlockEntity) {//Check for double chests
								Block block = state.getBlock();
								if(block instanceof ChestBlock) {
									ChestType type = state.getValue(ChestBlock.TYPE);
									if (type != ChestType.SINGLE) {
										BlockPos opos = p.relative(ChestBlock.getConnectedDirection(state));
										BlockState ostate = this.getLevel().getBlockState(opos);
										if (state.getBlock() == ostate.getBlock()) {
											ChestType otype = ostate.getValue(ChestBlock.TYPE);
											if (otype != ChestType.SINGLE && type != otype && state.getValue(ChestBlock.FACING) == ostate.getValue(ChestBlock.FACING)) {
												toCheck.add(opos);
												checkedBlocks.add(opos);
												otherHalf = opos;
											}
										}
									}
								}
							}
							if(inv != null) {
								IItemHandler wr = inv;
								BlockFilter blockFilter = filterData.findContaining(level, p);
								if (blockFilter != null && blockFilter.skip()) {
									toCheck.add(p);
									continue;
								}
								Priority pr;
								if (blockFilter != null) {
									// A Configurator filter wins over frame labels on the blocks it covers
									FrameLabels.markOverridden(level, p, otherHalf, false);
									wr = blockFilter.wrap(level, wr, FilteredStorage::new);
									pr = blockFilter.getPriority();
								} else {
									// Glow frames on the block (or the other half of its double chest) label it
									wr = FrameLabels.apply(level, p, otherHalf, false, wr, LabelledStorage::new);
									pr = wr instanceof FrameLabel.Labelled ? FrameLabel.PRIORITY : Priority.NORMAL;
								}

								IItemHandler ihr = IProxy.resolve(wr);
								if(ihr instanceof MergedStorage ih) {
									if(checkHandlers(ih, 0)) {
										addLoopWarning();
										continue;
									}
								}
								toCheck.add(p);
								connectedBlocks.add(p);
								if(otherHalf != null) {
									connectedBlocks.add(otherHalf);
									doctorCounters.sameStorage++;
								}
								localInventories.add(new LocalInv(pr, wr, p));
							}

							if(Config.getMultiblockInvs().contains(state.getBlock())) {
								skipBlocks(p, toCheck, state.getBlock(), rangeSq);
							}
						}
					}
				}
			}
		}
		localInventories.sort((a, b) -> -a.priority().compareTo(b.priority()));
		// Network Doctor inventory cap (doctorMaxInventories, off by default): the lowest priority inventories go first
		int cap = Config.get().doctorMaxInventories;
		int members = handlers.getStorages().size();
		for (LocalInv h : localInventories) {
			if(cap > 0 && handlers.getStorages().size() - members >= cap) {
				doctorCounters.overCap(h.pos());
				continue;
			}
			handlers.add(h.storage(), h.pos());
			localHandlers.add(h.storage(), h.pos());
		}
		localInventories.clear();
		if(!links.isEmpty()) {
			// Every link end provides and accesses: merge the local inventories of every network reachable over the links
			for (InventoryConnectorBlockEntity other : LinkGraph.reachable(this, InventoryConnectorBlockEntity::linkedNetworks)) {
				if(checkHandlers(other.localHandlers, 0)) {
					addLoopWarning();
					continue;
				}
				handlers.add(other.localView);
			}
		}
		handlers.refresh();
		localHandlers.refresh();
		toCheck.clear();
		checkedBlocks.clear();
		doctorCounters.reachedTwice = handlers.getDuplicatesSkipped();
		inventoryCount = -1;
		// A rebuild that changed the member list: every open terminal regroups after one
		if(!handlers.getStorages().equals(prevParts))stats.addRebuild(time);
	}

	private void skipBlocks(BlockPos pos, Stack<BlockPos> edges, Block block, double rangeSq) {
		ArrayDeque<BlockPos> pending = skipPending;
		pending.clear();
		pending.push(pos);
		edges.add(pos);
		while(!pending.isEmpty()) {
			BlockPos cp = pending.pop();
			for (Direction d : DIRECTIONS) {
				BlockPos p = cp.relative(d);
				if(!checkedBlocks.contains(p) && p.distSqr(pos) < rangeSq) {
					if(!level.isLoaded(p))continue;
					BlockState state = level.getBlockState(p);
					if(state.getBlock() == block) {
						checkedBlocks.add(p);
						connectedBlocks.add(p);
						edges.add(p);
						pending.push(p);
					}
				}
			}
		}
	}

	private void addLoopWarning() {
		doctorCounters.network++;
		if(!handlers.getStorages().contains(InfoHandler.INSTANCE))handlers.add(InfoHandler.INSTANCE);
	}

	/**
	 * The networks the Inventory Link ends of this network reach directly. Links work both ways, so every end of a
	 * channel sees the same ends; {@link LinkGraph} walks them without visiting a network twice.
	 */
	private List<InventoryConnectorBlockEntity> linkedNetworks() {
		if(isRemoved() || !(level instanceof ServerLevel sl))return Collections.emptyList();
		List<InventoryConnectorBlockEntity> out = new ArrayList<>();
		RemoteConnections rc = RemoteConnections.get(sl);
		for (IInventoryLink link : links) {
			if(!(link instanceof InventoryCableConnectorBlockEntity be) || be.isRemoved() || !(be.getLevel() instanceof ServerLevel bl))continue;
			UUID ch = link.getChannel();
			if(ch == null)continue;
			Channel chn = rc.getChannel(ch);
			if(chn == null)continue;
			for (IInventoryLink other : chn.findOthers(bl, be.getBlockPos(), be.getBeaconLevel())) {
				InventoryConnectorBlockEntity net = other.getLinkedNetwork();
				if(net != null && !net.isRemoved())out.add(net);
			}
		}
		return out;
	}

	private boolean checkHandlers(MergedStorage ih, int depth) {
		if(depth > 3 || ih == handlers || ih == localHandlers)return true;
		for (IItemHandler lo : ih.getStorages()) {
			IItemHandler ihr = IProxy.resolve(lo);
			if(ihr == handlers || ihr == localHandlers)return true;
			if(ihr instanceof MergedStorage i) {
				if(checkHandlers(i, depth+1))return true;
			}
		}
		return false;
	}

	public void addLinked(LinkedInv inv) {
		linkedInvs.add(inv);
		rescanRequested = true;
	}

	/** Something this network depends on (a linked connector's target) changed, rescan at the next opportunity. */
	public void requestRescan() {
		rescanRequested = true;
	}

	private static record LocalInv(Priority priority, IItemHandler storage, BlockPos pos) {}

	public static class LinkedInv implements Comparable<LinkedInv> {
		public Supplier<IItemHandler> handler;
		/** Block the handler reads from, or null when it has none (Item Locator). */
		public BlockPos pos;
		public long time;
		public Priority priority = Priority.NORMAL;
		/** Set when the connector is an Inventory Link end: the network then merges what its channel reaches. */
		public IInventoryLink link;

		@Override
		public int compareTo(LinkedInv o) {
			return -priority.compareTo(o.priority);
		}
	}

	public void unLink(LinkedInv linv) {
		if(linkedInvs.remove(linv))rescanRequested = true;
	}

	public IItemHandler getInventory() {
		return handlers;
	}

	public Set<BlockPos> getConnectedBlocks() {
		return connectedBlocks;
	}

	/** Network Doctor timing window of this network, null until it ticked on the server. */
	public NetworkStats getDoctorStats() {
		return doctorStats;
	}

	/** What the last scan left out while merging (duplicates, the inventory cap), for the Network Doctor. */
	public ScanCounters getDoctorCounters() {
		return doctorCounters;
	}

	/** The merged network storage (members, totals), for the Network Doctor. */
	public MergedStorage getNetworkStorage() {
		return handlers;
	}

	/** Inventories in the network, cached until the next scan. */
	public int getInventoryCount() {
		int c = inventoryCount;
		if(c < 0)inventoryCount = c = countInventories(handlers, Collections.newSetFromMap(new IdentityHashMap<>()));
		return c;
	}

	/** Slots of the whole network. */
	public int getSlotCount() {
		return getUsage().all();
	}

	/** How full the network is, 0 to 15. Cached in the network storage, see {@link MergedStorage#getFillSignal}. */
	@Override
	public int getComparatorSignal() {
		return level == null ? 0 : handlers.getFillSignal(level);
	}

	public UsageInfo getUsage() {
		int[] slots = new int[2];
		Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		ItemHandlers.forEach(handlers, (stack, count) -> {
			if(!stack.isEmpty() && !seen.add(stack))return true;
			slots[0]++;
			if(count <= 0)slots[1]++;
			return true;
		});
		return new UsageInfo(slots[0], slots[1], countInventories(handlers, Collections.newSetFromMap(new IdentityHashMap<>())));
	}

	private static int countInventories(MergedStorage m, Set<MergedStorage> visited) {
		if(!visited.add(m))return 0;
		int c = 0;
		for (IItemHandler part : m.getStorages()) {
			if(part == InfoHandler.INSTANCE)continue;
			IItemHandler root = IProxy.resolve(part);
			if(root == null)continue;
			if(root instanceof MergedStorage nested)c += countInventories(nested, visited);
			else c++;
		}
		return c;
	}

	/**
	 * @param all slots of the whole network
	 * @param free empty slots
	 * @param blocks inventories
	 */
	public static record UsageInfo(int all, int free, int blocks) {}

	/**
	 * What this network provides over Inventory Links: its local inventories, read live. A proxy of its own so a
	 * network merging it can tell which network (and dimension) the parts come from.
	 */
	public class LocalView implements IItemHandler, IProxy, ItemHandlers.Delegating {

		public InventoryConnectorBlockEntity getOwner() {
			return InventoryConnectorBlockEntity.this;
		}

		@Override
		public IItemHandler get() {
			return localHandlers;
		}

		@Override
		public IItemHandler getDelegate() {
			return localHandlers;
		}

		@Override
		public int getSlots() {
			return localHandlers.getSlots();
		}

		@Override
		public @NotNull ItemStack getStackInSlot(int slot) {
			return localHandlers.getStackInSlot(slot);
		}

		@Override
		public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
			return localHandlers.insertItem(slot, stack, simulate);
		}

		@Override
		public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
			return localHandlers.extractItem(slot, amount, simulate);
		}

		@Override
		public int getSlotLimit(int slot) {
			return localHandlers.getSlotLimit(slot);
		}

		@Override
		public boolean isItemValid(int slot, @NotNull ItemStack stack) {
			return localHandlers.isItemValid(slot, stack);
		}
	}

	private LazyOptional<IItemHandler> capability;

	@Override
	public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
		if (!this.remove && cap == ForgeCapabilities.ITEM_HANDLER) {
			if (capability == null)capability = LazyOptional.of(() -> handlers);
			return capability.cast();
		}
		return super.getCapability(cap, side);
	}

	@Override
	public void invalidateCaps() {
		super.invalidateCaps();
		if (capability != null) {
			capability.invalidate();
			capability = null;
		}
	}
}
