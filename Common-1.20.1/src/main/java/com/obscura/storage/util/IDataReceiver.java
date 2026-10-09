package com.obscura.storage.util;

import net.minecraft.nbt.CompoundTag;

public interface IDataReceiver {
	void receive(CompoundTag tag);
}
