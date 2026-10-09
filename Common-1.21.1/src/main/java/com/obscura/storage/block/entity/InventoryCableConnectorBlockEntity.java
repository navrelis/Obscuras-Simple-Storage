package com.obscura.storage.block.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.api.MultiblockInventoryAPI;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.doctor.NetworkDoctor;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.inventory.BlockFilter;
import com.obscura.storage.inventory.FrameLabels;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IInventoryConnectorReference;
import com.obscura.storage.inventory.IInventoryLink;
import com.obscura.storage.inventory.InventoryCableNetwork;
import com.obscura.storage.inventory.MultiInventoryAccess;
import com.obscura.storage.inventory.PlatformInventoryAccess;
import com.obscura.storage.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.obscura.storage.inventory.PlatformMultiInventoryAccess;
import com.obscura.storage.inventory.RemoteConnections;
import com.obscura.storage.inventory.RemoteConnections.Channel;
import com.obscura.storage.item.IItemFilter;
import com.obscura.storage.menu.InventoryLinkMenu;
import com.obscura.storage.migration.LegacyData;
import com.obscura.storage.util.BeaconLevelCalc;
import com.obscura.storage.util.TickerUtil.TickableServer;

public class InventoryCableConnectorBlockEntity extends PaintedBlockEntity implements MenuProvider, TickableServer, IInventoryConnector, IInventoryLink {
	private static final String CHANNEL_TAG = "channel";
	/**
	 * The 9 filter slots of the Filtered Inventory Cable Connector of the 1.20.1 builds. That block was
	 * removed; 1.20.1 turned each one into a normal connector on its first tick and dropped the filter
	 * items it held. One that was never loaded again in 1.20.1 is loaded as a normal connector here (registry
	 * alias, see LegacyIds#renamedPath) and drops them on its first tick, like 1.20.1 did.
	 */
	private static final String LEGACY_FILTER_TAG = "filter";
	private List<ItemStack> legacyFilterItems = List.of();
	private BlockInventoryAccess block = new BlockInventoryAccess();
	private MultiInventoryAccess mergedHandler = new PlatformMultiInventoryAccess();
	private Set<IInventoryConnector> linkedConnectors = new HashSet<>();
	private Collection<IInventoryAccess> filteredMerge = Collections.emptyList();
	private IInventoryAccess self = block;
	private UUID channel = null;
	/** Level of the beacon this connector faces, 0 without one (a beacon without a beam counts as 0 too). */
	private int beaconLevel = 0;
	private boolean facesBeacon;
	private boolean linkMode;

	// OPT-002: Cable network generation tracking
	private long lastCableGeneration = -1;
	private Collection<BlockPos> cachedNetBlocks = Collections.emptyList();
	/** The connectors found on this connector's cables, the part of linkedConnectors that is cached. */
	private final Set<IInventoryConnector> cableConnectors = new HashSet<>();
	private boolean rescanRequested = true;
	private boolean announced;
	/**
	 * Network Doctor: the timing window of the Inventory Connector network this connector works for.
	 * {@link #cableOwner} is the one found on its cables (refreshed with the cable lookup), the
	 * others are picked per check depending on the mode.
	 */
	private NetworkStats doctorOwner, cableOwner;

	public InventoryCableConnectorBlockEntity(BlockPos p_155229_, BlockState p_155230_) {
		super(Content.cableConnectorBE.get(), p_155229_, p_155230_);
	}

	@Override
	public void onLoad() {
		super.onLoad();
		if (!level.isClientSide) {
			BlockState state = level.getBlockState(worldPosition);
			Direction facing = state.getValue(InventoryCableConnectorBlock.FACING);
			block.onLoad(level, worldPosition.relative(facing), facing.getOpposite(), this);
		}
	}

	@Override
	public AbstractContainerMenu createMenu(int p_39954_, Inventory p_39955_, Player p_39956_) {
		return new InventoryLinkMenu(p_39954_, p_39955_, this);
	}

	/** The block in front changed; the beacon level is measured again at the next check. */
	public void requestRescan() {
		rescanRequested = true;
	}

	/**
	 * Runs every 5 ticks like before, but no longer throws the network away each time: the merged
	 * handler is rebuilt in place and only reports a change when its member list really changed (it
	 * used to be cleared and rebuilt unconditionally, which made every terminal on it regroup and
	 * resync everything 4 times a second). The cable network is only looked at again when the
	 * cable topology changed (or every 5 s), and the beacon pyramid every 20 ticks or when the
	 * block in front changed.
	 */
	@Override
	public void updateServer() {
		if (!legacyFilterItems.isEmpty())dropLegacyFilterItems();
		long time = level.getGameTime();
		int phase = (worldPosition.hashCode() & 0x7FFFFFFF) % 20;
		if(time % 5 == phase % 5) {
			// Booked to the network found by the previous check (the first check is not timed)
			NetworkStats stats = doctorOwner;
			long t0 = NetworkDoctor.begin(stats);
			try {
				check(time, phase);
			} finally {
				NetworkDoctor.end(stats, time, t0);
			}
		}
	}

	/** The 5-tick check of {@link #updateServer}. */
	private void check(long time, int phase) {
		InventoryCableNetwork net = InventoryCableNetwork.getNetwork(level);
		if (!announced) {
			// Not in any cached cable network yet: make the connectors on its cables look again.
			announced = true;
			net.markNodeInvalid(worldPosition);
		}
		BlockState state = level.getBlockState(worldPosition);
		Direction facing = state.getValue(InventoryCableConnectorBlock.FACING);
		BlockPos pos = worldPosition.relative(facing);
		BlockState st = level.getBlockState(pos);
		long currentGen = net.getGeneration();
		if (currentGen != lastCableGeneration || time % 100 == phase) {
			lastCableGeneration = currentGen;
			cachedNetBlocks = net.getNetworkNodes(worldPosition);
			detectCableNetwork();
		} else {
			// An unloaded connector must not keep feeding its stale inventories into this network.
			cableConnectors.removeIf(c -> c instanceof BlockEntity be && be.isRemoved());
		}
		linkedConnectors.clear();
		linkedConnectors.addAll(cableConnectors);
		boolean beacon = st.is(Blocks.BEACON);
		boolean rescan = rescanRequested || time % 20 == phase || beacon != facesBeacon;
		rescanRequested = false;
		facesBeacon = beacon;
		if (beacon) {
			if (rescan)
				beaconLevel = BeaconLevelCalc.calcBeaconLevel(level, pos.getX(), pos.getY(), pos.getZ());
		} else {
			beaconLevel = 0;
		}
		Channel chn = channel != null ? RemoteConnections.get(level).getChannel(channel) : null;
		// Link mode: facing a beacon, or a channel that still exists is selected (link at level 0 then)
		linkMode = beacon || chn != null;
		NetworkStats owner = cableOwner;
		if (linkMode) {
			self = mergedHandler;
			if (chn != null) {
				chn.register((ServerLevel) level, worldPosition);
				Set<IInventoryLink> links = chn.findOthers((ServerLevel) level, worldPosition, beaconLevel);
				links.forEach(l -> linkedConnectors.add(l.getConnector()));
				// The far end of a link serves the network the other side belongs to
				if (owner == null) {
					for (IInventoryLink l : links) {
						owner = l.getConnector().doctorStats();
						if (owner != null)break;
					}
				}
			}
			mergedHandler.build(this, linkedConnectors);
		} else {
			BlockEntity be = level.getBlockEntity(pos);
			if (be instanceof IInventoryConnectorReference ref) {
				var inv = ref.getConnectorRef();
				if (inv != null) {
					self = mergedHandler;
					if (inv.doctorStats() != null)owner = inv.doctorStats();

					BlockFilter f = BlockFilter.getFilterAt(level, worldPosition);
					if (f != null) {
						// Collected without touching the handler: building the unfiltered list first
						// and the filtered one right after would report two changes every time.
						List<IInventoryAccess> invs = new ArrayList<>();
						for (IInventoryAccess a : mergedHandler.collect(inv, Collections.emptyList())) {
							invs.add(f.wrap(level, a));
						}
						filteredMerge = invs;
						mergedHandler.build(this, Collections.emptyList());
					} else {
						mergedHandler.build(inv, Collections.emptyList());
						filteredMerge = mergedHandler.getConnected();
					}
				} else {
					mergedHandler.clear();
					self = PlatformInventoryAccess.EMPTY;
				}
			} else {
				mergedHandler.clear();
				self = applyBlockFilters(pos);
			}
		}
		doctorOwner = owner;
		mergedHandler.setDoctorStats(owner);
	}

	/**
	 * The inventory this connector exposes for the plain block it points at, behind the Inventory
	 * Configurator's block filters: the one on the target block itself first, then the one on this
	 * connector, so an item has to pass both (a filter covering both blocks applies once). A filter
	 * with skip on either leaves the target out of the network. The priority is the connector's
	 * filter's when it has one, else the target's filter's, else normal. Without a filter on the
	 * target, glow frames on it label it (see FrameLabels), below the connector's own filter.
	 *
	 * <p>Runs every 5 ticks and allocates nothing: the lookups are component reads, the multiblock probe
	 * reuses one consumer and {@link BlockFilter#wrap} hands back its cached wrapper while nothing
	 * changed, so {@link #self} keeps its identity and the network sees no change.
	 */
	private IInventoryAccess applyBlockFilters(BlockPos target) {
		BlockFilter own = BlockFilter.getFilterAt(level, worldPosition);
		BlockFilter tgt = findFilterContaining(target);
		// Frame labels on the target only count while no Configurator filter covers it
		BlockFilter targetFilter = tgt;
		if (tgt == own)tgt = null;
		if (tgt != null && tgt.skip() || own != null && own.skip())return PlatformInventoryAccess.EMPTY;
		IInventoryAccess acc = block;
		if (targetFilter != null)FrameLabels.markOverridden(level, targetFilter.getConnectedBlocks());
		else acc = FrameLabels.apply(level, target, acc);
		if (tgt != null)acc = own != null ? tgt.wrapWithoutPriority(level, acc) : tgt.wrap(level, acc);
		if (own != null)acc = own.wrap(level, acc);
		return acc;
	}

	private BlockPos probeTarget;
	private BlockFilter probeResult;
	private final Consumer<BlockPos> multiblockProbe = p -> {
		if (probeResult != null)return;
		BlockFilter f = BlockFilter.getFilterAt(level, p);
		if (f != null && f.getConnectedBlocks().contains(probeTarget))probeResult = f;
	};

	/**
	 * The block filter covering {@code pos}: the one on that block, or one on another part of the
	 * multiblock it belongs to (a double chest's filter sits on the half that was configured).
	 */
	private BlockFilter findFilterContaining(BlockPos pos) {
		BlockFilter f = BlockFilter.getFilterAt(level, pos);
		if (f != null)return f;
		probeTarget = pos;
		probeResult = null;
		MultiblockInventoryAPI.EVENT.invoker().detectMultiblocks(level, pos, level.getBlockState(pos), multiblockProbe);
		f = probeResult;
		probeResult = null;
		return f;
	}

	private void detectCableNetwork() {
		cableConnectors.clear();
		cableOwner = null;
		InventoryConnectorBlockEntity ownerConnector = null;
		for (BlockPos p : cachedNetBlocks) {
			if (!level.isLoaded(p))continue;

			BlockEntity be = level.getBlockEntity(p);
			if (be == this)continue;
			if (be instanceof IInventoryConnector te)
				cableConnectors.add(te);
			// Network Doctor: an Inventory Connector on these cables owns this connector's work (the
			// lowest position when there are several, so the choice does not flip between checks)
			if (be instanceof InventoryConnectorBlockEntity ic && ic.doctorStats() != null && (ownerConnector == null || p.compareTo(ownerConnector.getBlockPos()) < 0))
				ownerConnector = ic;
		}
		if (ownerConnector != null)cableOwner = ownerConnector.doctorStats();
	}

	@Override
	public NetworkStats doctorStats() {
		return doctorOwner;
	}

	/** Linking through a beacon or a channel instead of exposing the block in front. */
	public boolean isLinkMode() {
		return linkMode;
	}

	/** The block this connector faces (reads from). */
	public BlockPos getTargetPos() {
		BlockState state = getBlockState();
		return state.hasProperty(InventoryCableConnectorBlock.FACING) ? worldPosition.relative(state.getValue(InventoryCableConnectorBlock.FACING)) : worldPosition;
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("menu.obscuras_storage.inventory_connector");
	}

	public boolean stillValid(Player player) {
		if(channel != null) {
			Channel chn = RemoteConnections.get(level).getChannel(channel);
			if(chn != null && !chn.publicChannel && !chn.owner.equals(player.getUUID()))
				return false;
		}
		if (this.level.getBlockEntity(this.worldPosition) != this) {
			return false;
		} else {
			return !(player.distanceToSqr(this.worldPosition.getX() + 0.5D, this.worldPosition.getY() + 0.5D, this.worldPosition.getZ() + 0.5D) > 64.0D);
		}
	}

	@Override
	public IInventoryAccess getMergedHandler() {
		return self;
	}

	@Override
	public Collection<IInventoryAccess> getConnectedInventories() {
		return linkMode || self == PlatformInventoryAccess.EMPTY ? Collections.emptyList() : self == mergedHandler ? filteredMerge : Collections.singleton(self);
	}

	@Override
	public boolean hasConnectedInventories() {
		return !isRemoved() && self != PlatformInventoryAccess.EMPTY;
	}

	@Override
	public void loadAdditional(CompoundTag nbt, HolderLookup.Provider provider) {
		super.loadAdditional(nbt, provider);
		if(nbt.contains(CHANNEL_TAG)) {
			channel = nbt.getUUID(CHANNEL_TAG);
		} else {
			channel = null;
		}
		legacyFilterItems = readLegacyFilterItems(nbt, provider);
	}

	@Override
	public void saveAdditional(CompoundTag nbt, HolderLookup.Provider provider) {
		super.saveAdditional(nbt, provider);
		if(channel != null) {
			nbt.putUUID(CHANNEL_TAG, channel);
		}
		if (!legacyFilterItems.isEmpty()) {
			// Not handed out yet (the block did not tick since it was loaded): keep them
			ListTag list = new ListTag();
			for (ItemStack s : legacyFilterItems)list.add(s.save(provider));
			nbt.put(LEGACY_FILTER_TAG, list);
		}
	}

	/** The filter items of a 1.20.1 Filtered Inventory Cable Connector (other entries were ghost copies). */
	private static List<ItemStack> readLegacyFilterItems(CompoundTag nbt, HolderLookup.Provider provider) {
		if (!nbt.contains(LEGACY_FILTER_TAG, Tag.TAG_LIST))return List.of();
		ListTag list = nbt.getList(LEGACY_FILTER_TAG, Tag.TAG_COMPOUND);
		List<ItemStack> out = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			ItemStack s = LegacyData.parseItemStack(provider, list.getCompound(i));
			if (s.getItem() instanceof IItemFilter)out.add(s);
		}
		return out.isEmpty() ? List.of() : out;
	}

	private void dropLegacyFilterItems() {
		for (ItemStack s : legacyFilterItems)
			Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, s);
		legacyFilterItems = List.of();
		setChanged();
	}

	/** The filter items a converted 1.20.1 Filtered Inventory Cable Connector still has to drop (tests). */
	public List<ItemStack> getLegacyFilterItems() {
		return legacyFilterItems;
	}

	@Override
	public IInventoryConnector getConnector() {
		return this;
	}

	@Override
	public boolean isAccessibleFrom(ServerLevel world, BlockPos blockPos, int level) {
		if (!linkMode)return false;
		Config c = Config.get();
		boolean sameDim = this.level.dimension().equals(world.dimension());
		return canLink(beaconLevel, level, sameDim, blockPos.distSqr(worldPosition), c.invLinkBeaconLvl, c.invLinkBeaconLvlSameDim, c.invLinkBeaconLvlCrossDim, c.invLinkBeaconRange);
	}

	/**
	 * Whether two link connectors can reach each other. A connector that does not face a beacon has level 0.
	 *
	 * @param levelA beacon level of one end
	 * @param levelB beacon level of the other end
	 * @param sameDimension whether both ends are in the same dimension
	 * @param distSq squared distance between the ends, only used in the same dimension
	 * @param cLocalLvl config invLinkBeaconLvl, -1 disables links entirely
	 * @param cSDLvl config invLinkBeaconLvlSameDim, level of the stronger end for unlimited range in the same dimension, -1 disables it
	 * @param cCDLvl config invLinkBeaconLvlCrossDim, level of the stronger end for any dimension, -1 disables it
	 * @param range config invLinkBeaconRange, range in blocks in the same dimension
	 */
	public static boolean canLink(int levelA, int levelB, boolean sameDimension, double distSq, int cLocalLvl, int cSDLvl, int cCDLvl, double range) {
		int lvl = Math.max(levelA, levelB);
		int lvlM = Math.min(levelA, levelB);
		if (cLocalLvl == -1)return false;
		if (cCDLvl != -1 && lvl >= cCDLvl && (lvlM > 0 || cCDLvl == 0))return true;
		if (!sameDimension)return false;
		if (cSDLvl != -1 && lvl >= cSDLvl && (lvlM > 0 || cSDLvl == 0))return true;
		return distSq < range * range;
	}

	@Override
	public UUID getChannel() {
		return channel;
	}

	public void setChannel(UUID chn) {
		this.channel = chn;
		setChanged();
	}

	public int getBeaconLevel() {
		return beaconLevel;
	}

	@Override
	public Collection<IInventoryConnector> getConnectedConnectors() {
		return linkedConnectors;
	}

	public boolean hasBeacon() {
		BlockState state = level.getBlockState(worldPosition);
		Direction facing = state.getValue(InventoryCableConnectorBlock.FACING);
		BlockPos pos = worldPosition.relative(facing);
		BlockState st = level.getBlockState(pos);
		return st.is(Blocks.BEACON);
	}
}
