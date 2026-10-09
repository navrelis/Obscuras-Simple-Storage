package com.obscura.storage.migration;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.components.ConditionFilterComponent;
import com.obscura.storage.components.ConfiguratorComponent;
import com.obscura.storage.components.SimpleItemFilterComponent;
import com.obscura.storage.components.TagFilterComponent;
import com.obscura.storage.components.WorldPos;

/**
 * The settings of the mod's items as the 1.20.1 builds stored them, moved to the 1.21.1 data components.
 *
 * <p>Minecraft's data fixers turn the {@code tag} of an item stack into components; the keys it does not
 * know (all of the mod's own) end up in {@code minecraft:custom_data} unchanged. This build reads its own
 * components only, so without this an upgraded Item Filter would be empty and a Wireless Terminal unbound.
 * {@link #upgrade} moves each known key to its component and removes it from the custom data:
 * <table>
 * <tr><th>item</th><th>1.20.1 NBT</th><th>1.21.1 component</th></tr>
 * <tr><td>Item Filter</td><td>{@code Filter} (list of 1.20.1 stacks), {@code matchNBT}, {@code allowlist}</td><td>{@code obscuras_storage:simple_item_filter}</td></tr>
 * <tr><td>Tag Item Filter</td><td>{@code tags} (list of strings), {@code allowlist}</td><td>{@code obscuras_storage:tag_filter}</td></tr>
 * <tr><td>Condition Filter</td><td>{@code Required}, {@code Excluded}</td><td>{@code obscuras_storage:condition_filter}</td></tr>
 * <tr><td>(Adv.) Wireless Terminal</td><td>{@code BindX/Y/Z}, {@code BindDim}; {@code Restock}</td><td>{@code obscuras_storage:bound_pos}; {@code obscuras_storage:restock}</td></tr>
 * <tr><td>Inventory Configurator</td><td>{@code ts_configurator}</td><td>{@code obscuras_storage:configurator}</td></tr>
 * <tr><td>Paint Kit</td><td>{@code block} (block state)</td><td>{@code obscuras_storage:paint_state}</td></tr>
 * </table>
 * A component the stack already has wins (its legacy keys are then left alone). It runs from
 * {@code Item#verifyComponentsAfterLoad} of these items, i.e. every time such a stack is read or copied;
 * a stack without custom data costs one component lookup.
 */
public final class LegacyItemComponents {
	private static final Logger LOGGER = LogManager.getLogger(StorageMod.modid);
	/** Registries of the running server, for the item stacks inside an Item Filter. */
	private static volatile HolderLookup.Provider registries;

	private LegacyItemComponents() {}

	/** Set when a server starts (before its worlds load), cleared when it stopped. */
	public static void setRegistries(@Nullable HolderLookup.Provider provider) {
		registries = provider;
	}

	/** {@code Item#verifyComponentsAfterLoad} of the mod's items. */
	public static void onLoad(ItemStack stack) {
		CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
		if (cd == null || cd.isEmpty())return;
		try {
			upgrade(stack, registries);
		} catch (RuntimeException e) {
			// Never break loading a stack; the keys stay where they are.
			LOGGER.error("Could not move the 1.20.1 data of {}", stack, e);
		}
	}

	/**
	 * Moves the 1.20.1 keys of {@code stack}'s custom data to its components.
	 *
	 * @param provider registries to read item stacks with (Item Filter); without them the Item Filter's
	 *                 items stay in the custom data until a call with registries
	 * @return whether the stack changed
	 */
	public static boolean upgrade(ItemStack stack, @Nullable HolderLookup.Provider provider) {
		CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
		if (cd == null || cd.isEmpty())return false;
		CompoundTag tag = cd.copyTag();
		Item item = stack.getItem();
		boolean changed;
		if (item == Content.itemFilter.get())changed = itemFilter(stack, tag, provider);
		else if (item == Content.tagItemFilter.get())changed = tagFilter(stack, tag);
		else if (item == Content.conditionFilter.get())changed = conditionFilter(stack, tag);
		else if (item == Content.wirelessTerminal.get() || item == Content.advWirelessTerminal.get())changed = terminal(stack, tag);
		else if (item == Content.invConfig.get())changed = configurator(stack, tag);
		else if (item == Content.paintingKit.get())changed = paintKit(stack, tag);
		else return false;
		if (!changed)return false;
		if (tag.isEmpty())stack.remove(DataComponents.CUSTOM_DATA);
		else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return true;
	}

	/** Whether the stack sets the component itself (not only the item's default, see the Configurator). */
	private static boolean hasOwn(ItemStack stack, DataComponentType<?> type) {
		var v = stack.getComponentsPatch().get(type);
		return v != null && v.isPresent();
	}

	private static boolean itemFilter(ItemStack stack, CompoundTag tag, @Nullable HolderLookup.Provider provider) {
		if (!tag.contains("Filter") && !tag.contains("matchNBT") && !tag.contains("allowlist"))return false;
		if (hasOwn(stack, Content.simpleItemFilterComponent.get()))return false;
		ListTag list = tag.getList("Filter", Tag.TAG_COMPOUND);
		if (!list.isEmpty() && provider == null)return false;// read again once registries are known
		List<ItemStack> stacks = new ArrayList<>(9);
		for (int i = 0; i < list.size(); i++) {
			ItemStack s = LegacyData.parseItemStack(provider, list.getCompound(i));
			if (s.isEmpty())continue;
			// 1.20.1 saved the 9 slots compacted (SimpleContainer#createTag): the order is kept
			if (stacks.size() < 9)stacks.add(s);
			else LOGGER.warn("Item Filter had more than 9 entries, dropped {}", s);
		}
		while (stacks.size() < 9)stacks.add(ItemStack.EMPTY);
		stack.set(Content.simpleItemFilterComponent.get(), new SimpleItemFilterComponent(stacks, tag.getBoolean("matchNBT"), tag.getBoolean("allowlist")));
		tag.remove("Filter");
		tag.remove("matchNBT");
		tag.remove("allowlist");
		return true;
	}

	private static boolean tagFilter(ItemStack stack, CompoundTag tag) {
		if (!tag.contains("tags") && !tag.contains("allowlist"))return false;
		if (hasOwn(stack, Content.tagFilterComponent.get()))return false;
		ListTag list = tag.getList("tags", Tag.TAG_STRING);
		List<TagKey<Item>> tags = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			ResourceLocation rl = ResourceLocation.tryParse(list.getString(i));
			// A tag of this mod saved under the old id (toms_storage:trims) is read under its current name
			if (rl != null)tags.add(TagKey.create(Registries.ITEM, LegacyIds.toCurrent(rl)));
		}
		stack.set(Content.tagFilterComponent.get(), new TagFilterComponent(tags, tag.getBoolean("allowlist")));
		tag.remove("tags");
		tag.remove("allowlist");
		return true;
	}

	private static boolean conditionFilter(ItemStack stack, CompoundTag tag) {
		if (!tag.contains("Required") && !tag.contains("Excluded"))return false;
		if (!hasOwn(stack, Content.conditionFilterComponent.get())) {
			ConditionFilterComponent c = new ConditionFilterComponent(tag.getInt("Required"), tag.getInt("Excluded"));
			if (!c.isEmpty())stack.set(Content.conditionFilterComponent.get(), c);
		} else return false;
		tag.remove("Required");
		tag.remove("Excluded");
		return true;
	}

	private static boolean terminal(ItemStack stack, CompoundTag tag) {
		boolean changed = false;
		if (tag.contains("BindX") && !hasOwn(stack, Content.boundPosComponent.get())) {
			ResourceLocation dim = ResourceLocation.tryParse(tag.getString("BindDim"));
			if (dim != null) {
				BlockPos pos = new BlockPos(tag.getInt("BindX"), tag.getInt("BindY"), tag.getInt("BindZ"));
				stack.set(Content.boundPosComponent.get(), new WorldPos(ResourceKey.create(Registries.DIMENSION, dim), pos));
			}
			for (String k : new String[] {"BindX", "BindY", "BindZ", "BindDim"})tag.remove(k);
			changed = true;
		}
		if (tag.contains("Restock") && !hasOwn(stack, Content.restockComponent.get())) {
			if (tag.getBoolean("Restock") && stack.getItem() == Content.advWirelessTerminal.get())
				stack.set(Content.restockComponent.get(), true);
			tag.remove("Restock");
			changed = true;
		}
		return changed;
	}

	private static boolean configurator(ItemStack stack, CompoundTag tag) {
		if (!tag.contains("ts_configurator", Tag.TAG_COMPOUND) || hasOwn(stack, Content.configuratorComponent.get()))return false;
		CompoundTag c = tag.getCompound("ts_configurator");
		ListTag sel = c.getList("selection", Tag.TAG_COMPOUND);
		List<BlockPos> selection = new ArrayList<>(sel.size());
		for (int i = 0; i < sel.size(); i++)selection.add(pos(sel.getCompound(i)));
		stack.set(Content.configuratorComponent.get(), new ConfiguratorComponent(pos(c.getCompound("bound")), c.getBoolean("is_bound"),
				c.getBoolean("selecting"), c.getBoolean("show_inv_box"), c.getBoolean("mass_select"), pos(c.getCompound("box_start")),
				selection, c.getLong("last_action")));
		tag.remove("ts_configurator");
		return true;
	}

	/** NbtUtils.writeBlockPos of 1.20.1: {@code {X, Y, Z}}. */
	private static BlockPos pos(CompoundTag t) {
		return new BlockPos(t.getInt("X"), t.getInt("Y"), t.getInt("Z"));
	}

	private static boolean paintKit(ItemStack stack, CompoundTag tag) {
		if (!tag.contains("block", Tag.TAG_COMPOUND) || hasOwn(stack, Content.paintComponent.get()))return false;
		BlockState st = LegacyData.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("block"));
		if (!st.isAir())stack.set(Content.paintComponent.get(), st);
		tag.remove("block");
		return true;
	}
}
