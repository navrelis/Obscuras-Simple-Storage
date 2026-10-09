package com.obscura.storage.jade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.obscura.storage.block.entity.AbstractFilterBlockEntity;
import com.obscura.storage.block.entity.DisposalBlockEntity;
import com.obscura.storage.doctor.DoctorFormat;
import com.obscura.storage.util.FilterBlockStatus;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/** Ejector, Disposal and Exporter: what the block is doing, and for the Disposal how much it destroyed. */
public enum FilterBlockProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
	INSTANCE;

	@Override
	public ResourceLocation getUid() {
		return JadePlugin.FILTER_BLOCK;
	}

	@Override
	public void appendServerData(CompoundTag data, BlockAccessor accessor) {
		AbstractFilterBlockEntity te = (AbstractFilterBlockEntity) accessor.getBlockEntity();
		data.putByte("status", (byte) te.getStatus().ordinal());
		if (te instanceof DisposalBlockEntity d) {
			data.putLong("destroyed", d.getDestroyed().getTotal());
		}
	}

	@Override
	public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
		CompoundTag data = accessor.getServerData();
		if (!data.contains("status"))return;
		FilterBlockStatus status = FilterBlockStatus.byId(data.getByte("status"));
		tooltip.add(Component.translatable("tooltip.obscuras_storage.filter_block.status", Component.translatable(status.getTranslationKey())));
		if (data.contains("destroyed")) {
			tooltip.add(Component.translatable("tooltip.obscuras_storage.disposal.destroyed", DoctorFormat.count(Math.max(0, data.getLong("destroyed")))));
		}
	}
}
