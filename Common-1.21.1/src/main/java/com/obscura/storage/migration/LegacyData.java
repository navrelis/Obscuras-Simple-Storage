package com.obscura.storage.migration;

import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.obscura.storage.StorageMod;

/**
 * Data written by the mod's Minecraft 1.20.1 builds, read by this (1.21.1) build after a world was
 * upgraded from 1.20.1.
 *
 * <p>Minecraft's data fixers upgrade vanilla block entities and item stacks, but the NBT of a mod's block
 * entity is passed through unchanged. Item stacks and block states the mod stored in its own NBT therefore
 * still have the 1.20.1 shape: an item stack is {@code {id, Count:byte, tag:{...}}} while 1.21.1 reads
 * {@code {id, count:int, components:{...}}} (and reads an old stack as one item without any of its data).
 * The helpers here detect the old shape and send it through Minecraft's own data fixers, from the 1.20.1
 * data version ({@value #DATA_VERSION_1_20_1}) to the running one, before it is parsed.
 *
 * <p>Stacks that already have the 1.21.1 shape are never touched, so every helper is safe to call on
 * data of either version.
 */
public final class LegacyData {
	/** Data version of Minecraft 1.20.1, what the mod's 1.20.1 builds wrote. */
	public static final int DATA_VERSION_1_20_1 = 3465;
	/** Key of the data version that the mod writes next to stored block states (painted blocks). */
	public static final String DATA_VERSION_KEY = "DataVersion";

	// Own logger: the loaders' StorageMod classes have side effects in their static initializers.
	private static final Logger LOGGER = LogManager.getLogger(StorageMod.modid);

	private LegacyData() {}

	/** The data version of the running game. */
	public static int currentDataVersion() {
		return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
	}

	/**
	 * Whether {@code tag} is an item stack in the pre-1.20.5 format: a {@code Count} (any number) and no
	 * {@code count}, or a {@code tag} compound and no {@code components}. A 1.21.1 stack never has either.
	 */
	public static boolean isLegacyItemStack(CompoundTag tag) {
		if (tag == null || !tag.contains("id", Tag.TAG_STRING))return false;
		if (tag.contains("Count", Tag.TAG_ANY_NUMERIC) && !tag.contains("count"))return true;
		return tag.contains("tag", Tag.TAG_COMPOUND) && !tag.contains("components");
	}

	/**
	 * An item stack tag in the current format: a 1.20.1 stack goes through the data fixers (extra keys such
	 * as a {@code Slot} are kept), anything else is returned as it is (same instance). Never throws; a stack
	 * the fixers can not handle is returned unchanged and logged.
	 */
	public static CompoundTag upgradeItemStack(CompoundTag tag) {
		if (!isLegacyItemStack(tag))return tag;
		Tag out = update(References.ITEM_STACK, tag.copy(), DATA_VERSION_1_20_1);
		if (out instanceof CompoundTag c)return c;
		LOGGER.error("Could not upgrade the 1.20.1 item stack {}", tag);
		return tag;
	}

	/**
	 * An item stack saved by either version, like {@link ItemStack#parseOptional}: a 1.20.1 stack is
	 * upgraded first. An empty tag, air or a stack without items (1.20.1 saved an empty stack as
	 * {@code {id:"minecraft:air", Count:0b}}) is {@link ItemStack#EMPTY} without an error in the log.
	 */
	public static ItemStack parseItemStack(HolderLookup.Provider provider, CompoundTag tag) {
		if (tag == null || tag.isEmpty() || isEmptyStack(tag))return ItemStack.EMPTY;
		boolean legacy = isLegacyItemStack(tag);
		ItemStack stack = ItemStack.parseOptional(provider, upgradeItemStack(tag));
		// A mod item saved by 1.20.1 inside another stack (a filter in a filing cabinet) has its settings
		// in custom data; the stack was built before the provider was known to LegacyItemComponents.
		if (legacy && !stack.isEmpty())LegacyItemComponents.upgrade(stack, provider);
		return stack;
	}

	/** Like {@link ItemStack#parse}, for a stack saved by either version, see {@link #parseItemStack}. */
	public static Optional<ItemStack> parseItemStack(HolderLookup.Provider provider, Tag tag) {
		if (!(tag instanceof CompoundTag c))return tag == null ? Optional.empty() : ItemStack.parse(provider, tag);
		ItemStack s = parseItemStack(provider, c);
		return s.isEmpty() ? Optional.empty() : Optional.of(s);
	}

	/** 1.20.1 wrote an empty stack as air with count 0; 1.21.1 would log every one of them as invalid. */
	private static boolean isEmptyStack(CompoundTag tag) {
		String id = tag.getString("id");
		if (id.isEmpty())return !tag.contains("components") && !tag.contains("count") && !tag.contains("Count");
		ResourceLocation rl = ResourceLocation.tryParse(id);
		if (rl != null && rl.getNamespace().equals("minecraft") && rl.getPath().equals("air"))return true;
		if (tag.contains("Count", Tag.TAG_ANY_NUMERIC) && !tag.contains("count"))return tag.getInt("Count") <= 0;
		return false;
	}

	/**
	 * A block state compound ({@code {Name, Properties}}) saved at data version {@code fromVersion}, brought
	 * to the current version (renamed blocks such as {@code minecraft:grass} -> {@code short_grass}).
	 * The input is not changed.
	 */
	public static CompoundTag upgradeBlockState(CompoundTag tag, int fromVersion) {
		if (tag == null || tag.isEmpty() || fromVersion >= currentDataVersion())return tag;
		Tag out = update(References.BLOCK_STATE, tag.copy(), fromVersion);
		return out instanceof CompoundTag c ? c : tag;
	}

	/**
	 * Reads a block state the mod stored next to its own data version ({@link #DATA_VERSION_KEY} inside the
	 * compound, see {@link #writeBlockState}). A compound without it was written by the 1.20.1 builds (or a
	 * 1.21.1 build before this was added, where the 1.20.1 fixes change nothing) and is upgraded from 1.20.1.
	 */
	public static BlockState readBlockState(HolderGetter<Block> blocks, CompoundTag tag) {
		CompoundTag t = tag;
		if (!tag.isEmpty()) {
			int version = tag.contains(DATA_VERSION_KEY, Tag.TAG_ANY_NUMERIC) ? tag.getInt(DATA_VERSION_KEY) : DATA_VERSION_1_20_1;
			t = upgradeBlockState(tag, version);
		}
		return NbtUtils.readBlockState(blocks, t);
	}

	/** {@link NbtUtils#writeBlockState} plus the current data version, read by {@link #readBlockState}. */
	public static CompoundTag writeBlockState(BlockState state) {
		CompoundTag t = NbtUtils.writeBlockState(state);
		t.putInt(DATA_VERSION_KEY, currentDataVersion());
		return t;
	}

	private static Tag update(DSL.TypeReference type, Tag tag, int fromVersion) {
		try {
			return DataFixers.getDataFixer().update(type, new Dynamic<>(NbtOps.INSTANCE, tag), fromVersion, currentDataVersion()).getValue();
		} catch (RuntimeException e) {
			LOGGER.error("Data fixer failed on {} ({})", tag, type.typeName(), e);
			return null;
		}
	}
}
