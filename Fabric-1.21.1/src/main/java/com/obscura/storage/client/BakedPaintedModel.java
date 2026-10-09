package com.obscura.storage.client;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.StorageMod;
import com.obscura.storage.block.entity.PaintedBlockEntity;

public class BakedPaintedModel implements BakedModel {
	private BakedModel parent;
	private static final ConcurrentHashMap<BlockState, BakedModel> MODEL_CACHE = new ConcurrentHashMap<>();

	public BakedPaintedModel(BakedModel parent) {
		this.parent = parent;
	}

	public static void clearCache() {
		MODEL_CACHE.clear();
	}

	@Override
	public boolean isVanillaAdapter() {
		return false;
	}

	@Override
	public void emitBlockQuads(BlockAndTintGetter blockView, BlockState state, BlockPos pos,
			Supplier<RandomSource> randomSupplier, RenderContext context) {
		BlockEntity tile = blockView.getBlockEntity(pos);
		if(tile instanceof PaintedBlockEntity) {
			BakedModel model = null;
			try {
				BlockState blockstate = ((PaintedBlockEntity)tile).getPaintedBlockState();
				if (blockstate == null || blockstate == Blocks.AIR.defaultBlockState()) {
					blockstate = state;
					model = parent;
				}
				if(model == null) {
					final BlockState lookupState = blockstate;
					model = MODEL_CACHE.computeIfAbsent(lookupState, s -> Minecraft.getInstance().getBlockRenderer().getBlockModel(s));
				}
				if (!(model instanceof BakedPaintedModel)) {
					model.emitBlockQuads(blockView, state, pos, randomSupplier, context);
					return;
				}
			} catch (Exception e) {
				StorageMod.LOGGER.debug("Failed to get painted block model", e);
			}
		}

		QuadEmitter emitter = context.getEmitter();

		for(Direction direction : Direction.values()) {
			// Add a new face to the mesh
			emitter.square(direction, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f);
			// Set the sprite of the face, must be called after .square()
			// We haven't specified any UV coordinates, so we want to use the whole texture. BAKE_LOCK_UV does exactly that.
			emitter.spriteBake(getParticleIcon(), MutableQuadView.BAKE_LOCK_UV);
			// Enable texture usage
			emitter.color(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF);
			// Add the quad to the mesh
			emitter.emit();
		}
	}

	@Override
	public void emitItemQuads(ItemStack stack, Supplier<RandomSource> randomSupplier, RenderContext context) {
		parent.emitItemQuads(stack, randomSupplier, context);
	}

	@Override
	public ItemOverrides getOverrides() {
		return parent.getOverrides();
	}

	@Override
	public List<BakedQuad> getQuads(BlockState arg0, Direction arg1, RandomSource arg2) {
		return parent.getQuads(arg0, arg1, arg2);
	}

	@Override
	public TextureAtlasSprite getParticleIcon() {
		return parent.getParticleIcon();
	}

	@Override
	public ItemTransforms getTransforms() {
		return parent.getTransforms();
	}

	@Override
	public boolean isGui3d() {
		return false;
	}

	@Override
	public boolean isCustomRenderer() {
		return false;
	}

	@Override
	public boolean usesBlockLight() {
		return false;
	}

	@Override
	public boolean useAmbientOcclusion() {
		return true;
	}
}
