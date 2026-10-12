package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Function;

import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.obscura.storage.model.BakedPaintedModel;

/** The quads Sodium gets for a painted block (it never calls emitBlockQuads). */
@DisplayName("Painted quads")
public class PaintedQuadsTest {
	private static final RandomSource RANDOM = RandomSource.create(1L);
	private static final List<BakedQuad> FALLBACK = List.of(mock(BakedQuad.class));
	private static BlockState planks;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		planks = Blocks.OAK_PLANKS.defaultBlockState();
	}

	@Test
	@DisplayName("a painted block gets the quads of the painted state's model, asked with the painted state")
	void paintedUsesPaintedModel() {
		List<BakedQuad> quads = List.of(mock(BakedQuad.class), mock(BakedQuad.class));
		BakedModel model = mock(BakedModel.class);
		when(model.getQuads(planks, Direction.NORTH, RANDOM)).thenReturn(quads);
		Function<BlockState, BakedModel> lookup = s -> s == planks ? model : null;
		assertSame(quads, BakedPaintedModel.paintedQuads(planks, lookup, Direction.NORTH, RANDOM, FALLBACK));
		verify(model).getQuads(planks, Direction.NORTH, RANDOM);
	}

	@Test
	@DisplayName("an unpainted block (null or air) keeps the fallback")
	void unpaintedKeepsFallback() {
		Function<BlockState, BakedModel> lookup = s -> {
			throw new AssertionError("no lookup for an unpainted block");
		};
		assertSame(FALLBACK, BakedPaintedModel.paintedQuads(null, lookup, Direction.UP, RANDOM, FALLBACK));
		assertSame(FALLBACK, BakedPaintedModel.paintedQuads(Blocks.AIR.defaultBlockState(), lookup, Direction.UP, RANDOM, FALLBACK));
	}

	@Test
	@DisplayName("a painted state that is itself a painted model keeps the fallback")
	void paintedModelKeepsFallback() {
		BakedModel model = new BakedPaintedModel();
		assertSame(FALLBACK, BakedPaintedModel.paintedQuads(planks, s -> model, Direction.UP, RANDOM, FALLBACK));
	}

	@Test
	@DisplayName("a missing model keeps the fallback")
	void missingModelKeepsFallback() {
		assertSame(FALLBACK, BakedPaintedModel.paintedQuads(planks, s -> null, Direction.UP, RANDOM, FALLBACK));
	}

	@Test
	@DisplayName("a failing lookup or model never reaches the mesher, the fallback is returned")
	void failureKeepsFallback() {
		assertSame(FALLBACK, BakedPaintedModel.paintedQuads(planks, s -> {
			throw new IllegalStateException("no client");
		}, Direction.UP, RANDOM, FALLBACK));
		BakedModel model = mock(BakedModel.class);
		when(model.getQuads(any(), any(), any())).thenThrow(new RuntimeException("broken model"));
		assertSame(FALLBACK, BakedPaintedModel.paintedQuads(planks, s -> model, Direction.UP, RANDOM, FALLBACK));
	}
}
