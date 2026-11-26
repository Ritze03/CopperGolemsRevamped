# Farmer profession

Farms an assigned field of at most 9x9 with ONE tool. Hoe: tills ground, plants seeds, harvests mature crops. Axe:
harvests only melon and pumpkin blocks. Seed items go back to the seed chest, everything else to the output chest.
Id `coppergolemsrevamped:farmer`, `runsVanillaBrain = false`.

## Entry points

- `profession/farmer/FarmerProfession:init` declares it. Slot groups (placed by cell in the Inventory tab, see [gui.md](gui.md); the tool is also shown in the golem's hand): `tool` (1 slot, max
  stack 1, filter `ItemTags.HOES` or `ItemTags.AXES`), `seeds` (1 slot x64, filter `FarmerLogic:isSeed`), `carry`
  (9 x64, any item). Inventory indices: `TOOL_SLOT` 0, `SEED_SLOT` 1, `CARRY_FROM` 2..10.
- Assigns: `field` (`Kind.AREA`, "Assign Field", maxSize 9), `tools` (CHEST, "Assign Tool Chest"), `seeds` (CHEST,
  "Assign Seed Chest"; input AND output for seeds), `output` (CHEST, "Assign Output Chest", icon `assign_output` by
  key). Icons for the others come from the kind fallback (`assign_area`, `assign_chest`), see [gui.md](gui.md).
- `profession/farmer/FarmerLogic:tick` is the loop; `FarmerLogic:cells`, `FarmerLogic:isSeed` are public helpers.

## Field and soil layer

Two clicks are stored as `field_a`/`field_b` (see [assign-mode.md](assign-mode.md)); the field is their x/z
rectangle, at most 9x9 (`AssignMode:areaFits`, also re-checked in `FarmerLogic:cells`, which returns nothing for an
oversized field). The **soil Y is chosen per column** (`FarmerLogic:soilCells`): the clicked Y range extended by 1 up and
1 down (lower click Y - 1 to higher click Y + 1) is searched, and the best block wins: (1) farmland, else (2) a block with a
crop, melon or pumpkin directly above, else (3) a tillable block with air above; among equal ranks the highest Y. A column
with no candidate is skipped. Crops live at soil Y + 1. The result is cached per scan (`State.soil`, `RESCAN_TICKS`); the
loaded-chunk check uses the column corners (`FarmerLogic:cells`, at the lower click's Y). Cells are listed x ascending, then z ascending.

Why: user: "the farmer should always consider one block up and down of the selected area (so marking crops works just as
well, as marking the field below." Players click either the farmland or the crops above it; the window makes both work and
tolerates slightly uneven fields. The 9x9 x/z limit is unchanged (D14 "'Assign Area' up to 9x9").

## Seed definition

A seed is a `BlockItem` whose block is a `CropBlock` but not a `TorchflowerCropBlock` (wheat seeds, carrots,
potatoes, beetroot seeds; modded crops extending `CropBlock` work). Why: D14 "A 'seed' is any item that plants a
crop"; the check is by block class. Melon/pumpkin seeds are excluded: a stem is never planted (the axe farmer does not
plant, the hoe farmer ignores fruit), so they are plain items and go to the output chest. Torchflower seeds are
excluded because `TorchflowerCropBlock:getStateForAge` turns the crop into the `TORCHFLOWER` flower block at its last
stage (never a mature `CropBlock`): it would block its cell forever. Planting writes `defaultBlockState()` above the
farmland only if `BlockState:canSurvive` holds (light >= 8 for crops); the seed is consumed only when placed.

## First-seed rule

When the seed slot is empty the golem takes up to 64 of the item in the **lowest-index seed-chest slot that holds a
seed** (merging the same item from later slots). While the slot holds a seed item it is topped up (below 16) only with
that same item. Why: user spec "Uses whichever seed comes first in the seed chest".

## One tool, two modes

The mode is read from the item in the tool slot (`FarmerLogic:modeOf`): `HOE`, `AXE` or `NONE` (empty).

- **Hoe** (and no tool): crop work as described under Loop (harvest, plant, till). A golem with no tool still harvests
  and plants on existing farmland but tills nothing.
- **Axe**: ONLY harvests `Blocks.MELON` and `Blocks.PUMPKIN` at each column's soil Y + 1 anywhere in the field rectangle (not carved
  pumpkins, never stems). Never tills, plants or harvests crops, and fetches no seeds. A fruit grows on a cell next to
  its stem, so every cell is checked, not only stem cells.

Why one tool and two modes (user): an axe farmer must not make farmland, so an axe golem on a melon field does not
till around; the user chose "axe = ONLY melon/pumpkin, leaves stems alone".

**Tool replacement is same-type only** (hoe for hoe, axe for axe; `FarmerLogic:findTool`, `FarmerLogic:fetchTool`).
A protected enchanted tool (at most 10 durability left, see [mending.md](mending.md)) is swapped with a same-type tool from
the tool chest; the resting one goes into that chest slot (nothing lost or duplicated). A plain tool is used until it
breaks, **including its last durability point**; the empty slot then takes the first hoe or axe from the chest. Why: the old
"never use the last point" rule (to keep the slot's type) parked 1-durability tools in the chest forever. An empty slot takes the first hoe or axe (lowest chest slot) and that sets the type; it is fetched only
when the field has work for some tool (tillable ground or a fruit block), the golem takes whichever comes first.

Why: the type needs no persistent state (`GolemData` has no field for it): the item in the slot IS the type. A swap of a
protected enchanted tool therefore stays same-type, so a hoe is never silently replaced by an axe. A plain tool that
breaks empties the slot, and the next fetch (any hoe or axe) sets the type again.

## Tool swing

With a tool in hand the farmer swings (`ai/ToolSwing`, see [lumber.md](lumber.md) "Chopping animation") before the action
lands: hoe harvest and tilling 1 swing (12 ticks), axe on melon/pumpkin 2 swings (24, with crack overlay). Planting and
bare-handed harvest do not swing. `FarmerLogic:swingsFor`; the swing counter lives in `State.swingElapsed`, reset in `done`.
Tests: `FarmerTest:hoeWorkSwingsAndReturnsToIdle`, `swingCounts`.

## Loop

Transient state (cooldown, walk state, skip map) in a `WeakHashMap` like the Cleaner; persistent state is only
`GolemData`, saved after every mutation. Per tick, in order:

1. `field` empty/oversized or `output` not a container: stop, retry in 40 ticks. `tools`/`seeds` are optional.
2. Tool: empty slot and some cell needs tilling or holds a fruit block, or the tool is a protected enchanted one and its own
   mode has work (hoe: tillable cell, axe: fruit block): walk to the tool chest and take/swap per the rule above.
3. Seeds (not in axe mode): some cell needs planting/tilling and the seed slot holds < 16: fetch per the first-seed rule.
4. Nearest workable cell (by distance, ties in scan order), one action per 5 ticks. Axe mode considers only fruit
   cells (`FarmerLogic:actForAxe`), the other modes `FarmerLogic:actFor`:
   - **harvest** (hoe/none): crop above is a mature `CropBlock` (`isMaxAge`). Drops come from `Block:getDrops` with an
     empty tool and are stored in the seed slot (same item first) then carry.
   - **fruit** (axe): melon/pumpkin block above, a usable axe. Drops from `Block:getDrops` with the axe
     (melon slices 3-7, pumpkin 1), axe loses 1 durability per block.
   Both use the same rule: `workable` pre-checks a roll, but `act` is the authority: it rolls once, checks that exact
   list fits (on a copy of the inventory) and only then calls `destroyBlock(pos, false)`; if it does not fit the block
   stays and the cell is skipped for 1200 ticks (the carry is delivered meanwhile). Why: drops are random (wheat seeds
   0-3, melon slices 3-7), so a second roll after the check could be bigger and, with the block already destroyed,
   lose items (D9: no item lost or duplicated).
   - **plant**: farmland with air above and a seed in the slot.
   - **till**: dirt, grass block or dirt path with air above, a usable hoe in the tool slot (not a protected enchanted one) and a seed in
     the slot (so the hoe is not worn for nothing); sets farmland, `hurtAndBreak(1)` on the hoe stack (server-level
     overload, null player), then plants.
5. Otherwise carry non-empty, deliver (cooldown `Interact.OPEN_TICKS` when items moved):
   - seed items (`isSeed`) go to the `seeds` chest when assigned and reachable, over the carry slots only (seed and
     tool slots are never delivered);
   - everything else, and seeds when no seed chest is assigned, to `output`;
   - seeds the seed chest could not take (full) are then delivered to `output` (`State:seedChestFull`, cleared once
     the carry holds no seed). Why: either leftover policy loses nothing; "stay and retry" would keep the golem
     walking to a full chest every 40 ticks and stall its harvest as the carry fills, overflow to output cannot.
   Leftovers that fit nowhere stay and are retried every 40 ticks.
6. Else (no field work, nothing to deliver) top up damaged Mending tools, held or in the tools chest, from the tools chest's
   XP bottles, however little they miss (the golem walks there, 1 transfer item per bottle). Why: user request, repair while
   waiting instead of idling; it only runs when steps 1 to 5 found nothing, so real work is never interrupted. See
   [mending.md](mending.md). Test: `MendingTest:farmerTopsUpHoeWhileIdle`.
7. Else idle, rescan every 20 ticks.

The field scan behind steps 2 to 4 (what each cell needs, and the nearest workable cell with its `workable` loot roll) is
cached for `FarmerLogic:RESCAN_TICKS` (20) ticks while the golem walks or swings, and redone at once after any action
or give-up (`done`, `giveUp`) or a tool-mode change. A cached cell whose block changed or that got skipped is picked
again. Why: perf review #11, both passes over all 81 cells (the second rolling loot tables) ran on every walking tick.
Step 1 also waits while any field corner's chunk is unloaded (`FarmerLogic:fieldLoaded`, see
[chunk-loading.md](chunk-loading.md), "Unloaded targets").

Why the seed chest is both source and sink (user): "Farmers should drop their seeds into their 'seeds' chest. Not the
output." Seeds the harvest yields feed the next planting round instead of piling up in the output.

### Reach and chest lids

All walking goes through `ai/Interact:approach` (see [../architecture/interaction.md](../architecture/interaction.md)):
the golem stands within 1 block of what it touches (2-block fallback built into `approach`). The target of a cell
action is the **crop block** (soil Y + 1), so the golem stands beside it on the field (or one step up/down), never on
it. Chests are the target for the tool, seed and output trips. A successful take/insert calls
`Interact.openChest` (TAKE/INSERT) and sets the cooldown to `Interact.OPEN_TICKS` so the lid is seen to open.

Chest moves (tool, seeds, delivery) are budgeted (16 items per 5 ticks); a cut visit stays at the chest and goes on next window (`State.seedTopUp` for seeds), see [../architecture/transfer-limit.md](../architecture/transfer-limit.md).

Give-up (like the Cleaner), on top of `approach`'s own fallback: the distance to the walking target must shrink by
more than 0.1 (squared) within 200 ticks, else the cell or chest is ignored for 1200 ticks. While the golem is not on
the ground it waits (first ticks after spawning) but those ticks count toward the same 200-tick limit, so a golem
floating in water gives up instead of waiting forever. A plant that fails (`canSurvive` false, e.g. too dark) also
skips the cell for 1200 ticks. `FarmerLogic:isSkipped` is the test hook.

## Tests (`FarmerTest`)

- `harvestsReplantsAndOutputs`: 3x3 moist farmland, 4 mature wheat, seed chest slot 0 beetroot seeds / slot 1 wheat
  seeds. Beetroot seeds are conserved per tick; all 9 cells end as beetroots, output holds exactly 4 wheat and no
  seeds (the harvest's seed drops return to the seed chest, which ends with at least the original 20 wheat seeds).
- `tillsDirtWithHoe`: 2x2 dirt, iron hoe in the tool chest: farmland + wheat everywhere, hoe damage 4.
- `plainHoeBreaksThenNextIsFetched`: wooden hoe at max-1 damage, iron hoe in the chest: the wooden hoe is used up (never
  parked in the chest), iron hoe ends in the slot (changed: it used to assert a swap and the worn hoe in the chest).
- `seedsGoToSeedChestRestToOutput`: carry with wheat, wheat seeds, carrots, melon seeds on a field with no work:
  seed items end in the seed chest, the rest in output, the lid opened (`Interact:isOpen`) and closed.
- `seedsOverflowToOutput` (full seed chest), `noSeedChestSeedsToOutput`.
- `axeHarvestsFruitLeavesStems`: melon and pumpkin block next to their stems, a carved pumpkin, a dirt cell, hoe and
  axe in the tool chest: fruits gone (melon slices 3-7, 1 pumpkin in output), axe damage 2, stems/carved pumpkin/dirt
  untouched, nothing planted, seed chest untouched; at each harvest `Interact:inReach` (1 block) holds.
- `hoeIgnoresMelons`; `wornHoeReplacedOnlyByHoe` / `wornAxeReplacedOnlyByAxe` (a protected enchanted tool is replaced only by the same
  type; the other type stays in the chest); `plainHoeWithoutReplacementIsUsedUp` / `plainAxeWithoutReplacementIsUsedUp`
  (a plain tool at max-1 damage uses its last point and breaks, never parked; these replace the old
  `noReplacementKeepsWorn*` tests that asserted the removed "never use the last point" rule); `emptySlotTakesFirstToolAndFilter` (slot filter, first hoe/axe wins).
- `fieldClickedOnCropLayer`, `fieldClickedBelowSoil`: 2x2 farmland with mature wheat, both corners at crop Y (farmland Y + 1) or
  at farmland Y - 1: harvested and replanted, 4 wheat in output.
- `fieldLimit9`: spec maxSize 9, `areaFits` 9x9 / not 10x9 / not 9x10, oversized stored field yields no cells.
- `harvestWithNearlyFullCarryLosesNothing`: forced drop roll (`FarmerLogic:testDropRoll`): the checked roll fits, the
  stored roll (wheat + 3 seeds) does not. The crop must stay untouched, then be harvested after the carry was
  delivered; wheat/seeds conserved every tick.
- `stemSeedsIgnored`: melon seeds first in the seed chest, wheat seeds second: cells get wheat, never melon stems.
- `seedDefinition`: seed and non-seed items (melon/pumpkin/torchflower seeds are not seeds).

## Known limits

- Stems are never planted or harvested and melon/pumpkin seeds are not seeds (axe mode does not plant); torchflower,
  pitcher pods, sweet berries, nether wart and cane are not supported either. Why: torchflower becomes a flower block,
  so the cell would stay occupied forever; fruit has its own (axe) mode.
- A non-crop block above a soil cell (e.g. grass, short grass, or a melon seen by a hoe farmer) blocks tilling and
  planting there: the golem never breaks non-crop blocks other than the axe's two fruit blocks.
- A tool without a same-type replacement stays at 1 durability and that tool work stops (by design).
- Unbreaking on the tool is not tested (the server `hurtAndBreak` overload should apply it; unverified).
- No bone meal, no watering: dry farmland reverts to dirt when empty and is then re-tilled (costing durability).
- Y variance beyond the +-1 window around the clicked range is not handled; a column whose soil lies outside it is skipped.

## Mending

Any enchanted tool is protected at 10 durability or less, plain tools are used until they break (no setting). See [mending.md](mending.md).

A Mending hoe or axe is repaired with XP bottles from the **tools chest** and never used below 10 durability: it is parked
in the chest (or swapped for a usable same-type tool); without a tool the golem still harvests but does not till or break
fruit. Parking empties the slot, so the empty-slot rule above decides the next tool. See [mending.md](mending.md).

## Keep layout

With Keep layout (taking) on, the golem leaves 1 of every stack in the seed chest and tools chest (seeds, XP
bottles; slots holding 1 are skipped); with Keep layout (storing) on, seeds, harvest and surplus only merge into non-empty slots of the same item in
the seed and output chest. Tools are exempt (unstackable). A seed chest with no spare seed counts as empty, and a chest
with no matching stack counts as full (the usual full handling applies). See [keep-layout.md](keep-layout.md).
