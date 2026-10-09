package com.obscura.storage.tile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Content;
import com.obscura.storage.gui.EjectorMenu;
import com.obscura.storage.util.EjectorMode;
import com.obscura.storage.util.FilterAmount;
import com.obscura.storage.util.FilterBlockLogic;
import com.obscura.storage.util.FilterBlockStatus;

/**
 * Ejector: takes matching items out of the source and drops them in front of the block like a vanilla
 * dropper. Works only while powered: {@link EjectorMode#CONTINUOUS} drops a batch every cooldown,
 * {@link EjectorMode#PULSE} one batch per rising edge, on the next server tick whatever the cooldown
 * (like the Basic Inventory Hopper's pulse), so a redstone clock faster than the cooldown loses no pulse.
 */
public class EjectorBlockEntity extends AbstractFilterBlockEntity {
	public static final String MODE_TAG = "Mode", AMOUNT_TAG = "Amount";
	/** The dropper's values: how far in front of the centre the item appears, and its speed. */
	private static final double DROP_OFFSET = 0.7;
	private static final int DROP_SPEED = 6;
	/** Vanilla level events of a dropper: its click and the smoke puff at the front. */
	private static final int EVENT_DISPENSE_SOUND = 1000, EVENT_SMOKE = 2000;

	private EjectorMode mode = EjectorMode.CONTINUOUS;
	private FilterAmount amount = FilterAmount.ITEM;
	/** A rising edge is waiting for the next server tick. Not saved, like the hopper's. */
	private boolean pulsePending;

	public EjectorBlockEntity(BlockPos pos, BlockState state) {
		super(Content.ejectorTile.get(), pos, state);
	}

	@Override
	public void saveAdditional(CompoundTag compound) {
		super.saveAdditional(compound);
		compound.putByte(MODE_TAG, (byte) mode.ordinal());
		compound.putByte(AMOUNT_TAG, (byte) amount.ordinal());
	}

	@Override
	public void load(CompoundTag nbtIn) {
		super.load(nbtIn);
		mode = EjectorMode.byId(nbtIn.getByte(MODE_TAG));
		amount = FilterAmount.byId(nbtIn.getByte(AMOUNT_TAG), FilterAmount.ITEM);
	}

	public EjectorMode getMode() {
		return mode;
	}

	public void setMode(EjectorMode mode) {
		this.mode = mode;
		// Switching into Pulse mode while powered must not fire a pulse
		pulsePending = false;
		resetSearch();
		setChanged();
	}

	public FilterAmount getAmount() {
		return amount;
	}

	public void setAmount(FilterAmount amount) {
		this.amount = amount;
		resetSearch();
		setChanged();
	}

	@Override
	protected void onPowerChanged(boolean rising) {
		// Dropped on the next server tick of this block entity (same game tick for most redstone sources)
		if (rising && mode == EjectorMode.PULSE)pulsePending = true;
	}

	@Override
	protected boolean waitsForCooldown() {
		return FilterBlockLogic.ejectorWaitsForCooldown(mode);
	}

	@Override
	protected boolean claimRun() {
		if (!FilterBlockLogic.ejectorMayRun(mode, isPowered(), pulsePending))return false;
		pulsePending = false;
		return true;
	}

	@Override
	protected boolean prepare() {
		return level.isLoaded(frontPos);
	}

	@Override
	protected boolean operate() {
		ItemStack batch = source.extractBatch(filter, amount);
		if (batch.isEmpty())return false;
		if (!drop(batch))source.giveBack(batch);
		return true;
	}

	/**
	 * Drops {@code stack} at the front face with the dropper's position, velocity, sound and smoke
	 * (DefaultDispenseItemBehavior.spawnItem, which does not tell whether the entity was added).
	 *
	 * @return false when the world did not take the item entity; the stack is untouched then
	 */
	private boolean drop(ItemStack stack) {
		Direction dir = getFacing();
		double x = worldPosition.getX() + 0.5 + DROP_OFFSET * dir.getStepX();
		double y = worldPosition.getY() + 0.5 + DROP_OFFSET * dir.getStepY();
		double z = worldPosition.getZ() + 0.5 + DROP_OFFSET * dir.getStepZ();
		y -= dir.getAxis() == Direction.Axis.Y ? 0.125 : 0.15625;
		ItemEntity e = new ItemEntity(level, x, y, z, stack);
		double d = level.random.nextDouble() * 0.1 + 0.2;
		double spread = 0.0172275 * DROP_SPEED;
		e.setDeltaMovement(level.random.triangle(dir.getStepX() * d, spread), level.random.triangle(0.2, spread), level.random.triangle(dir.getStepZ() * d, spread));
		if (!level.addFreshEntity(e))return false;
		level.levelEvent(EVENT_DISPENSE_SOUND, worldPosition, 0);
		level.levelEvent(EVENT_SMOKE, worldPosition, dir.get3DDataValue());
		return true;
	}

	@Override
	protected FilterBlockStatus blockedStatus() {
		return isPowered() ? null : FilterBlockStatus.NEEDS_REDSTONE;
	}

	@Override
	protected String getMenuKey() {
		return "ejector";
	}

	@Override
	public AbstractContainerMenu createMenu(int p_createMenu_1_, Inventory p_createMenu_2_, Player p_createMenu_3_) {
		return new EjectorMenu(p_createMenu_1_, p_createMenu_2_, this);
	}
}
