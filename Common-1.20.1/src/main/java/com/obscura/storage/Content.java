package com.obscura.storage;

import java.util.function.Function;
import java.util.function.Supplier;

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

import com.obscura.storage.block.BasicInventoryHopperBlock;
import com.obscura.storage.block.CraftingTerminalBlock;
import com.obscura.storage.block.DisposalBlock;
import com.obscura.storage.block.EjectorBlock;
import com.obscura.storage.block.ExporterBlock;
import com.obscura.storage.block.FilingCabinetBlock;
import com.obscura.storage.block.FilteredInventoryCableConnectorBlock;
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
import com.obscura.storage.gui.CraftingTerminalMenu;
import com.obscura.storage.gui.DisposalMenu;
import com.obscura.storage.gui.EjectorMenu;
import com.obscura.storage.gui.ExporterMenu;
import com.obscura.storage.gui.FilingCabinetMenu;
import com.obscura.storage.gui.InventoryConfiguratorMenu;
import com.obscura.storage.gui.InventoryLinkMenu;
import com.obscura.storage.gui.ConditionFilterMenu;
import com.obscura.storage.gui.ItemFilterMenu;
import com.obscura.storage.gui.LevelEmitterMenu;
import com.obscura.storage.gui.StorageTerminalMenu;
import com.obscura.storage.gui.TagItemFilterMenu;
import com.obscura.storage.item.AdvWirelessTerminalItem;
import com.obscura.storage.item.ConditionFilterItem;
import com.obscura.storage.item.FilterItem;
import com.obscura.storage.item.InventoryConfiguratorItem;
import com.obscura.storage.item.PaintKitItem;
import com.obscura.storage.item.LegacyFilteredConnectorItem;
import com.obscura.storage.item.PaintedBlockItem;
import com.obscura.storage.item.PolyFilterItem;
import com.obscura.storage.item.TagFilterItem;
import com.obscura.storage.item.WirelessTerminalItem;
import com.obscura.storage.platform.Platform;
import com.obscura.storage.tile.BasicInventoryHopperBlockEntity;
import com.obscura.storage.tile.CraftingTerminalBlockEntity;
import com.obscura.storage.tile.DisposalBlockEntity;
import com.obscura.storage.tile.EjectorBlockEntity;
import com.obscura.storage.tile.ExporterBlockEntity;
import com.obscura.storage.tile.FilingCabinetBlockEntity;
import com.obscura.storage.tile.FilteredInventoryCableConnectorBlockEntity;
import com.obscura.storage.tile.InventoryCableConnectorBlockEntity;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.tile.InventoryInterfaceBlockEntity;
import com.obscura.storage.tile.InventoryProxyBlockEntity;
import com.obscura.storage.tile.LevelEmitterBlockEntity;
import com.obscura.storage.tile.OpenCrateBlockEntity;
import com.obscura.storage.tile.PaintedBlockEntity;
import com.obscura.storage.tile.ShulkerDockBlockEntity;
import com.obscura.storage.tile.StorageTerminalBlockEntity;
import com.obscura.storage.util.GameObject;
import com.obscura.storage.util.GameObject.GameObjectBlockEntity;

public class Content {
	public static GameObject<InventoryConnectorBlock> connector = blockWithItem("inventory_connector", InventoryConnectorBlock::new);
	public static GameObject<StorageTerminalBlock> terminal = blockWithItem("storage_terminal", StorageTerminalBlock::new);
	public static GameObject<TrimBlock> inventoryTrim = blockWithItem("trim", TrimBlock::new);
	public static GameObject<OpenCrateBlock> openCrate = blockWithItem("open_crate", OpenCrateBlock::new);
	public static GameObject<PaintedTrimBlock> paintedTrim = blockWithItemNoTab("painted_trim", PaintedTrimBlock::new, PaintedBlockItem.makeHidden());
	public static GameObject<InventoryCableBlock> invCable = blockWithItem("inventory_cable", InventoryCableBlock::new);
	public static GameObject<FramedInventoryCableBlock> invCableFramed = blockWithItem("inventory_cable_framed", FramedInventoryCableBlock::new, PaintedBlockItem.make());
	public static GameObject<InventoryCableConnectorBlock> invCableConnector = blockWithItem("inventory_cable_connector", InventoryCableConnectorBlock::new);
	public static GameObject<FilteredInventoryCableConnectorBlock> invCableConnectorFiltered = blockWithItemNoTab("inventory_cable_connector_filtered", FilteredInventoryCableConnectorBlock::new, LegacyFilteredConnectorItem.make());
	public static GameObject<FramedInventoryCableConnectorBlock> invCableConnectorFramed = blockWithItem("inventory_cable_connector_framed", FramedInventoryCableConnectorBlock::new, PaintedBlockItem.make());
	public static GameObject<InventoryProxyBlock> invProxy = blockWithItem("inventory_proxy", InventoryProxyBlock::new, PaintedBlockItem.make());
	public static GameObject<CraftingTerminalBlock> craftingTerminal = blockWithItem("crafting_terminal", CraftingTerminalBlock::new);
	public static GameObject<BasicInventoryHopperBlock> invHopperBasic = blockWithItem("basic_inventory_hopper", BasicInventoryHopperBlock::new);
	public static GameObject<LevelEmitterBlock> levelEmitter = blockWithItem("level_emitter", LevelEmitterBlock::new);
	public static GameObject<InventoryInterfaceBlock> invInterface = blockWithItem("inventory_interface", InventoryInterfaceBlock::new);
	public static GameObject<FilingCabinetBlock> filingCabinet = blockWithItem("filing_cabinet", FilingCabinetBlock::new);
	public static GameObject<ShulkerDockBlock> shulkerDock = blockWithItem("shulker_dock", ShulkerDockBlock::new);
	public static GameObject<ExporterBlock> exporter = blockWithItem("exporter", ExporterBlock::new);
	public static GameObject<EjectorBlock> ejector = blockWithItem("ejector", EjectorBlock::new);
	public static GameObject<DisposalBlock> disposal = blockWithItem("disposal", DisposalBlock::new);

	public static GameObject<PaintKitItem> paintingKit = item("paint_kit", PaintKitItem::new);
	public static GameObject<WirelessTerminalItem> wirelessTerminal = item("wireless_terminal", WirelessTerminalItem::new);
	public static GameObject<AdvWirelessTerminalItem> advWirelessTerminal = item("adv_wireless_terminal", AdvWirelessTerminalItem::new);
	public static GameObject<FilterItem> itemFilter = item("item_filter", FilterItem::new);
	public static GameObject<PolyFilterItem> polyItemFliter = item("polymorphic_item_filter", PolyFilterItem::new);
	public static GameObject<TagFilterItem> tagItemFliter = item("tag_item_filter", TagFilterItem::new);
	public static GameObject<ConditionFilterItem> conditionFilter = item("condition_filter", ConditionFilterItem::new);
	public static GameObject<InventoryConfiguratorItem> invConfig = item("inventory_configurator", InventoryConfiguratorItem::new);

	public static GameObjectBlockEntity<InventoryConnectorBlockEntity> connectorTile = blockEntity("inventory_connector", InventoryConnectorBlockEntity::new, connector);
	public static GameObjectBlockEntity<StorageTerminalBlockEntity> terminalTile = blockEntity("storage_terminal", StorageTerminalBlockEntity::new, terminal);
	public static GameObjectBlockEntity<OpenCrateBlockEntity> openCrateTile = blockEntity("open_crate", OpenCrateBlockEntity::new, openCrate);
	public static GameObjectBlockEntity<PaintedBlockEntity> paintedTile = blockEntity("painted", PaintedBlockEntity::new, Content.paintedTrim, Content.invCableFramed);
	public static GameObjectBlockEntity<InventoryCableConnectorBlockEntity> invCableConnectorTile = blockEntity("inventory_cable_connector", InventoryCableConnectorBlockEntity::new, invCableConnector, invCableConnectorFramed);
	public static GameObjectBlockEntity<FilteredInventoryCableConnectorBlockEntity> invCableConnectorFilteredTile = blockEntity("inventory_cable_connector_filtered", FilteredInventoryCableConnectorBlockEntity::new, invCableConnectorFiltered);
	public static GameObjectBlockEntity<InventoryProxyBlockEntity> invProxyTile = blockEntity("inventory_proxy", InventoryProxyBlockEntity::new, invProxy);
	public static GameObjectBlockEntity<CraftingTerminalBlockEntity> craftingTerminalTile = blockEntity("crafting_terminal", CraftingTerminalBlockEntity::new, craftingTerminal);
	public static GameObjectBlockEntity<BasicInventoryHopperBlockEntity> invHopperBasicTile = blockEntity("basic_inventory_hopper", BasicInventoryHopperBlockEntity::new, invHopperBasic);
	public static GameObjectBlockEntity<LevelEmitterBlockEntity> levelEmitterTile = blockEntity("level_emitter", LevelEmitterBlockEntity::new, levelEmitter);
	public static GameObjectBlockEntity<InventoryInterfaceBlockEntity> invInterfaceTile = blockEntity("inventory_interface", InventoryInterfaceBlockEntity::new, invInterface);
	public static GameObjectBlockEntity<FilingCabinetBlockEntity> filingCabinetTile = blockEntity("filing_cabinet", FilingCabinetBlockEntity::new, filingCabinet);
	public static GameObjectBlockEntity<ShulkerDockBlockEntity> shulkerDockTile = blockEntity("shulker_dock", ShulkerDockBlockEntity::new, shulkerDock);
	public static GameObjectBlockEntity<ExporterBlockEntity> exporterTile = blockEntity("exporter", ExporterBlockEntity::new, exporter);
	public static GameObjectBlockEntity<EjectorBlockEntity> ejectorTile = blockEntity("ejector", EjectorBlockEntity::new, ejector);
	public static GameObjectBlockEntity<DisposalBlockEntity> disposalTile = blockEntity("disposal", DisposalBlockEntity::new, disposal);

	public static GameObject<MenuType<StorageTerminalMenu>> storageTerminal = menu("storage_terminal", StorageTerminalMenu::new);
	public static GameObject<MenuType<CraftingTerminalMenu>> craftingTerminalCont = menu("crafting_terminal", CraftingTerminalMenu::new);
	public static GameObject<MenuType<LevelEmitterMenu>> levelEmitterConatiner = menu("level_emitter", LevelEmitterMenu::new);
	public static GameObject<MenuType<InventoryLinkMenu>> inventoryLink = menu("inventory_link", InventoryLinkMenu::new);
	public static GameObject<MenuType<ItemFilterMenu>> itemFilterConatiner = menu("item_filter", ItemFilterMenu::new);
	public static GameObject<MenuType<TagItemFilterMenu>> tagItemFilterConatiner = menu("tag_item_filter", TagItemFilterMenu::new);
	public static GameObject<MenuType<ConditionFilterMenu>> conditionFilterConatiner = menu("condition_filter", ConditionFilterMenu::new);
	public static GameObject<MenuType<InventoryConfiguratorMenu>> invConfigConatiner = menu("inventory_configurator", InventoryConfiguratorMenu::new);
	public static GameObject<MenuType<FilingCabinetMenu>> filingCabinetContainer = menu("filing_cabinet", FilingCabinetMenu::new);
	public static GameObject<MenuType<ExporterMenu>> exporterMenu = menu("exporter", ExporterMenu::new);
	public static GameObject<MenuType<EjectorMenu>> ejectorMenu = menu("ejector", EjectorMenu::new);
	public static GameObject<MenuType<DisposalMenu>> disposalMenu = menu("disposal", DisposalMenu::new);

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

	public static void init() {
	}
}
