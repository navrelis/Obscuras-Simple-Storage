package com.obscura.storage.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record BlockFace(BlockPos pos, Direction from) {
}
