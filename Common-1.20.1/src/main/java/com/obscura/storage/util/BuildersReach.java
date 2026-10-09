package com.obscura.storage.util;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.obscura.storage.Config;
import com.obscura.storage.StorageMod;
import com.obscura.storage.item.AdvWirelessTerminalItem;
import com.obscura.storage.item.BoundTerminal;
import com.obscura.storage.item.WirelessTerminal;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.tile.StorageTerminalBlockEntity;

/**
 * Builder's Reach: pick-block from storage and the Advanced Wireless Terminal's Restock.
 *
 * <p>Both pull through the bound terminal's own {@link StorageTerminalBlockEntity#pullStack}, so they
 * see the same network (and filters) as the terminal screen. A terminal only counts when the player
 * could open it right now ({@link BoundTerminal}), with the same range, beacon and dimension rules.
 */
public class BuildersReach {
	/** Minimum server ticks between two pick-from-storage requests of one player. */
	public static final int PICK_COOLDOWN_TICKS = 5;
	/** Restock checks a player every this many server ticks. */
	public static final int RESTOCK_INTERVAL = 10;
	/** Restock tops up the held stack once it has fewer items than this. */
	public static final int RESTOCK_BELOW = 16;
	public static final int HOTBAR_SIZE = 9;
	/** Hotbar plus main inventory; armor and offhand are never used. */
	public static final int INVENTORY_SIZE = 36;
	/** Survival block reach (4.5) plus one block of slack for lag, like the 1.21 interaction range check. */
	public static final double PICK_REACH = 4.5D + 1.0D;

	private static final Cooldown<ServerPlayer> pickCooldown = new Cooldown<>(PICK_COOLDOWN_TICKS);

	public static enum PlacementKind {
		/** The selected hotbar slot is empty. */
		SELECTED,
		/** Another hotbar slot is empty, put it there and select it. */
		HOTBAR,
		/** The hotbar is full: the selected item moves to this empty main inventory slot. */
		MAIN_SWAP,
		FULL
	}

	public static record Placement(PlacementKind kind, int slot) {
		public static final Placement FULL = new Placement(PlacementKind.FULL, -1);
	}

	/**
	 * Where a picked stack goes: the selected hotbar slot if empty, else the first empty hotbar slot,
	 * else the first empty main inventory slot (swapped with the selected slot, like vanilla creative
	 * pick-block), else nowhere.
	 */
	public static Placement choosePlacement(int selected, IntPredicate isEmpty) {
		if (selected >= 0 && selected < HOTBAR_SIZE && isEmpty.test(selected))return new Placement(PlacementKind.SELECTED, selected);
		for (int i = 0;i < HOTBAR_SIZE;i++) {
			if (isEmpty.test(i))return new Placement(PlacementKind.HOTBAR, i);
		}
		for (int i = HOTBAR_SIZE;i < INVENTORY_SIZE;i++) {
			if (isEmpty.test(i))return new Placement(PlacementKind.MAIN_SWAP, i);
		}
		return Placement.FULL;
	}

	/** Items Restock should add to the held stack, 0 when it should do nothing. */
	public static int restockAmount(boolean isBlockItem, int count, int maxStackSize) {
		if (!isBlockItem || count <= 0 || count >= RESTOCK_BELOW)return 0;
		return Math.max(0, maxStackSize - count);
	}

	/** Spreads the players over the interval so they are not all checked on the same tick. */
	public static boolean isRestockTick(int tick, int playerId) {
		return Math.floorMod(tick + playerId, RESTOCK_INTERVAL) == 0;
	}

	/** Per-key rate limit on the server tick counter. */
	public static class Cooldown<K> {
		private final Map<K, Integer> last = new WeakHashMap<>();
		private final int ticks;

		public Cooldown(int ticks) {
			this.ticks = ticks;
		}

		/** True (and remembers {@code now}) when {@code key} may act at tick {@code now}. */
		public boolean tryUse(K key, int now) {
			Integer l = last.get(key);
			// now < l: the tick counter restarted (another world in the same session)
			if (l != null && now >= l && now - l < ticks)return false;
			last.put(key, now);
			return true;
		}
	}

	/** How far (in blocks) the hit location a client reports may lie outside the targeted block. */
	public static final float HIT_SLACK = 1.0F;

	/**
	 * The hit a client reported for its pick-block: the targeted block, the face (a 3D data value, 0 to 5) and the hit
	 * location relative to the block. Null when it is not plausible (unknown face, location not within
	 * {@link #HIT_SLACK} of the block, NaN), the caller then falls back to a synthetic hit.
	 */
	public static BlockHitResult reportedHit(BlockPos pos, int face, float dx, float dy, float dz) {
		if (face < 0 || face > 5)return null;
		if (!inSlack(dx) || !inSlack(dy) || !inSlack(dz))return null;
		return new BlockHitResult(new Vec3(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz), Direction.from3DDataValue(face), pos, false);
	}

	private static boolean inSlack(float offset) {
		// False for NaN as well
		return offset >= -HIT_SLACK && offset <= 1.0F + HIT_SLACK;
	}

	/** Server: pick-block found nothing in the player's inventory, without a reported hit. */
	public static void pickFromStorage(ServerPlayer player, BlockPos pos) {
		pickFromStorage(player, pos, null);
	}

	/**
	 * Server: pick-block found nothing in the player's inventory.
	 *
	 * @param hit what the client aimed at (see {@link #reportedHit}), null for a synthetic hit at the block centre
	 */
	public static void pickFromStorage(ServerPlayer player, BlockPos pos, BlockHitResult hit) {
		if (!Config.get().buildersReachEnabled)return;
		if (player.isSpectator() || player.getAbilities().instabuild || !player.isAlive())return;
		if (!pickCooldown.tryUse(player, player.server.getTickCount()))return;

		ServerLevel level = player.serverLevel();
		if (new AABB(pos).distanceToSqr(player.getEyePosition()) > PICK_REACH * PICK_REACH)return;
		if (!level.isLoaded(pos))return;
		BlockState state = level.getBlockState(pos);
		if (state.isAir())return;
		ItemStack clone;
		try {
			clone = Platform.getCloneItemStack(level, hit, pos, player);
		} catch (RuntimeException e) {
			// A modded block that cannot say what it is: nothing to pick
			StorageMod.LOGGER.warn("Could not get the pick-block item of {} at {}", state, pos, e);
			return;
		}
		if (clone == null || clone.isEmpty())return;
		// The plain item, without block entity data, like survival pick-block
		ItemStack pick = new ItemStack(clone.getItem());
		if (pick.isEmpty())return;

		Inventory inv = player.getInventory();
		if (inv.findSlotMatchingItem(pick) != -1)return;

		boolean[] carries = new boolean[1];
		StorageTerminalBlockEntity te = findUsableTerminal(player, s -> {
			boolean c = s.getItem() instanceof WirelessTerminal && BoundTerminal.isBound(s);
			carries[0] |= c;
			return c;
		});
		if (te == null) {
			if (carries[0])player.displayClientMessage(Component.translatable("chat.obscuras_storage.pick_block.no_terminal"), true);
			return;
		}

		// Decide the slot before pulling, so nothing leaves the storage when there is no room
		Placement place = choosePlacement(inv.selected, i -> inv.getItem(i).isEmpty());
		if (place.kind() == PlacementKind.FULL) {
			player.displayClientMessage(Component.translatable("chat.obscuras_storage.pick_block.inventory_full"), true);
			return;
		}

		StoredItemStack pulled = te.pullStack(new StoredItemStack(pick, 1), pick.getMaxStackSize());
		if (pulled == null) {
			player.displayClientMessage(Component.translatable("chat.obscuras_storage.pick_block.not_in_storage", pick.getHoverName()), true);
			return;
		}
		ItemStack got = pulled.getActualStack();
		int slot = place.slot();
		if (!inv.getItem(slot).isEmpty()) {
			// Cannot happen on the server thread; never lose the pulled items
			returnItems(player, te, got);
			return;
		}
		switch (place.kind()) {
		case SELECTED:
			inv.setItem(slot, got);
			break;
		case HOTBAR:
			inv.setItem(slot, got);
			inv.selected = slot;
			player.connection.send(new ClientboundSetCarriedItemPacket(slot));
			break;
		case MAIN_SWAP:
			inv.setItem(slot, inv.getItem(inv.selected));
			inv.setItem(inv.selected, got);
			break;
		default:
			returnItems(player, te, got);
			return;
		}
		inv.setChanged();
	}

	/** Server: the Toggle Restock key. Toggles the first Advanced Wireless Terminal the player carries. */
	public static void toggleRestock(ServerPlayer player) {
		if (player.isSpectator())return;
		if (!Config.get().restockEnabled) {
			player.displayClientMessage(Component.translatable("chat.obscuras_storage.restock.disabled"), true);
			return;
		}
		ItemStack t = PlayerInvUtil.findItem(player, s -> s.getItem() instanceof AdvWirelessTerminalItem, ItemStack.EMPTY, Function.identity());
		if (t.isEmpty()) {
			player.displayClientMessage(Component.translatable("chat.obscuras_storage.restock.no_terminal"), true);
			return;
		}
		boolean on = !AdvWirelessTerminalItem.isRestockOn(t);
		AdvWirelessTerminalItem.setRestock(t, on);
		player.getInventory().setChanged();
		player.displayClientMessage(Component.translatable(on ? "chat.obscuras_storage.restock.on" : "chat.obscuras_storage.restock.off"), true);
	}

	/** Server, end of every tick. */
	public static void tickRestock(MinecraftServer server) {
		if (!Config.get().restockEnabled)return;
		int tick = server.getTickCount();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!isRestockTick(tick, p.getId()))continue;
			ItemStack hand = p.getMainHandItem();
			int amount = restockAmount(hand.getItem() instanceof BlockItem, hand.getCount(), hand.getMaxStackSize());
			if (amount <= 0)continue;
			restock(p, hand, amount);
		}
	}

	private static void restock(ServerPlayer player, ItemStack hand, int amount) {
		if (player.isSpectator() || player.getAbilities().instabuild || !player.isAlive())return;
		StorageTerminalBlockEntity te = findUsableTerminal(player, s -> s.getItem() instanceof AdvWirelessTerminalItem && AdvWirelessTerminalItem.isRestockOn(s));
		if (te == null)return;
		ItemStack one = hand.copy();
		one.setCount(1);
		StoredItemStack pulled = te.pullStack(new StoredItemStack(one, 1), amount);
		if (pulled == null)return;
		ItemStack got = pulled.getActualStack();
		if (player.getMainHandItem() == hand && ItemStack.isSameItemSameTags(hand, got)) {
			int add = Math.min(got.getCount(), hand.getMaxStackSize() - hand.getCount());
			if (add > 0) {
				hand.grow(add);
				got.shrink(add);
			}
		}
		if (!got.isEmpty())returnItems(player, te, got);
		player.getInventory().setChanged();
	}

	/**
	 * First carried item matching {@code candidate} whose bound terminal the player could open right
	 * now, as that terminal's block entity. Null when there is none.
	 */
	private static StorageTerminalBlockEntity findUsableTerminal(Player player, Predicate<ItemStack> candidate) {
		StorageTerminalBlockEntity[] found = new StorageTerminalBlockEntity[1];
		PlayerInvUtil.findItem(player, s -> {
			if (!candidate.test(s))return false;
			// A Trinkets slot scan tests every equipped stack, so only the first usable terminal is kept
			StorageTerminalBlockEntity te = BoundTerminal.resolve(player, s).getTerminal(player);
			if (te == null)return false;
			if (found[0] == null)found[0] = te;
			return true;
		}, ItemStack.EMPTY, Function.identity());
		return found[0];
	}

	/** Puts items back into the network; what does not fit goes to the player (or drops at their feet). */
	private static void returnItems(ServerPlayer player, StorageTerminalBlockEntity te, ItemStack stack) {
		if (stack.isEmpty())return;
		ItemStack rem = te.pushStack(stack);
		if (!rem.isEmpty())player.getInventory().placeItemBackInInventory(rem);
	}
}
