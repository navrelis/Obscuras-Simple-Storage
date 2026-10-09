package com.obscura.storage.item;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.obscura.storage.StorageModClient;
import com.obscura.storage.block.BasicInventoryHopperBlock;
import com.obscura.storage.doctor.DoctorCommand;
import com.obscura.storage.gui.InventoryConfiguratorMenu;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.util.BlockFilter;
import com.obscura.storage.util.ConfiguratorData;

public class InventoryConfiguratorItem extends Item implements ILeftClickListener {

	public InventoryConfiguratorItem() {
		super(new Properties().stacksTo(1));
	}

	@Override
	public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag flag) {
		StorageModClient.tooltip("inventory_configurator", tooltip);
	}

	/**
	 * Right click on a block, called by the use block callback before the block's own use and before
	 * {@link #useOn}. The Basic Inventory Hopper (in the inventory_configurator_skip tag, it has no block
	 * filter) uses the configurator to switch its mode/amount, handle it here and stop so the configurator
	 * neither becomes the hopper filter nor runs its own action (also when sneaking, where the block's use
	 * is skipped and useOn would run).
	 */
	public static InteractionResult onRightClickBlock(Player player, Level level, InteractionHand hand, BlockPos pos) {
		if (player.isSpectator() || !(player.getItemInHand(hand).getItem() instanceof InventoryConfiguratorItem))return InteractionResult.PASS;
		if (!(level.getBlockState(pos).getBlock() instanceof BasicInventoryHopperBlock))return InteractionResult.PASS;
		if (!level.isClientSide)
			BasicInventoryHopperBlock.useConfigurator(level, pos, player);
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		return doAction(context.getLevel(), context.getPlayer(), context.getItemInHand(), true, context.getClickedPos(), context.getHand());
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		doAction(level, player, stack, true, null, hand);
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
	}

	@Override
	public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
		return false;
	}

	@Override
	public boolean onLeftClick(ItemStack itemstack, BlockPos pos, Player player) {
		doAction(player.level(), player, itemstack, false, pos, InteractionHand.MAIN_HAND);
		return true;
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
		if (!level.isClientSide && entity instanceof Player player) {
			if (stack != player.getItemInHand(InteractionHand.MAIN_HAND) && stack != player.getItemInHand(InteractionHand.OFF_HAND)) {
				ConfiguratorData.State state = ConfiguratorData.get(stack);
				if (state.showInvBox() || state.massSelect()) {
					ConfiguratorData.set(stack, state.hiddenItem(level.getGameTime()));
				}
			}
		}
	}

	private InteractionResult doAction(Level level, Player player, ItemStack stack, boolean rightClick, BlockPos useOn, InteractionHand hand) {
		if (player == null) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide) {
			return InteractionResult.SUCCESS;
		}

		ConfiguratorData.Configurator conf = new ConfiguratorData.Configurator(stack, level.getGameTime());
		if (conf.debounce()) {
			return InteractionResult.SUCCESS;
		}

		if (conf.isBound()) {
			BlockPos bound = conf.bound();
			if (player.distanceToSqr(bound.getX(), bound.getY(), bound.getZ()) < 16 * 16 && level.isLoaded(bound)) {
				BlockFilter filter = BlockFilter.getOrCreateFilterAt(level, bound);
				if (filter == null) {
					conf.clear();
				} else if (useOn != null && conf.selecting() && !(player.isSecondaryUseActive() && rightClick)) {
					if (rightClick) {
						if (!useOn.equals(bound)) {
							if (!filter.getConnectedBlocks().remove(useOn)) {
								filter.addConnected(level, useOn);
							} else {
								filter.markDirty();
							}
							conf.setSelection(filter.getConnectedBlocks());
							level.blockEntityChanged(bound);
							return InteractionResult.SUCCESS;
						}
					} else if (!player.isSecondaryUseActive()) {
						if (conf.massSelect()) {
							BlockPos boxStart = conf.boxStart();
							long dx = (long) Math.abs(boxStart.getX() - useOn.getX()) + 1;
							long dy = (long) Math.abs(boxStart.getY() - useOn.getY()) + 1;
							long dz = (long) Math.abs(boxStart.getZ() - useOn.getZ()) + 1;
							// inclusive block dimensions (a flat or single-line selection is still limited)
							if (dx * dy * dz < 64) {
								BlockFilter.bounds(boxStart, useOn).forEach(p -> filter.addConnected(level, p));
								level.blockEntityChanged(bound);
								conf.massSelectEnd(filter.getConnectedBlocks());
							} else {
								player.displayClientMessage(Component.translatable("chat.obscuras_storage.area_too_big"), true);
								conf.massSelectEnd();
							}
						} else {
							conf.massSelectStart(useOn);
						}
						return InteractionResult.SUCCESS;
					} else {
						conf.massSelectEnd();
						return InteractionResult.SUCCESS;
					}
					conf.clear();
				} else if (rightClick) {
					openMenu(level, player, bound, filter, hand);
					conf.clear();
					return InteractionResult.SUCCESS;
				}
			}
		}

		if (useOn != null && player.distanceToSqr(useOn.getX(), useOn.getY(), useOn.getZ()) < 256) {
			if (!rightClick && level.getBlockEntity(useOn) instanceof InventoryConnectorBlockEntity connector) {
				conf.clear();
				conf.showInvBox(connector.getConnectedBlocks());
				// One line network summary for everyone (Network Doctor, no timings)
				if (player instanceof ServerPlayer sp && connector.getDoctorStats() != null)
					sp.sendSystemMessage(DoctorCommand.summaryLine(connector, sp.server));
				return InteractionResult.SUCCESS;
			}

			BlockFilter filter = BlockFilter.findBlockFilterAt(level, useOn);
			if (filter == null) {
				return InteractionResult.SUCCESS;
			}
			if (rightClick) {
				openMenu(level, player, useOn, filter, hand);
				for (BlockPos p : filter.getConnectedBlocks()) {
					if (!p.equals(filter.getMainPos())) {
						BlockFilter.removeFilterAt(level, p);
					}
				}
			}
			conf.showInvBox(filter.getConnectedBlocks());
			return InteractionResult.SUCCESS;
		}

		if (rightClick) {
			player.displayClientMessage(Component.translatable("tooltip.obscuras_storage.inventory_configurator.selection"), true);
		}
		return InteractionResult.SUCCESS;
	}

	private void openMenu(Level level, Player player, BlockPos pos, BlockFilter filter, InteractionHand hand) {
		BlockEntity be = level.getBlockEntity(pos);
		Component blockName = null;
		if (be instanceof net.minecraft.world.Nameable n) {
			blockName = n.getDisplayName();
		}
		if (blockName == null) {
			blockName = level.getBlockState(pos).getBlock().getName();
		}
		final Component title = blockName;
		player.openMenu(new SimpleMenuProvider((id, inv, pl) -> new InventoryConfiguratorMenu(id, inv, pos, filter, hand),
				Component.translatable("menu.obscuras_storage.inventory_configurator", title)));
	}
}
