package com.obscura.storage.jade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import com.obscura.storage.block.entity.BasicInventoryHopperBlockEntity;
import com.obscura.storage.util.HopperLogic;
import com.obscura.storage.util.HopperMode;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public enum InventoryHopperProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
	INSTANCE;

	@Override
	public ResourceLocation getUid() {
		return JadePlugin.INVENTORY_HOPPER;
	}

	@Override
	public void appendServerData(CompoundTag data, BlockAccessor accessor) {
		BasicInventoryHopperBlockEntity te = (BasicInventoryHopperBlockEntity) accessor.getBlockEntity();
		var f = te.getFilter();
		if (!f.isEmpty()) {
			data.put("filter", f.save(accessor.getLevel().registryAccess()));
		}
		data.putBoolean("enabled", te.isEnabled());
		data.putByte("mode", (byte) te.getMode().ordinal());
		data.putByte("amount", (byte) te.getAmount());
	}

	@Override
	public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
		var f = ItemStack.parseOptional(accessor.getLevel().registryAccess(), accessor.getServerData().getCompound("filter"));
		if (!f.isEmpty()) {
			JadeUtil.addFilterInfo(tooltip, accessor, f);
		}
		HopperMode mode = HopperMode.byId(accessor.getServerData().getByte("mode"));
		if (mode != HopperMode.NORMAL) {
			int amount = HopperLogic.clampAmount(accessor.getServerData().getByte("amount"));
			tooltip.add(Component.translatable("tooltip.obscuras_storage.hopper_mode_amount", Component.translatable(mode.getTranslationKey()), amount));
		}
		if (!accessor.getServerData().getBoolean("enabled")) {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.disabled_by_redstone"));
		}
	}
}
