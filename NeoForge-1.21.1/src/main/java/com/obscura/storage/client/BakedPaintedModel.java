package com.obscura.storage.client;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;

import com.obscura.storage.StorageMod;
import com.obscura.storage.block.entity.PaintedBlockEntity;

/**
 * Renders a painted block with the model of the block it is painted with, or its own model when it is
 * not painted. Details (same intent as the Fabric twin): item overrides and transforms come from
 * the wrapped model instead of null, a failing lookup falls back to the own model instead of rendering
 * nothing, and the render layers are those of the model actually drawn, so quads are emitted once
 * instead of once per layer.
 */
public class BakedPaintedModel implements IDynamicBakedModel {
	private final Block blockFor;
	private final BakedModel parent;

	public BakedPaintedModel(Block blockFor, BakedModel parent) {
		this.blockFor = blockFor;
		this.parent = parent;
	}

	/** The painted state from the model data, or null when the block is not painted. */
	private static BlockState paintedState(ModelData data) {
		Supplier<BlockState> supp = data.get(PaintedBlockEntity.FACADE_STATE);
		if (supp == null)return null;
		try {
			BlockState st = supp.get();
			return st == null || st.isAir() ? null : st;
		} catch (Exception e) {
			StorageMod.LOGGER.debug("Failed to get painted block state", e);
			return null;
		}
	}

	private BakedModel modelFor(BlockState painted) {
		if (painted == null)return parent;
		try {
			BakedModel m = Minecraft.getInstance().getBlockRenderer().getBlockModel(painted);
			// Painted with another painted block: draw our own model, never recurse
			return m == null || m instanceof BakedPaintedModel ? parent : m;
		} catch (Exception e) {
			StorageMod.LOGGER.debug("Failed to get painted block model", e);
			return parent;
		}
	}

	@Override
	public boolean useAmbientOcclusion() {
		return true;
	}

	@Override
	public boolean isGui3d() {
		return parent.isGui3d();
	}

	@Override
	public boolean usesBlockLight() {
		return parent.usesBlockLight();
	}

	@Override
	public boolean isCustomRenderer() {
		return false;
	}

	@Deprecated
	@Override
	public TextureAtlasSprite getParticleIcon() {
		return parent.getParticleIcon(ModelData.EMPTY);
	}

	@Override
	public TextureAtlasSprite getParticleIcon(@NotNull ModelData data) {
		BlockState painted = paintedState(data);
		BakedModel m = modelFor(painted);
		return m == parent ? parent.getParticleIcon(ModelData.EMPTY) : m.getParticleIcon(ModelData.EMPTY);
	}

	@Override
	public ItemOverrides getOverrides() {
		return parent.getOverrides();
	}

	@SuppressWarnings("deprecation")
	@Override
	public ItemTransforms getTransforms() {
		return parent.getTransforms();
	}

	@Override
	public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
			@NotNull RandomSource rand, @NotNull ModelData modelData, @Nullable RenderType layer) {
		BlockState painted = paintedState(modelData);
		BakedModel model = modelFor(painted);
		BlockState drawState = model == parent ? state : painted;
		if (layer != null && drawState != null && !model.getRenderTypes(drawState, rand, ModelData.EMPTY).contains(layer))
			return Collections.emptyList();
		return model.getQuads(drawState, side, rand, ModelData.EMPTY, layer);
	}

	@Override
	public ModelData getModelData(BlockAndTintGetter world, BlockPos pos, BlockState state, ModelData tileData) {
		return tileData;
	}

	@Override
	public ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand,
			@NotNull ModelData data) {
		BlockState painted = paintedState(data);
		BakedModel model = modelFor(painted);
		if (model == parent)return parent.getRenderTypes(state, rand, ModelData.EMPTY);
		return model.getRenderTypes(painted, rand, ModelData.EMPTY);
	}

	public Block getBlock() {
		return blockFor;
	}
}
