package com.obscura.storage.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.platform.GameObject.GameRegistry;
import com.obscura.storage.platform.GameObject.GameRegistryBE;

import dev.emi.trinkets.api.TrinketsApi;
import io.netty.buffer.ByteBuf;

public class Platform {
	public static final GameRegistry<Item> ITEMS = new GameRegistry<>(BuiltInRegistries.ITEM);
	public static final GameRegistry<Block> BLOCKS = new GameRegistry<>(BuiltInRegistries.BLOCK);
	public static final GameRegistryBE BLOCK_ENTITY = new GameRegistryBE(BuiltInRegistries.BLOCK_ENTITY_TYPE);
	public static final GameRegistry<MenuType<?>> MENU_TYPE = new GameRegistry<>(BuiltInRegistries.MENU);
	public static final GameRegistry<DataComponentType<?>> DATA_COMPONENT_TYPES = new GameRegistry<>(BuiltInRegistries.DATA_COMPONENT_TYPE);

	private static List<Item> tabItems = new ArrayList<>();

	public static <I extends Item> I addItemToTab(I item) {
		tabItems.add(item);
		return item;
	}

	private static final ResourceKey<CreativeModeTab> ITEM_GROUP = ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.tryBuild(StorageMod.modid, "tab"));

	public static final CreativeModeTab STORAGE_MOD_TAB = FabricItemGroup.builder().title(Component.translatable("itemGroup.obscuras_storage.tab")).icon(() -> new ItemStack(Content.terminal.get())).displayItems((p, out) -> {
		tabItems.forEach(out::accept);
	}).build();

	static {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ITEM_GROUP, STORAGE_MOD_TAB);
	}

	public static boolean vivecraft = FabricLoader.getInstance().isModLoaded("vivecraft");
	/** How this loader finds the item inventory of a block, named by the Network Doctor. */
	public static final String INVENTORY_API = "Fabric Transfer API (ItemStorage.SIDED)";

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

	public static boolean notifyBlocks(Level level, BlockPos worldPosition, Direction direction) {
		return true;
	}

	public static RegistryFriendlyByteBuf makeRegByteBuf(ByteBuf buffer, RegistryAccess reg) {
		return new RegistryFriendlyByteBuf(buffer, reg);
	}

	public static ItemStack getCloneItemStack(Level level, HitResult hitResult, BlockPos pos, Player player) {
		var st = level.getBlockState(pos);
		return st.getBlock().getCloneItemStack(level, pos, st);
	}

	/**
	 * Whether a furnace burns this stack as fuel: the vanilla fuel map, which Fabric's FuelRegistry
	 * feeds.
	 */
	public static boolean isFuel(ItemStack stack) {
		return AbstractFurnaceBlockEntity.getFuel().getOrDefault(stack.getItem(), 0) > 0;
	}

	/**
	 * Object that is replaced whenever the fuel definitions are reloaded, so a cache derived from
	 * {@link #isFuel} knows when to rebuild. Compared by identity: vanilla rebuilds the fuel map
	 * after a datapack reload and otherwise hands out the same instance.
	 */
	public static Object fuelSource() {
		return AbstractFurnaceBlockEntity.getFuel();
	}

	public static boolean canScrapeWithItem(ItemStack item, Player player, InteractionHand hand) {
		return item.is(ItemTags.AXES);
	}

	/**
	 * Loader specific data of a shared block entity that does not extend PlatformBlockEntity (the
	 * Filing Cabinet), called at the end of its loadAdditional. Nothing to do on Fabric: Cardinal
	 * Components reads the block filter itself. The NeoForge build uses it to read that CCA data.
	 */
	public static void readCrossLoaderData(BlockEntity be, CompoundTag tag, HolderLookup.Provider provider) {
	}

	/** Counterpart of {@link #readCrossLoaderData}, called at the end of saveAdditional. */
	public static void writeCrossLoaderData(BlockEntity be, CompoundTag tag, HolderLookup.Provider provider) {
	}
}
