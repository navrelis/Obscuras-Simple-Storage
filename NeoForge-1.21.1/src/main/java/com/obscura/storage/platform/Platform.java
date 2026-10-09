package com.obscura.storage.platform;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.entity.BlockFilterAttachment;
import com.obscura.storage.migration.LegacyIds;
import com.obscura.storage.platform.GameObject.GameRegistry;
import com.obscura.storage.platform.GameObject.GameRegistryBE;

import io.netty.buffer.ByteBuf;
import top.theillusivec4.curios.api.CuriosCapability;

public class Platform {

	public static final GameRegistry<Item> ITEMS = new GameRegistry<>(Registries.ITEM);
	public static final GameRegistry<Block> BLOCKS = new GameRegistry<>(Registries.BLOCK);
	public static final GameRegistryBE BLOCK_ENTITY = new GameRegistryBE();
	public static final GameRegistry<MenuType<?>> MENU_TYPE = new GameRegistry<>(Registries.MENU);
	public static final DeferredRegister<CreativeModeTab> TAB = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, StorageMod.modid);
	public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, StorageMod.modid);
	public static final GameRegistry<DataComponentType<?>> DATA_COMPONENT_TYPES = new GameRegistry<>(Registries.DATA_COMPONENT_TYPE);

	private static List<Item> tabItems = new ArrayList<>();
	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> STORAGE_MOD_TAB = TAB.register("tab", () ->
	CreativeModeTab.builder()
	.title(Component.translatable("itemGroup.obscuras_storage.tab"))
	.icon(() -> new ItemStack(Content.terminal.get()))
	.displayItems((p, out) -> {
		tabItems.forEach(out::accept);
	})
	.build()
			);

	/** Same id as the Fabric build's Cardinal Components key, see {@link FabricDataCompat}. */
	public static final Supplier<AttachmentType<BlockFilterAttachment>> BLOCK_FILTER = ATTACHMENT_TYPES.register(
			LegacyIds.BLOCK_FILTER_PATH, () -> AttachmentType.serializable(BlockFilterAttachment::new).build());

	static {
		// Old worlds: attachments saved as toms_storage:block_filter (also on vanilla block entities,
		// through the chunk NBT) and the creative tab, see GameObject#addLegacyAlias.
		GameObject.addLegacyAlias(ATTACHMENT_TYPES, LegacyIds.BLOCK_FILTER_PATH);
		GameObject.addLegacyAlias(TAB, "tab");
	}

	public static boolean vivecraft = ModList.get().isLoaded("vivecraft");
	/** How this loader finds the item inventory of a block, named by the Network Doctor. */
	public static final String INVENTORY_API = "NeoForge item handler capability (Capabilities.ItemHandler.BLOCK)";

	public static <I extends Item> I addItemToTab(I item) {
		tabItems.add(item);
		return item;
	}

	public static void register(IEventBus bus) {
		ITEMS.register(bus);
		BLOCKS.register(bus);
		BLOCK_ENTITY.register(bus);
		MENU_TYPE.register(bus);
		TAB.register(bus);
		ATTACHMENT_TYPES.register(bus);
		DATA_COMPONENT_TYPES.register(bus);
	}

	public static InteractionResult checkUse(Level worldIn, BlockHitResult lookingAt, Player playerIn, InteractionHand handIn) {
		PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(playerIn, handIn, lookingAt.getBlockPos(), lookingAt);
		NeoForge.EVENT_BUS.post(event);
		if (event.isCanceled()) {
			return event.getCancellationResult();
		}
		return null;
	}

	private static boolean curios = ModList.get().isLoaded("curios");
	public static <T> T checkExtraSlots(Player player, Predicate<ItemStack> is, T def, Function<ItemStack, T> map) {
		if(curios) {
			var handler = player.getCapability(CuriosCapability.INVENTORY);
			if (handler == null)return def;
			var s = handler.findCurios(is);
			if(!s.isEmpty())return map.apply(s.get(0).stack());
		}
		return def;
	}

	public static boolean notifyBlocks(Level level, BlockPos worldPosition, Direction direction) {
		return !EventHooks.onNeighborNotify(level, worldPosition, level.getBlockState(worldPosition), EnumSet.of(direction), false).isCanceled();
	}

	public static RegistryFriendlyByteBuf makeRegByteBuf(ByteBuf buffer, RegistryAccess reg) {
		return new RegistryFriendlyByteBuf(buffer, reg, ConnectionType.NEOFORGE);
	}

	public static ItemStack getCloneItemStack(Level level, HitResult hitResult, BlockPos pos, Player player) {
		return level.getBlockState(pos).getCloneItemStack(hitResult, level, pos, player);
	}

	/**
	 * Whether a furnace burns this stack as fuel. Asked the way a real furnace does, through the
	 * furnace_fuels data map and the item's own override: AbstractFurnaceBlockEntity.getFuel() is
	 * deprecated on NeoForge and only knows the hard-coded vanilla list.
	 */
	public static boolean isFuel(ItemStack stack) {
		return stack.getBurnTime(RecipeType.SMELTING) > 0;
	}

	/**
	 * Object that is replaced whenever the fuel definitions are reloaded (datapack reload, client
	 * sync), so a cache derived from {@link #isFuel} knows when to rebuild. Compared by identity.
	 */
	public static Object fuelSource() {
		return BuiltInRegistries.ITEM.getDataMap(NeoForgeDataMaps.FURNACE_FUELS);
	}

	public static boolean canScrapeWithItem(ItemStack item, Player player, InteractionHand hand) {
		return item.canPerformAction(ItemAbilities.AXE_SCRAPE);
	}

	/**
	 * Loader specific data of a shared block entity that does not extend PlatformBlockEntity (the
	 * Filing Cabinet), called at the end of its loadAdditional: reads a block filter saved by the
	 * Fabric build, see {@link FabricDataCompat}.
	 */
	public static void readCrossLoaderData(BlockEntity be, CompoundTag tag, HolderLookup.Provider provider) {
		FabricDataCompat.readFabricBlockFilter(be, tag, provider);
	}

	/** Counterpart of {@link #readCrossLoaderData}, called at the end of saveAdditional. */
	public static void writeCrossLoaderData(BlockEntity be, CompoundTag tag, HolderLookup.Provider provider) {
		FabricDataCompat.writeFabricBlockFilter(be, tag, provider);
	}
}
