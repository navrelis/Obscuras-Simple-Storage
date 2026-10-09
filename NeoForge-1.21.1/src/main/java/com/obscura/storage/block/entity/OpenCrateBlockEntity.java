package com.obscura.storage.block.entity;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.obscura.storage.Content;
import com.obscura.storage.platform.FabricDataCompat;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.util.RedstoneSignals;
import com.obscura.storage.util.TickerUtil.OnLoadListener;
import com.obscura.storage.util.TickerUtil.TickableServer;

/**
 * Exposes the item entities in front of it as a container (wrapped in an InvWrapper capability). Details
 * (same intent as the Fabric twin): the scanned box is cached per facing, dead entities are
 * never handed out, the tick phase cannot go negative, and a split stack is written back to the entity
 * so clients see the new count.
 */
public class OpenCrateBlockEntity extends BlockEntity implements TickableServer, Container, OnLoadListener, ComparatorOutput.Source {
	private List<ItemEntity> items = new ArrayList<>();
	private AABB cachedAABB;
	private Direction cachedFacing;
	private final ComparatorOutput comparator = new ComparatorOutput();

	public OpenCrateBlockEntity(BlockPos pos, BlockState state) {
		super(Content.openCrateBE.get(), pos, state);
	}

	@Override
	public void onLoad() {
		super.onLoad();
		if (level != null && !level.isClientSide) {
			refreshCachedAABB();
		}
	}

	private void refreshCachedAABB() {
		BlockState state = level.getBlockState(worldPosition);
		Direction f = state.hasProperty(BlockStateProperties.FACING) ? state.getValue(BlockStateProperties.FACING) : Direction.UP;
		if (f != cachedFacing || cachedAABB == null) {
			cachedFacing = f;
			cachedAABB = new AABB(worldPosition.relative(f));
		}
	}

	/** The dropped items in front of the crate like a chest: every item entity is a slot, out of 27. */
	@Override
	public int getComparatorSignal() {
		return RedstoneSignals.entityFill(items);
	}

	@Override
	public void updateServer() {
		// Math.abs(Integer.MIN_VALUE) is negative, which would never match the tick phase
		if(level.getGameTime() % 5 == (worldPosition.hashCode() & 0x7FFFFFFF) % 5){
			refreshCachedAABB();
			items = level.getEntitiesOfClass(ItemEntity.class, cachedAABB, ItemEntity::isAlive);
			comparator.tick(level, worldPosition, this::getComparatorSignal);
		}
	}

	private ItemEntity entity(int index) {
		if(index >= 0 && items.size() > index){
			ItemEntity ent = items.get(index);
			if(ent.isAlive() && !ent.getItem().isEmpty())return ent;
		}
		return null;
	}

	@Override
	public int getContainerSize() {
		return items.size() + 1;
	}

	@Override
	public boolean isEmpty() {
		for (ItemEntity e : items) {
			if (e.isAlive() && !e.getItem().isEmpty())return false;
		}
		return true;
	}

	@Override
	public ItemStack getItem(int index) {
		ItemEntity ent = entity(index);
		return ent != null ? ent.getItem() : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItem(int index, int count) {
		ItemEntity ent = entity(index);
		if (ent == null || count < 1)return ItemStack.EMPTY;
		ItemStack rest = ent.getItem().copy();
		ItemStack s = rest.split(count);
		if(rest.isEmpty())ent.remove(RemovalReason.KILLED);
		else ent.setItem(rest);// marks the entity data dirty, clients see the new count
		return s;
	}

	@Override
	public ItemStack removeItemNoUpdate(int index) {
		ItemEntity ent = entity(index);
		if (ent == null)return ItemStack.EMPTY;
		ItemStack s = ent.getItem().copy();
		ent.remove(RemovalReason.KILLED);
		return s;
	}

	@Override
	public void setItem(int index, ItemStack stack) {
		if (stack.isEmpty())return;
		ItemEntity ent = entity(index);
		if (ent != null) {
			// InvWrapper grows an existing slot through setItem
			ent.setItem(stack);
			return;
		}
		BlockState state = level.getBlockState(worldPosition);
		Direction f = Direction.UP;
		if(state.getBlock() == Content.openCrate.get())f = state.getValue(BlockStateProperties.FACING);
		BlockPos p = worldPosition.relative(f);
		ItemEntity entityitem = new ItemEntity(level, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, stack);
		entityitem.setDefaultPickUpDelay();
		entityitem.setDeltaMovement(Vec3.ZERO);
		level.addFreshEntity(entityitem);
		items.add(entityitem);
	}

	@Override
	public int getMaxStackSize() {
		return 64;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public void startOpen(Player player) {
	}

	@Override
	public void stopOpen(Player player) {
	}

	@Override
	public boolean canPlaceItem(int index, ItemStack stack) {
		return index == items.size();
	}

	@Override
	public void clearContent() {
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.loadAdditional(tag, provider);
		FabricDataCompat.readFabricBlockFilter(this, tag, provider);
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		FabricDataCompat.writeFabricBlockFilter(this, tag, provider);
	}
}
