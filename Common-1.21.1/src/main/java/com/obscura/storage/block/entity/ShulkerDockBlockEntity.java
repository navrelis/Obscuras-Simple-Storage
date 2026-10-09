package com.obscura.storage.block.entity;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.ShulkerDockBlock;
import com.obscura.storage.inventory.ITidyExempt;
import com.obscura.storage.migration.LegacyData;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.util.ComparatorOutput;
import com.obscura.storage.util.RedstoneSignals;
import com.obscura.storage.util.ShulkerDockContainer;

/**
 * Holds one shulker box and exposes its 27 slots ({@link #getInv()}) to the platform inventory APIs.
 *
 * <p>Storage model: the docked box item is kept in {@link #box} WITHOUT its contents (its
 * {@code minecraft:container} component is emptied while docked); the contents live in
 * {@link #inv}. They are written back into a copy of the box item whenever the box leaves the dock
 * ({@link #undock}: taken out, swapped, block broken or exploded) and whenever the block entity is
 * saved. So at any moment exactly one place holds the items: the live container while docked, the
 * box item after it left.
 */
public class ShulkerDockBlockEntity extends BlockEntity implements ComparatorOutput.Source, ITidyExempt {
	/** Items that can be docked: by default the 17 vanilla shulker boxes. */
	public static final TagKey<Item> DOCKABLE = TagKey.create(Registries.ITEM, ResourceLocation.tryBuild(StorageMod.modid, "dockable"));
	private static final String BOX_TAG = "Box";

	private final ShulkerDockContainer inv = new ShulkerDockContainer(this);
	/** The docked box, one item, contents stripped. EMPTY when the dock is empty. */
	private ItemStack box = ItemStack.EMPTY;
	/** Cached comparator signal, -1 after a change until it is read again. */
	private int comparator = -1;

	public ShulkerDockBlockEntity(BlockPos pos, BlockState state) {
		super(Content.shulkerDockBE.get(), pos, state);
	}

	/**
	 * Whether {@code stack} can be docked: in {@link #DOCKABLE}, no unrolled loot table
	 * ({@code minecraft:container_loot}), and its stored contents fit the 27 slots (a modded box with more
	 * slots would otherwise lose items).
	 */
	public static boolean isDockable(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(DOCKABLE))return false;
		// Unopened loot box: the placed box would roll the loot table instead of keeping the written contents
		if (stack.has(DataComponents.CONTAINER_LOOT))return false;
		ItemContainerContents c = stack.get(DataComponents.CONTAINER);
		return c == null || c.stream().count() <= ShulkerDockContainer.SLOTS;
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
		NonNullList<ItemStack> items = NonNullList.withSize(ShulkerDockContainer.SLOTS, ItemStack.EMPTY);
		ItemContainerContents c = stack.get(DataComponents.CONTAINER);
		if (c != null)c.copyInto(items);
		ItemStack b = stack.copy();
		stripContents(b);
		inv.load(items);
		box = b;
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

	/** A copy of the docked box with the live contents written into its container component. */
	private ItemStack withContents() {
		ItemStack out = box.copy();
		writeContents(out, inv.items());
		return out;
	}

	/** Sets the box item's contents (vanilla shulker box format), keeping an empty box identical to a fresh one. */
	private static void writeContents(ItemStack stack, List<ItemStack> items) {
		ItemContainerContents c = ItemContainerContents.fromItems(items);
		if (c.equals(ItemContainerContents.EMPTY) && !stack.getPrototype().has(DataComponents.CONTAINER))stack.remove(DataComponents.CONTAINER);
		else stack.set(DataComponents.CONTAINER, c);
	}

	private static void stripContents(ItemStack stack) {
		writeContents(stack, List.of());
	}

	/**
	 * A box docked by the 1.20.1 builds whose block entity data the data fixers did not turn into a
	 * container component (only vanilla block entity ids are converted, a pack may make other items
	 * dockable): its 1.20.1 {@code Items} list (byte {@code Slot}) is read into {@code items} and removed
	 * from the block entity data. Entries that find no slot stay in the block entity data, nothing is lost.
	 */
	private static void takeLegacyBlockEntityItems(ItemStack box, NonNullList<ItemStack> items, HolderLookup.Provider provider) {
		CustomData data = box.get(DataComponents.BLOCK_ENTITY_DATA);
		if (data == null || !data.contains("Items"))return;
		CompoundTag bet = data.copyTag();
		ListTag list = bet.getList("Items", Tag.TAG_COMPOUND);
		ListTag left = new ListTag();
		for (int i = 0; i < list.size(); i++) {
			CompoundTag e = list.getCompound(i);
			ItemStack s = LegacyData.parseItemStack(provider, e);
			if (s.isEmpty())continue;
			int slot = e.getByte("Slot") & 255;
			if (slot >= items.size() || !items.get(slot).isEmpty()) {
				slot = items.indexOf(ItemStack.EMPTY);
				if (slot < 0) {
					left.add(e);
					continue;
				}
			}
			items.set(slot, s);
		}
		if (left.isEmpty())bet.remove("Items");
		else bet.put("Items", left);
		box.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(bet));
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
					sum += RedstoneSignals.slotFill(s.getCount(), inv.getMaxStackSize(s));
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
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.saveAdditional(tag, provider);
		// The box is saved as the item it would be when taken out: contents written back.
		if (hasBox())tag.put(BOX_TAG, withContents().save(provider));
		// Not a PlatformBlockEntity: keep the loader specific block filter (Configurator) portable
		Platform.writeCrossLoaderData(this, tag, provider);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
		super.loadAdditional(tag, provider);
		// A dock saved by the 1.20.1 builds holds a 1.20.1 item stack (contents in BlockEntityTag.Items):
		// LegacyData sends it through the data fixers, which move vanilla box contents to the container component.
		Tag boxTag = tag.get(BOX_TAG);
		boolean legacy = boxTag instanceof CompoundTag ct && LegacyData.isLegacyItemStack(ct);
		ItemStack loaded = boxTag != null ? LegacyData.parseItemStack(provider, boxTag).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
		box = ItemStack.EMPTY;
		inv.clear();
		if (!loaded.isEmpty()) {
			// No tag check here: a box that was docked stays docked even if a pack removed it from the tag.
			NonNullList<ItemStack> items = NonNullList.withSize(ShulkerDockContainer.SLOTS, ItemStack.EMPTY);
			ItemContainerContents c = loaded.get(DataComponents.CONTAINER);
			if (c != null)c.copyInto(items);
			else if (legacy)takeLegacyBlockEntityItems(loaded, items, provider);
			loaded.setCount(1);
			stripContents(loaded);
			inv.load(items);
			box = loaded;
		}
		comparator = -1;
		Platform.readCrossLoaderData(this, tag, provider);
		// Data put into a placed dock (e.g. a creative pick-block copy): show the right window.
		if (level != null && !level.isClientSide)changed();
	}
}
