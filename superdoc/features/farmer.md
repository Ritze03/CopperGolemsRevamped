# Farmer profession

Farms an assigned field of at most 9x9: tills ground with a hoe, plants seeds, harvests mature crops and delivers
them to an output chest. Id `coppergolemsrevamped:farmer`, `runsVanillaBrain = false`.

## Entry points

- `profession/farmer/FarmerProfession:init` declares it. Slot groups (one row each in the GUI): `tool` (1 slot, max
  stack 1, filter `ItemTags.HOES`), `seeds` (1 slot x64, filter `FarmerLogic:isSeed`), `carry` (9 x64, any item).
  Inventory indices: `TOOL_SLOT` 0, `SEED_SLOT` 1, `CARRY_FROM` 2..10.
- Assigns: `field` (`Kind.AREA`, "Assign Field", maxSize 9), `tools` (CHEST, "Assign Tool Chest"), `seeds` (CHEST,
  "Assign Seed Chest"), `output` (CHEST, "Assign Output Chest", icon `assign_output` by key). Icons for the others
  come from the kind fallback (`assign_area`, `assign_chest`), see [gui.md](gui.md).
- `profession/farmer/FarmerLogic:tick` is the loop; `FarmerLogic:cells`, `FarmerLogic:isSeed` are public helpers.

## Field and soil layer

Two clicks are stored as `field_a`/`field_b` (see [assign-mode.md](assign-mode.md)); the field is their x/z
rectangle, at most 9x9 (`AssignMode:areaFits`, also re-checked in `FarmerLogic:cells`, which returns nothing for an
oversized field). The **soil layer is the Y of the lower click**; crops live at soil Y + 1. Click the dirt/farmland
blocks themselves. Cells are listed x ascending, then z ascending.

Why: user spec "farms a set field, at most 9x9 per golem"; D14 "'Assign Area' up to 9x9". One Y layer keeps the
field flat and the per-cell logic trivial; the Y of the higher click is ignored.

## Seed definition

A seed is a `BlockItem` whose block is a `CropBlock` but not a `TorchflowerCropBlock` (wheat seeds, carrots,
potatoes, beetroot seeds; modded crops extending `CropBlock` work). Why: D14 "A 'seed' is any item that plants a
crop"; the check is by block class. Stems (melon/pumpkin seeds) are excluded because their fruit is never harvested,
and torchflower seeds because `TorchflowerCropBlock:getStateForAge` turns the crop into the `TORCHFLOWER` flower
block at its last stage (never a mature `CropBlock`): either would block its cell forever. Planting writes
`defaultBlockState()` above the farmland only if `BlockState:canSurvive` holds (light >= 8 for crops); the seed is
consumed only when placed.

## First-seed rule

When the seed slot is empty the golem takes up to 64 of the item in the **lowest-index seed-chest slot that holds a
seed** (merging the same item from later slots). While the slot holds a seed item it is topped up (below 16) only with
that same item. Why: user spec "Uses whichever seed comes first in the seed chest".

## Loop

Transient state (cooldown, walking target, skip map) in a `WeakHashMap` like the Cleaner; persistent state is only
`GolemData`, saved after every mutation. Per tick, in order:

1. `field` empty/oversized or `output` not a container: stop, retry in 40 ticks. `tools`/`seeds` are optional.
2. Hoe: some cell needs tilling, and the tool slot is empty (or the hoe breaks on its next use) and the tool chest
   holds a hoe (a replacement must not itself break next use): walk to the chest, take one hoe, or swap with the
   worn one (it goes into the chest slot, so nothing is lost or duplicated).
3. Seeds: some cell needs planting/tilling and the seed slot holds < 16: fetch per the first-seed rule.
4. Nearest workable cell (by distance, ties in scan order), one action per 5 ticks, in reach per
   `TransporterLogic:inReach` on the soil block:
   - **harvest**: crop above is a mature `CropBlock` (`isMaxAge`). Drops come from `Block:getDrops` with an empty tool
     and are stored in seed slot (same item first) then carry. `workable` pre-checks a roll, but
     `act` is the authority: it rolls once, checks that exact list fits (on a copy of the inventory) and only then
     calls `destroyBlock(pos, false)`; if it does not fit the crop stays and the cell is skipped for 1200 ticks (the
     carry is delivered meanwhile). Why: drops are random (wheat seeds 0-3), so a second roll after the check could
     be bigger and, with the crop already destroyed, lose items (D9: no item lost or duplicated).
   - **plant**: farmland with air above and a seed in the slot.
   - **till**: dirt, grass block or dirt path with air above, a hoe in the tool slot and a seed in the slot (so the
     hoe is not worn for nothing); sets farmland, `hurtAndBreak(1)` on the hoe stack (server-level overload, null
     player), then plants. A broken hoe leaves the slot empty (vanilla behaviour).
5. Otherwise carry non-empty: walk to the output chest and `TransporterLogic:insertAll` over the carry slots only
   (seed and tool slots are never delivered). Leftovers stay and are retried every 40 ticks.
6. Else idle, rescan every 20 ticks.

Give-up (like the Cleaner): the distance to the walking target must shrink by more than 0.1 (squared) within 200
ticks, else the cell or chest is ignored for 1200 ticks. A null path counts as no progress, not as instant give-up,
and while the golem is not on the ground it waits (first ticks after spawning) but those ticks count toward the same
200-tick limit, so a golem floating in water gives up instead of waiting forever.
A plant that fails (`canSurvive` false, e.g. too dark) also skips the cell for 1200 ticks.
`FarmerLogic:isSkipped` is the test hook.

## Tests (`FarmerTest`)

- `harvestsReplantsAndOutputs`: 3x3 moist farmland, 4 mature wheat, seed chest slot 0 beetroot seeds / slot 1 wheat
  seeds. Per tick beetroot seeds are conserved; at the end all 9 cells hold beetroots, output holds exactly 4 wheat,
  the 20 wheat seeds are still in the chest.
- `tillsDirtWithHoe`: 2x2 dirt, iron hoe in the tool chest: farmland + wheat everywhere, hoe in the tool slot with
  damage 4.
- `wornHoeIsSwappedNotLost`: wooden hoe at max-1 damage, iron hoe in the chest: per tick exactly 2 hoes exist; at the
  end the iron hoe is in the tool slot and the worn one in the chest.
- `fieldLimit9`: spec maxSize 9, `areaFits` 9x9 / not 10x9 / not 9x10, oversized stored field yields no cells.
- `harvestWithNearlyFullCarryLosesNothing`: forced drop roll (`FarmerLogic:testDropRoll`): the checked roll fits, the
  stored roll (wheat + 3 seeds) does not. The crop must stay untouched, then be harvested after the carry was
  delivered; wheat/seeds conserved every tick.
- `stemSeedsIgnored`: melon seeds first in the seed chest, wheat seeds second: cells get wheat, never melon stems.
- `seedDefinition`: seed and non-seed items (melon/pumpkin/torchflower seeds are not seeds).

## Known limits

- Stems (melon/pumpkin), torchflower, pitcher pods, sweet berries, nether wart and cane are not supported and not
  seeds. Why: stems' fruit is never harvested and torchflower becomes a flower block, so the cell would stay
  occupied forever.
- A non-crop block above a soil cell (e.g. grass or short grass) blocks tilling and planting there: the golem never
  breaks non-crop blocks.
- Unbreaking on the hoe is not tested (the server `hurtAndBreak` overload should apply it; unverified).
- No bone meal, no watering: dry farmland reverts to dirt when empty and is then re-tilled (costing durability).
- Field Y variance (slopes) is not handled: only the lower click's layer is farmed.
