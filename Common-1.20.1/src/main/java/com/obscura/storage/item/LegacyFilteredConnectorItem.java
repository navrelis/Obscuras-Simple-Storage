package com.obscura.storage.item;

import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Content;

/**
 * Leftover Filtered Inventory Cable Connector items place a normal Inventory Cable Connector.
 */
public class LegacyFilteredConnectorItem extends BlockItem {

	public static Function<Block, Item> make() {
		return b -> new LegacyFilteredConnectorItem(b, new Item.Properties());
	}

	private LegacyFilteredConnectorItem(Block block, Item.Properties p) {
		super(block, p);
	}

	@Override
	@Nullable
	protected BlockState getPlacementState(BlockPlaceContext context) {
		BlockState state = Content.invCableConnector.get().getStateForPlacement(context);
		return state != null && canPlace(context, state) ? state : null;
	}
}
