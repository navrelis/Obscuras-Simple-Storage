package com.obscura.storage.tile;

import java.util.UUID;

import net.minecraftforge.items.IItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.gui.InventoryLinkMenu;
import com.obscura.storage.util.IInventoryLink;
import com.obscura.storage.util.NetworkChanges;
import com.obscura.storage.util.RemoteConnections;
import com.obscura.storage.util.RemoteConnections.Channel;

public class InventoryCableConnectorBlockEntity extends AbstractInventoryCableConnectorBlockEntity implements MenuProvider, IInventoryLink {
	private static final String CHANNEL_TAG = "channel";
	private UUID channel = null;
	/** Level of the beacon this connector faces, 0 without one (a beacon without a beam counts as 0 too). */
	private int beaconLevel = 0;
	private boolean hasBeacon;

	public InventoryCableConnectorBlockEntity(BlockPos pos, BlockState state) {
		super(Content.invCableConnectorTile.get(), pos, state);
	}

	@Override
	public void updateServer() {
		super.updateServer();
		if(!level.isClientSide && level.getGameTime() % 20 == 18) {
			BlockState state = level.getBlockState(worldPosition);
			Direction facing = state.getValue(InventoryCableConnectorBlock.FACING);
			BlockPos pos = this.worldPosition.relative(facing);
			BlockState st = level.getBlockState(pos);
			if(st.is(Blocks.BEACON)) {
				hasBeacon = true;
				beaconLevel = calcBeaconLevel(level, pos.getX(), pos.getY(), pos.getZ());
			} else {
				hasBeacon = false;
				beaconLevel = 0;
			}
		}
	}

	/**
	 * A connector links when it faces a beacon or when a channel that still exists is selected. Without a beacon it
	 * links at level 0, so only the range applies (see {@link #canLink}). A connector without a channel that does not
	 * face a beacon is a normal connector for whatever it points at.
	 */
	public boolean isLinkMode() {
		if(hasBeacon)return true;
		return channel != null && RemoteConnections.get(level).getChannel(channel) != null;
	}

	/**
	 * A link end points at nothing itself: it registers with its channel, and the network it is part of merges the
	 * local inventories of every network reachable over its links (see {@link InventoryConnectorBlockEntity}). So every
	 * link end both provides its network's inventories and accesses the others', like on 1.21.1.
	 */
	@Override
	protected IItemHandler getPointedAt(BlockPos pos, Direction facing) {
		if(isLinkMode()) {
			if(channel != null) {
				Channel chn = RemoteConnections.get(level).getChannel(channel);
				if(chn != null) {
					chn.register((ServerLevel) level, this.worldPosition);
				}
			}
			return null;
		}
		return super.getPointedAt(pos, facing);
	}

	@Override
	protected IInventoryLink getLink() {
		return isLinkMode() ? this : null;
	}

	public static int calcBeaconLevel(Level world, int x, int y, int z) {
		int i = 0;

		BlockEntity ent = world.getBlockEntity(new BlockPos(x, y, z));
		if(ent instanceof BeaconBlockEntity b) {
			if(b.getBeamSections().isEmpty())return 0;

			for(int j = 1; j <= 4; i = j++) {
				int k = y - j;
				if (k < world.getMinBuildHeight()) {
					break;
				}

				boolean flag = true;

				for(int l = x - j; l <= x + j && flag; ++l) {
					for(int i1 = z - j; i1 <= z + j; ++i1) {
						if (!world.getBlockState(new BlockPos(l, k, i1)).is(BlockTags.BEACON_BASE_BLOCKS)) {
							flag = false;
							break;
						}
					}
				}

				if (!flag) {
					break;
				}
			}
		}
		return i;
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory inv, Player player) {
		return new InventoryLinkMenu(syncId, inv, this);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("menu.obscuras_storage.inventory_connector");
	}

	@Override
	public void load(CompoundTag nbt) {
		super.load(nbt);
		if(nbt.contains(CHANNEL_TAG)) {
			channel = nbt.getUUID(CHANNEL_TAG);
		} else {
			channel = null;
		}
		// The "remote" flag of older versions (provide or access) is ignored: every link end does both now
	}

	@Override
	public void saveAdditional(CompoundTag nbt) {
		super.saveAdditional(nbt);
		if(channel != null) {
			nbt.putUUID(CHANNEL_TAG, channel);
		}
	}

	@Override
	public boolean isAccessibleFrom(ServerLevel fromWorld, BlockPos fromPos, int fromLevel) {
		if(isRemoved() || !isLinkMode())return false;
		Config c = Config.get();
		boolean sameDim = level.dimension().equals(fromWorld.dimension());
		return canLink(beaconLevel, fromLevel, sameDim, fromPos.distSqr(worldPosition), c.invLinkBeaconLvl, c.invLinkBeaconLvlSameDim, c.invLinkBeaconLvlCrossDim, c.invLinkBeaconRange);
	}

	@Override
	public InventoryConnectorBlockEntity getLinkedNetwork() {
		return isRemoved() ? null : master;
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
		if(cLocalLvl == -1)return false;
		if(cCDLvl != -1 && lvl >= cCDLvl && (lvlM > 0 || cCDLvl == 0))return true;
		if(!sameDimension)return false;
		if(cSDLvl != -1 && lvl >= cSDLvl && (lvlM > 0 || cSDLvl == 0))return true;
		return distSq < range * range;
	}

	public boolean stillValid(Player p_59619_) {
		if(channel != null) {
			Channel chn = RemoteConnections.get(level).getChannel(channel);
			if(chn != null && !chn.publicChannel && !chn.owner.equals(p_59619_.getUUID()))
				return false;
		}
		if (this.level.getBlockEntity(this.worldPosition) != this) {
			return false;
		} else {
			return !(p_59619_.distanceToSqr(this.worldPosition.getX() + 0.5D, this.worldPosition.getY() + 0.5D, this.worldPosition.getZ() + 0.5D) > 64.0D);
		}
	}

	@Override
	public UUID getChannel() {
		return channel;
	}

	public void setChannel(UUID channel) {
		RemoteConnections.get(level).invalidateCache(this.channel);
		this.channel = channel;
		setChanged();
		NetworkChanges.bump();
	}

	public int getBeaconLevel() {
		return beaconLevel;
	}
}
