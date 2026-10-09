package com.obscura.storage.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Content;
import com.obscura.storage.inventory.IInventoryAccess;
import com.obscura.storage.inventory.IInventoryAccess.IInventoryChangeTracker;
import com.obscura.storage.menu.DisposalMenu;
import com.obscura.storage.util.DestroyedCounter;
import com.obscura.storage.util.FilterAmount;
import com.obscura.storage.util.FilterBlockLogic;
import com.obscura.storage.util.FilterBlockStatus;

/**
 * Disposal: takes matching items out of the source and destroys them for good, up to one stack per
 * operation, and counts what it destroyed. A newly placed Disposal is paused; a redstone signal stops
 * a running one. The counts live in this block entity only, breaking the block loses them.
 */
public class DisposalBlockEntity extends AbstractFilterBlockEntity {
	public static final String PAUSED_TAG = "Paused";

	/** Started by hand in the GUI: a Disposal that was just placed must never destroy anything. */
	private boolean paused = true;
	private final DestroyedCounter destroyed = new DestroyedCounter();

	public DisposalBlockEntity(BlockPos pos, BlockState state) {
		super(Content.disposalBE.get(), pos, state);
	}

	@Override
	public void saveAdditional(CompoundTag compound, HolderLookup.Provider provider) {
		super.saveAdditional(compound, provider);
		compound.putBoolean(PAUSED_TAG, paused);
		destroyed.save(compound);
	}

	@Override
	public void loadAdditional(CompoundTag nbtIn, HolderLookup.Provider provider) {
		super.loadAdditional(nbtIn, provider);
		// A missing key reads as paused: never start destroying because data is incomplete
		paused = !nbtIn.contains(PAUSED_TAG) || nbtIn.getBoolean(PAUSED_TAG);
		destroyed.load(nbtIn);
	}

	public boolean isPaused() {
		return paused;
	}

	public void setPaused(boolean paused) {
		this.paused = paused;
		resetSearch();
		setChanged();
	}

	public DestroyedCounter getDestroyed() {
		return destroyed;
	}

	public void resetDestroyed() {
		destroyed.reset();
		setChanged();
	}

	@Override
	protected boolean claimRun() {
		return FilterBlockLogic.disposalMayRun(paused, isPowered());
	}

	@Override
	protected boolean operate(IInventoryAccess source, IInventoryChangeTracker tt) {
		ItemStack batch = extractBatch(source, tt, FilterAmount.STACK);
		if (batch.isEmpty())return false;
		// Only what the filter matches is ever destroyed, whatever a source handed out
		if (!filter.test(batch)) {
			giveBack(source, batch);
			return true;
		}
		destroyed.add(BuiltInRegistries.ITEM.getKey(batch.getItem()), batch.getCount());
		setChanged();
		return true;
	}

	@Override
	protected FilterBlockStatus blockedStatus() {
		if (paused)return FilterBlockStatus.PAUSED;
		return isPowered() ? FilterBlockStatus.STOPPED_BY_REDSTONE : null;
	}

	@Override
	protected String getMenuKey() {
		return "disposal";
	}

	@Override
	public AbstractContainerMenu createMenu(int p_createMenu_1_, Inventory p_createMenu_2_, Player p_createMenu_3_) {
		return new DisposalMenu(p_createMenu_1_, p_createMenu_2_, this);
	}
}
