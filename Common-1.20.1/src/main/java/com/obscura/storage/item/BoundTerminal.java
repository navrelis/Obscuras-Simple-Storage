package com.obscura.storage.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.obscura.storage.StorageTags;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.PlayerInvUtil;

/**
 * Server: the terminal a wireless terminal is bound to, and whether the player reaches it right now.
 *
 * <p>Shared by every remote access path (right click, the Open Terminal key, pick-block from storage
 * and Restock), so all of them apply the same dimension, range and beacon rules. The range rule
 * itself is {@link WirelessTerminal#isInRange}.
 */
public record BoundTerminal(Status status, Level level, BlockPos pos) {

	public static enum Status {
		NOT_BOUND(null),
		/** The bound dimension does not exist (any more). */
		NO_LEVEL(null),
		NOT_LOADED("chat.obscuras_storage.terminal_not_loaded"),
		OUT_OF_RANGE("chat.obscuras_storage.terminal_out_of_range"),
		INVALID_BLOCK("chat.obscuras_storage.terminal_invalid_block"),
		OK(null),
		;
		private final String message;

		private Status(String message) {
			this.message = message;
		}
	}

	private static final BoundTerminal NOT_BOUND = new BoundTerminal(Status.NOT_BOUND, null, null);
	private static final BoundTerminal NO_LEVEL = new BoundTerminal(Status.NO_LEVEL, null, null);

	/** Whether the stack carries a bound terminal position (the BindX/BindY/BindZ/BindDim tags). */
	public static boolean isBound(ItemStack stack) {
		return stack.hasTag() && stack.getTag().contains("BindX");
	}

	/** Whether the stack is bound to the terminal block at {@code pos} in {@code level} (same position and dimension). */
	public static boolean isBoundTo(ItemStack stack, Level level, BlockPos pos) {
		if (!isBound(stack))return false;
		CompoundTag tag = stack.getTag();
		return tag.getInt("BindX") == pos.getX() && tag.getInt("BindY") == pos.getY() && tag.getInt("BindZ") == pos.getZ()
				&& level.dimension().location().equals(ResourceLocation.tryParse(tag.getString("BindDim")));
	}

	/**
	 * The largest range among the wireless terminals the player carries that are bound to the terminal block at
	 * {@code pos} in {@code level}, 0 when there is none. Terminals bound elsewhere say nothing about this one.
	 */
	public static int reachTo(Player player, Level level, BlockPos pos) {
		int[] best = new int[1];
		PlayerInvUtil.findItem(player, s -> {
			if (s.getItem() instanceof WirelessTerminal term && isBoundTo(s, level, pos))best[0] = Math.max(best[0], term.getRange(player, s));
			// Never matches: every carried stack (and the extra slots) is looked at
			return false;
		}, 0, s -> 0);
		return best[0];
	}

	/** Server only. Checks in the order opening always did: dimension exists, chunk loaded, range, block. */
	public static BoundTerminal resolve(Player player, ItemStack stack) {
		if (!(stack.getItem() instanceof WirelessTerminal term))return NOT_BOUND;
		if (!isBound(stack))return NOT_BOUND;
		CompoundTag tag = stack.getTag();
		MinecraftServer server = player.getServer();
		if (server == null)return NO_LEVEL;
		ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(tag.getString("BindDim")));
		Level termWorld = server.getLevel(dimKey);
		if (termWorld == null)return NO_LEVEL;
		BlockPos pos = new BlockPos(tag.getInt("BindX"), tag.getInt("BindY"), tag.getInt("BindZ"));
		if (!termWorld.isLoaded(pos))return new BoundTerminal(Status.NOT_LOADED, termWorld, pos);
		if (!term.isInRange(player, stack, termWorld, pos))return new BoundTerminal(Status.OUT_OF_RANGE, termWorld, pos);
		if (!termWorld.getBlockState(pos).is(StorageTags.REMOTE_ACTIVATE))return new BoundTerminal(Status.INVALID_BLOCK, termWorld, pos);
		return new BoundTerminal(Status.OK, termWorld, pos);
	}

	public boolean isOk() {
		return status == Status.OK;
	}

	/** Shows the action bar message for a failed resolve, if the status has one. */
	public void showMessage(Player player) {
		if (status.message != null)
			player.displayClientMessage(Component.translatable(status.message), true);
	}

	/** Opens the terminal like a click on the block would. Only valid when {@link #isOk()}. */
	public InteractionResult open(Player player, InteractionHand hand) {
		BlockHitResult hit = new BlockHitResult(new Vec3(pos.getX(), pos.getY(), pos.getZ()), Direction.UP, pos, true);
		return level.getBlockState(pos).use(level, player, hand, hit);
	}

	/**
	 * The terminal block entity, when the player could open it right now (the block's own
	 * {@link StorageTerminalBlockEntity#canInteractWith} check included). Null otherwise.
	 */
	public StorageTerminalBlockEntity getTerminal(Player player) {
		if (!isOk())return null;
		if (level.getBlockEntity(pos) instanceof StorageTerminalBlockEntity te && !te.isRemoved() && te.canInteractWith(player, false))
			return te;
		return null;
	}
}
