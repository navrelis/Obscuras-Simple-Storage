package com.obscura.storage;

import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.MenuType.MenuSupplier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType.BlockEntitySupplier;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.serialization.Codec;

import com.obscura.storage.block.BasicInventoryHopperBlock;
import com.obscura.storage.block.CraftingTerminalBlock;
import com.obscura.storage.block.DisposalBlock;
import com.obscura.storage.block.EjectorBlock;
import com.obscura.storage.block.ExporterBlock;
import com.obscura.storage.block.FilingCabinetBlock;
import com.obscura.storage.block.FramedInventoryCableBlock;
import com.obscura.storage.block.FramedInventoryCableConnectorBlock;
import com.obscura.storage.block.InventoryCableBlock;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.block.InventoryConnectorBlock;
import com.obscura.storage.block.InventoryInterfaceBlock;
import com.obscura.storage.block.InventoryProxyBlock;
import com.obscura.storage.block.LevelEmitterBlock;
import com.obscura.storage.block.OpenCrateBlock;
import com.obscura.storage.block.PaintedTrimBlock;
import com.obscura.storage.block.ShulkerDockBlock;
import com.obscura.storage.block.StorageTerminalBlock;
import com.obscura.storage.block.TrimBlock;
import com.obscura.storage.block.entity.BasicInventoryHopperBlockEntity;
import com.obscura.storage.block.entity.CraftingTerminalBlockEntity;
import com.obscura.storage.block.entity.DisposalBlockEntity;
import com.obscura.storage.block.entity.EjectorBlockEntity;
import com.obscura.storage.block.entity.ExporterBlockEntity;
import com.obscura.storage.block.entity.FilingCabinetBlockEntity;
import com.obscura.storage.block.entity.InventoryCableConnectorBlockEntity;
import com.obscura.storage.block.entity.InventoryConnectorBlockEntity;
import com.obscura.storage.block.entity.InventoryInterfaceBlockEntity;
import com.obscura.storage.block.entity.InventoryProxyBlockEntity;
import com.obscura.storage.block.entity.LevelEmitterBlockEntity;
import com.obscura.storage.block.entity.OpenCrateBlockEntity;
import com.obscura.storage.block.entity.PaintedBlockEntity;
import com.obscura.storage.block.entity.ShulkerDockBlockEntity;
import com.obscura.storage.block.entity.StorageTerminalBlockEntity;
import com.obscura.storage.components.ConditionFilterComponent;
import com.obscura.storage.components.ConfiguratorComponent;
import com.obscura.storage.components.SimpleItemFilterComponent;
import com.obscura.storage.components.TagFilterComponent;
import com.obscura.storage.components.WorldPos;
import com.obscura.storage.item.AdvWirelessTerminalItem;
import com.obscura.storage.item.ConditionFilterItem;
import com.obscura.storage.item.FilterItem;
import com.obscura.storage.item.InventoryConfiguratorItem;
import com.obscura.storage.item.PaintKitItem;
import com.obscura.storage.item.PaintedBlockItem;
import com.obscura.storage.item.PolyFilterItem;
import com.obscura.storage.item.TagFilterItem;
import com.obscura.storage.item.WirelessTerminalItem;
import com.obscura.storage.menu.ConditionFilterMenu;
import com.obscura.storage.menu.CraftingTerminalMenu;
import com.obscura.storage.menu.DisposalMenu;
import com.obscura.storage.menu.EjectorMenu;
import com.obscura.storage.menu.ExporterMenu;
import com.obscura.storage.menu.FilingCabinetMenu;
import com.obscura.storage.menu.InventoryConfiguratorMenu;
import com.obscura.storage.menu.InventoryLinkMenu;
import com.obscura.storage.menu.ItemFilterMenu;
import com.obscura.storage.menu.LevelEmitterMenu;
import com.obscura.storage.menu.StorageTerminalMenu;
import com.obscura.storage.menu.TagItemFilterMenu;
import com.obscura.storage.platform.GameObject;
import com.obscura.storage.platform.GameObject.GameObjectBlockEntity;
import com.obscura.storage.platform.Platform;

public class Content {
	public static final GameObject<InventoryConnectorBlock> connector = blockWithItem("inventory_connector", InventoryConnectorBlock::new);
	public static final GameObject<StorageTerminalBlock> terminal = blockWithItem("storage_terminal", StorageTerminalBlock::new);
	public static final GameObject<CraftingTerminalBlock> craftingTerminal = blockWithItem("crafting_terminal", CraftingTerminalBlock::new);
	public static final GameObject<OpenCrateBlock> openCrate = blockWithItem("open_crate", OpenCrateBlock::new);
	public static final GameObject<TrimBlock> inventoryTrim = blockWithItem("trim", TrimBlock::new);
	public static final GameObject<PaintedTrimBlock> paintedTrim = blockWithItemNoTab("painted_trim", PaintedTrimBlock::new, PaintedBlockItem.makeHidden());
	public static final GameObject<LevelEmitterBlock> levelEmitter = blockWithItem("level_emitter", LevelEmitterBlock::new);
	public static final GameObject<InventoryCableBlock> invCable = blockWithItem("inventory_cable", InventoryCableBlock::new);
	public static final GameObject<FramedInventoryCableBlock> invCableFramed = blockWithItem("inventory_cable_framed", FramedInventoryCableBlock::new, PaintedBlockItem.make());
	public static final GameObject<BasicInventoryHopperBlock> basicInvHopper = blockWithItem("basic_inventory_hopper", BasicInventoryHopperBlock::new);
	public static final GameObject<InventoryInterfaceBlock> invInterface = blockWithItem("inventory_interface", InventoryInterfaceBlock::new);
	public static final GameObject<FilingCabinetBlock> filingCabinet = blockWithItem("filing_cabinet", FilingCabinetBlock::new);
	public static final GameObject<ShulkerDockBlock> shulkerDock = blockWithItem("shulker_dock", ShulkerDockBlock::new);
	public static final GameObject<ExporterBlock> exporter = blockWithItem("exporter", ExporterBlock::new);
	public static final GameObject<EjectorBlock> ejector = blockWithItem("ejector", EjectorBlock::new);
	public static final GameObject<DisposalBlock> disposal = blockWithItem("disposal", DisposalBlock::new);
	public static final GameObject<InventoryCableConnectorBlock> invCableConnector = blockWithItem("inventory_cable_connector", InventoryCableConnectorBlock::new);
	public static final GameObject<FramedInventoryCableConnectorBlock> invCableConnectorFramed = blockWithItem("inventory_cable_connector_framed", FramedInventoryCableConnectorBlock::new);
	public static final GameObject<InventoryProxyBlock> invProxy = blockWithItem("inventory_proxy", InventoryProxyBlock::new);

	public static final GameObject<PaintKitItem> paintingKit = item("paint_kit", PaintKitItem::new);
	public static final GameObject<WirelessTerminalItem> wirelessTerminal = item("wireless_terminal", WirelessTerminalItem::new);
	public static final GameObject<AdvWirelessTerminalItem> advWirelessTerminal = item("adv_wireless_terminal", AdvWirelessTerminalItem::new);
	public static final GameObject<FilterItem> itemFilter = item("item_filter", FilterItem::new);
	public static final GameObject<PolyFilterItem> polyItemFilter = item("polymorphic_item_filter", PolyFilterItem::new);
	public static final GameObject<TagFilterItem> tagItemFilter = item("tag_item_filter", TagFilterItem::new);
	public static final GameObject<ConditionFilterItem> conditionFilter = item("condition_filter", ConditionFilterItem::new);
	public static final GameObject<InventoryConfiguratorItem> invConfig = item("inventory_configurator", InventoryConfiguratorItem::new);

	public static final GameObjectBlockEntity<InventoryConnectorBlockEntity> connectorBE = blockEntity("inventory_connector", InventoryConnectorBlockEntity::new, connector);
	public static final GameObjectBlockEntity<OpenCrateBlockEntity> openCrateBE = blockEntity("open_crate", OpenCrateBlockEntity::new, openCrate);
	public static final GameObjectBlockEntity<PaintedBlockEntity> paintedBE = blockEntity("painted", PaintedBlockEntity::new, paintedTrim, invCableFramed);
	public static final GameObjectBlockEntity<StorageTerminalBlockEntity> terminalBE = blockEntity("storage_terminal", StorageTerminalBlockEntity::new, terminal);
	public static final GameObjectBlockEntity<CraftingTerminalBlockEntity> craftingTerminalBE = blockEntity("crafting_terminal", CraftingTerminalBlockEntity::new, craftingTerminal);
	public static final GameObjectBlockEntity<LevelEmitterBlockEntity> levelEmitterBE = blockEntity("level_emitter", LevelEmitterBlockEntity::new, levelEmitter);
	public static final GameObjectBlockEntity<BasicInventoryHopperBlockEntity> basicInvHopperBE = blockEntity("basic_inventory_hopper", BasicInventoryHopperBlockEntity::new, basicInvHopper);
	public static final GameObjectBlockEntity<InventoryInterfaceBlockEntity> invInterfaceBE = blockEntity("inventory_interface", InventoryInterfaceBlockEntity::new, invInterface);
	public static final GameObjectBlockEntity<FilingCabinetBlockEntity> filingCabinetBE = blockEntity("filing_cabinet", FilingCabinetBlockEntity::new, filingCabinet);
	public static final GameObjectBlockEntity<ShulkerDockBlockEntity> shulkerDockBE = blockEntity("shulker_dock", ShulkerDockBlockEntity::new, shulkerDock);
	public static final GameObjectBlockEntity<ExporterBlockEntity> exporterBE = blockEntity("exporter", ExporterBlockEntity::new, exporter);
	public static final GameObjectBlockEntity<EjectorBlockEntity> ejectorBE = blockEntity("ejector", EjectorBlockEntity::new, ejector);
	public static final GameObjectBlockEntity<DisposalBlockEntity> disposalBE = blockEntity("disposal", DisposalBlockEntity::new, disposal);
	public static final GameObjectBlockEntity<InventoryCableConnectorBlockEntity> cableConnectorBE = blockEntity("inv_cable_connector", InventoryCableConnectorBlockEntity::new, invCableConnector, invCableConnectorFramed);
	public static final GameObjectBlockEntity<InventoryProxyBlockEntity> invProxyBE = blockEntity("inv_proxy", InventoryProxyBlockEntity::new, invProxy);

	public static final GameObject<MenuType<StorageTerminalMenu>> storageTerminalMenu = menu("storage_terminal", StorageTerminalMenu::new);
	public static final GameObject<MenuType<CraftingTerminalMenu>> craftingTerminalMenu = menu("crafting_terminal", CraftingTerminalMenu::new);
	public static final GameObject<MenuType<LevelEmitterMenu>> levelEmitterMenu = menu("level_emitter", LevelEmitterMenu::new);
	public static final GameObject<MenuType<InventoryLinkMenu>> inventoryLink = menu("inventory_link", InventoryLinkMenu::new);
	public static final GameObject<MenuType<ItemFilterMenu>> itemFilterMenu = menu("item_filter", ItemFilterMenu::new);
	public static final GameObject<MenuType<TagItemFilterMenu>> tagItemFilterMenu = menu("tag_item_filter", TagItemFilterMenu::new);
	public static final GameObject<MenuType<ConditionFilterMenu>> conditionFilterMenu = menu("condition_filter", ConditionFilterMenu::new);
	public static final GameObject<MenuType<InventoryConfiguratorMenu>> invConfigMenu = menu("inventory_configurator", InventoryConfiguratorMenu::new);
	public static final GameObject<MenuType<FilingCabinetMenu>> filingCabinetMenu = menu("filing_cabinet", FilingCabinetMenu::new);
	public static final GameObject<MenuType<ExporterMenu>> exporterMenu = menu("exporter", ExporterMenu::new);
	public static final GameObject<MenuType<EjectorMenu>> ejectorMenu = menu("ejector", EjectorMenu::new);
	public static final GameObject<MenuType<DisposalMenu>> disposalMenu = menu("disposal", DisposalMenu::new);

	public static final GameObject<DataComponentType<BlockState>> paintComponent = Platform.DATA_COMPONENT_TYPES.register("paint_state", () -> DataComponentType.<BlockState>builder().persistent(BlockState.CODEC).build());
	public static final GameObject<DataComponentType<WorldPos>> boundPosComponent = Platform.DATA_COMPONENT_TYPES.register("bound_pos", () -> DataComponentType.<WorldPos>builder().persistent(WorldPos.CODEC).build());
	public static final GameObject<DataComponentType<SimpleItemFilterComponent>> simpleItemFilterComponent = Platform.DATA_COMPONENT_TYPES.register("simple_item_filter", () -> DataComponentType.<SimpleItemFilterComponent>builder().persistent(SimpleItemFilterComponent.CODEC).build());
	public static final GameObject<DataComponentType<TagFilterComponent>> tagFilterComponent = Platform.DATA_COMPONENT_TYPES.register("tag_filter", () -> DataComponentType.<TagFilterComponent>builder().persistent(TagFilterComponent.CODEC).build());
	public static final GameObject<DataComponentType<ConditionFilterComponent>> conditionFilterComponent = Platform.DATA_COMPONENT_TYPES.register("condition_filter", () -> DataComponentType.<ConditionFilterComponent>builder().persistent(ConditionFilterComponent.CODEC).networkSynchronized(ConditionFilterComponent.STREAM_CODEC).build());
	public static final GameObject<DataComponentType<ConfiguratorComponent>> configuratorComponent = Platform.DATA_COMPONENT_TYPES.register("configurator", () -> DataComponentType.<ConfiguratorComponent>builder().persistent(ConfiguratorComponent.CODEC).build());
	public static final GameObject<DataComponentType<Boolean>> restockComponent = Platform.DATA_COMPONENT_TYPES.register("restock", () -> DataComponentType.<Boolean>builder().persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build());

	private static <B extends Block> GameObject<B> blockWithItem(String name, Supplier<B> create) {
		return blockWithItem(name, create, b -> new BlockItem(b, new Properties()));
	}

	private static <B extends Block, I extends Item> GameObject<B> blockWithItem(String name, Supplier<B> create, Function<Block, I> createItem) {
		GameObject<B> re = Platform.BLOCKS.register(name, create);
		item(name, () -> createItem.apply(re.get()));
		return re;
	}

	private static <B extends Block, I extends Item> GameObject<B> blockWithItemNoTab(String name, Supplier<B> create, Function<Block, I> createItem) {
		GameObject<B> re = Platform.BLOCKS.register(name, create);
		itemNoTab(name, () -> createItem.apply(re.get()));
		return re;
	}

	private static <I extends Item> GameObject<I> item(String name, Supplier<I> fact) {
		return Platform.ITEMS.register(name, () -> Platform.addItemToTab(fact.get()));
	}

	private static <I extends Item> GameObject<I> itemNoTab(String name, Supplier<I> fact) {
		return Platform.ITEMS.register(name, fact);
	}

	@SuppressWarnings("unchecked")
	@SafeVarargs
	private static <BE extends BlockEntity> GameObjectBlockEntity<BE> blockEntity(String name, BlockEntitySupplier<? extends BE> create, GameObject<? extends Block>... blocks) {
		return (GameObjectBlockEntity<BE>) Platform.BLOCK_ENTITY.registerBE(name, create, blocks);
	}

	private static <M extends AbstractContainerMenu> GameObject<MenuType<M>> menu(String name, MenuSupplier<M> create) {
		return Platform.MENU_TYPE.register(name, () -> new MenuType<>(create, FeatureFlags.VANILLA_SET));
	}

	/**
	 * Called during mod initialization to ensure all content (blocks, items,
	 * block entities, menus, data components) are registered via their
	 * static field initializers. This class relies on static initialization
	 * order per JLS §12.4.1.
	 */
	public static void init() {
		if (initialized) return;
		initialized = true;
		// All registration happens in static field initializers above.
		// This method forces class loading to trigger those initializers.
	}
	private static boolean initialized = false;
}
