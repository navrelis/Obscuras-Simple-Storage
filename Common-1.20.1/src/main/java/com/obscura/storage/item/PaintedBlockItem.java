package com.obscura.storage.item;

import java.util.function.Function;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.block.IPaintable;

public class PaintedBlockItem extends BlockItem {
	/**
	 * Stacks saved before v5.0.0 keep ids of this mod in the old namespace in their NBT (painted block
	 * states, tag names, the stacks inside a filter); they are rewritten when the stack is loaded, see
	 * LegacyIds#migrateStrings.
	 */
	@Override
	public void verifyTagAfterLoad(CompoundTag tag) {
		super.verifyTagAfterLoad(tag);
		LegacyIds.migrateStrings(tag);
	}


	public static Function<Block, Item> makeHidden() {
		return b -> new PaintedBlockItem(b, new Item.Properties());
	}

	public static Function<Block, Item> make() {
		return b -> new PaintedBlockItem(b, new Properties());
	}

	private PaintedBlockItem(Block block, Item.Properties p) {
		super(block, p);
	}

	@Override
	public Component getName(ItemStack is) {
		MutableComponent tc = (MutableComponent) super.getName(is);
		if(is.hasTag() && is.getTag().getCompound("BlockEntityTag").contains("block")) {
			BlockState st = IPaintable.readBlockState(null, is.getTag().getCompound("BlockEntityTag").getCompound("block"));
			tc.append(" (");
			tc.append(st.getBlock().getName().withStyle(ChatFormatting.GREEN));
			tc.append(")");
		}
		return tc;
	}

}
