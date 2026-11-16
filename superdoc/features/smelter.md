# Smelter profession

Feeds up to 8 furnaces (furnace, blast furnace, smoker) in an assigned area from an input chest and a fuel chest and
delivers the results to an output chest. Id `coppergolemsrevamped:smelter`, `runsVanillaBrain = false`. Code:
`profession/smelter/SmelterProfession:init` (declaration), `profession/smelter/SmelterLogic:tick` (loop).

## Declaration

- Assigns: `furnaces` (`Kind.AREA`, max 16x16, Y is the span of the two corners, scanned at most 32 high), `input`,
  `fuel`, `output` (`Kind.CHEST`). Icons: `assign_input`, `assign_output` are reused, `assign_fuel` (chest with a coal
  badge, base item coal) is new, the area uses the generic `assign_area`.
- Slots (`SmelterProfession:SLOTS` = 12), in data order: group `furnace` (8 slots 0..7, row 3, columns 0..7, label centred
  above at (2,4)), group `xp` (slot 8, `XP_SLOT`, at (3,8), take-only for players, no label: (2,8) is plain filler) and group
  `output` (slots 9..11, `RESULT_FIRST`..`SLOTS-1`, row 4, columns 3..5, take-only for players like the Crafter output,
  no label). Slot `i` (0..7, `MAX_FURNACES`) is the golem's **input** buffer for furnace `i`: raw items to feed and fuel
  to feed or return, never results. Slots 9..11 are the 3 **shared result slots**: every furnace's results, merged by item.
  Why no XP label (user: found it redundant, the bottles in the slot are enough): no hint is shown while the slot is empty.
  Slot 8 holds the XP bottles made from the golem's stored XP, see [xp-bottles.md](xp-bottles.md). Nothing is shown in the
  golem's hand.
  Why the result slots (user request, from a screenshot of the Inventory tab: "bottom 3 should be the output slots, add two,
  for reliability/speed"): with a result in its own furnace's buffer a furnace whose buffer was busy had to wait for the next
  pass, and results and input took turns in one stack. Now COLLECT empties any furnace into the shared row at once, and the
  buffers only ever hold input. Why 3: the user's choice; it covers three kinds of product per delivery trip. Why no label
  for the row: every label sits above its slots, and the cells above (row 3) are the furnace buffers.
  Why 8 + XP in row 3 unchanged: the 9-wide row was the user's wish ("looks nicer").
- Older golems (saved with 8 or 9 slots): `SmelterLogic:tick` calls `migrate` before anything else (before the cooldown, so
  the list grows on the first tick; until then `GolemMenu:stillValid` closes an open menu, one tick at most): the list is
  grown to `SLOTS` (items kept), and a result an older version left in a furnace buffer moves to the result slots. Which item
  is a result is only certain for what is neither smeltable nor fuel (iron ingot, diamond); a smeltable one (stone) stays in
  its buffer as raw input (fed or returned to the input chest, never lost). What does not fit the 3 result slots stays in its
  buffer too and goes back to the input chest. A non-bottle item in the XP slot (an older version's leftover) is swept into
  the result slots on each tick (`sweepXpSlot`); `DELIVER` also takes whatever is left in slot 8.
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

The loop is an explicit cycle, `SmelterLogic.Phase`: COLLECT, DELIVER, FETCH, FEED, FUEL (FUEL_FETCH + FUEL, one phase to
the outside), RETURN, then COLLECT again. Test hooks: `SmelterLogic:phaseOf`, `SmelterLogic:phaseListener` (called on every
change of the visible phase; WAIT is reported while nothing can be done). Why this order: the user wanted products out
sooner ("it actually empties furnaces in between, right now he just keeps restocking"), so every cycle empties the
furnaces first and delivers before it restocks. Each phase serves every furnace that needs it once (`State.served` bitmask,
reset when a new phase starts) so one busy furnace cannot starve the rest; a phase without work is skipped, and with
nothing to do at all the golem sits in WAIT (idle every 40 ticks, with a status). `SmelterLogic:pick` starts at the current
phase. The target is walked to with `Interact.approach` (see [../architecture/interaction.md](../architecture/interaction.md));
chests get their lid opened (`Interact.openChest`) after a move, furnaces have no lid.

Batch rule: `SmelterLogic:BATCH` = 8. A furnace's input is only topped up to 8 items (`planFetch`, `feedable`, FEED), about
8 x 200 ticks of cooking. Why: with 64 in the slot the golem would restock once and the first ingot would sit in the
furnace for the whole run; with 8 the cycle comes back to collect long before a batch is done, and still re-tops every
furnace before it runs dry, so few furnaces with lots of input stay busy. Raising it trades earlier output for fewer trips.

1. `COLLECT`: a furnace's result slot into the shared result slots (`SmelterLogic:addResult`: same item first, then an empty
   slot), and a non-fuel leftover in the fuel slot (the empty bucket of lava) the same way. Any result is collected, no
   threshold (the old "8 or more, or done" rule is gone: results must come out regularly). A furnace is only collectable
   while the result slots have room for its item (`resultSpace`: free room in same-item stacks plus empty slots); with 3
   different kinds or 3 full stacks COLLECT has no work and the cycle moves on to DELIVER, the furnaces keep their output
   until there is room.
   The furnace's stored XP goes into the golem's store (`SmelterLogic:takeFurnaceXp`, no orbs, see
   [xp-bottles.md](xp-bottles.md)) and the map is cleared through `mixin/AbstractFurnaceAccessor` (vanilla clears it only in
   the player variant; without that the XP would be paid on every collect and the map grows forever).
2. `DELIVER` (output chest): the result slots and the XP bottle slot go to the chest, even a few items (`deliverSlots`).
3. `FETCH` (input chest): `planFetch`. For each smeltable item type in chest order, the furnaces that can take it (their
   own type has a recipe, checked with `RecipeManager.createCheck(RecipeType.SMELTING/BLASTING/SMOKING)`; input empty or
   the same item; room left up to `BATCH`) share it one item at a time to the lowest level, into their empty buffers. 24 ore
   over 3 furnaces is 8 + 8 + 8; an item only some furnaces accept is split among those. A furnace claimed by one type gets
   nothing else that round. Why one item at a time: round-robin gives an exact even split and respects the room of
   half-filled furnaces.
4. `FEED`: the buffer goes into the furnace's input slot, only if the slot is empty or holds the same item, the furnace
   smelts it and the batch has room. Never into the fuel slot.
5. `FUEL_FETCH` (fuel chest): `planFuel` for furnaces that have something to cook and a fuel slot that is empty or at most
   half of `FUEL_TARGET` (8): only items with `DataComponents.COOKING_FUEL` (the 26.3 fuel API), a furnace that has fuel
   gets only that item, a scarce fuel is shared evenly. Why the target and the half rule: "topped" without a trip for every
   single coal.
6. `FUEL`: the buffer goes into the fuel slot. Never into the input slot.
7. `RETURN`: a RAW buffer that no furnace can take for 200 ticks goes back to the input chest, a FUEL buffer to the fuel
   chest. A full chest keeps them. Results are never returned, they are delivered.

A furnace buffer has a transient `Kind` (RAW, FUEL) set when the golem fills it. After a restart or a player's edit it is
derived (`SmelterLogic:kindOf`): smeltable -> RAW, fuel -> FUEL, anything else -> RAW (fits no furnace, so `RETURN` takes it
back to the input chest). Why only two kinds: buffers are input only, results live in the result slots. That removes the old
ambiguity "stone reads as RAW after a restart" (stone is a result of cobblestone and smeltable): a result is never in a
buffer, so nothing has to guess. Remaining limit: an item that is both smeltable and fuel (logs) is read as RAW after a
restart.
Why a buffer holds one thing at a time: one stack per furnace, so raw and fuel take turns; lossless because every
step moves items slot to slot or chest to slot and leaves the remainder where it came from (D9, `GolemData.save` after
each action).

## Transfer limit

Every item move is budgeted (16 items per 5 ticks per golem): chest fetch, fuel fetch, deliver, return **and** feed / fuel / collect at the furnaces. A cut step keeps its `Work` (`State.pending`, not `served`), the FETCH / FUEL_FETCH plan survives in `State.moves`, and cut visits never set the full-chest blocks. See [../architecture/transfer-limit.md](../architecture/transfer-limit.md).

## Keep layout

With [keep-layout.md](keep-layout.md) on, the input, fuel and output chests follow the rule: ore and fuel are taken at most `count - 1` per slot (`SmelterLogic:removeFrom`), and the fetch/fuel plans (`planFetch`, `planFuel`, `fetched`) count only that takeable amount, so the even split never promises items it cannot take. Results and leftovers (`moveOut`) only merge into non-empty same-item slots; with no matching stack the output counts as full and `outBlockedUntil` applies as usual. Furnace slots are exempt.

## Never block

Each rule has a test in `SmelterTest`.

- Unsmeltable input items are never pulled (`planFetch` only takes types a usable furnace smelts).
- A furnace input holding a different item is never touched or fed; its output is still collected.
- Full or missing output chest: results stay in the result slots, `outBlockedUntil` skips `DELIVER` for 200 ticks, the
  other furnaces keep being served and collected until the result slots are full (then COLLECT has no work, the furnaces
  keep their output and the rest of the loop goes on). Same for full input and fuel chests on `RETURN`.
- Missing or unassigned targets, no furnaces: nothing is planned, the golem idles every 40 ticks with a status. Nothing throws.
- Unreachable target (furnace or chest): no progress for 200 ticks (airborne ticks do not count) -> ignored for 600 ticks
  (`State.skip`), the golem carries on with the rest. Why not idle like the Crafter: one bad furnace must not stop the other seven.
- No fuel: collecting and topping up the inputs go on.
- A frozen golem is not ticked at all (`GolemTicker`, see [freeze.md](freeze.md)).

## Tests (`SmelterTest`)

`evenSplitAcrossThreeFurnaces`, `atMostEightFurnacesAreUsed`, `typeSplitSmokerAndFurnace`,
`unsmeltableItemsStayInTheInputChest`, `foreignInputIsNeverFought`, `fullOutputChestKeepsResultsAndKeepsFeeding`,
`noFuelStillCollectsAndFeeds`, `noFurnacesAndMissingTargetsIdle`, `lavaBucketLeavesAnEmptyBucketInTheOutput`,
`unreachableFurnaceIsGivenUp` (furnace 4 blocks above the floor), `frozenSmelterDoesNothing`, `phasesRunInCycleOrder`,
`earlyOutput`, `inputNeverExceedsBatch`, `blastFurnaceAndFurnaceSplit` (ore reaches the blast furnace, beef never does),
`blastFurnaceAloneWorksEndToEnd`.
Result slots: `inventoryTabLayout` (buffers row 3 cols 0..7, take-only XP at (3,8), take-only results at row 4 cols 3..5,
labels, 12 slots), `resultsNeverLandInFurnaceBuffers` (two products from three furnaces, never in a buffer, seen in the result
slots), `fullResultRowTriggersDeliver` (3 kinds held: DELIVER is the first phase, the furnace output waits, then follows),
`sameResultsMergeIntoOneSlot`, `eightSlotSmelterGrowsToTwelve`, `nineSlotSmelterMigratesWithoutLoss` (4 results + ore + fuel
in the buffers of a 9-slot golem: 3 move to the result slots, the 4th and the rest return, nothing lost).
XP tests are in `XpTest` ([xp-bottles.md](xp-bottles.md)). Why `fullResultRowTriggersDeliver` polls `phaseOf` instead of
`phaseListener`: the hook is one static shared with `phasesRunInCycleOrder`, and game tests run in parallel.
