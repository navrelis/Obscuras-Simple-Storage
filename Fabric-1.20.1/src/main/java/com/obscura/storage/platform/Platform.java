package com.obscura.storage.platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.util.GameObject.GameRegistry;
import com.obscura.storage.util.GameObject.GameRegistryBE;

import dev.emi.trinkets.api.TrinketsApi;

public class Platform {

	public static final GameRegistry<Item> ITEMS = new GameRegistry<>(BuiltInRegistries.ITEM);
	public static final GameRegistry<Block> BLOCKS = new GameRegistry<>(BuiltInRegistries.BLOCK);
	public static final GameRegistryBE BLOCK_ENTITY = new GameRegistryBE(BuiltInRegistries.BLOCK_ENTITY_TYPE);
	public static final GameRegistry<MenuType<?>> MENU_TYPE = new GameRegistry<>(BuiltInRegistries.MENU);

	private static List<Item> tabItems = new ArrayList<>();

	public static <I extends Item> I addItemToTab(I item) {
		tabItems.add(item);
		return item;
	}

	private static final ResourceKey<CreativeModeTab> ITEM_GROUP = ResourceKey.create(Registries.CREATIVE_MODE_TAB, new ResourceLocation(StorageMod.modid, "tab"));

	public static final CreativeModeTab STORAGE_MOD_TAB = FabricItemGroup.builder().title(Component.translatable("itemGroup.obscuras_storage.tab")).icon(() -> new ItemStack(Content.terminal.get())).displayItems((p, out) -> {
		tabItems.forEach(out::accept);
	}).build();

	static {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ITEM_GROUP, STORAGE_MOD_TAB);
	}

	public static CompoundTag getSyncTag(ItemStack stack) {
		Item item = stack.getItem();
		CompoundTag compoundtag = null;
		if (item.canBeDepleted() || item.shouldOverrideMultiplayerNbt()) {
			compoundtag = stack.getTag();
		}
		return compoundtag;
	}

	/** How this loader finds the item inventory of a block, named by the Network Doctor. */
	public static final String INVENTORY_API = "Fabric Transfer API (ItemStorage.SIDED)";

	/** Whether the block entity at {@code pos} exposes an item inventory on {@code side}: the lookup of the Inventory Connector's scan. */
	public static boolean hasItemInventory(Level level, BlockPos pos, BlockState state, @Nullable Direction side) {
		return ItemStorage.SIDED.find(level, pos, state, level.getBlockEntity(pos), side) != null;
	}

	/** Whether there is an item inventory at {@code pos} for a cable connector, hopper or terminal: the Transfer API, or a vanilla container. */
	public static boolean hasItemInventoryAt(Level level, BlockPos pos, @Nullable Direction side) {
		return ItemStorage.SIDED.find(level, pos, side) != null || HopperBlockEntity.getContainerAt(level, pos) != null;
	}

	public static InteractionResult checkUse(Level worldIn, BlockHitResult lookingAt, Player playerIn, InteractionHand handIn) {
		InteractionResult r = UseBlockCallback.EVENT.invoker().interact(playerIn, worldIn, handIn, lookingAt);
		if(r == InteractionResult.PASS)return null;
		return r;
	}

	private static boolean trinkets = FabricLoader.getInstance().isModLoaded("trinkets");
	public static <T> T checkExtraSlots(Player player, Predicate<ItemStack> is, T def, Function<ItemStack, T> map) {
		if(trinkets) {
			var tc = TrinketsApi.getTrinketComponent(player).orElse(null);
			if(tc != null) {
				var s = tc.getEquipped(is);
				if(!s.isEmpty())return map.apply(s.get(0).getB());
			}
		}
		return def;
	}

	public static CompoundTag readNbtTag(FriendlyByteBuf buf) {
		return buf.readAnySizeNbt();
	}

	public static int getRecipeWidth(Recipe<?> recipe) {
		return -1;
	}

	/** Items that burn in a furnace (Condition Filter Fuel): the furnace fuel map, which holds vanilla, tag and FuelRegistry entries. */
	public static Set<Item> getFuelItems() {
		Set<Item> set = new HashSet<>();
		AbstractFurnaceBlockEntity.getFuel().forEach((item, time) -> {
			if (item != null && time != null && time > 0)set.add(item);
		});
		return set;
	}

	public static ItemStack getCloneItemStack(Level level, HitResult hitResult, BlockPos pos, Player player) {
		var st = level.getBlockState(pos);
		return st.getBlock().getCloneItemStack(level, pos, st);
	}
}
