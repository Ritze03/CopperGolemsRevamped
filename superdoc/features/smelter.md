# Smelter profession

Feeds up to 8 furnaces (furnace, blast furnace, smoker) in an assigned area from an input chest and a fuel chest and
delivers the results to an output chest. Id `coppergolemsrevamped:smelter`, `runsVanillaBrain = false`. Code:
`profession/smelter/SmelterProfession:init` (declaration), `profession/smelter/SmelterLogic:tick` (loop).

## Declaration

- Assigns: `furnaces` (`Kind.AREA`, max 16x16, Y is the span of the two corners, scanned at most 32 high), `input`,
  `fuel`, `output` (`Kind.CHEST`). Icons: `assign_input`, `assign_output` are reused, `assign_fuel` (chest with a coal
  badge, base item coal) is new, the area uses the generic `assign_area`.
- Slots: one group `furnace` of 9 (`SmelterProfession:SLOTS`), one full row on the Inventory tab at row 3, columns 0..8,
  furnace label centred above it at (2,4). Slots 0..7 (`MAX_FURNACES`): slot `i` is the golem's buffer for furnace `i`.
  Slot 8 (`OVERFLOW`) is a shared overflow buffer (see below). Nothing is shown in the golem's hand.
  Why 9: the user asked for it ("hold up to 9 stacks, looks nicer"): a full row of 9 looks better than 8. The furnace cap
  stays 8 (earlier user spec "up to 8 furnaces max"), so slot 8 has no furnace and needs a job.
  Why overflow for results: simplest role that never blocks and loses nothing. `SmelterLogic:resultSlot` puts a result
  into furnace `i`'s buffer, and when that is busy (raw or fuel waiting, or a different result) into slot 8, so the
  furnace keeps working. `kindOf` always reads slot 8 as RESULT, so `DELIVER` takes it to the output chest and `FEED`,
  `FUEL`, `RETURN` never touch it. Slot 8 is never fed to a furnace, so it cannot be stuck on an item nobody accepts.
- Older golems saved with 8 slots: `SmelterLogic:tick` grows `GolemData.items` to `SLOTS` (items kept, slot 8 empty) and
  saves, on its first tick. Until then `GolemMenu:stillValid` closes an open menu (stored list shorter than the profession's
  size), which is one tick at most.
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

1. `COLLECT`: result slot into the buffer, and a non-fuel leftover in the fuel slot (the empty bucket of lava) the same
   way. Any result is collected, no threshold (the old "8 or more, or done" rule is gone: results must come out regularly).
   The furnace's stored XP pops at the furnace (`getRecipesToAwardAndPopExperience`) and the map is cleared through
   `mixin/AbstractFurnaceAccessor` (vanilla clears it only in the player variant; without that the XP would pop on every
   collect and the map grows forever).
2. `DELIVER` (output chest): every RESULT buffer, slot 8 included, goes to the chest, even a few items.
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
`unreachableFurnaceIsGivenUp` (furnace 4 blocks above the floor), `frozenSmelterDoesNothing`, `inventoryTabIsOneFullRowOfNine` (9 cells, label at (2,4)), `eightSlotSmelterGrowsToNine`, `phasesRunInCycleOrder`, `earlyOutput`, `inputNeverExceedsBatch`.
