package com.obscura.storage.tile;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import com.obscura.storage.Content;
import com.obscura.storage.item.IItemFilter;

/**
 * Legacy block entity of the removed Filtered Inventory Cable Connector.
 * It only exists to keep old worlds loadable: on its first server tick it replaces
 * itself with a normal Inventory Cable Connector and drops the stored filter items.
 */
public class FilteredInventoryCableConnectorBlockEntity extends AbstractInventoryCableConnectorBlockEntity {
	private final SimpleContainer filter = new SimpleContainer(9);

	public FilteredInventoryCableConnectorBlockEntity(BlockPos pos, BlockState state) {
		super(Content.invCableConnectorFilteredTile.get(), pos, state);
	}

	@Override
	public void updateServer() {
		// Runs from the regular block entity ticker, never during chunk loading itself.
		convert();
	}

	private void convert() {
		if(level == null || level.isClientSide || isRemoved())return;
		BlockState old = level.getBlockState(worldPosition);
		// Skip if the chunk unloaded / block was already replaced in the meantime.
		if(level.getBlockEntity(worldPosition) != this || !old.is(Content.invCableConnectorFiltered.get()))return;

		BlockState state = Content.invCableConnector.get().defaultBlockState();
		for(Property<?> p : old.getProperties()) {
			state = copyProperty(state, old, p);
		}

		dropFilters();
		filter.clearContent();
		CompoundTag data = saveWithoutMetadata();

		level.setBlock(worldPosition, state, Block.UPDATE_ALL);
		BlockEntity be = level.getBlockEntity(worldPosition);
		if(be instanceof InventoryCableConnectorBlockEntity c && be != this) {
			c.load(data);
			c.setChanged();
		}
	}

	private static <T extends Comparable<T>> BlockState copyProperty(BlockState to, BlockState from, Property<T> p) {
		return to.hasProperty(p) ? to.setValue(p, from.getValue(p)) : to;
	}

	@Override
	public void saveAdditional(CompoundTag tag) {
		super.saveAdditional(tag);
		tag.put("filter", filter.createTag());
	}

	@Override
	public void load(CompoundTag tag) {
		super.load(tag);
		filter.fromTag(tag.getList("filter", 10));
	}

	public void dropFilters() {
		if(level == null)return;
		for(int i = 0;i<filter.getContainerSize();i++) {
			ItemStack is = filter.getItem(i);
			if(is.getItem() instanceof IItemFilter) {
				Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), is);
				filter.setItem(i, ItemStack.EMPTY);
			}
		}
	}
}
