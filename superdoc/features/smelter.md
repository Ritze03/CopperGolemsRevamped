# Smelter profession

Feeds up to 8 furnaces (furnace, blast furnace, smoker) in an assigned area from an input chest and a fuel chest and
delivers the results to an output chest. Id `coppergolemsrevamped:smelter`, `runsVanillaBrain = false`. Code:
`profession/smelter/SmelterProfession:init` (declaration), `profession/smelter/SmelterLogic:tick` (loop).

## Declaration

- Assigns: `furnaces` (`Kind.AREA`, max 16x16, Y is the span of the two corners, scanned at most 32 high), `input`,
  `fuel`, `output` (`Kind.CHEST`). Icons: `assign_input`, `assign_output` are reused, `assign_fuel` (chest with a coal
  badge, base item coal) is new, the area uses the generic `assign_area`.
- Slots: one group `furnace` of 8 (`SmelterProfession:SLOTS`), shown on the Inventory tab at row 3, columns 0..7 with a
  furnace label at (2,3). Slot `i` is the golem's buffer for furnace `i`. Why 8 slots: user spec "8 inventory slots (one for
  each furnace)". Nothing is shown in the golem's hand.
- Icon `profession_smelter` on base item `furnace` (copper furnace with a flame).
- Status: `SmelterLogic:status` ("Smelting in N furnaces", "No furnaces in the area", "No furnace area set", "No
  reachable furnaces", "Output chest is full or missing", "Out of fuel", "Nothing to smelt"), hooked in `ProfessionStatus`.

## Furnaces

`SmelterLogic:rescan` reads the area every 100 ticks (and right after a failed access). Any `AbstractFurnaceBlockEntity` in a
loaded chunk counts. A furnace keeps its slot index while it exists; free slots take the nearest new furnaces to the
input chest (ties: x, y, z). Why sticky: a buffer holds items for "its" furnace, so a nearer furnace appearing must not
shift every index. Cost: an older farther furnace keeps its slot over a newly built nearer one.
Why the cap of 8 and nearest-first: user spec, max 8 furnaces, and nearest ones mean the shortest trips.

## Loop

A pass cycles through phases, each serving every furnace that needs it once (`State.served` bitmask per pass, reset when a
new phase starts) so one busy furnace cannot starve the rest; a phase without work is skipped. `SmelterLogic:pick` starts
at the current phase. The target is walked to with `Interact.approach` (see [../architecture/interaction.md](../architecture/interaction.md));
chests get their lid opened (`Interact.openChest`) after a move, furnaces have no lid.

1. `FETCH` (input chest): `planFetch`. For each smeltable item type in chest order, the furnaces that can take it (their
   own type has a recipe, checked with `RecipeManager.createCheck(RecipeType.SMELTING/BLASTING/SMOKING)`; input empty or
   the same item; room left) share it one item at a time to the lowest level, into their empty buffers. 24 ore over 3
   furnaces is 8 + 8 + 8; an item only some furnaces accept is split among those. A furnace claimed by one type gets
   nothing else that round. Why one item at a time: round-robin gives an exact even split and respects the room of
   half-filled furnaces.
2. `FEED`: the buffer goes into the furnace's input slot, only if the slot is empty or holds the same item and the
   furnace smelts it. Never into the fuel slot.
3. `FUEL_FETCH` (fuel chest): `planFuel` for furnaces that have something to cook and a fuel slot that is empty or at most
   half of `FUEL_TARGET` (8): only items with `DataComponents.COOKING_FUEL` (the 26.3 fuel API), a furnace that has fuel
   gets only that item, a scarce fuel is shared evenly. Why the target and the half rule: "topped" without a trip for every
   single coal.
4. `FUEL`: the buffer goes into the fuel slot. Never into the input slot.
5. `COLLECT`: result slot into the buffer, and a non-fuel leftover in the fuel slot (the empty bucket of lava) the same
   way. A result is only fetched when it is worth a trip: 8 or more, or the furnace is done / cannot cook its input /
   is not burning (so results are collected with no fuel and from a furnace with a foreign input). The furnace's stored XP
   pops at the furnace (`getRecipesToAwardAndPopExperience`) and the map is cleared through
   `mixin/AbstractFurnaceAccessor` (vanilla clears it only in the player variant; without that the XP would pop on every
   collect and the map grows forever).
6. `DELIVER` (output chest): every RESULT buffer goes to the chest.
7. `RETURN`: a RAW buffer that no furnace can take for 200 ticks goes back to the input chest, a FUEL buffer to the fuel
   chest. A full chest keeps them.

A buffer has a transient `Kind` (RAW, FUEL, RESULT) set when the golem fills it. After a restart or a player's edit it is
derived: smeltable -> RAW, fuel -> FUEL, anything else -> RESULT. Known limit: after a restart an item that is both a
result and smeltable (stone) or both fuel and smeltable (logs) is read as RAW.
Why a buffer holds one thing at a time: one stack per furnace, so raw, fuel and results take turns; lossless because every
step moves items slot to slot or chest to slot and leaves the remainder where it came from (D9, `GolemData.save` after
each action).

## Never block

Each rule has a test in `SmelterTest`.

- Unsmeltable input items are never pulled (`planFetch` only takes types a usable furnace smelts).
- A furnace input holding a different item is never touched or fed; its output is still collected.
- Full or missing output chest: results stay in the buffer, `outBlockedUntil` skips `DELIVER` for 200 ticks, the other
  furnaces keep being served; the buffer's furnace just waits. Same for full input and fuel chests on `RETURN`.
- Missing or unassigned targets, no furnaces: nothing is planned, the golem idles every 40 ticks with a status. Nothing throws.
- Unreachable target (furnace or chest): no progress for 200 ticks (airborne ticks do not count) -> ignored for 600 ticks
  (`State.skip`), the golem carries on with the rest. Why not idle like the Crafter: one bad furnace must not stop the other seven.
- No fuel: collecting and topping up the inputs go on.
- A frozen golem is not ticked at all (`GolemTicker`, see [freeze.md](freeze.md)).

## Tests (`SmelterTest`)

`evenSplitAcrossThreeFurnaces`, `atMostEightFurnacesAreUsed`, `typeSplitSmokerAndFurnace`,
`unsmeltableItemsStayInTheInputChest`, `foreignInputIsNeverFought`, `fullOutputChestKeepsResultsAndKeepsFeeding`,
`noFuelStillCollectsAndFeeds`, `noFurnacesAndMissingTargetsIdle`, `lavaBucketLeavesAnEmptyBucketInTheOutput`,
`unreachableFurnaceIsGivenUp` (furnace 4 blocks above the floor), `frozenSmelterDoesNothing`, `inventoryTabIsOneRowOfEight`.
