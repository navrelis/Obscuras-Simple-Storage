package com.obscura.storage.tile;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.ShulkerDockBlock;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.util.ITidyExempt;
import com.obscura.storage.util.RedstoneSignals;
import com.obscura.storage.util.ShulkerDockContainer;

/**
 * Holds one shulker box and exposes its 27 slots ({@link #getInv()}) to the platform inventory APIs.
 *
 * <p>Storage model: the docked box item is kept in {@link #box} WITHOUT its contents (the
 * {@code Items} list of its {@code BlockEntityTag} is removed while docked); the contents live in
 * {@link #inv}. They are written back into a copy of the box item, in the vanilla 1.20.1 shulker box
 * item format ({@code BlockEntityTag.Items}, one compound with a {@code Slot} byte per non-empty
 * slot), whenever the box leaves the dock ({@link #undock}: taken out, swapped, block broken or
 * exploded) and whenever the block entity is saved. So at any moment exactly one place holds the
 * items: the live container while docked, the box item after it left.
 *
 * <p>Everything else of the box item (name, lore, enchantments, {@code BlockEntityTag.Lock}, the
 * {@code id} of the block entity data) stays as it came in: a box that leaves the dock unchanged is
 * identical to the one that went in, also an empty box with an empty {@code Items} list (as vanilla
 * drops an emptied box) and one without any data (a crafted box).
 */
public class ShulkerDockBlockEntity extends BlockEntity implements ComparatorOutput.Source, ITidyExempt {
	/** Items that can be docked: by default the 17 vanilla shulker boxes. */
	public static final TagKey<Item> DOCKABLE = TagKey.create(Registries.ITEM, new ResourceLocation(StorageMod.modid, "dockable"));
	private static final String BOX_TAG = "Box";
	/** Item NBT of the block entity data, and its item list (vanilla names). */
	private static final String BE_TAG = "BlockEntityTag", ITEMS = "Items", LOOT_TABLE = "LootTable";

	private final ShulkerDockContainer inv = new ShulkerDockContainer(this);
	/** The docked box, one item, contents stripped. EMPTY when the dock is empty. */
	private ItemStack box = ItemStack.EMPTY;
	/** Whether the docked box came with an {@code Items} list, so an empty one leaves with it again. */
	private boolean boxHadItems;
	/** Cached comparator signal, -1 after a change until it is read again. */
	private int comparator = -1;

	public ShulkerDockBlockEntity(BlockPos pos, BlockState state) {
		super(Content.shulkerDockTile.get(), pos, state);
	}

	/**
	 * Whether {@code stack} can be docked: in {@link #DOCKABLE}, and its stored contents fit the 27
	 * slots exactly as vanilla would load them (a modded box with more slots, a slot listed twice or
	 * a box whose loot table was not rolled yet would otherwise lose items).
	 */
	public static boolean isDockable(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(DOCKABLE))return false;
		CompoundTag bet = BlockItem.getBlockEntityData(stack);
		if (bet == null)return true;
		// Unopened loot box: the placed box would roll the loot table and ignore the written items
		if (bet.contains(LOOT_TABLE))return false;
		if (!bet.contains(ITEMS))return true;
		if (!bet.contains(ITEMS, Tag.TAG_LIST))return false;
		// A list of anything but compounds would be read as empty
		if (bet.get(ITEMS) instanceof ListTag raw && !raw.isEmpty() && raw.getElementType() != Tag.TAG_COMPOUND)return false;
		ListTag list = bet.getList(ITEMS, Tag.TAG_COMPOUND);
		boolean[] used = new boolean[ShulkerDockContainer.SLOTS];
		for (int i = 0; i < list.size(); i++) {
			int slot = list.getCompound(i).getByte("Slot") & 255;
			if (slot >= ShulkerDockContainer.SLOTS || used[slot])return false;
			used[slot] = true;
		}
		return true;
	}

	public boolean hasBox() {
		return !box.isEmpty();
	}

	/** The exposed inventory: 27 slots while a box is docked, none otherwise. */
	public Container getInv() {
		return inv;
	}

	/** A copy of the docked box as it would leave the dock (contents included), EMPTY when none. */
	public ItemStack peekBox() {
		return hasBox() ? withContents() : ItemStack.EMPTY;
	}

	/**
	 * Docks {@code stack} (exactly one dockable item, which the dock takes over) when the dock is
	 * empty. Returns whether it was docked. Server side.
	 */
	public boolean dock(ItemStack stack) {
		if (hasBox() || stack.getCount() != 1 || !isDockable(stack))return false;
		ItemStack b = stack.copy();
		NonNullList<ItemStack> items = NonNullList.withSize(ShulkerDockContainer.SLOTS, ItemStack.EMPTY);
		boolean had = readContents(b, items);
		stripContents(b);
		inv.load(items);
		box = b;
		boxHadItems = had;
		changed();
		return true;
	}

	/**
	 * Takes the box out: returns it with the current contents written back and leaves the dock empty
	 * (0 slots). EMPTY when there was no box. Server side.
	 */
	public ItemStack undock() {
		if (!hasBox())return ItemStack.EMPTY;
		ItemStack out = withContents();
		// From here on the items only exist in `out`: the live slots are cleared and the dock reports
		// 0 slots before anything else can run (everything is on the server thread).
		box = ItemStack.EMPTY;
		boxHadItems = false;
		inv.clear();
		changed();
		return out;
	}

	/**
	 * A right click with a dockable item in {@code hand}: docks one of it, and hands out the box that
	 * was docked before (into that hand when it is now empty, else the inventory, else dropped).
	 * The held box is always moved, also in creative mode, so the dock never holds a copy.
	 */
	public void insertFromHand(Player player, InteractionHand hand) {
		ItemStack held = player.getItemInHand(hand);
		if (!isDockable(held))return;
		ItemStack one = held.copyWithCount(1);
		ItemStack old = undock();
		if (!dock(one)) {
			// Cannot happen (checked above); put the old box back so nothing is lost.
			if (!old.isEmpty())dock(old);
			return;
		}
		held.shrink(1);
		if (!old.isEmpty())give(player, hand, old);
		playSound(true);
	}

	/** A right click with an empty main hand: the box goes into that hand. */
	public void takeOutToHand(Player player) {
		ItemStack out = undock();
		if (out.isEmpty())return;
		give(player, InteractionHand.MAIN_HAND, out);
		playSound(false);
	}

	/** The block is gone (broken, exploded, replaced): the box drops whole, with its contents. */
	public void dropBox() {
		ItemStack out = undock();
		if (!out.isEmpty() && level != null)
			Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, out);
	}

	/** Last resort for a stack written into the container while no box is docked: drop it, never void it. */
	public void rescue(ItemStack stack) {
		StorageMod.LOGGER.warn("Shulker Dock at {}: an item was written while no box is docked, dropping {}", worldPosition, stack);
		if (level != null && !level.isClientSide)
			Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5, stack.copy());
	}

	private static void give(Player player, InteractionHand hand, ItemStack stack) {
		if (player.getItemInHand(hand).isEmpty())player.setItemInHand(hand, stack);
		else if (!player.getInventory().add(stack))player.drop(stack, false);
	}

	private void playSound(boolean in) {
		if (level == null)return;
		level.playSound(null, worldPosition, in ? SoundEvents.SHULKER_BOX_CLOSE : SoundEvents.SHULKER_BOX_OPEN, SoundSource.BLOCKS, 0.5F, 1.1F);
	}

	/** A copy of the docked box with the live contents written into its block entity data. */
	private ItemStack withContents() {
		ItemStack out = box.copy();
		writeContents(out, inv.items(), boxHadItems);
		return out;
	}

	/**
	 * Reads the contents of a box item into {@code items} the way vanilla loads a placed box
	 * ({@code ContainerHelper.loadAllItems}). Returns whether the item had an {@code Items} list.
	 */
	private static boolean readContents(ItemStack stack, NonNullList<ItemStack> items) {
		CompoundTag bet = BlockItem.getBlockEntityData(stack);
		if (bet == null || !bet.contains(ITEMS, Tag.TAG_LIST))return false;
		ContainerHelper.loadAllItems(bet, items);
		return true;
	}

	/**
	 * Writes {@code items} into the box item (vanilla shulker box item format). A box without contents
	 * keeps no {@code Items} list unless it came with one ({@code keepEmptyList}), so an empty box stays
	 * identical to the one that went in. A vanilla box that gets its first contents also gets the block
	 * entity id, as vanilla writes it.
	 */
	private static void writeContents(ItemStack stack, List<ItemStack> items, boolean keepEmptyList) {
		boolean any = false;
		for (ItemStack s : items) {
			if (!s.isEmpty()) {
				any = true;
				break;
			}
		}
		if (!any && !keepEmptyList) {
			stripContents(stack);
			return;
		}
		boolean created = BlockItem.getBlockEntityData(stack) == null;
		CompoundTag bet = stack.getOrCreateTagElement(BE_TAG);
		NonNullList<ItemStack> list = NonNullList.withSize(items.size(), ItemStack.EMPTY);
		for (int i = 0; i < items.size(); i++)list.set(i, items.get(i));
		ContainerHelper.saveAllItems(bet, list, true);
		// Like BlockItem.setBlockEntityData; only for data written from scratch, existing data keeps its shape
		if (created && stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock)
			bet.putString("id", String.valueOf(BlockEntityType.getKey(BlockEntityType.SHULKER_BOX)));
	}

	/** Removes the {@code Items} list, everything else of the block entity data stays. */
	private static void stripContents(ItemStack stack) {
		CompoundTag bet = BlockItem.getBlockEntityData(stack);
		if (bet != null)bet.remove(ITEMS);
	}

	/** After a dock or undock: comparator, chunk dirty, and the window shows the new box. */
	private void changed() {
		comparator = -1;
		setChanged();
		if (level != null && !level.isClientSide && !isRemoved()) {
			// The world's state, not the cached one: while the block is being removed (onRemove) the
			// world already holds the new block, and it must never be set back to a dock.
			BlockState st = level.getBlockState(worldPosition);
			if (!(st.getBlock() instanceof ShulkerDockBlock) || level.getBlockEntity(worldPosition) != this)return;
			ShulkerDockBlock.Box b = ShulkerDockBlock.Box.of(box);
			if (st.getValue(ShulkerDockBlock.BOX) != b)
				level.setBlock(worldPosition, st.setValue(ShulkerDockBlock.BOX, b), Block.UPDATE_ALL);
			level.gameEvent(null, GameEvent.BLOCK_CHANGE, worldPosition);
		}
	}

	public void invalidateComparator() {
		comparator = -1;
	}

	@Override
	public void setChanged() {
		comparator = -1;
		super.setChanged();
	}

	/** The vanilla container formula over the 27 slots; 0 without a box and for an empty box. */
	@Override
	public int getComparatorSignal() {
		if (comparator < 0) {
			if (!hasBox()) {
				comparator = 0;
			} else {
				float sum = 0;
				boolean any = false;
				for (ItemStack s : inv.items()) {
					if (s.isEmpty())continue;
					any = true;
					sum += RedstoneSignals.slotFill(s.getCount(), Math.min(inv.getMaxStackSize(), s.getMaxStackSize()));
				}
				comparator = RedstoneSignals.fill(sum, any, ShulkerDockContainer.SLOTS);
			}
		}
		return comparator;
	}

	/** Comparator formula reference (vanilla), for tests: equal to {@link #getComparatorSignal()} while docked. */
	public int vanillaComparatorSignal() {
		return hasBox() ? AbstractContainerMenu.getRedstoneSignalFromContainer(inv) : 0;
	}

	@Override
	protected void saveAdditional(CompoundTag tag) {
		super.saveAdditional(tag);
		// The box is saved as the item it would be when taken out: contents written back.
		if (hasBox())tag.put(BOX_TAG, withContents().save(new CompoundTag()));
	}

	@Override
	public void load(CompoundTag tag) {
		super.load(tag);
		ItemStack loaded = tag.contains(BOX_TAG, Tag.TAG_COMPOUND) ? ItemStack.of(tag.getCompound(BOX_TAG)) : ItemStack.EMPTY;
		box = ItemStack.EMPTY;
		boxHadItems = false;
		inv.clear();
		if (!loaded.isEmpty()) {
			// No tag check here: a box that was docked stays docked even if a pack removed it from the tag.
			NonNullList<ItemStack> items = NonNullList.withSize(ShulkerDockContainer.SLOTS, ItemStack.EMPTY);
			boolean had = readContents(loaded, items);
			loaded.setCount(1);
			stripContents(loaded);
			inv.load(items);
			box = loaded;
			boxHadItems = had;
		}
		comparator = -1;
		// Data put into a placed dock (e.g. a creative pick-block copy): show the right window.
		if (level != null && !level.isClientSide)changed();
	}
}
