package com.obscura.storage.mixin.client;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.client.BakedPaintedModel;

/**
 * Sodium 0.5.x without Indium does not implement the Fabric Rendering API, so its chunk mesher never calls
 * {@link BakedPaintedModel#emitBlockQuads} and only asks the model for {@code getQuads}. This hands it the
 * painted quads there. Sodium 0.6+ implements the Rendering API and does not have this class (package
 * {@code net.caffeinemc.mods.sodium}); without Sodium the target is absent. Either way the mixin is skipped.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class SodiumBlockRendererMixin {
	@Redirect(method = "getGeometry", remap = false, require = 0, at = @At(value = "INVOKE", remap = true,
			target = "Lnet/minecraft/client/resources/model/BakedModel;getQuads(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
	private List<BakedQuad> obscuras_storage$paintedQuads(BakedModel model, BlockState state, Direction face,
			RandomSource random, BlockRenderContext ctx, Direction faceArg) {
		List<BakedQuad> quads = model.getQuads(state, face, random);
		if (model instanceof BakedPaintedModel) {
			return BakedPaintedModel.paintedQuads(ctx.world(), ctx.pos(), face, random, quads);
		}
		return quads;
	}
}
