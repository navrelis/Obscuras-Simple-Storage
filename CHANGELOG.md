# Obscura's Simple Storage - Changelog

## v5.1.0

### Highlights
* New: **Ejector, Disposal and Exporter**, three wooden "filter blocks" with a 3x3 ghost filter. The back plugs into the storage (a cable, an Inventory Connector, an Inventory Interface, or a single inventory), the front is the output. The Ejector drops matching items in front of it, the Disposal destroys them, and the Exporter moves them into the inventory in front of it.
* New: **Highlight Connected Inventories**. A button in every terminal and a key (default H) outline every inventory of a storage network through walls in green for 10 seconds.
* Fixed: a small memory leak in the Frame Labels overlay after leaving a world.
* All of this is on all four builds (Fabric 1.21.1, NeoForge 1.21.1, Fabric 1.20.1, Forge 1.20.1 / NeoForge 1.20.1) with the same features, config key and messages. Jade support for the new blocks is on 1.21.1 only, as for the other blocks. The one other difference is the placement next to a terminal (see "Placement").
* **Update the server and the clients together** (new blocks). Worlds from 5.0.0 load unchanged, and there is nothing to migrate.

### New

#### Filter blocks: Ejector, Disposal and Exporter
* Three wooden cubes in the mod's style, mined with an axe. Right-click one to open its screen with a 3x3 ghost filter.
* **Back and front.** The back plugs into the storage: an Inventory Cable, an Inventory Connector or an Inventory Interface (which means the whole network), or directly onto a single inventory such as a chest. The front is the output.
* **Placement.** Against a cable or connector, the back plugs in. Against a chest (any block with a block entity, such as a barrel or the Shulker Dock), the front faces it. Otherwise the back turns to an adjacent cable or connector, and if there is none the front faces the player, like a dropper. A terminal counts as a cable on 1.21.1 only: on 1.20.1 a block placed against a terminal turns its front to it, on 1.21.1 its back.
* **Filter.** Nine ghost slots hold a reference, so no item is consumed. JEI, REI and EMI drag and drop works, and Shift-click on an inventory item adds it. A plain item matches by item only: durability, enchantments, names and other data are ignored. The **Exact** toggle makes a plain entry match only the same item with the same data.
* **Filter items.** One of the mod's filter items (Item Filter, Tag Item Filter, Condition Filter or Polymorphic Item Filter) in a slot works as that filter. A stack matches when any entry matches. An empty filter does nothing, on all three blocks.
* **Not inventories.** The blocks hold no items themselves. Connectors never treat them as storage, and the Inventory Configurator skips them. The Network Doctor lists them as "filter blocks" (a count in `doctor at`, and their work counts towards the network's cost).
* **Speed.** One operation every `filterBlockCooldown` ticks (default 8). Between operations a block only counts down, and when there is nothing to do it stays idle until something its filter matches changes in the storage or the target, or the filter changes.
* **Status.** Running, Paused, Stopped by redstone, Needs redstone, No source, No target or Filter is empty. The Disposal shows it in its screen, and with Jade (1.21.1) it shows when you look at any of the three.

#### Ejector
* Takes matching items out of the storage and drops them in front of it, like a dropper (same sound and smoke). It works only while powered by redstone.
* **Mode.** Continuous (default): one batch every cooldown while powered. Pulse: exactly one batch per redstone pulse (rising edge), on the next tick, not limited by the cooldown.
* **Amount.** 1 item (default), or 1 stack (up to 64, or the item's stack size) of one item type.
* Recipe: top row planks, Inventory Cable, planks; bottom row redstone, dropper, redstone.

#### Disposal
* Takes matching items out of the storage and destroys them permanently, up to one stack per operation.
* A newly placed Disposal is **paused** and must be started in its screen. A redstone signal stops a running one.
* The screen shows a red warning, the status, the total destroyed and the three most destroyed items. Hovering a plain filter entry shows "Destroyed: N". The reset button needs Shift held, so a stray click does not wipe the counter.
* It counts up to 256 different items individually. Beyond that only the total grows. The counts are saved with the block and are lost when the block is broken.
* Recipe: top row planks, Inventory Cable, planks; bottom row cactus, cauldron, cactus.

#### Exporter
* Moves matching items from the storage into the inventory in front of it: chests, double chests, barrels, the Shulker Dock (it fills the docked shulker box) and inventories from other mods.
* It runs on its own, and a redstone signal stops it.
* **Amount.** 1 stack per operation (default) or 1 item.
* Nothing is lost when the target is full: the items stay in the storage and the Exporter tries again (a full target is asked again every 100 ticks).
* The target may be connected to the same storage. The Exporter never pulls from the inventory it fills.
* Recipe: top row planks, Inventory Cable, planks; bottom row redstone, hopper, redstone.

#### Highlight Connected Inventories
* Outlines every inventory that is part of a storage network, through walls, in green for 10 seconds. It shows the nearest 256 in the current dimension. A message tells you how many were found, how many of those are in other dimensions (they are not outlined), and how many more have no known position (for example over an Inventory Link).
* Three ways to start it:
  * A new **button in every terminal**, below the Tidy button.
  * A new key binding, **Highlight Connected Inventories** (default **H**). In an open terminal it works while no text field is focused, so typing in the search bar is not affected. With an Auto Search mode on the search button the search bar always has the focus, so the key never fires in the screen there; the button always works.
  * The same key in the world: look at a terminal, cable, connector, cable connector, Ejector, Disposal or Exporter within 8 blocks, or hold a bound wireless terminal (its normal range rules apply).
* The terminal closes so the outlines can be seen. Each player can make one request per second.
* It is governed by the existing config option `itemLocatorEnabled`. The Item Locator itself (Alt + click on an item, cyan outlines, 64 positions) is unchanged.
* It is not checked against claim or protection mods, because it only reveals positions. A server that minds can turn `itemLocatorEnabled` off.

### Fixes
* **Frame Labels overlay:** the client kept a reference to the last world's level after you left a world (a small memory leak). It is cleared when you join a world and when you disconnect.

### Compatibility
* Minecraft, loader and optional mod requirements are the same as v5.0.0: Fabric 1.21.1 (Java 21, Fabric API), NeoForge 1.21.1 (21.1 or newer, Java 21), Fabric 1.20.1 (Java 17, Fabric API), Forge 1.20.1 (47.1 or newer, Java 17) or NeoForge 1.20.1 (47.1.x). Optional: JEI, REI, EMI, Jade and Polymorph (1.21.1 only), Trinkets (Fabric) and Curios (NeoForge, Forge), Mod Menu (Fabric).
* **The server and every client must run 5.1.0.** The new blocks and the new key binding are not in 5.0.0.
* **No migration.** Worlds from 5.0.0 load unchanged, and configs and key bindings are kept. The new config option starts at its default.
* **Modpack authors:** the new blocks and items are `obscuras_storage:ejector`, `obscuras_storage:disposal` and `obscuras_storage:exporter`, each with a recipe of the same name.
* Existing config options keep their meaning, including `itemLocatorEnabled`.

### Config
New option, in the same config files and with the same name on all four builds. The Fabric JSON uses this name, and the NeoForge and Forge server TOML files use it as well.

| Option | Default | Range | What it does |
| --- | --- | --- | --- |
| `filterBlockCooldown` | `8` | 1 - 200 | Ticks between two operations of an Ejector, Disposal or Exporter. An Ejector in Pulse mode drops on every redstone pulse instead |

* Existing config files get the new option at its default.

### Development
* Version bumped to v5.1.0 on all four builds. Jar names stay `obscuras_simple_storage-<loader>-mc<version>-v5.1.0.jar`.
* The Ejector, Disposal and Exporter share one base implementation (block, block entity, menu and screen) in each Minecraft version's common code.
* Added JUnit tests and in-world GameTests for the filter blocks on every loader. Automated tests per build: Fabric 1.21.1 166 JUnit tests + 117 GameTests, NeoForge 1.21.1 no JUnit tests + 122 GameTests, Fabric 1.20.1 176 JUnit tests + 126 GameTests, Forge 1.20.1 104 JUnit tests + 132 GameTests (also 132 on NeoForge 1.20.1 47.1).
* The release script `ExportedJars\rebuild_all_and_export_jar.ps1` builds, tests and exports all four jars.

## v5.0.0

### Highlights
* **New mod id: `obscuras_storage`.** The mod is now fully Obscura's Simple Storage, with its own id, packages, command, config files and texture folder. Worlds, configs and key bindings from 4.x are migrated automatically on all four builds, and the old config files are kept as `*.migrated`.
* New: **Shulker Dock**. A block that holds one shulker box. The network, hoppers and comparators see the box's 27 slots, and the window on the front shows the box's real colour.
* New: **Tidy button**. One click in a terminal defragments the whole network over a few seconds: partial stacks are merged and stray items move to the chest where they belong.
* New: **Frame Labels**. A glow item frame holding an item on a network chest makes that chest take only that item from the network, and take it first.
* New: **comparator output** for the Inventory Connector, Inventory Interface, Filing Cabinet, Open Crate and Shulker Dock, and an **analog mode** (signal 0 to 15) for the Level Emitter.
* New: **Network Doctor** (`/obscuras_storage doctor ...`) for server admins and modpack developers: which network costs how much server time, why a block does or does not join a network, the effective config, and a JSON/CSV export.
* **Minecraft 1.20.1 to 1.21.1 world upgrade**: worlds from the 1.20.1 builds load in the 1.21.1 builds without losing items, filters or Inventory Link channels (one known limit: painted framed block items lose their paint, see below).
* All of this is on all four builds (Fabric 1.21.1, NeoForge 1.21.1, Fabric 1.20.1, Forge 1.20.1 / NeoForge 1.20.1) with the same features, config keys, commands and messages.
* **Update the server and the clients together**, and replace `toms_storage:` ids and tags in datapacks and scripts with `obscuras_storage:` (see "Compatibility").

### New

#### Shulker Dock
* A wooden block with a window on the front. Right-click it with a shulker box to dock it, right-click with an empty hand to take the box out. If a box is already docked, the two swap.
* The box's 27 slots join the network like a chest. Connectors, filters, priority, hoppers, Frame Labels and the Inventory Configurator treat it as an inventory. A comparator reads the fill level of the docked box. A dock without a box has no slots.
* The window shows the colour of the docked box (all 16 dyes, and the default look for an undyed box).
* Sneak + right-click with a box in your hand places it as a block. In creative mode the box is moved into the dock, not copied.
* Breaking the dock or blowing it up drops the dock and the box as items, with contents and custom name intact. Nothing is spilled as 27 stacks.
* The usual shulker rule applies: shulker boxes and bundles cannot be put into a docked box. A box that still carries an unopened loot table is refused.
* Recipe: a Barrel, an Inventory Cable and 2 iron ingots (shapeless).
* The item tag `obscuras_storage:dockable` decides which boxes can be docked (the 17 vanilla shulker boxes by default). Boxes from other mods can be added if they store their contents the vanilla way and hold no more than 27 stacks.

#### Frame Labels
* Put a **glow item frame** on any face of an inventory that is part of a network, and an item in the frame. That inventory then takes only that item from the network, and is filled first. Everything already inside stays visible and can be taken out.
* Several frames on one inventory label it with all of their items. On a double chest, a frame on either half labels the whole chest.
* Matching is by item type and ignores item data. A filter (Item, Tag, Polymorphic or Condition Filter) in the frame works as that filter, which is the way to get exact matching.
* A filter set with the Inventory Configurator on the same block wins over the label. A filter on an Inventory Cable Connector still applies on top.
* A label controls what the network puts in (terminals, and hoppers or pipes inserting through a connector or interface). A hopper pointing straight at the chest is not checked. Only glow item frames count.
* Feedback without new textures: holding the Inventory Configurator outlines labelled inventories within 16 blocks in aqua, the Configurator overlay shows `Label: <item>` for the inventory you look at, and the action bar says `Label set: <item>` or `Label removed`.
* Tidy uses labels as homes. New config key `frameLabelsEnabled`.

#### Tidy button
* A broom button below the terminal's side buttons (Storage Terminal, Crafting Terminal and the wireless terminals). Hover it for a rough estimate; click it to defragment the network.
* For each item the mod picks a home: a chest labelled with a glow item frame for that item, else a chest whose Configurator filter lets the item in (highest priority first, then the one holding the most), else the chest that already holds the most of it. Partial stacks are merged inside each inventory, and stray items move to the home until it is full.
* Safety: at most 8 stack moves per tick and network, at most 30 seconds per run and a 30 second cooldown (all configurable). Every move is checked against the live slots and goes through the same inventory paths the network uses, so filters are respected. Nothing is dropped or voided, and a run that is cut short keeps the moves already made.
* It never empties an inventory set to Keep last item, never moves an item from a higher-priority chest into a lower-priority one (unless the target is labelled or filtered for it), and leaves alone Filing Cabinets, Open Crates, Inventory Proxies and Interfaces, machines (furnaces, brewing stands, hoppers, dispensers, droppers, jukeboxes, lecterns, chiseled bookshelves, decorated pots, and crafters on 1.21.1), anything in the block tag `obscuras_storage:tidy_skip` and anything marked Skip. Docked shulker boxes are not touched unless `tidyIncludeDocks` is on.
* One run per network, shared by everyone who has a terminal open on it. The button shows a progress bar, and clicking it again stops the run. A message on the action bar reports the moves made, the slots freed and how many items had no room. The button says why it is disabled ("Nothing to tidy", "Cooling down", "Disabled by server", "Another tidy is running", "No storage network").
* New config keys: `tidyEnabled`, `tidyOpsOnly`, `tidyMovesPerTick`, `tidyMaxSeconds`, `tidyCooldownSeconds`, `tidyIncludeDocks`. Tidy's work counts towards the network's cost in the Network Doctor.

#### Comparator output and analog Level Emitter
* Vanilla comparators read the **fill level** of the Inventory Connector (the whole network), the Inventory Interface (the network behind it), the Filing Cabinet (512 slots), the Open Crate (each dropped item entity in front of it counts as one of 27 slots, like a chest) and the Shulker Dock (the docked box).
* The signal follows the vanilla container formula: 0 when empty, 1 for a single item, 15 when full. A comparator next to a Connector or Interface is updated at most every 5 ticks, and nothing is computed while no comparator is there.
* The **Level Emitter** has a second toggle for an analog output. "More than": 0 when none is stored, 1 as soon as any is stored, rising in steps to 15 at the threshold or more. "Less than": 15 when none is stored, falling in steps to 1 just below the threshold, 0 at the threshold or more. The icon shows the current mode and its tooltip spells out the signal. Existing emitters keep their On/Off output.

#### Network Doctor
* Needs permission level 2 (or the console). Everyone else gets the vanilla "unknown command" message.
* `/obscuras_storage doctor top [count]` lists the heaviest networks by average time per tick.
* `/obscuras_storage doctor at [pos] [dimension]` shows one network in detail: inventories (with duplicates removed and why, and how many are labelled), slots, cables, terminals, hoppers, skipped blocks with the reason, timing, sync cost, the cap, a Tidy run in progress, and a hint for the likely cause when it sees one. Any block of the network works as `pos`.
* `/obscuras_storage doctor block [id]` and `doctor block at <pos>` tell you whether a block can join a network and why, which answers "why is my machine not showing up?".
* `/obscuras_storage doctor config` lists the effective server config (changed options marked, with the default) and the block tags that decide connectivity. `doctor reset` clears the timing windows.
* `/obscuras_storage doctor export` writes a JSON and a CSV file to `<world>/obscuras_storage/doctor/`.
* Timing is always on and needs no start command: two `nanoTime` calls per network per tick, a rolling 30 second window, no allocations. Unloaded networks drop out. Player clicks, wireless terminals, Builder's Reach, other mods' pipes and work on other threads are not counted.
* A warning goes to the server log (at most once per network every 10 minutes) when a network averages at least `doctorWarnMs` per tick or has more than `doctorWarnInventories` inventories. An optional per-network inventory cap, `doctorMaxInventories` (off by default), lists the lowest-priority inventories over the cap as skipped ("over cap").
* For everyone: left-clicking an Inventory Connector with the Inventory Configurator sends the player one chat line (inventories, slots, how full, terminals, duplicates merged), without timings.

### Rebrand and migration
* **New mod id `obscuras_storage`** (it was `toms_storage`) on all four builds. The Java packages are now `com.obscura.storage`, the texture and data folders are `assets/obscuras_storage` and `data/obscuras_storage`, the command is `/obscuras_storage`, and the tags and translation keys use the new namespace. The mod name, jar names and logo are unchanged, the logo is now a 512 px image, and the MIT `LICENSE` ships inside every jar.
* **Worlds migrate automatically.** Old block, item and block entity ids are mapped to the new ones on load on all four builds (NeoForge registry aliases, Fabric registry mixin, Forge missing-mapping remaps plus registry aliases). Painted block states, filter items, Filing Cabinet contents, Inventory Configurator block filters and Inventory Link channels carry over. On 1.20.1, ids written inside item data are rewritten on load.
* **Configs migrate once.** The old config file is copied to the new name (`config/obscuras_storage.json` on Fabric, `obscuras_storage-server.toml` and `obscuras_storage-common.toml` on NeoForge and Forge, including per-world copies in `serverconfig/` and `defaultconfigs/`) and the old file is kept as `*.migrated`. A new file is never overwritten. The Inventory Configurator and Inventory Link data files move the same way. The new v5 options start at their defaults.
* **Key bindings migrate.** The keys you set for Open Terminal and Toggle Restock are copied to the new names.
* **Not migrated:** advancement and recipe book progress (the advancements and recipes have new ids, so they show up as new), and datapacks, scripts, recipes, loot tables and resource packs that still use the old namespace.
* Back up your world first, as always. You can still move a world between Fabric and NeoForge on 1.21.1, or between Fabric and Forge on 1.20.1. Take the Advanced Wireless Terminal out of a Trinkets or Curios slot before you switch.

### Upgrading Minecraft 1.20.1 to 1.21.1
* Worlds from the 1.20.1 builds (Fabric, Forge or NeoForge 1.20.1) can be opened with the 1.21.1 builds (Fabric or NeoForge). Minecraft converts its own data, and the mod now converts its own data as well. Make a backup first.
* **Converted:** items in Shulker Docks and their boxes, Filing Cabinet contents, filters in Basic Inventory Hoppers and Level Emitters, the Crafting Terminal grid, terminal sort and control settings, painted blocks, and the data other mods and item stacks keep inside the mod's blocks.
* **Renamed blocks and ids are resolved,** so Inventory Cable Connectors and Inventory Proxies keep their Inventory Link channels, painted `ts.*` blocks become the painted blocks of this mod, and the removed Filtered Inventory Cable Connector (1.20.1) turns into a normal connector as before.
* **Inventory Configurator filters** (priority, side, skip, keep last item and the filter item) move from the 1.20.1 world data into the blocks when their chunk loads.
* **Items of the mod** carry their old NBT over as item components (for example filters, and Wireless Terminal bindings).
* **Known limits:**
  * **Painted framed block items lose their paint.** Minecraft's own converter drops the paint data of an item. The item itself stays, and you can paint it again with the Paint Kit. Painted blocks that are placed in the world keep their paint.
  * Tag Filters that use tags renamed between the two Minecraft versions (for example `c:iron_ingots` became `c:ingots/iron`) are not remapped. Open the filter and pick the tag again.
  * After a crash (not after a clean stop) the first start can apply an already converted Configurator filter a second time. You may end up with a duplicate filter item, never with a lost one.
* Worlds do not move down from 1.21.1 to 1.20.1.

### Fixes
* **Shulker Dock** refuses a shulker box that still has an unopened loot table, because docking it would roll the loot again and replace the stored contents.
* **1.20.1 to 1.21.1 upgrade:** items in docks, Filing Cabinets, filters, crafting grids and Inventory Link channels are kept instead of being lost (see "Upgrading Minecraft 1.20.1 to 1.21.1").
* Bug fixes from v4.0.1 are included.

### Compatibility
* Minecraft, loader and optional mod requirements are the same as v4.0.1: Fabric 1.21.1 (Java 21, Fabric API), NeoForge 1.21.1 (21.1 or newer, Java 21), Fabric 1.20.1 (Java 17, Fabric API), Forge 1.20.1 (47.1 or newer, Java 17) or NeoForge 1.20.1 (47.1.x). Optional: JEI, REI, EMI, Jade and Polymorph (1.21.1 only), Trinkets (Fabric) and Curios (NeoForge, Forge), Mod Menu (Fabric).
* **The server and every client must run 5.0.0.** The server's settings are sent to the players when they join.
* **Modpack authors and server admins:** the mod id changed from `toms_storage` to `obscuras_storage`. Update every `toms_storage:` id and tag in datapacks, KubeJS or CraftTweaker scripts, recipes, loot tables and quest or advancement files to `obscuras_storage:` (for example `obscuras_storage:inventory_connector`, and the tags `obscuras_storage:trims`, `obscuras_storage:inventory_connector_skip` and `obscuras_storage:inventory_configurator_skip`). Resource packs that replace textures or translations need the folder `assets/obscuras_storage`. Worlds, configs and key bindings are migrated by the mod, but data that lives outside the world and the mod's own files is not.
* New tags that packs can extend: the item tag `obscuras_storage:dockable` (which shulker boxes the Shulker Dock accepts) and the block tag `obscuras_storage:tidy_skip` (blocks the Tidy button never touches).
* The `blockedBlocks` and `blockedMods` options and the other existing config values keep their meaning. On NeoForge and Forge a few TOML options keep their older key names (`inventoryConnectorRange`, `invCableConnectorMaxScanSize`, `wirelessReach`, `onlyTrimsConnect`, `wirelessTermBeaconLvlDim`), so existing files keep working.

### Config
New options, in the same config files and with the same names on all four builds. The Fabric JSON uses these names, and the NeoForge and Forge TOML files use them as well.

| Option | Default | Range | What it does |
| --- | --- | --- | --- |
| `frameLabelsEnabled` | `true` | true / false | Allows Frame Labels |
| `tidyEnabled` | `true` | true / false | Allows the Tidy button |
| `tidyOpsOnly` | `false` | true / false | Only operators (permission level 2) may use the Tidy button |
| `tidyMovesPerTick` | `8` | 1 - 64 | Stack moves per tick and network while Tidy runs |
| `tidyMaxSeconds` | `30` | 5 - 600 | A Tidy run stops after this many seconds, planning included. The moves made stay |
| `tidyCooldownSeconds` | `30` | 0 - 3600 | Seconds before the same network can be tidied again |
| `tidyIncludeDocks` | `false` | true / false | Tidy also rearranges the boxes in Shulker Docks |
| `doctorMaxInventories` | `0` | 0 - 1000000 | Per-network inventory cap of the Network Doctor. 0 = no cap |
| `doctorWarnMs` | `5.0` | 0 - 1000 | Log a warning when a network averages at least this many ms per tick. 0 = off |
| `doctorWarnInventories` | `0` | 0 - 1000000 | Log a warning when a network has more inventories than this. 0 = off |

* Existing config files get the new options at their defaults.

### Development
* Version bumped to v5.0.0 on all four builds. Jar names stay `obscuras_simple_storage-<loader>-mc<version>-v5.0.0.jar`.
* The mod id, Java packages, resource and data folders, command and translation keys moved to `obscuras_storage` / `com.obscura.storage`. Migration code (registry aliases, Fabric registry mixin, Forge missing-mapping remaps, config, key binding and saved data migration) lives next to the loaders and is covered by JUnit tests and in-world GameTests with real v4.0.1 and 1.20.1-format data.
* Added JUnit tests and in-world GameTests for the Shulker Dock, Frame Labels, Tidy (including a planner test on random networks), comparator output, the analog Level Emitter and the Network Doctor on every loader.
* The release script `ExportedJars\rebuild_all_and_export_jar.ps1` builds, tests and exports all four jars.

## v4.0.1

### Highlights
* Bug-fix release for all four builds (Fabric 1.21.1, NeoForge 1.21.1, Fabric 1.20.1, Forge 1.20.1 / NeoForge 1.20.1). No new features, and worlds load unchanged.
* Fixed (1.20.1): Builder's Reach and Restock did not work until the bound terminal had been opened once after loading the world.
* Fixed (NeoForge 1.21.1): the Condition Filter trait "Fuel" now recognises fuels added by other mods and datapacks.
* Fixed (all builds): carrying a basic Wireless Terminal no longer blocks an Advanced Wireless Terminal that is bound to another terminal.
* Update the server and the clients together: the pick-block packet changed, so a 4.0.1 server needs 4.0.1 clients.

### Fixes

#### 1.20.1 (Fabric and Forge)
* Builder's Reach (pick-block) and Restock work right after loading the world. The terminal's storage is now looked up when it is needed instead of only after the terminal had been opened.
* A terminal that was next to a chest which got swapped for another block kept using the removed chest, so items could land in the old inventory. The terminal now follows the swap.
* Basic Inventory Hopper in Pulse mode: a redstone pulse that arrives while the hopper is not connected to a network is dropped. It no longer stays stored and fires later when a cable is attached.
* Basic Inventory Hopper in Keep Stock mode: counting the target's items on a big network reads the network's shared totals instead of scanning every inventory, so it is much cheaper.
* Forge: the Basic Inventory Hopper keeps the data other mods attach to it when it is saved.
* Fabric: a config value outside its allowed range is clamped to the nearest allowed value instead of resetting to the default. The bounds are the same as on 1.21.1 (`invConnectorScanRange` at least 1, `invConnectorMaxCables` at most 16384).

#### NeoForge 1.21.1
* Condition Filter "Fuel" asks NeoForge for the burn time, so fuels from other mods and from datapacks (`furnace_fuels` data map) count.

#### All builds
* A terminal's wireless range only counts Wireless Terminals that are bound to that terminal. A basic Wireless Terminal in your inventory no longer hides an Advanced Wireless Terminal bound to a different terminal.
* Builder's Reach uses the exact spot you aim at, so multipart blocks give the right item. The server checks the hit, and a modded block that cannot report its item no longer causes an error.

### Development
* Version bumped to v4.0.1 on all builds.
* Builder's Reach pick-block: the client now sends the exact block hit with the request, and the server validates it. Client and server must both run 4.0.1.
* Added JUnit tests and in-world GameTests for each of the fixes above (terminal reach, pick-block hit, Pulse and Keep Stock hoppers, config clamping, data-map fuel on NeoForge).

### Compatibility
* Same Minecraft, loader and optional mod requirements as v4.0.0.
* Existing worlds and configs load unchanged.
* Server and clients should both update to v4.0.1 (the pick-block packet changed).

## v4.0.0

### Highlights
* New loaders: **NeoForge 1.21.1** and **Forge 1.20.1** (the Forge 1.20.1 jar also runs on NeoForge 1.20.1). Obscura's Simple Storage now ships as four builds from two shared code bases: Fabric 1.21.1 and NeoForge 1.21.1, Fabric 1.20.1 and Forge 1.20.1. All four have the same features, balance and fixes.
* New: **Item Locator**. Alt + click an item in a Storage or Crafting Terminal and every inventory on the network that holds it is outlined through walls.
* New: **Pulse and Keep Stock modes** for the Basic Inventory Hopper, controlled with the Inventory Configurator. Ideal for feeding crafters, dispensers and droppers a fixed number of items.
* New: **Condition Filter**. A filter that matches items by traits (damaged, enchanted, food, fuel, ...) instead of a list.
* New: **Builder's Reach**. Middle-click (pick-block) a block you do not carry to pull a stack of it out of your bound terminal's network, and let the Advanced Wireless Terminal **Restock** the block you are holding.
* **Performance pass** on both Minecraft versions: idle terminals and connectors cost almost nothing, and network changes are sent as small updates instead of full resyncs.
* **Fabric 1.20.1 now matches Fabric 1.21.1**: search popup and search syntax, storage statistics, Inventory Link owner and public/private channels, Configurator icons, filter tooltips, offhand slot and Obscura's branding are the same on both versions.
* **Inventory Link works without a beacon** on both versions (within 4096 blocks in the same dimension); beacons on both ends still unlock unlimited range and other dimensions.
* Fixed missing and broken GUI textures (1.20.1) and gave every sort mode its own icon.
* Balance pass: longer wireless ranges, one shared beacon rule for the Advanced Wireless Terminal and the Inventory Link, a vanilla-speed Basic Inventory Hopper, a fire resistant Advanced Wireless Terminal.
* A large fix pass: item loss, crashes, a server-side exploit, storage vanishing from the terminal, recipes, loot tables, tags and advancements that never loaded on 1.20.1, and a number of bugs that only showed up once the in-world GameTests existed.

### New loaders

#### NeoForge 1.21.1
* New `NeoForge-1.21.1` project (NeoForge 21.1.252) built on the same shared code as Fabric 1.21.1 (`Common-1.21.1`). It has every feature and fix of the Fabric build: config defaults and migration, tracker correctness, filtered, proxy and multi-inventory access, painted block models, Item Locator, Builder's Reach (pick-block and restock), Condition Filter and the hopper modes.
* Optional: JEI, REI, EMI, Jade, Polymorph and Curios (Curios replaces Trinkets for the Advanced Wireless Terminal in the belt slot). None of them is required or bundled.
* Fixes of NeoForge problems that the port also removed: wrong slot mapping of multi-inventories, "find next" on filtered inventories, keep-last applied twice, blocked blocks resolved too early, and chests counted twice.
* A furnace or brewing stand that is connected on two faces counts its shared slots once. Faces with identical slots are merged, and partly overlapping faces exclude the shared slots from counting while keeping their own insert and extract rules.
* The config screen is the NeoForge one (see Config).

#### Forge 1.20.1 (also NeoForge 1.20.1)
* New `Forge-1.20.1` project (Forge 47.4.10, Java 17 toolchain) built on the same shared code as Fabric 1.20.1 (`Common-1.20.1`), with Forge item-handler versions of the Fabric Transfer API classes: terminal snapshot sync, merged and filtered storage, block entities, filters, Item Locator, Filing Cabinet and Inventory Interface.
* Every fix and feature matches Fabric 1.20.1. Curios replaces Trinkets; JEI, REI and EMI are optional.
* The same jar loads on NeoForge 1.20.1 (47.1.x): the GameTests were run on Forge 47.4.10 and on NeoForge 47.1.106, and the release jar starts on real Forge and NeoForge dedicated servers.
* Registry ids are identical to Fabric 1.20.1, see Compatibility for moving worlds between loaders.
* Fuel detection goes through the platform layer, so Forge counts burn times added by other mods for the Condition Filter.

### New features (all four builds)

#### Item Locator
* Alt + click an item in a Storage Terminal or Crafting Terminal. The terminal closes and every inventory on the network that holds the item is outlined through walls for 10 seconds, the nearest 64 first.
* An action-bar message summarizes the result, including matches in other dimensions and in storage that has no known position.
* The server resolves the item from the menu's own sync ids, rate-limits requests and reuses the network's existing change data, so nothing is rescanned and nothing runs per tick.
* Can be switched off with `itemLocatorEnabled` (see Config).

#### Basic Inventory Hopper: Pulse and Keep Stock
* Normal: the hopper works as before. Existing hoppers and worlds are unchanged.
* Pulse: each redstone rising edge moves exactly the set amount, one item at a time, with the destination re-checked for every item. A vanilla Crafter therefore receives one item per slot. The last redstone state is saved, so reloading a chunk does not fire a pulse.
* Keep Stock: moves items only while the target holds fewer matching items than the set amount.
* The amount is the stack size used when a plain item filter is set (right-click the hopper with a stack). Right-clicking the hopper with the Inventory Configurator cycles the mode; sneak + right-click cycles the amount through 1, 2, 4, 8, 9, 16, 32 and 64.
* The current mode and amount show when you right-click the hopper, in Jade (1.21.1 builds) and in the hopper item's tooltip.
* Note for 1.20.1: the vanilla Crafter only exists from Minecraft 1.21, but Pulse and Keep Stock still work for dispensers, droppers and any other target.

#### Condition Filter
* Matches items by traits instead of a list: damaged, enchanted, renamed, stackable, food, fuel, smeltable, block and has contents.
* Each trait is set to ignore, required or excluded in a small GUI. A filter with nothing set matches nothing, like the other empty filters.
* Works everywhere the other filters work.
* Recipe: Item Filter + Comparator + Lapis Lazuli.
* Fuel and smeltable checks use lists that are built on the server thread and rebuilt after a datapack reload (`/reload`), so filtering stays cheap and safe on the scan threads. Smeltable means a smelting recipe.

#### Builder's Reach
* Pick-block: middle-click a block you do not have in your inventory and a stack of it is pulled out of the bound terminal's network. This works when a carried Wireless Terminal or Advanced Wireless Terminal could open that terminal right now, using the same range, beacon and dimension rules as opening it. An Advanced Wireless Terminal in the Trinkets (Fabric) or Curios (NeoForge and Forge) belt slot counts too; only the Advanced terminal fits into that slot.
* Restock (Advanced Wireless Terminal): the "Toggle Restock" key binding switches it on and off. While on, the held building block is topped up from storage when the stack drops below 16.
* Terminal range and beacon checks now go through one shared resolver used by opening, pick-block and restock, so all three always agree.
* Both features can be switched off with `buildersReachEnabled` and `restockEnabled` (see Config).

### Inventory Link without a beacon (both Minecraft versions)
* An Inventory Cable Connector with a selected, existing channel links to the other ends of that channel without any beacon, as long as they are in the same dimension and within `invLinkBeaconRange` (default 4096 blocks).
* Beacons on both ends unlock unlimited range in the same dimension (level 1) and other dimensions (level 3). On 1.20.1, a connector that faces a beacon behaves as before.
* The Inventory Link GUI opens with an empty hand on any Inventory Cable Connector, so you can still place blocks against a connector while holding an item.

### Performance

#### Fabric 1.21.1 and NeoForge 1.21.1
* Terminal sync: hash-based id map, compaction that only runs when something went stale, and count-only updates patch the client list in place instead of re-sorting it.
* Change tracking: versions are derived from the stable parts of per-lookup wrappers (double chests, sided inventories), so versioned storages are not iterated while idle, and vanilla containers use an allocation-free per-slot comparison.
* Block filters keep one wrapper per input inventory instead of rebuilding wrappers and trackers on every scan, and only recompile when their contents change.
* Connectors rescan only on neighbour updates, filter changes or a 20-tick heartbeat, and rebuilds with unchanged members are no-ops, so open terminals no longer resync four times a second.
* Cable networks are invalidated per network and only when the cable graph really changes: redstone or emitter toggles no longer invalidate every network in the dimension.
* The terminal's beacon scan walks nearby block entities instead of about 5000 block lookups.

#### Fabric 1.20.1 and Forge 1.20.1
* Terminal: contents are re-read every 4 ticks into a shared, allocation-free snapshot keyed by item variant, the sync only sends variants that changed, and an idle terminal costs a version compare. The id map is a hash map (full syncs were quadratic).
* Block filters: O(1) position index; filter groups apply at any distance; filters that only held defaults are pruned on load; connectors no longer create filters on their own, and a filter is removed with its block (the filter item drops).
* Connectors rescan on reported changes with a 20-tick heartbeat, reuse their collections, keep only inventory positions and skip unloaded chunks; the skip-block check uses the squared range.
* Level Emitters count item variants and share the network snapshot.
* The terminal's beacon scan walks nearby block entities instead of about 5000 block lookups and is skipped when disabled.
* Client: sort keys are cached, and count-only updates do not re-sort.

### Parity: Fabric 1.20.1 now matches Fabric 1.21.1
* Terminal: search options popup (auto-focus, keep, sync, smart item search; also used by the EMI and REI transfer handlers) and the storage statistics tooltip; the free-slots message includes the inventory count.
* Terminal search syntax: `|` for or, several terms for and, `@mod`, `#tag` and `$nbt`.
* Ghost stacks show a yellow 0, the terminal has an offhand slot, and an item that throws while rendering no longer crashes the screen (it is skipped and logged once).
* Inventory Link: the tooltip shows the channel owner and public or private, and selecting a channel checks access.
* Inventory Configurator: icon buttons, the tooltip-style overlay and the highlight for painted blocks.
* Filter tooltips list the contents and allow or deny when CTRL is held.
* Recipes use the loader's conventional tags (c: on Fabric, forge: on Forge) where they exist, and the Inventory Proxy recipe pattern matches 1.21.1.
* Creative tab and mod metadata are named Obscura's Simple Storage, and the English wording and key names follow 1.21.1. On 1.21.1, all seven languages also say Obscura's Simple Storage now.
* On both versions, shift-pulling from the terminal no longer puts items into the offhand.
* On 1.21.1, an Inventory Cable Connector also applies the filter on the block it points at, like 1.20.1 does: an item must pass both filters (applied once if one filter covers both), with skip and priority, and the connector's own filter wins.

### GUI
* Fabric 1.20.1 and Forge 1.20.1: added the missing filter toggle texture (the toggles showed the missing-texture square), and the list rows, scroll knobs, Add and Remove buttons, lock icons and Clear Grid art are where the screens read them (they rendered empty). The Inventory Link scroll track fits its scroller, and button icons are drawn 1:1 instead of squashed from 16 to 14 pixels.
* All versions: filled a 2 pixel hole in the Storage Terminal frame, fixed a wrong corner pixel and cropped the search-mode icon to its draw size.
* All versions: every sort mode has its own icon. Rarity is a gold star, Registry ID shows ID, Durability shows three coloured durability bars and Item Group a 2x2 grid (they were copies of other icons).

### Balance changes
* Wireless Terminal range is now 32 blocks (was 16). Advanced Wireless Terminal range is now 128 blocks (was 64). Both remain configurable.
* One beacon rule for the Advanced Wireless Terminal and the Inventory Link, on all builds: a level 1 beacon gives unlimited range within the same dimension, a level 3 beacon works across dimensions. (Cross-dimension for the Advanced Wireless Terminal was level 4, for the Inventory Link level 2.) The basic Wireless Terminal is not extended by beacons: it always reaches 32 blocks in the same dimension.
* Inventory Link: works without a beacon within 4096 blocks in the same dimension on both Minecraft versions, see above.
* The Basic Inventory Hopper now moves one item every 8 ticks, the speed of a vanilla hopper (was 10 ticks on 1.21.1). The cooldown is read live from the config, and on 1.20.1 hopper and Level Emitter rescans are spread over different ticks per block to avoid lag spikes.
* The Advanced Wireless Terminal is fire resistant (it is a netherite item).
* Existing configs are migrated: a value that still equals its old default is moved to the new default, a value you changed is kept (see Config).

### Removed
* 1.20.1 (Fabric and Forge): the Filtered Inventory Cable Connector is gone. The Inventory Configurator replaces it: its recipe, creative tab entry, menu and screen are removed, and it is hidden in JEI, REI and EMI.
* Already placed Filtered Inventory Cable Connectors are converted automatically: on the first tick they turn into a normal Inventory Cable Connector (facing and connections kept) and drop the items that were in their filter. A leftover connector item in an inventory places a normal Inventory Cable Connector. Set the filter again with the Inventory Configurator.
* The block id stays registered only so old worlds keep loading.
* 1.20.1: the provide/access toggle of the Inventory Link is gone. Every link end provides and accesses, like on 1.21.1. The network is walked once per link network, so loops cannot make a network contain itself.

### Fixes

#### Found by the automated tests
* Fabric 1.20.1: a filter set with the Inventory Configurator on an Inventory Cable Connector was ignored since the Filtered Connector was removed. The connector now applies its own filter and the filter on the block it points at (item, allow or deny, keep-last, side, priority, skip), and cable-connected inventories join the priority sort. (Found by the new GameTests; also applies to Forge 1.20.1.)
* Fabric 1.21.1: the Inventory Configurator could not put a filter on vanilla or modded chests and barrels, because the block filter was only registered for the mod's own block entities. It is registered for every block entity again; block entities without a filter cost nothing (no NBT, no tick work).
* Fabric 1.20.1 and Forge 1.20.1: fixed a crash in the Configurator highlight next to a painted framed cable, which read connection properties that block does not have.
* NeoForge 1.21.1: fixed furnaces and brewing stands connected on two faces counting their shared slots twice.
* Fixed tooltip texts such as "Reach: %d blocks" showing the placeholder literally on 1.21.1: vanilla translations only support `%s`, so all numeric placeholders are `%s` in every language on both versions. The Inventory Link tooltip now reads "Without beacons: 4096 block range in the same dimension." and a `%%s` typo in ja_jp is fixed.
* 1.20.1: fixed invalid escape sequences in the English language file (a beacon tooltip) that would have made the whole file fail to load. All resource JSON is strict-checked now.
* 1.21.1: fixed the per-item totals of the change tracker drifting when count-only changes arrived on the multithreaded path, which could outline storage that no longer held the item.
* 1.21.1: the Inventory Configurator's remove works from any block of a multi-block filter; terminal data packets decode NBT with a 2 MiB limit instead of an unlimited one; the Level Emitter follows an in-place rotation.
* 1.21.1 (Fabric and NeoForge): brewing stands, furnaces and other sided inventories on the network count each slot once while keeping their per-side rules; a filtered network hopper no longer loops inside the first inventory; the off-thread scan keeps its version when nothing changed.
* 1.20.1: hopper top and bottom searches are separate, the Open Terminal cooldown and a 100 character search cap are enforced, a Level Emitter turns off when its target is gone, and an unbound Advanced Wireless Terminal no longer shadows a bound one.

#### Fabric 1.20.1
* Fixed a Filing Cabinet item-loss bug: slots were saved with a single byte for the slot number, so items in slots 256 to 511 overwrote earlier ones after a reload. Slots are saved as ints now; old saves still load.
* The Filing Cabinet rejects shulker boxes, bundles and other container items.
* Fixed loot tables of the framed and painted blocks and the proxy (they used a 1.21-only function and failed to load), so breaking these blocks drops them again.
* Fixed the Basic Inventory Hopper recipe, which produced an unknown item. The Filing Cabinet, Inventory Interface, Inventory Configurator and the clean recipes were only present in the 1.21 folder and could not be crafted; they are now available in survival. Unused 1.21 data folders were removed.
* Fixed item tags and the Trinkets belt slot tag for trims and the connector/configurator skip lists, which used old ids and did nothing.
* Rewrote the advancements in the 1.20.1 format with the real item ids.
* Fixed terminal updates being lost while a JEI, REI or EMI recipe screen was open, which left the terminal list stale until it was reopened.
* Crafting Terminal recipe-fill requests and menu button ids are validated, Open Crate amounts are clamped, and the Inventory Configurator's mass-select size check is corrected.
* Terminal sort mode and keep-last settings are saved and no longer lost on restart.
* Fixed Basic Inventory Hopper and Level Emitter rescans all firing on the same tick.

#### Fabric 1.21.1
* Security (Fabric 1.21.1 only; NeoForge never had this code): removed the gzip step on terminal sync packets. The server inflated client data without a size limit, so a small packet could expand to tens of megabytes on the server thread (decompression bomb). Packets are already small and the connection compresses them.
* Fixed a crash (index out of bounds in a ticking block entity) when the network's connections were rebuilt while the change tracker still held old data.
* Fixed a crash in the Basic Inventory Hopper (NPE) when it ticked before it finished loading; it now works from its facing until loaded and follows in-place rotation.
* Fixed items vanishing from the terminal when a chest was placed or replaced behind a filtered cable connector; filtered inventories are re-resolved on every use, and slots of removed storage no longer linger as ghost items.
* Fixed item, tag and poly filters building their sets off the server thread, which could make items flicker or disappear.
* Recipe advancements moved to the 1.21 folder (`advancement/recipes`) so they load.
* The Filing Cabinet rejects shulker boxes and bundles; Open Crate insert and extract amounts are clamped.
* Crafting Terminal fill only accepts 3x3 crafting recipes.
* The Inventory Configurator menu closes when its filter is gone instead of deleting items placed in it.
* Changing the hopper's filter no longer moves one leftover non-matching item.
* Fixed terminal updates being lost while a JEI, REI or EMI recipe screen was open.

### Config
* New options (all builds): `buildersReachEnabled`, `restockEnabled` and `itemLocatorEnabled`, all on by default and server authoritative.
* Changed defaults (all builds): `wirelessRange` 16 to 32, `advWirelessRange` 64 to 128, `wirelessTermBeaconLvlCrossDim` 4 to 3, `invLinkBeaconLvlCrossDim` 2 to 3. `basicHopperCooldown` 10 to 8. New allowed ranges: `wirelessRange` 4 to 256, `advWirelessRange` 16 to 1024.
* Inventory Link: `invLinkBeaconLvl` default 1 to 0 (1.20.1). On all builds `invLinkBeaconLvl` only matters as -1, which disables inventory linking entirely; range without beacons is `invLinkBeaconRange` (1 to 65536, default 4096).
* Fabric 1.20.1 config keys were renamed to the 1.21.1 names: `invRange` is now `invConnectorScanRange`, `wirelessTermBeaconLvlDim` is now `wirelessTermBeaconLvlCrossDim`, `invLinkBeaconLvlDim` is now `invLinkBeaconLvlCrossDim`. New on 1.20.1: `basicHopperCooldown`, `invLinkBeaconRange` and `invLinkBeaconLvlSameDim`.
* Migration: configs carry a hidden `configVersion`. When an older file is loaded, renamed 1.20.1 values are carried over, and any value that still equals its old default is moved to the new default. Values you changed yourself are kept. The migrated file is saved once at startup.
* Config files by loader: Fabric uses `config/toms_storage.json` (Cloth Config screen, Mod Menu optional). NeoForge and Forge use two TOML files: `toms_storage-server.toml` (one per world, synced to clients; holds the gameplay options and `configVersion`) and `toms_storage-common.toml` (the connector block lists). They migrate the same way as the Fabric file, and only the side that owns a file migrates it. Config values are not shared between loaders; copy your changes over by hand when you switch.
* Config key names differ per loader. The Fabric JSON uses the names listed above. The NeoForge and Forge TOML files keep the older key names where an option existed there, so existing config files keep working: `inventoryConnectorRange` (`invConnectorScanRange`), `onlyTrimsConnect` (`onlyTrims`), `invCableConnectorMaxScanSize` (`invConnectorMaxCables`), `wirelessReach` (`wirelessRange`) and `wirelessTermBeaconLvlDim` (`wirelessTermBeaconLvlCrossDim`). The Inventory Link cross-dimension level is `invLinkBeaconLvlCrossDim` on NeoForge and `invLinkBeaconLvlDim` on Forge. The other options use the same names as the Fabric JSON.

### Development
* Version bumped to v4.0.0 on all builds.
* Each Minecraft version has a shared code base (`Common-1.21.1`, `Common-1.20.1`) with one thin platform project per loader on top, so a fix lands in every loader at once.
* Automated tests per build (JUnit + in-world GameTests):

| Build | JUnit tests | GameTests |
| --- | --- | --- |
| Fabric 1.21.1 | 91 | 22 |
| NeoForge 1.21.1 | none | 24 |
| Fabric 1.20.1 | 101 | 23 |
| Forge 1.20.1 | 47 | 24 (also 24 on NeoForge 1.20.1 47.1.106) |

* JUnit covers the tracker and storage snapshots, terminal sync round trips, filters, the Filing Cabinet save format, hopper modes, Condition Filter, config migration, the Inventory Link rule, terminal search and strict language file checks. The GameTests run on a headless server and check double chests counted once, terminal pull and push conserving items, cable network break and restore, cable connector filters (including a chest placed later), the hopper modes (also into a Crafter on 1.21.1), Level Emitter, Filing Cabinet with more than 256 items, Open Crate stack sizes, Condition Filter, Item Locator, Builder's Reach pick-block, Inventory Link (1.20.1) and, on NeoForge, loading of the mod's data and cross-loader filter data. GameTests are not part of the release jars.
* Run them per project: `gradlew.bat build` (includes JUnit) and `gradlew.bat runGametest` (Fabric) or `gradlew.bat runGameTestServer` (NeoForge, Forge). `gradlew.bat -PneoForge runGameTestServer` in `Forge-1.20.1` runs the Forge jar's tests on NeoForge 1.20.1.
* `ExportedJars\rebuild_all_and_export_jar.ps1` builds all four projects with tests and copies the four release jars into `ExportedJars`. `-SkipTests` packages without tests.
* Resource files are strict-JSON checked before each commit after an invalid escape in a 1.20.1 language file slipped through compilation.
* A bug and performance audit of both versions and the NeoForge 1.21.1 / Forge 1.20.1 port plan were written.

### Compatibility

| | Fabric 1.21.1 | NeoForge 1.21.1 | Fabric 1.20.1 | Forge 1.20.1 |
| --- | --- | --- | --- | --- |
| Minecraft | 1.21.1 | 1.21.1 | 1.20.1 | 1.20.1 |
| Loader | Fabric Loader + Fabric API | NeoForge 21.1.x | Fabric Loader + Fabric API | Forge 47.1+ (47.4.10 tested), NeoForge 1.20.1 47.1.x (47.1.106 tested) |
| JEI | optional | optional | optional | optional |
| REI | optional | optional | optional | optional |
| EMI | optional | optional | optional | optional |
| Jade | optional | optional | - | - |
| Polymorph | optional | optional | - | - |
| Advanced Wireless Terminal in the belt slot | Trinkets | Curios | Trinkets | Curios |
| Mod Menu | optional | - | optional | - |

* Existing worlds keep working: Filing Cabinets saved by older versions load, existing hoppers start in Normal mode, placed Filtered Inventory Cable Connectors on 1.20.1 convert on their first tick (their filter items drop), and existing configs are migrated as described above.
* Inventory Links keep working, but ranges and beacon rules follow the new balance values; check custom beacon setups.
* Moving a world between loaders of the same Minecraft version: block, item and registry ids are the same on Fabric 1.20.1 and Forge 1.20.1, and on Fabric 1.21.1 and NeoForge 1.21.1. On 1.21.1, filters set with the Inventory Configurator are read by both loaders and written in the other loader's format too, including filters on vanilla and modded chests. Make a backup first. An Advanced Wireless Terminal carried in a Trinkets or Curios belt slot is kept by that mod, so take it out of the slot before you switch. Config files are separate per loader.
* Worlds do not move between Minecraft versions.

## v3.0.2

### Highlights
* Fixed Fabric 1.21.1 Storage Terminal becoming laggy/unresponsive and search not reacting when vanilla double chests, furnaces, hoppers, droppers, dispensers, or brewing stands are on the network. Each of these was being re-detected as "changed" every single tick, spamming sync packets and forcing the client to re-filter its entire item list up to 20 times per second. Double chests are also no longer counted twice.
* Terminal search on both versions is now debounced on large item lists and can no longer get permanently stuck mid-search.
* Fixed Fabric 1.20.1 painted Trim not showing its paint; painted blocks now update their appearance correctly.

### Fixes
* Fabric 1.21.1: change tracking is now owned per connected block face instead of being looked up by the storage wrapper, fixing the false "changed every tick" detection for double chests and other sided inventories; both halves of a double chest are deduplicated, while an item-filtered inventory is never deduplicated away.
* Both versions: fixed a search debounce that could never release on item lists with 1000+ distinct item types, and hardened sorting so a bad comparator can no longer wedge the terminal open.
* Filing Cabinet: fixed a scrollbar divide-by-zero/NaN when all contents fit in the visible rows, along with a stale scroll offset in that case; fixed being able to place a second item type into an empty visible page.
* Fabric 1.21.1: added missing narration and tooltip translations for the sort/control/priority/side buttons, popup text fields, and the inventory configurator hint.
* Both versions: Inventory Link channel names are now sanitized to strip invisible/control characters and lone surrogates, and a name made up of only invisible/space-like characters is rejected; Fabric 1.20.1 now also enforces the same 20-channels-per-player limit as Fabric 1.21.1.
* Fabric 1.20.1: fixed the `painted_trim` block using the plain, unpainted trim model.
* Fabric 1.20.1: fixed the Open Crate being able to extract more items than an item entity actually held when two extractions happened in the same tick (item duplication).
* Fabric 1.20.1: fixed painted blocks rendering invisible when held in hand, caused by a missing model cache and missing item render data; the cache is now also cleared correctly on resource reload.
* Fabric 1.20.1: an unpainted painted block (for example right after painting a Trim, or if its paint data was lost) now renders the plain trim look instead of turning invisible, and painting a block with itself can no longer crash the renderer.
* Fabric 1.20.1: a rare regex failure during terminal search no longer leaves stale items on screen.
* Fabric 1.20.1: the Storage Terminal's tall display mode no longer shows more rows than it can actually manage, and mouse-wheel scrolling now scrolls a full page instead of a fixed 5 rows and no longer breaks in tall mode.
* Fabric 1.20.1: added a missing chat message translation ("Terminal out of range (Not Loaded)").

### Development
* Fabric 1.21.1 now has a JUnit test setup (fabric-loader-junit, mockito) including a regression test for the double-chest tracker fix; run with `./gradlew.bat test` from `Fabric-1.21.1`.

### Compatibility
* Fabric 1.20.1
* Fabric 1.21.1

## v3.0.1

### Fixes
* Fixed Fabric 1.20.1 mod blocks not dropping their item when broken. Block loot JSON had been under the 1.21+ datapack path `loot_table/blocks/` (not loaded on 1.20.1), while legacy `loot_tables/blocks/ts.*` filenames did not match current block registry ids. Loot tables now live under `loot_tables/blocks/` with ids aligned to blocks, including `inventory_cable_connector_filtered` and corrected drops for the three `ts.*_painted` blocks.

### Compatibility
* Fabric 1.20.1
* Fabric 1.21.1 (version bump in lockstep; no additional code changes required for the 1.20.1 loot-path issue)

## v3.0.0

### Highlights
* Terminal sorting expansion with 4 new modes: Rarity, Registry ID, Durability, and Item Group.
* Cross-version parity improvements between Fabric 1.20.1 and 1.21.1 for key user-facing interactions.
* The basic Wireless Terminal can now be bound to a Storage Terminal (sneak + right-click), in addition to the Advanced Wireless Terminal. Earlier versions only supported binding the Advanced version.

### Changes
* Added new terminal sorting comparators in both 1.20.1 and 1.21.1.
* Improved 1.20.1 terminal sort-state encoding to support additional sorting modes safely.
* Added localization entries for all new sorting options.
* Basic Wireless Terminal now supports sneak + right-click binding to a Storage / Crafting Terminal in both 1.20.1 (NBT) and 1.21.1 (`bound_pos` data component). When bound, opening the terminal (right-click in air or keybind) targets the bound block while still respecting the basic terminal's wireless range.
* Updated 1.20.1 and 1.21.1 wireless terminal tooltip to mention sneak-click binding, plus a new line showing the bound coordinates.

### Fixes
* Fixed 1.20.1 advanced wireless terminal distance/beacon validation mismatch with 1.21.1 behavior.
* Fixed 1.20.1 basic inventory hopper filter retrieval replacing held items.
* Fixed 1.21.1 inventory configurator missing right-click selection feedback.
* Fixed 1.20.1 Advanced Wireless Terminal binding being silently rejected: `data/toms_storage/tags/blocks/remote_activate.json` referenced legacy block ids (`ts.storage_terminal`, `ts.crafting_terminal`) that no longer match registered blocks. The tag now lists the correct ids and the duplicate at the singular `tags/block/` path has been removed.
* Fixed 1.21.1 mod blocks disappearing without dropping their item when broken: `pack.mcmeta` declared `pack_format: 48` (data-pack format for 1.21.1) but the bundled resource pack expected format `34`, which could mark the bundled pack as incompatible. The metadata now declares `pack_format: 34` with `supported_formats: [34, 48]` to satisfy both the resource and data sides of the pack.

### Compatibility
* Fabric 1.20.1
* Fabric 1.21.1

## v2.0.0

### Highlights
* Major reliability update for storage network detection and terminal syncing.
* Recommended update for all Fabric players on 1.20.1 and 1.21.1.

### Changes
* Improved connector and cable refresh timing for faster network updates.
* Improved terminal item sync behavior so the first open rebuilds correctly.
* Improved connector network handling to avoid stale detection states.

### Fixes
* Fixed a 1.21.1 issue where storages placed after connector and terminal setup were not detected correctly.
* Fixed intermittent cases where storage network updates could depend on placement order.
* Fixed a 1.20.1 block tag issue that could affect expected mining speed behavior.
* Fixed legacy/invalid mineable tag entries to use correct modern block ids.
* Removed accidental internal interaction mixin classes that could impact vanilla behavior.

### Compatibility
* Fabric 1.20.1
* Fabric 1.21.1

### Player Note
* Existing worlds are supported.
* If you had old terminals/connectors placed before this update, reopening the terminal after updating will refresh the network state.
