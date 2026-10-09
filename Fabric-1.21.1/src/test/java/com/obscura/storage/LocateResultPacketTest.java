package com.obscura.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.obscura.storage.network.HighlightNetworkPacket;
import com.obscura.storage.network.LocateResultPacket;
import com.obscura.storage.util.ItemLocator;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

/** The locate reply carries up to 256 positions for "all connected inventories" and 64 for an item; the highlight request is tiny. */
@DisplayName("Locate result and highlight packets")
public class LocateResultPacketTest {
	private static final BlockPos BASE = new BlockPos(10, 64, 10);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static RegistryFriendlyByteBuf buf() {
		return RegistryFriendlyByteBuf.decorator(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)).apply(Unpooled.buffer());
	}

	private static List<BlockPos> positions(int count) {
		List<BlockPos> out = new ArrayList<>();
		for (int i = 0; i < count; i++)out.add(BASE.offset(i, 0, 0));
		return out;
	}

	private static LocateResultPacket roundTrip(LocateResultPacket p) {
		RegistryFriendlyByteBuf b = buf();
		LocateResultPacket.STREAM_CODEC.encode(b, p);
		LocateResultPacket back = LocateResultPacket.STREAM_CODEC.decode(b);
		assertEquals(0, b.readableBytes(), "the whole packet must be read");
		return back;
	}

	/** A reply as a (misbehaving) server could send it: no limit on the way in. */
	private static RegistryFriendlyByteBuf raw(ItemStack item, int count) {
		RegistryFriendlyByteBuf b = buf();
		ItemStack.OPTIONAL_STREAM_CODEC.encode(b, item);
		ByteBufCodecs.VAR_INT.encode(b, count);
		for (BlockPos p : positions(count))b.writeBlockPos(p);
		for (int i = 0; i < 3; i++)ByteBufCodecs.VAR_INT.encode(b, 0);
		return b;
	}

	@Test
	@DisplayName("All connected inventories: an empty item and 256 positions survive, 257 are refused on both sides")
	void allKeeps256() {
		assertEquals(256, ItemLocator.MAX_ALL_POSITIONS);
		LocateResultPacket all = new LocateResultPacket(ItemStack.EMPTY, positions(256), 300, 4, 2);
		LocateResultPacket back = roundTrip(all);
		assertTrue(back.isAll());
		assertEquals(all.positions(), back.positions());
		assertEquals(300, back.inDimension());
		assertEquals(4, back.otherDimensions());
		assertEquals(2, back.unknown());

		assertThrows(EncoderException.class, () -> LocateResultPacket.STREAM_CODEC.encode(buf(), new LocateResultPacket(ItemStack.EMPTY, positions(257), 257, 0, 0)));
		assertThrows(DecoderException.class, () -> LocateResultPacket.STREAM_CODEC.decode(raw(ItemStack.EMPTY, 257)));
		assertEquals(256, LocateResultPacket.STREAM_CODEC.decode(raw(ItemStack.EMPTY, 256)).positions().size());
	}

	@Test
	@DisplayName("Item locate keeps its limit of 64 positions on both sides")
	void itemKeeps64() {
		assertEquals(64, ItemLocator.MAX_POSITIONS);
		LocateResultPacket item = new LocateResultPacket(new ItemStack(Items.IRON_INGOT), positions(64), 64, 0, 0);
		LocateResultPacket back = roundTrip(item);
		assertFalse(back.isAll());
		assertTrue(back.item().is(Items.IRON_INGOT));
		assertEquals(64, back.positions().size());

		assertThrows(EncoderException.class, () -> LocateResultPacket.STREAM_CODEC.encode(buf(), new LocateResultPacket(new ItemStack(Items.IRON_INGOT), positions(65), 65, 0, 0)));
		assertThrows(DecoderException.class, () -> LocateResultPacket.STREAM_CODEC.decode(raw(new ItemStack(Items.IRON_INGOT), 65)));
		// 65 positions are fine for "all", but an item reply may not use that allowance
		assertEquals(65, LocateResultPacket.STREAM_CODEC.decode(raw(ItemStack.EMPTY, 65)).positions().size());
	}

	@Test
	@DisplayName("The highlight request carries a block position or none")
	void highlightRequest() {
		for (BlockPos pos : new BlockPos[] {null, BASE, new BlockPos(-30000000, -64, 29999999)}) {
			FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
			HighlightNetworkPacket.STREAM_CODEC.encode(b, new HighlightNetworkPacket(pos));
			HighlightNetworkPacket back = HighlightNetworkPacket.STREAM_CODEC.decode(b);
			assertEquals(pos, back.pos());
			assertEquals(0, b.readableBytes());
		}
		FriendlyByteBuf none = new FriendlyByteBuf(Unpooled.buffer());
		HighlightNetworkPacket.STREAM_CODEC.encode(none, new HighlightNetworkPacket(null));
		assertEquals(1, none.readableBytes(), "no position: one byte");
		assertNull(HighlightNetworkPacket.STREAM_CODEC.decode(none).pos());
	}
}
