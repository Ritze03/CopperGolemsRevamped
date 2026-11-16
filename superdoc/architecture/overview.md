# Architecture Overview

The start-here page: orientation to the codebase, linking out to sibling docs rather than
duplicating them. Index of all pages: [../README.md](../README.md).

## Big picture

Copper Golems Revamped is a Fabric mod for Minecraft 26.3 (Mojang names, unobfuscated; mod id
`coppergolemsrevamped`) that makes Copper Golems useful. Right-clicking a golem with an empty hand opens a
server-side GUI (works on vanilla clients) where the player picks a profession, assigns its targets (chests, blocks, areas) and toggles follow
mode. Implemented professions: Chest Sorter, Lumber, Crafter, Transporter, Farmer, Cleaner, Smelter, Butcher, Distributor.
A golem with no profession stands still. A golem with a profession keeps its chunks loaded, see [chunk-loading.md](../features/chunk-loading.md).

## Data flow

1. Empty-hand right-click: `mixin/CopperGolemMixin:cgr$emptyHand` -> `menu/GolemMenuOpener:open` (server)
   opens `menu/GolemMenu`, a vanilla chest-type menu: the GUI is server-side only, vanilla clients need no mod.
   See [gui.md](../features/gui.md).
2. The menu is always 6 rows with a tab bar (Status, Targets, Inventory, Settings, Profession, plus Recipe for a
   profession with a recipe group or Filter for a filter group (Transporter), no Back button); tabs switch in place
   (`menu/GolemMenu:showTab`, the cells come from `menu/GolemViews:build`). Clicks on icon cells are handled in
   `menu/GolemMenu:clicked`: a profession button -> `GolemMenu:choose` (None switches at once, otherwise a confirm screen)
   -> `core/GolemService:changeProfession` (takes the player: stored items go to the player; then the menu reopens),
   follow and Freeze toggles -> flip the `GolemData` fields (see [freeze.md](../features/freeze.md)), assign button -> `assign/AssignMode:arm` (see
   [assign-mode.md](../features/assign-mode.md)), Show / Clear -> `ai/WalkTo` / the assignments, Help ->
   `menu/GolemMenu:sendHelp` (chat text from `menu/Tab` and `profession/ProfessionHelp`).
3. All per-golem state is one persistent attachment, `core/GolemData` (`core/ModAttachments:GOLEM_DATA`):
   profession, assignments, carried items, follow and freeze state.
4. Every server tick the vanilla brain tick is wrapped (`mixin/CopperGolemMixin:cgr$gateBrain`) and routed to
   `core/GolemTicker:tick`: first the chunk ticket is kept or released (`chunk/GolemChunkLoader`) and the hand display
   synced, then a frozen golem stops and returns (nothing below runs, see [freeze.md](../features/freeze.md)), then
   `ai/WalkTo:tick` (a pending walk-to wins), then follow mode (`ai/FollowMode:tick`); a golem with no profession stands
   still; otherwise the vanilla brain runs only for professions with `runsVanillaBrain` (Chest Sorter), every other
   profession runs its own tick (`profession/Profession#tick`, e.g. `profession/transporter/TransporterLogic:tick`).
   See [transporter.md](../features/transporter.md).
   **Safety net:** `GolemTicker:tick` wraps all of that in a `try/catch (RuntimeException)`. A throwing golem is logged
   once per minute at most (UUID, profession) and its tick is skipped for 200 ticks, then retried (it keeps backing off
   if it keeps failing). Why: a single golem must never take down the server (user-visible crash 2026-11-15, array index
   error in Lumber at a raised tick rate). `GolemTicker.rethrow` (set by `CoreFeedbackTest`) makes GameTests rethrow so
   real bugs still fail them.
5. Death and statue conversion drop stored items via `core/GolemService:dropAll` (mixin hooks).

## Module map

Package root: `src/main/java/com/coppergolemsrevamped` (there is no client source code (no `src/client` set): the mod is server-side only; tests in `src/gametest`).

| Path | Role |
| --- | --- |
| `CopperGolemsRevamped` | Mod entrypoint; initialises attachments, chunk loader, professions, assign mode |
| `core/` | `GolemData` attachment, `ModAttachments`, `GolemService` (profession change, spawn chest prefill, auto profession name, item hand-over to the player, drops), `GolemTicker` (brain gate), `GolemMending` (Mending repair and never-break guard for tool professions, see [mending.md](../features/mending.md)) |
| `chunk/` | `GolemChunkLoader`: persistent chunk ticket per profession golem, see [chunk-loading.md](../features/chunk-loading.md) |
| `mixin/` | `AbstractFurnaceAccessor`: furnace stored-XP map for the Smelter; `AnimalMixin`: Butcher takes the breeding XP; `EntityPushMixin` + `LivingEntityPushMixin`: animals do not push a Butcher; `CopperGolemMixin`: interact, brain gate, death and statue drops; `EntityMixin`: lets the Butcher take a struck animal's death loot into its carry; `GolemSpawnMixin`: records the spawn copper chest (build pattern); `TransportItemsBetweenContainersMixin`: Chest Sorter visited-chest limit |
| `profession/` | `Profession` declaration record, `Professions` registry, `ProfessionHelp` (help texts) |
| `profession/transporter/` | Transporter declaration and work loop |
| `profession/chest_sorter/` | Chest Sorter declaration and visited-chest limit, see [chest-sorter.md](../features/chest-sorter.md) |
| `profession/butcher/` | Butcher declaration and cull/breed/collect loop, see [butcher.md](../features/butcher.md) |
| `profession/cleaner/` | Cleaner declaration and area pickup loop, see [cleaner.md](../features/cleaner.md) |
| `profession/crafter/` | Crafter declaration and fetch/craft/deliver loop, see [crafter.md](../features/crafter.md) |
| `profession/farmer/` | Farmer declaration and till/plant/harvest/deliver loop, see [farmer.md](../features/farmer.md) |
| `profession/smelter/` | Smelter declaration and phase loop (fetch, feed, fuel, collect, deliver), see [smelter.md](../features/smelter.md) |
| `profession/distributor/` | Distributor declaration and fetch/distribute/return loop, see [distributor.md](../features/distributor.md) |
| `profession/lumber/` | Lumber declaration and fell/replant/deliver loop, see [lumber.md](../features/lumber.md) |
| `ai/` | `FollowMode`; `WalkTo` (walk to an assigned block or area outline); `Interact`: stand position / reach and chest lids, see [interaction.md](interaction.md); `TransferBudget`: the 16 items per 5 ticks golem transfer limit, see [transfer-limit.md](transfer-limit.md); `ToolSwing`: visible chopping/hoeing motion for Lumber and Farmer, see [lumber.md](../features/lumber.md) |
| `assign/` | `AssignMode`: armed next-click target assignment, area capping (`clampCorner`), walk-to trigger |
| `menu/` | `GolemMenu` (6-row tabbed menu, fixed slots routed to cells, click handling), `GolemViews` (cells per tab), `Cell`, `Tab`, `GolemIcon` (icon stacks), `GolemItemsContainer` over `GolemData`, `GolemMenuOpener` |
| `src/main/resources/assets/` | Icon models and `minecraft/items/*` overrides; also zipped as the optional resource pack |
| `src/gametest/` | GameTests, see [testing.md](testing.md) |

Vanilla 26.3 and Fabric API signatures used throughout: [vanilla-copper-golem.md](vanilla-copper-golem.md).

## Where to look for X

- Add a profession: declare a `profession/Profession` and register it with `profession/Professions:register`
  (example: `profession/transporter/TransporterProfession:init`); call its init from `CopperGolemsRevamped:onInitialize`.
- Change what a golem remembers: `core/GolemData:CODEC` (and the fields next to it).
- Change a profession's slot layout in the Inventory tab: its `SlotGroup` declaration (`at`, `labeled`), `profession/Profession:validateLayout`.
- Change a tab or add a GUI control or icon: `menu/GolemViews`, `menu/Tab`, `menu/GolemIcon`, see [gui.md](../features/gui.md).
- Change the Status texts: `profession/ProfessionStatus`. The golem's visible hand item: `core/GolemHandDisplay`.
- Change how targets are assigned or validated: `assign/AssignMode:click`, `assign/AssignMode:matches`.
- Change when the vanilla brain runs (and the None case, where the golem stands still): `core/GolemTicker:tick`.
- Change the help texts: `profession/ProfessionHelp`.
- Change the walk-to behaviour: `ai/WalkTo`.
- Change the area size cap: `assign/AssignMode:clampCorner`.
- Change how the spawn chest is recorded: `mixin/GolemSpawnMixin`.
- Change chunk loading (ticket radius, refresh, release rules): `chunk/GolemChunkLoader:keep`, `GolemChunkLoader:release`, see [chunk-loading.md](../features/chunk-loading.md).
- Change the item transfer limit or how a visit continues when it runs out: `ai/TransferBudget`, see [transfer-limit.md](transfer-limit.md).
- Change where golems stand to interact, or the chest-lid animation: `ai/Interact:approach`, `Interact:standPos`, `Interact:openChest`, see [interaction.md](interaction.md).
- Change the Transporter's item moving: `profession/transporter/TransporterLogic:takeAll`, `TransporterLogic:insertAll`.
- Change the Cleaner area or pickup rules: `profession/cleaner/CleanerLogic:box`, `CleanerLogic:tick`.
- Change the Farmer field, seed or tool (hoe/axe) rules: `profession/farmer/FarmerLogic:cells`, `FarmerLogic:isSeed`, `FarmerLogic:modeOf`, `FarmerLogic:findTool`, `FarmerLogic:tick`, see [farmer.md](../features/farmer.md).
- Change Lumber tree detection, the break rule or the felling step: `profession/lumber/LumberLogic:detectTree`, `LumberLogic:breakAllowed`, `LumberLogic:breakBlock`, `LumberLogic:breakTicks`, see [lumber.md](../features/lumber.md).
- Change the Butcher animal rules, the 8-adult / breeding caps or how Looting is applied: `profession/butcher/ButcherLogic:FARM_ANIMALS`, `ButcherLogic:isProtected`, `ButcherLogic:surplus`, `ButcherLogic:breedAllowed`, `ButcherLogic:strike`, `ButcherLogic:Phase` (the cycle), `ButcherLogic:capture` + `mixin/EntityMixin` (drops into the carry), see [butcher.md](../features/butcher.md).
- Change the Crafter recipe matching or loop: `profession/crafter/CrafterLogic:plan`, `CrafterLogic:decide`, see [crafter.md](../features/crafter.md).
- Change stored XP, the 7-XP bottle conversion or where XP is earned and delivered: `core/GolemXp`, `GolemData:xp`, `SmelterLogic:takeFurnaceXp`, `ButcherLogic:takeBreedingXp`, see [xp-bottles.md](../features/xp-bottles.md).
- Change Mending repair or the "never break a Mending tool" rule for Lumber, Farmer and Butcher: `core/GolemMending`, see [mending.md](../features/mending.md).
- Change the Smelter furnace scan, split or never-block rules: `profession/smelter/SmelterLogic:rescan`, `SmelterLogic:planFetch`, `SmelterLogic:pick`, see [smelter.md](../features/smelter.md).
- Change the Distributor area scan, the multiple-of-N fetch plan or the share split: `profession/distributor/DistributorLogic:rescan`, `DistributorLogic:planFetch`, `DistributorLogic:sharesOf`, `DistributorLogic:distribute`, see [distributor.md](../features/distributor.md).
- Change the Keep layout rule (leave 1 per slot, never fill empty slots): `profession/transporter/TransporterLogic:takeAll`, `TransporterLogic:insertAll`, `TransporterLogic:layoutRule`, Chest Sorter in `mixin/TransportItemsBetweenContainersMixin`, see [keep-layout.md](../features/keep-layout.md).
- Change the Chest Sorter chest limit: `profession/chest_sorter/ChestSorterProfession:visitedLimit`, mixin `mixin/TransportItemsBetweenContainersMixin`.
- Change death/statue drops: `core/GolemService:dropAll`, hooks in `mixin/CopperGolemMixin`.
- Write a GameTest: `src/gametest/java/com/coppergolemsrevamped/test/TransporterTest`, notes in [testing.md](testing.md).

## Build and test

Gradle must run on JDK 25: set `JAVA_HOME` to a JDK 25 at invocation. `./gradlew build` compiles, runs the
GameTests (`runGameTest`, part of `check`) and packages (mod jar plus the resource-pack zip, see [gui.md](../features/gui.md)); `./gradlew runGameTest` runs just the tests.
