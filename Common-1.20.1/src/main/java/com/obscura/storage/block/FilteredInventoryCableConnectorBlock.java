package com.obscura.storage.block;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.tile.FilteredInventoryCableConnectorBlockEntity;

/**
 * Legacy block kept registered only so old worlds keep loading.
 * Placed instances convert themselves to a normal Inventory Cable Connector
 * (see {@link FilteredInventoryCableConnectorBlockEntity}).
 */
public class FilteredInventoryCableConnectorBlock extends InventoryCableConnectorBlock {

	public FilteredInventoryCableConnectorBlock() {
		super(false);
	}

	@Override
	public void appendHoverText(ItemStack stack, BlockGetter worldIn, List<Component> tooltip,
			TooltipFlag flagIn) {
		tooltip.add(Component.translatable("tooltip.obscuras_storage.filtered_legacy"));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new FilteredInventoryCableConnectorBlockEntity(pos, state);
	}

	@SuppressWarnings("deprecation")
	@Override
	public void onRemove(BlockState state, Level world, BlockPos pos, BlockState state2, boolean flag) {
		if (!state.is(state2.getBlock())) {
			BlockEntity blockentity = world.getBlockEntity(pos);
			if (blockentity instanceof FilteredInventoryCableConnectorBlockEntity te) {
				te.dropFilters();
			}

			super.onRemove(state, world, pos, state2, flag);
		}
	}
}
