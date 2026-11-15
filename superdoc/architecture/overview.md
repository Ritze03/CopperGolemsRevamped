# Architecture Overview

The start-here page: orientation to the codebase, linking out to sibling docs rather than
duplicating them. Index of all pages: [../README.md](../README.md).

## Big picture

Copper Golems Revamped is a Fabric mod for Minecraft 26.3 (Mojang names, unobfuscated; mod id
`coppergolemsrevamped`) that makes Copper Golems useful. Right-clicking a golem with an empty hand opens a
server-side GUI (works on vanilla clients) where the player picks a profession, assigns its targets (chests, blocks, areas) and toggles follow
mode. Six professions are planned: Chest Sorter, Lumber, Crafter, Transporter, Farmer, Cleaner.
**Phase A is built: the foundation plus the Transporter.** A golem with no profession keeps vanilla behaviour.

## Data flow

1. Empty-hand right-click: `mixin/CopperGolemMixin:cgr$emptyHand` -> `menu/GolemMenuOpener:open` (server)
   opens `menu/GolemMenu`, a vanilla chest-type menu: the GUI is server-side only, vanilla clients need no mod.
   See [gui.md](../features/gui.md).
2. Clicks on control cells are handled in `menu/GolemMenu:clicked`: profession cell ->
   `core/GolemService:changeProfession` (then the menu reopens), follow cell -> flips the follow fields of
   `GolemData`, assign cell -> `assign/AssignMode:arm` (see [assign-mode.md](../features/assign-mode.md)).
3. All per-golem state is one persistent attachment, `core/GolemData` (`core/ModAttachments:GOLEM_DATA`):
   profession, assignments, carried items, follow state.
4. Every server tick the vanilla brain tick is wrapped (`mixin/CopperGolemMixin:cgr$gateBrain`) and routed to
   `core/GolemTicker:tick`: follow mode (`ai/FollowMode:tick`), else the profession tick
   (`profession/Profession#tick`, e.g. `profession/transporter/TransporterLogic:tick`), else the vanilla brain.
   See [transporter.md](../features/transporter.md).
5. Death and statue conversion drop stored items via `core/GolemService:dropAll` (mixin hooks).

## Module map

Package root: `src/main/java/com/coppergolemsrevamped` (there is no client source code (no `src/client` set): the mod is server-side only; tests in `src/gametest`).

| Path | Role |
| --- | --- |
| `CopperGolemsRevamped` | Mod entrypoint; initialises attachments, professions, assign mode |
| `core/` | `GolemData` attachment, `ModAttachments`, `GolemService` (profession change, drops), `GolemTicker` (brain gate) |
| `mixin/` | `CopperGolemMixin`: interact, brain gate, death and statue drops; `TransportItemsBetweenContainersMixin`: Chest Sorter visited-chest limit |
| `profession/` | `Profession` declaration record, `Professions` registry |
| `profession/transporter/` | Transporter declaration and work loop |
| `profession/chest_sorter/` | Chest Sorter declaration and visited-chest limit, see [chest-sorter.md](../features/chest-sorter.md) |
| `profession/cleaner/` | Cleaner declaration and area pickup loop, see [cleaner.md](../features/cleaner.md) |
| `profession/crafter/` | Crafter declaration and fetch/craft/deliver loop, see [crafter.md](../features/crafter.md) |
| `profession/farmer/` | Farmer declaration and till/plant/harvest/deliver loop, see [farmer.md](../features/farmer.md) |
| `ai/` | `FollowMode` |
| `assign/` | `AssignMode`: armed next-click target assignment |
| `menu/` | `GolemMenu` (chest-type layout built from the profession declaration, click handling), `GolemIcon` (icon stacks), containers over `GolemData`, `GolemMenuOpener` |
| `src/main/resources/assets/` | Icon models and `minecraft/items/*` overrides; also zipped as the optional resource pack |
| `src/gametest/` | GameTests, see [testing.md](testing.md) |

Vanilla 26.3 and Fabric API signatures used throughout: [vanilla-copper-golem.md](vanilla-copper-golem.md).

## Where to look for X

- Add a profession: declare a `profession/Profession` and register it with `profession/Professions:register`
  (example: `profession/transporter/TransporterProfession:init`); call its init from `CopperGolemsRevamped:onInitialize`.
- Change what a golem remembers: `core/GolemData:CODEC` (and the fields next to it).
- Change the GUI layout or slots: `menu/GolemMenu` constructor.
- Add a GUI control or icon: `menu/GolemMenu:buildControls`, `menu/GolemIcon`, see [gui.md](../features/gui.md).
- Change how targets are assigned or validated: `assign/AssignMode:click`, `assign/AssignMode:matches`.
- Change when the vanilla brain runs: `core/GolemTicker:tick`.
- Change the Transporter's item moving: `profession/transporter/TransporterLogic:takeAll`, `TransporterLogic:insertAll`.
- Change the Cleaner area or pickup rules: `profession/cleaner/CleanerLogic:box`, `CleanerLogic:tick`.
- Change the Farmer field, seed or hoe rules: `profession/farmer/FarmerLogic:cells`, `FarmerLogic:isSeed`, `FarmerLogic:tick`, see [farmer.md](../features/farmer.md).
- Change the Crafter recipe matching or loop: `profession/crafter/CrafterLogic:plan`, `CrafterLogic:decide`, see [crafter.md](../features/crafter.md).
- Change the Chest Sorter chest limit: `profession/chest_sorter/ChestSorterProfession:visitedLimit`, mixin `mixin/TransportItemsBetweenContainersMixin`.
- Change death/statue drops: `core/GolemService:dropAll`, hooks in `mixin/CopperGolemMixin`.
- Write a GameTest: `src/gametest/java/com/coppergolemsrevamped/test/TransporterTest`, notes in [testing.md](testing.md).

## Build and test

Gradle must run on JDK 25: set `JAVA_HOME` to a JDK 25 at invocation. `./gradlew build` compiles, runs the
GameTests (`runGameTest`, part of `check`) and packages (mod jar plus the resource-pack zip, see [gui.md](../features/gui.md)); `./gradlew runGameTest` runs just the tests.
