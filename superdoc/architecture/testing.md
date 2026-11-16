# Testing

GameTests live in the `gametest` source set (`src/gametest`, test mod id `coppergolemsrevamped-test`),
registered through the `fabric-gametest` entrypoint list in `src/gametest/resources/fabric.mod.json`.
`./gradlew build` runs them via `check` (`runGameTest`). Fabric GameTest API reference:
[vanilla-copper-golem.md](vanilla-copper-golem.md) section 10. The client GameTest (below) is
not part of `check`.

## Tests

Per class, with the `@GameTest` method count. Detail of what each test checks is on the feature page.

- `TransporterTest` (8): `transporterMovesEverything` (12 stacks, two item types, A to B with exact counts,
  `maxTicks = 2400`, a run needs ~1200 ticks), plus the shared `ai/Interact` tests `standPosPicksNearestOneBlockSpot`,
  `reachRules`, `openChestClosesAfterTimer`, `killedGolemDoesNotLeakOpener`, `walkingAwayClosesChest`,
  `transporterStandsCloseAndOpensLids`, `walledOffChestUsesTwoBlockFallback` (see [interaction.md](interaction.md)).
- `GolemDataTest` (2): `golemDataSurvivesSaveLoad` (full entity round trip: `Entity#saveWithoutId` into
  `TagValueOutput`, then `Entity#load` of a fresh golem from `TagValueInput`; proves `GolemData` is a persistent
  attachment, not just that its codec works), `assignAreaHelpers` (pure `AssignMode:areaFits` / `AssignMode:inRange`).
- `CoreFeedbackTest` (14): `throwingGolemBacksOff` (the `GolemTicker` safety net; the class sets `GolemTicker.rethrow = true` statically so every other test still fails on exceptions), spawn chest (`spawnChestFromBuildPattern`, `spawnChestPrefillRules`), profession switch hands
  items to the player (`switchMovesItemsToPlayer`, `switchDropsRestAtPlayerWhenFull`), None stands still
  (`noneProfessionStandsStill`), auto profession name (`professionShownAsName`, `playerNameTagIsKept`), `WalkTo`
  (`walkToStopsNextToBlock`, `areaAssignWalksOutline`, `unreachableWaypointIsSkipped`), area clamp
  (`areaCornerIsCappedNotRejected`), `everyProfessionHasHelp`. See [gui.md](../features/gui.md),
  [assign-mode.md](../features/assign-mode.md).
- `MenuTest` (25, incl. `settingsKeepLayoutToggle`, `settingsStripLogsToggleIsLumberOnly`, incl. `professionTabIsCentredAndWraps`): the tabbed menu. `opensOnStatusWithTabBar`, `noBackButtonAndCrafterBarHasRecipe` (no Back cell on any tab of any profession, Crafter bar has 6 tabs with Recipe before Inventory), `tabSwitchKeepsTheMenuInstance` (sets
  `player.containerMenu`, clicks tabs and checks it is still the same menu), `controlCellsSwallowEveryOtherClick`,
  `noneSwitchesWithoutConfirm`, `confirmFlowAndCancel`, `targetsShowAndClear`, `targetsAreaClearAndColumns`,
  `lumberAndFarmerLayouts` (cell positions of every profession's Inventory tab), `settingsFollowToggle`, `settingsFreezeToggle`,
  `statusShowsToolCarriedAndHolding`, `recipeCellSetsACopyAndClearsWithAnEmptyHand` (cursor of 10 planks stays 10, template
  1), `recipeCellOtherClicksConserveItems`, `inputCellWithoutTemplateRefusesItems`, `recipeCellKeepsTheSurplusOfAnOddStack`,
  `ghostCellsCannotBeTaken` (PICKUP,
  QUICK_MOVE, CLONE, PICKUP_ALL, SWAP, THROW on the ghost output and a faded cell; no display copy escapes, item count
  conserved), `realOutputIsStillTakeOnly`, `handToolIsDisplayOnlyAndNeverDrops` (kills a golem, counts the drops), `vanillaThrowNeverSeesTheDisplayCopy`,
  `handDisplayRules`. The mock player (`makeMockServerPlayerInLevel`) can open and close menus, so reopen paths run too.
  See [gui.md](../features/gui.md).
- `ChestSorterTest` (7): `visitedLimit`, `legacyItemsAreDropped` (`GolemTicker:tick` migrates old waxed-copper items),
  `menuWithoutSlotGroups` (same 6-row menu), `mixinAppliesLimit`, `sortsFreshGolem`,
  `sortsWaxedOxidizedSourceIntoMatchingChest` (a waxed oxidized copper chest as the source), `sortsAfterBeingTransporter`
  ([chest-sorter.md](../features/chest-sorter.md)).
- `CleanerTest` (10): `cleansItemsInsideArea`, `areaLimit16`, `partialPickupConservesItems`, `borderItemIgnored`,
  `skipRuleExpires`, `deliversWhenOnlySkippedItemsRemain`, `unreachableItemDoesNotBlock`, `pickupWorksFromSpawnTick`,
  `opensOutputChestWhenDelivering`, `keepLayoutDeliversOnlyOntoMatchingStacks` ([cleaner.md](../features/cleaner.md)).
- `CrafterTest` (14): `craftsSticksUntilOutputFull`, `craftsRepeaterWithPerCellSlots`, `fullOutputChestKeepsProductAndResumes`,
  `noCraftWithoutTable`, `noCraftWhenTableReplaced`, `deliversEverythingWhenTemplateCleared`, `staleInputItemsAreDelivered`,
  `lidsOpenAndReachIsOneBlock`, `guiLayoutIsOneRememberingGrid`, `recipeTabShowsTheTemplateAsGhosts`, `shiftClickFillsRememberedInputOnly`,
  `itemsDropOnProfessionChange`, `keepLayoutUsesOnlyTakeablesAndMerges`, `keepLayoutOutputWithoutMatchingStackCountsAsFull` ([crafter.md](../features/crafter.md)).
- `FarmerTest` (20): `harvestsReplantsAndOutputs`, `tillsDirtWithHoe`, `wornHoeIsSwappedNotLost`,
  `harvestWithNearlyFullCarryLosesNothing`, `stemSeedsIgnored`, `seedsGoToSeedChestRestToOutput`, `seedsOverflowToOutput`,
  `noSeedChestSeedsToOutput`, `axeHarvestsFruitLeavesStems`, `hoeIgnoresMelons`, `wornHoeReplacedOnlyByHoe`,
  `wornAxeReplacedOnlyByAxe`, `noReplacementKeepsWornHoe`, `noReplacementKeepsWornAxe`, `emptySlotTakesFirstToolAndFilter`,
  `fieldLimit9`, `seedDefinition`, `hoeWorkSwingsAndReturnsToIdle`, `swingCounts`, `keepLayoutFetchesAndDeliversAroundTheLayout` ([farmer.md](../features/farmer.md)). The nearly-full-carry test forces the drop roll
  through the global hook `FarmerLogic:testDropRoll` (filtered to its own crop position, since tests run in parallel).
- `LumberTest` (38): `stripLogsGivesStrippedLogs`, `stripLogsIsVisibleStep`, `fellsMixedPlainAndStrippedTree`, `stripLogsOffByDefault`, `stripLogsNeverBreaksMendingAxe`, `strippedStateFollowsVanilla`, `fellsAlreadyStrippedTree`, `stripLogsSurvivesSaveLoad` (strip setting, see lumber.md), `brokenAxeEmptiesSlotAndRefetches` (axe at 1 durability breaks, slot empty, no exception, new axe fetched), `fellsOakFromTheGround`, `fellsBranchyTree`, `fellsTwoByTwoAndReplantsFour`, `collectsGroundDrops`, `collectsDropsOneBlockFromTheSapling`, `collectsDropInsideTheSaplingFromOutside` (drop on the sapling cell is pulled in from the safe ring),
  `bonemealGrowsTheSaplingThenFellsIt`, `fullCarryDeliversMidTreeAndContinues`, `noAxeNoWork`, `wornAxeIsSwapped`,
  `walledInTrunkIsFelledFromTwoBlocks`, `unreachableTreeIsSkipped`, `fullCycleOrder`, `dropsAreCollectedBeforeBoneMeal`, `lowSaplingStockWaitsForDropsBeforeBoneMeal`, `boneMealCyclesKeepTheSaplingStock`, `twoByTwoSiteWaitsForFourSaplings`,
  `neverStandsInTheSapling`, `stepsOutOfTheSapling`, `breakAllowedRules`, `detectTreeRules`,
  `breakTimeFollowsAxeSpeed`, `chopsHoldTheInteractionStateAndReturnsToIdle`, `toolSwingRulesAndCleanup`, `toolSwingHoldAndCleanup`, `lumberDeclaration`, `keepLayoutFetchesAndDeliversAroundTheLayout` ([lumber.md](../features/lumber.md)).
- `ButcherTest` (22, incl. `keepLayoutFoodFetchLeavesOne`, `keepLayoutSingleFoodItemIsNotTaken`, `cooldownAnimalsAreCulledFirst`, `crowdDoesNotMoveTheButcher`, `pushIsOneSidedOnlyAgainstAnimals`): `cullsSurplusKeepsEightAdultsAndCalves` (12 cows + 3 calves), `namedAndLeashedAreNeverTouched`, `protectionRules`, `countingRules`,
  `lootingThreeIsAppliedToTheKill` / `noLootingWithoutTheEnchantment` (the Looting level the loot function reads, and that the real tool is in hand during
  the hit), `durabilityGoesToTheRealStack`, `wornWeaponIsSwappedNotLost`, `breedsPigsWithCarrots`, `noBreedingAtTheBabyCap`,
  `noWeaponStillBreeds`, `cycleOrder` (recorded phases BREED, KILL, DELIVER), `dropsGoStraightToCarry` (no item entity, drops in the carry), `noCullingWithoutCarryRoom`, `fullOutputStopsCullingKeepsTheCarry`, `frozenButcherDoesNothing`,
  `butcherDeclaration` ([butcher.md](../features/butcher.md)). Each test builds a walled 6x6 pen with the chests in the wall.
- `MendingTest` (12): Mending repair with bottles and never breaking a Mending tool for Lumber, Butcher and Farmer, stored tools repaired and reused, D9 every tick, idle top-up for Butcher and Farmer, `mendingWastesNoXp` (no XP wasted, store spent first) ([mending.md](../features/mending.md)).
- `XpTest` (11, incl. `keepLayoutBottlesMergeOnly`, `keepLayoutNoMatchingStackKeepsTheXp`): stored XP and XP bottles for Smelter and Butcher, full chest, profession change, save/load; the smelter test uses gold ore (exactly 1.0 XP) because furnace XP of 0.7 is rounded at random per collection, which made an iron-ore version flaky ([xp-bottles.md](../features/xp-bottles.md)). Orb checks use `around(helper)`, the test's own structure box only (structures are ~15 blocks apart; a wider box counted neighbours' orbs), and the profession test discards its orbs after counting.
- `SmelterTest` (24 incl. blast furnace cases and `keepLayoutLeavesOneAndNeverFillsEmptyOutputSlots`, `keepLayoutOutputWithoutMatchingStackCountsAsFull`): even split (gold ore, exactly 1.0 XP, so the XP total is asserted exactly; iron's random rounding made a bound flaky), 8-furnace cap, furnace types, one test per never-block rule, lava bucket, freeze, Inventory layout ([smelter.md](../features/smelter.md)).
- `TransferLimitTest` (2): `transporterNeverMovesMoreThan16PerWindow` (256 items A to B, chest totals sampled every tick, at most 16 per 5-tick window, A's lid open during the whole take, at least 150 ticks), `smelterFetchOf64OreCompletes` (a fetch cut by the budget several times still gives 8 to each of 8 furnaces and 64 ingots). See [transfer-limit.md](transfer-limit.md). No existing test needed a larger tick budget.
- `TransporterFilterTest` (12): Filter tab only for the Transporter, layout equal to the Inventory tab, placing one item, filter items and mode survive save/load, old save (9 items, no mode) loads as Off and is padded, mode button cycles, Whitelist / Blacklist / Off move the right items (item conservation), empty whitelist moves nothing and the status says so, non-passing carry is still delivered, filter items go to the player on a profession change ([transporter.md](../features/transporter.md)).
- `FreezeTest` (5): `frozenTransporterMovesNothing` (200 ticks, chests unchanged, golem within 0.5 blocks),
  `unfreezingResumesTheWork`, `freezingClosesAnOpenLidAndStops`, `frozenSurvivesSaveLoad`, `frozenChestSorterDoesNotSort`
  ([freeze.md](../features/freeze.md)).
- `KeepLayoutTest` (6): Keep layout for the Transporter (A keeps 1 per slot, B's empty slots stay empty), the plain-container rule incl. the unstackable and furnace exemptions, off-case, and the Chest Sorter mixin ([keep-layout.md](../features/keep-layout.md)).
- `ChunkLoadTest` (6): `workingGolemHoldsTicket`, `ticketMovesWithGolem`, `releasedOnNone`, `releasedOnDiscard`,
  `sharedTicketStaysForOtherGolem`, `ticketSurvivesSaveLoad` ([chunk-loading.md](../features/chunk-loading.md)).

Total: 238 `@GameTest` methods (`grep -c '@GameTest' src/gametest/java/com/coppergolemsrevamped/test/*.java` per file,
`GolemGuiClientTest` has none), so the log says `All 239 required tests passed` (see Gotchas). Update the numbers here when
you add or remove a test.

**Register every new test class** in the `fabric-gametest` list of `src/gametest/resources/fabric.mod.json`. An
unregistered class is not an error: it silently runs 0 tests and the pass count just stays lower.

## Client tests (headless sway only)

`ToolSwingClientTest` (second client entrypoint, runs in the same `runClientGameTest`): real Lumber loop on a 3-log tree,
40 screenshots (`chop-NN`) while it chops; look at them to judge the swing pose.

`GolemGuiClientTest` (`fabric-client-gametest` entrypoint, `enableClientGameTests = true` in
`build.gradle`) launches the real client, creates a singleplayer world, spawns a golem next to the
player, opens `GolemMenuOpener:open` on the server thread (on a chosen tab) and screenshots the chest menu 13 times:
no profession (Status and Profession tabs), Transporter (Status, Targets, Inventory, Settings, plus a tooltip), Chest Sorter
(Status), Cleaner (Targets), Crafter (Inventory with a remembered recipe: faded cells and the ghost output; Recipe tab), Farmer
(Inventory), Lumber (Inventory and Status).
Toasts are cleared before each screenshot so the top-right cell is visible.
Window 1280x720, GUI scale 3. Screenshots land in `build/run/clientGameTest/screenshots/`.
Task: `runClientGameTest`. It is not wired into `check`/`build` (the `runGameTest` server task is).

**Every client launch runs inside its own headless sway, never on the real display.**
Why: user rule 2026-11-15, "Ingame test (if any) always using headless sway!".

```sh
RT=$HOME/tmp/cgr-sway-run; mkdir -p $RT; chmod 700 $RT; : > <scratch>/sway.conf
# private compositor, own runtime dir, nothing inherited from the real session
XDG_RUNTIME_DIR=$RT env -u WAYLAND_DISPLAY -u DISPLAY -u SWAYSOCK -u I3SOCK \
  WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_HEADLESS_OUTPUTS=1 WLR_RENDERER=pixman \
  ccorral -- sway -c <scratch>/sway.conf &
# a wayland-N socket and sway-ipc*.sock appear in $RT
export XDG_RUNTIME_DIR=$RT SWAYSOCK=$(ls $RT/sway-ipc*.sock) WAYLAND_DISPLAY=wayland-1
unset DISPLAY
swaymsg output HEADLESS-1 resolution 1280x720
env | grep -E 'DISPLAY|XDG_RUNTIME'   # verify: only the private ones
JAVA_HOME=<jdk-25> ccorral -- ./gradlew runClientGameTest
pkill -f "sway -c <scratch>/sway.conf"   # when done; leave nothing running
```

Kill only your own sway, by matching your private config/runtime path (as above). Never a broad `pkill sway`:
other projects run their own headless sways. `pkill -f` patterns can also match the invoking shell itself, so
keep the pattern specific (or kill by the PID of the sway you started).

Notes: the `wayland-N` number may differ (read it from `$RT`). With `DISPLAY` unset the client uses the
Wayland driver (SDL, GL via software rendering) and renders fine under pixman. Harmless log noise:
narrator (`flite`) and OpenAL device failures, `Illegal option value 0 for Anisotropic Filtering`.
If GL fails, try `WLR_RENDERER=gles2` or Xwayland of that sway; never fall back to the real display.
The sway "Failed to bind socket ... X0" error is harmless (another X server owns `:0`).

## Gotchas

- **The reported count is our tests + 1.** Vanilla ships a required test instance
  `minecraft:always_pass` (data pack `test_instance/always_pass.json`) and the Fabric runner runs every
  registered instance, so with N `@GameTest` methods the log says `All N+1 required tests passed`.
  It is not a double registration of our entrypoint.
- The default structure `fabric-gametest-api-v1:empty` is 8x8x8 of air with no floor. Tests that need
  ground place their own (`helper.setBlock` stone at y=0); keep chests within x/z 0..7.
- Entity type constants are in `EntityTypes` (e.g. `EntityTypes.COPPER_GOLEM`), not `EntityType`.
- `inRange` measures to the block centre, so a block exactly 32 away is out of range.

## Why

Why the save/load test goes through a real entity instead of encoding `GolemData.CODEC` directly:
a codec round trip passes even if the attachment was registered without `persistent(...)`; only the
entity path catches that.
