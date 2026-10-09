package com.obscura.storage.block.entity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SidedStorageBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.obscura.storage.Content;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.util.RedstoneSignals;
import com.obscura.storage.util.TickerUtil.OnLoadListener;
import com.obscura.storage.util.TickerUtil.TickableServer;

public class OpenCrateBlockEntity extends BlockEntity implements SidedStorageBlockEntity, TickableServer, OnLoadListener, ComparatorOutput.Source {
	private List<ItemEntity> items = new ArrayList<>();
	private Handler handler = new Handler();
	private AABB cachedAABB;
	private Direction cachedFacing;
	private final ComparatorOutput comparator = new ComparatorOutput();

	public OpenCrateBlockEntity(BlockPos pos, BlockState state) {
		super(Content.openCrateBE.get(), pos, state);
	}

	@Override
	public void onLoad() {
		if (level != null && !level.isClientSide) {
			refreshCachedAABB();
		}
	}

	private void refreshCachedAABB() {
		BlockState state = level.getBlockState(worldPosition);
		Direction f = state.getValue(BlockStateProperties.FACING);
		if (f != cachedFacing) {
			cachedFacing = f;
			BlockPos p = worldPosition.relative(f);
			cachedAABB = new AABB(p);
		}
	}

	/** The dropped items in front of the crate like a chest: every item entity is a slot, out of 27. */
	@Override
	public int getComparatorSignal() {
		return RedstoneSignals.entityFill(items);
	}

	@Override
	public void updateServer() {
		if(level.getGameTime() % 5 == (worldPosition.hashCode() & 0x7FFFFFFF) % 5){
			refreshCachedAABB();
			items = level.getEntitiesOfClass(ItemEntity.class, cachedAABB);
			comparator.tick(level, worldPosition, this::getComparatorSignal);
		}
	}

	@Override
	public Storage<ItemVariant> getItemStorage(Direction side) {
		return handler;
	}

	private class State {
		private ItemStack spawn;
		private ItemEntity entity;
		private int remove;
	}

	private class Handler extends SnapshotParticipant<List<State>> implements Storage<ItemVariant> {
		private List<State> state = new ArrayList<>();

		@Override
		protected List<State> createSnapshot() {
			return new ArrayList<>(state);
		}

		@Override
		protected void readSnapshot(List<State> snapshot) {
			this.state = new ArrayList<>(snapshot);
		}

		@Override
		protected void onFinalCommit() {
			if(!state.isEmpty()) {
				state.forEach(s -> {
					if(s.spawn != null) {
						BlockState state = level.getBlockState(worldPosition);
						Direction f = Direction.UP;
						if(state.getBlock() == Content.openCrate.get())f = state.getValue(BlockStateProperties.FACING);
						BlockPos p = worldPosition.relative(f);
						ItemEntity entityitem = new ItemEntity(level, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, s.spawn);
						entityitem.setDefaultPickUpDelay();
						entityitem.setDeltaMovement(Vec3.ZERO);
						level.addFreshEntity(entityitem);
						items.add(entityitem);
					} else if(s.entity != null) {
						s.entity.getItem().split(s.remove);
						if(s.entity.getItem().isEmpty())s.entity.discard();
					}
				});
			}
			state = new ArrayList<>();
		}

		@Override
		public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
			if (maxAmount < 1 || resource.isBlank()) return 0;
			// One entity per insert, at most one full stack: a plain (int) cast of the long amount
			// could produce oversized or negative stacks.
			ItemStack spawn = resource.toStack();
			int amount = (int) Math.min(maxAmount, spawn.getMaxStackSize());
			if (amount < 1) return 0;
			spawn.setCount(amount);
			updateSnapshots(transaction);
			State state = new State();
			state.spawn = spawn;
			this.state.add(state);
			return amount;
		}

		@Override
		public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
			if (maxAmount < 1)return 0;
			long ext = 0;
			for (ItemEntity e : new ArrayList<>(items)) {
				if(resource.matches(e.getItem()) && e.isAlive()) {
					ext += extract0(e, clampToInt(maxAmount - ext), transaction);
					if(ext == maxAmount)break;
				}
			}
			return ext;
		}

		/** Long.MAX_VALUE is a valid request amount, (int) would turn it into -1. */
		private static int clampToInt(long amount) {
			return (int) Math.min(amount, Integer.MAX_VALUE);
		}

		private long extract0(ItemEntity ent, int maxAmount, TransactionContext transaction) {
			if (maxAmount < 1)return 0;
			if (ent == null)return 0;
			// Subtract already-pending removals for this entity to prevent double-extraction
			int pending = 0;
			for (State s : this.state) {
				if (s.entity == ent) {
					pending += s.remove;
				}
			}
			int available = ent.getItem().getCount() - pending;
			if (available <= 0) return 0;
			updateSnapshots(transaction);
			State state = new State();
			state.entity = ent;
			state.remove = Math.min(maxAmount, available);
			this.state.add(state);
			return state.remove;
		}

		@Override
		public Iterator<StorageView<ItemVariant>> iterator() {
			return items.stream().filter(ItemEntity::isAlive).map(item -> (StorageView<ItemVariant>) new StorageView<ItemVariant>() {

				@Override
				public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
					if(!item.isAlive() || !resource.matches(item.getItem()))return 0L;
					return extract0(item, clampToInt(maxAmount), transaction);
				}

				@Override
				public boolean isResourceBlank() {
					return item.getItem().isEmpty();
				}

				@Override
				public ItemVariant getResource() {
					return ItemVariant.of(item.getItem());
				}

				@Override
				public long getAmount() {
					return item.getItem().getCount();
				}

				@Override
				public long getCapacity() {
					return 64;
				}
			}).iterator();
		}
	}
}
