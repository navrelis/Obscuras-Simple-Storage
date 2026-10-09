package com.obscura.storage.platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.crafting.IShapedRecipe;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.util.ItemHandlers;
import com.obscura.storage.util.GameObject.GameRegistry;
import com.obscura.storage.util.GameObject.GameRegistryBE;

public class Platform {

	public static final GameRegistry<Item> ITEMS = new GameRegistry<>(ForgeRegistries.ITEMS);
	public static final GameRegistry<Block> BLOCKS = new GameRegistry<>(ForgeRegistries.BLOCKS);
	public static final GameRegistryBE BLOCK_ENTITY = new GameRegistryBE(ForgeRegistries.BLOCK_ENTITY_TYPES);
	public static final GameRegistry<MenuType<?>> MENU_TYPE = new GameRegistry<>(ForgeRegistries.MENU_TYPES);
	private static final DeferredRegister<CreativeModeTab> TAB = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, StorageMod.modid);

	private static List<Item> tabItems = new ArrayList<>();

	public static final RegistryObject<CreativeModeTab> STORAGE_MOD_TAB = TAB.register("tab", () -> CreativeModeTab.builder()
			.title(Component.translatable("itemGroup.obscuras_storage.tab"))
			.icon(() -> new ItemStack(Content.terminal.get()))
			.displayItems((p, out) -> tabItems.forEach(out::accept))
			.build());

	public static <I extends Item> I addItemToTab(I item) {
		tabItems.add(item);
		return item;
	}

	/** Hooks the deferred registers up to the mod event bus. */
	public static void register(IEventBus bus) {
		BLOCKS.register(bus);
		ITEMS.register(bus);
		BLOCK_ENTITY.register(bus);
		MENU_TYPE.register(bus);
		TAB.register(bus);
		// The creative tab id of versions before v5.0.0, see LegacyRegistryAliases (no-op while the tab registry is vanilla only)
		bus.addListener((RegisterEvent e) -> {
			if (e.getRegistryKey().equals(Registries.CREATIVE_MODE_TAB))LegacyRegistryAliases.addAliases(e.getForgeRegistry(), List.of("tab"));
		});
	}

	public static CompoundTag getSyncTag(ItemStack stack) {
		Item item = stack.getItem();
		CompoundTag compoundtag = null;
		if (item.isDamageable(stack) || item.shouldOverrideMultiplayerNbt()) {
			compoundtag = stack.getShareTag();
		}
		return compoundtag;
	}

	/**
	 * Lets other mods react to a wireless terminal's remote right click like to a real one.
	 *
	 * @return the result when a listener handled it, null otherwise
	 */
	/** How this loader finds the item inventory of a block, named by the Network Doctor. */
	public static final String INVENTORY_API = "Forge item handler capability (ForgeCapabilities.ITEM_HANDLER)";

	/** Whether the block entity at {@code pos} exposes an item inventory on {@code side}: the lookup of the Inventory Connector's scan. */
	public static boolean hasItemInventory(Level level, BlockPos pos, BlockState state, @Nullable Direction side) {
		BlockEntity be = level.getBlockEntity(pos);
		return be != null && ItemHandlers.ofBlockEntity(be, side) != null;
	}

	/** Whether there is an item inventory at {@code pos} for a cable connector, hopper or terminal: the capability, or a vanilla container. */
	public static boolean hasItemInventoryAt(Level level, BlockPos pos, @Nullable Direction side) {
		return ItemHandlers.find(level, pos, side) != null;
	}

	public static InteractionResult checkUse(Level worldIn, BlockHitResult lookingAt, Player playerIn, InteractionHand handIn) {
		PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(playerIn, handIn, lookingAt.getBlockPos(), lookingAt);
		MinecraftForge.EVENT_BUS.post(event);
		if (event.isCanceled()) {
			return event.getCancellationResult();
		}
		return null;
	}

	private static boolean curios = ModList.get().isLoaded("curios");
	public static <T> T checkExtraSlots(Player player, Predicate<ItemStack> is, T def, Function<ItemStack, T> map) {
		if(curios) {
			ItemStack s = CuriosCompat.findFirst(player, is);
			if(!s.isEmpty())return map.apply(s);
		}
		return def;
	}

	public static CompoundTag readNbtTag(FriendlyByteBuf buf) {
		return buf.readAnySizeNbt();
	}

	public static int getRecipeWidth(Recipe<?> recipe) {
		if (recipe instanceof IShapedRecipe<?> sr)
			return sr.getRecipeWidth();
		return -1;
	}

	/** Items that burn in a furnace (Condition Filter Fuel): every item with a Forge burn time. */
	public static Set<Item> getFuelItems() {
		Set<Item> set = new HashSet<>();
		for (Item item : ForgeRegistries.ITEMS) {
			try {
				if (ForgeHooks.getBurnTime(new ItemStack(item), RecipeType.SMELTING) > 0)set.add(item);
			} catch (RuntimeException e) {
				// A broken item must not keep the filter from working
			}
		}
		return set;
	}

	public static ItemStack getCloneItemStack(Level level, HitResult hitResult, BlockPos pos, Player player) {
		BlockState st = level.getBlockState(pos);
		HitResult hit = hitResult != null ? hitResult : new BlockHitResult(Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false);
		return st.getCloneItemStack(hit, level, pos, player);
	}
}
