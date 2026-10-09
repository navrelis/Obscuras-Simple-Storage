package com.obscura.storage.model;

import java.util.Collections;
import java.util.List;

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
import net.minecraft.world.level.block.state.BlockState;

import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.IDynamicBakedModel;
import net.minecraftforge.client.model.data.ModelData;

import com.obscura.storage.tile.PaintedBlockEntity;

/**
 * Model of the painted blocks (painted trim and the {@code ts.*_painted} variants): renders the
 * painted block state's model, or the plain trim model ({@code obscuras_storage:paint}) while unpainted.
 * Forge twin of the Fabric renderer-API model; the painted state comes in as {@link ModelData}.
 */
public class BakedPaintedModel implements IDynamicBakedModel {
	private final BakedModel parent;

	public BakedPaintedModel(BakedModel parent) {
		this.parent = parent;
	}

	private static BlockState painted(ModelData data) {
		BlockState st = data.get(PaintedBlockEntity.FACADE_STATE);
		return st == null || st.isAir() ? null : st;
	}

	private static BakedModel modelOf(BlockState st) {
		return Minecraft.getInstance().getBlockRenderer().getBlockModel(st);
	}

	@Override
	public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
			@NotNull RandomSource rand, @NotNull ModelData modelData, @Nullable RenderType layer) {
		BlockState st = painted(modelData);
		if (st == null) {
			if(layer != null && layer != RenderType.solid())return Collections.emptyList();
			return parent.getQuads(state, side, rand, ModelData.EMPTY, layer);
		}
		BakedModel model = modelOf(st);
		if (model instanceof BakedPaintedModel)return Collections.emptyList();
		// Vanilla models ignore the layer argument, so only answer for the layers the painted block renders in
		if (layer != null && !model.getRenderTypes(st, rand, ModelData.EMPTY).contains(layer))return Collections.emptyList();
		return model.getQuads(st, side, rand, ModelData.EMPTY, layer);
	}

	@Override
	public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
		BlockState st = painted(data);
		if (st == null)return ChunkRenderTypeSet.of(RenderType.solid());
		BakedModel model = modelOf(st);
		if (model instanceof BakedPaintedModel)return ChunkRenderTypeSet.of(RenderType.solid());
		return model.getRenderTypes(st, rand, ModelData.EMPTY);
	}

	@Override
	public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter level, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData modelData) {
		return modelData;
	}

	@Override
	public TextureAtlasSprite getParticleIcon(@NotNull ModelData data) {
		BlockState st = painted(data);
		if (st != null) {
			BakedModel model = modelOf(st);
			if (!(model instanceof BakedPaintedModel))return model.getParticleIcon(ModelData.EMPTY);
		}
		return parent.getParticleIcon(ModelData.EMPTY);
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

	@SuppressWarnings("deprecation")
	@Override
	public TextureAtlasSprite getParticleIcon() {
		return parent.getParticleIcon();
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
}
