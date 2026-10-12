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

import com.obscura.storage.model.BakedPaintedModel;

/**
 * Sodium 0.5.x does not implement the Fabric Rendering API, so BakedPaintedModel#emitBlockQuads never
 * runs there without Indium and painted blocks would show the stone fallback of getQuads. This swaps in
 * the quads of the painted block's own model. Sodium's mesher calls this on its chunk build threads.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public class SodiumBlockRendererMixin {
	@Redirect(method = "getGeometry", at = @At(value = "INVOKE", remap = true,
			target = "Lnet/minecraft/client/resources/model/BakedModel;getQuads(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;)Ljava/util/List;"),
			require = 0)
	private List<BakedQuad> obscuras_storage$paintedQuads(BakedModel model, BlockState state, Direction face,
			RandomSource random, BlockRenderContext ctx, Direction faceArg) {
		List<BakedQuad> quads = model.getQuads(state, face, random);
		if(model instanceof BakedPaintedModel)
			return BakedPaintedModel.paintedQuads(ctx.world(), ctx.pos(), face, random, quads);
		return quads;
	}
}
