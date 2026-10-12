package com.obscura.storage.client;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

/** The quads that renderers without the Fabric Rendering API (Sodium 0.5.x without Indium) get for painted blocks. */
@DisplayName("Painted model quads")
public class BakedPaintedModelTest {
	private static final BlockState PAINT = Blocks.OAK_PLANKS.defaultBlockState();
	private static final List<BakedQuad> FALLBACK = List.of(mock(BakedQuad.class));
	private static final List<BakedQuad> PAINTED = List.of(mock(BakedQuad.class));

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	@DisplayName("A painted state returns the quads of its looked-up model, called with the painted state")
	void paintedStateUsesLookedUpModel() {
		RandomSource random = RandomSource.create(1L);
		BakedModel model = mock(BakedModel.class);
		when(model.getQuads(PAINT, Direction.NORTH, random)).thenReturn(PAINTED);
		@SuppressWarnings("unchecked")
		Function<BlockState, BakedModel> lookup = mock(Function.class);
		when(lookup.apply(PAINT)).thenReturn(model);

		assertSame(PAINTED, BakedPaintedModel.paintedQuads(PAINT, lookup, Direction.NORTH, random, FALLBACK));
		verify(model).getQuads(PAINT, Direction.NORTH, random);
	}

	@Test
	@DisplayName("No painted state or air returns the fallback without a lookup")
	void nothingPaintedReturnsFallback() {
		@SuppressWarnings("unchecked")
		Function<BlockState, BakedModel> lookup = mock(Function.class);
		RandomSource random = RandomSource.create(1L);

		assertSame(FALLBACK, BakedPaintedModel.paintedQuads(null, lookup, null, random, FALLBACK));
		assertSame(FALLBACK, BakedPaintedModel.paintedQuads(Blocks.AIR.defaultBlockState(), lookup, null, random, FALLBACK));
		verify(lookup, never()).apply(any());
	}

	@Test
	@DisplayName("A painted model as lookup result returns the fallback")
	void paintedModelReturnsFallback() {
		BakedModel parent = mock(BakedModel.class);
		BakedModel painted = new BakedPaintedModel(parent);

		assertSame(FALLBACK, BakedPaintedModel.paintedQuads(PAINT, s -> painted, Direction.UP, RandomSource.create(1L), FALLBACK));
		verify(parent, never()).getQuads(any(), any(), any());
	}

	@Test
	@DisplayName("A missing model returns the fallback")
	void missingModelReturnsFallback() {
		assertSame(FALLBACK, BakedPaintedModel.paintedQuads(PAINT, s -> null, Direction.UP, RandomSource.create(1L), FALLBACK));
	}

	@Test
	@DisplayName("A throwing lookup or model returns the fallback")
	void throwingLookupReturnsFallback() {
		RandomSource random = RandomSource.create(1L);
		BakedModel broken = mock(BakedModel.class);
		when(broken.getQuads(any(), any(), any())).thenThrow(new IllegalStateException("broken model"));

		assertSame(FALLBACK, BakedPaintedModel.paintedQuads(PAINT, s -> {
			throw new IllegalStateException("no model");
		}, Direction.UP, random, FALLBACK));
		assertSame(FALLBACK, BakedPaintedModel.paintedQuads(PAINT, s -> broken, Direction.UP, random, FALLBACK));
	}
}
