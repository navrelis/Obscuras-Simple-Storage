## Obscura's Simple Storage
A vanilla-style, heavily optimized Minecraft storage mod for Fabric, NeoForge and Forge. Connect the chests, barrels and other inventories you already have into one network and reach all of it from a single terminal. No power, no channels, no storage cells.  
Developed and maintained by **Navrelis**. Mod id: `obscuras_storage`.

### Supported versions
| Minecraft | Loader | Jar |
| --- | --- | --- |
| 1.21.1 | Fabric | `obscuras_simple_storage-fabric-mc1.21.1-v5.1.1.jar` |
| 1.21.1 | NeoForge | `obscuras_simple_storage-neoforge-mc1.21.1-v5.1.1.jar` |
| 1.20.1 | Fabric | `obscuras_simple_storage-fabric-mc1.20.1-v5.1.1.jar` |
| 1.20.1 | Forge (also runs on NeoForge 1.20.1) | `obscuras_simple_storage-forge-mc1.20.1-v5.1.1.jar` |

All four builds have the same features, balance and fixes. The server and the clients must run the same version.

### New in v5.1.0
- **Ejector**: takes matching items out of the storage and drops them in front of it, like a dropper. Needs redstone, with a Continuous and a Pulse mode.
- **Disposal**: takes matching items out of the storage and destroys them for good. It starts paused and counts what it destroyed.
- **Exporter**: moves matching items from the storage into the chest, barrel, Shulker Dock or other inventory in front of it.
- The three **filter blocks** plug into a cable, an Inventory Connector, an Inventory Interface or a single inventory, and use a 3x3 ghost filter (the Item Filter, Tag Item Filter, Condition Filter and Polymorphic Item Filter work in a slot too).
- **Highlight Connected Inventories**: a button in every terminal and the key H outline every inventory of a network through walls for 10 seconds.
- **Updating from 4.x:** the mod id changed to `obscuras_storage` in v5.0.0 (it was `toms_storage`). Worlds, configs and key bindings are migrated automatically, but datapacks, scripts and resource packs that still use the old id must be changed. See [CHANGELOG.md](CHANGELOG.md) for the details and known limits, and for everything that came with v5.0.0.
### Performance and reliability
The mod is built to stay cheap on multiplayer servers, in singleplayer worlds and in huge builds with 1000+ containers:
- **Inventory Hopper**: about 93% less idle tick cost
- **Cable Network**: per-network invalidation instead of global, dirty flag gating on rebuilds
- **Slot Scanning**: O(S) iterator-based scanning
- **Filter System**: hash-based O(1) lookups
- **Terminal Sync**: only changed items are sent, immediate first sync, search debounce
- **GUI Rendering**: no per-frame allocations
- **Memory and Thread Safety**: no leaks from world state or stale caches, volatile fields and concurrent maps where needed
- **Zero compiler warnings**
- **Tested**: JUnit tests plus in-world GameTests on a headless server for every loader

### Features
- Storage & Crafting Terminal
- Inventory Connector
- Open Crate
- Wireless Terminal
- Inventory Cable and Cable Connector, Inventory Trim, Inventory Proxy and Inventory Interface
- Inventory Configurator with Item, Tag, Polymorphic and Condition Filters
- Inventory Hopper with Pulse and Keep Stock modes
- Ejector, Disposal and Exporter (filter blocks)
- Filing Cabinet
- Shulker Dock
- Frame Labels (glow item frames as chest labels)
- Tidy button
- Level Emitter with analog mode, and comparator output on storage blocks
- Inventory Link (works without a beacon)
- Item Locator and Highlight Connected Inventories
- Builder's Reach (pick-block from storage, Restock)
- Network Doctor for server admins and modpack developers

### Requirements
| | Fabric 1.21.1 | NeoForge 1.21.1 | Fabric 1.20.1 | Forge 1.20.1 |
| --- | --- | --- | --- | --- |
| Minecraft | 1.21.1 | 1.21.1 | 1.20.1 | 1.20.1 |
| Loader | [Fabric Loader](https://fabricmc.net/use) | [NeoForge](https://neoforged.net) 21.1.x | [Fabric Loader](https://fabricmc.net/use) | [Forge](https://files.minecraftforge.net) 47.1+, or NeoForge 1.20.1 (47.1.x) |
| Other | [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) | - | [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) | - |

### Optional integrations
None of these is required or bundled.

| | Fabric 1.21.1 | NeoForge 1.21.1 | Fabric 1.20.1 | Forge 1.20.1 |
| --- | --- | --- | --- | --- |
| JEI | yes | yes | yes | yes |
| REI | yes | yes | yes | yes |
| EMI | yes | yes | yes | yes |
| Jade | yes | yes | - | - |
| Polymorph | yes | yes | - | - |
| Trinkets | yes | - | yes | - |
| Curios | - | yes | - | yes |
| Mod Menu | yes | - | yes | - |

Trinkets (Fabric) and Curios (NeoForge, Forge) let you carry a Wireless Terminal in an accessory slot.

### Building
You need **JDK 21** for the Gradle wrapper (set `JAVA_HOME` or put it on `PATH`). Forge 1.20.1 compiles with Java 17, which Gradle downloads by itself through a toolchain, so no separate JDK 17 is needed. The first build needs network access to fetch dependencies.

Each loader is its own Gradle project; the loader-agnostic code lives in `Common-1.21.1` and `Common-1.20.1`, which the loader projects compile in (they are not built on their own). Run these from the project folder (`gradlew.bat` on Windows, `./gradlew` elsewhere):

| Project | Build (with JUnit tests) | GameTests |
| --- | --- | --- |
| `Fabric-1.21.1` | `gradlew.bat build` | `gradlew.bat runGametest` |
| `NeoForge-1.21.1` | `gradlew.bat build` | `gradlew.bat runGameTestServer` |
| `Fabric-1.20.1` | `gradlew.bat build` | `gradlew.bat runGametest` |
| `Forge-1.20.1` | `gradlew.bat build` | `gradlew.bat runGameTestServer`, and `gradlew.bat -PneoForge runGameTestServer` for NeoForge 1.20.1 |

The jars are written to each project's `build/libs/` (for example `obscuras_simple_storage-fabric-mc1.21.1-v5.1.1.jar`; ignore the `-sources` jar). GameTests are not part of the release jars. `gradlew.bat runClient` starts a dev client.

To build, test and collect all four release jars in one go, run the release script with PowerShell 7:

```
pwsh ExportedJars\rebuild_all_and_export_jar.ps1
pwsh ExportedJars\rebuild_all_and_export_jar.ps1 -SkipTests   # quick packaging, no tests
```

It runs a clean build, the JUnit tests and the GameTests of every project, stops at the first failure, and copies the four jars into `ExportedJars` only when everything passed. It ends with a summary table of tests, GameTests, jar names and sizes.

### License
MIT, see [LICENSE](LICENSE).

### Credits
- **Navrelis**: development, performance work, new features, bug fixes
- **[Luna](https://www.curseforge.com/members/luna)**: vanilla-style textures, used with her explicit permission
