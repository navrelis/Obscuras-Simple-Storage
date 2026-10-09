package com.obscura.storage.util;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import com.obscura.storage.block.AbstractStorageTerminalBlock;
import com.obscura.storage.block.IInventoryCable;
import com.obscura.storage.doctor.DoctorScan;
import com.obscura.storage.item.BoundTerminal;
import com.obscura.storage.item.WirelessTerminal;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.tile.StorageTerminalBlockEntity;

/**
 * "Highlight Connected Inventories" asked with the key, outside a terminal screen.
 *
 * <p>The client only says which block it points at, or that it holds a wireless terminal. The server
 * decides which network that is and answers with {@link ItemLocator#locateAll}, the same reply as the
 * terminal's highlight button. Runs only when a player asks, never per tick.
 */
public final class HighlightNetwork {
	/** Farthest a pointed at block may be from the player's eyes, in blocks. A terminal menu stays open to about 7.5. */
	public static final double MAX_DISTANCE = 8.0D;

	private HighlightNetwork() {}

	/** Client: whether the server can resolve this block to a network (a terminal, or a cable, connector or cable connector). */
	public static boolean isNetworkBlock(BlockState state) {
		return state.getBlock() instanceof AbstractStorageTerminalBlock || state.getBlock() instanceof IInventoryCable;
	}

	/**
	 * Server: the highlight key. Without a position the wireless terminal in the player's hands is used.
	 * Everything that does not check out (switch, cooldown, reach, a block that is on no network) is ignored.
	 */
	public static void request(ServerPlayer player, @Nullable BlockPos pos) {
		if (!ItemLocator.isEnabled() || !player.isAlive() || !ItemLocator.tryStartRequest(player))return;
		BlockEntity source = resolve(player, pos);
		if (source instanceof StorageTerminalBlockEntity te)ItemLocator.locateAll(player, te);
		else if (source instanceof InventoryConnectorBlockEntity connector)ItemLocator.locateAll(player, connector);
	}

	/**
	 * Server: the terminal or Inventory Connector whose network {@code pos} means. A terminal is its own source,
	 * a cable, connector or cable connector leads to the connector of its network. Null when the block is out of
	 * reach, not loaded or on no network. Without a position it is the terminal of the wireless terminal in a hand,
	 * with the message of opening it when it cannot be reached.
	 */
	public static @Nullable BlockEntity resolve(ServerPlayer player, @Nullable BlockPos pos) {
		if (pos == null)return wirelessTerminal(player);
		ServerLevel level = player.serverLevel();
		if (new AABB(pos).distanceToSqr(player.getEyePosition()) > MAX_DISTANCE * MAX_DISTANCE || !level.isLoaded(pos))return null;
		if (level.getBlockEntity(pos) instanceof StorageTerminalBlockEntity te)return te;
		if (level.getBlockState(pos).getBlock() instanceof IInventoryCable)return DoctorScan.find(level, pos);
		return null;
	}

	/** The terminal a bound wireless terminal in a hand leads to, with the range, beacon and dimension rules of opening it. */
	private static @Nullable StorageTerminalBlockEntity wirelessTerminal(ServerPlayer player) {
		ItemStack held = boundWirelessTerminal(player.getMainHandItem());
		if (held.isEmpty())held = boundWirelessTerminal(player.getOffhandItem());
		if (held.isEmpty())return null;
		BoundTerminal bound = BoundTerminal.resolve(player, held);
		if (!bound.isOk()) {
			bound.showMessage(player);
			return null;
		}
		return bound.getTerminal(player);
	}

	private static ItemStack boundWirelessTerminal(ItemStack stack) {
		return stack.getItem() instanceof WirelessTerminal && BoundTerminal.isBound(stack) ? stack : ItemStack.EMPTY;
	}
}
