# Lumber profession

Fells one assigned tree from the ground, collects the drops, replants it and uses bone meal from the input chest.
Needs an axe, which wears out. Id `coppergolemsrevamped:lumber`, `runsVanillaBrain = false`.

## Entry points

- `profession/lumber/LumberProfession:init` declares it. Slot groups (3 groups, so 5 slot rows + the control row = the
  6-row maximum, see [gui.md](gui.md)): `tool` (1 slot, max stack 1, filter axe), `supplies` (2 slots x64, filter
  `ItemTags.SAPLINGS` or bone meal), `carry` (9 x64, any item). Inventory indices: `AXE_SLOT` 0, `SUPPLY_FROM`
  1..2, `CARRY_FROM` 3..11.
- Why `supplies` is one group of two slots: `Profession.SlotGroup` has one filter per group and a fourth group would
  need 7 rows. The golem finds saplings and bone meal in either slot (`LumberLogic:supplySlot`); it fills one slot per
  kind and never puts a second sapling kind into the second slot, so the bone meal always has room.
- Why no shears slot any more: leaves are no longer broken (see Leaves), so shears have nothing to do.
- Assigns: `tree` (`Kind.BLOCK`, "Assign Tree"), `input` (CHEST, "Assign Input Chest"), `output` (CHEST,
  "Assign Output Chest"). Icons: `assign_input` / `assign_output` by key, `assign_block` for the tree (see gui.md).
- `profession/lumber/LumberLogic:tick` is the loop. Public helpers: `LumberLogic:detectTree`,
  `LumberLogic:breakAllowed`, `LumberLogic:breakBlock`, `LumberLogic:breakTicks`, `LumberLogic:isSkipped`.

## History: why this design

First version (D15) felled one column: the golem broke the log in front of it, snapped into the gap and climbed the
shaft it cleared. The in-game test said: it had problems walking back to its station (only worked when helped), did
not pick up its drops, did not replant, flew up the tree, only mined the clicked trunk block of a 2x2 tree and left
branches, and should use bone meal. So the shaft, the front-or-above rule and the snap moves were deleted and the
design below replaced them. General user rule behind the walking: "golems should always try to get into 1 block reach
of what they interact with, 2 blocks if unreachable (lumbers are an exception)". The exception is the tree only:
chests use `Interact.approach` with the 1-block rule like every golem, the golem reaches up the trunk from the ground.

## The tree spot and tree detection

"Assign Tree" stores the clicked block T (`Kind.BLOCK`, see [assign-mode.md](assign-mode.md)): the sapling or the
bottom log (any one of the four for a 2x2 trunk). Clicking again overwrites it. The assign mode rejects air.

`LumberLogic:detectTree(level, T, known)` is the tree: a flood fill (26-neighbourhood, so diagonal branches connect)
over blocks of the **same kind** as the seed log, started from T if it is a log plus the logs of the previous scan
that still stand. Bounds: never below T's Y, horizontal Chebyshev radius 8 around T (`TREE_RADIUS`), at most 32
above T (`TREE_HEIGHT`), at most 256 logs (`TREE_MAX_LOGS`). 2x2 trunks and branches come for free.

Why same wood kind only: in a forest a pure `BlockTags.LOGS` flood would fell the neighbouring tree or a house.
Why start only from T and not below: player structures under or next to the tree stay safe. Why the `known` seeds:
if T itself is gone (a player broke it, or the golem broke the bottom log) the upper logs are still connected to the
remembered ones; state is transient, so after a server restart a half-felled tree whose T is gone is no longer found.

When a tree appears (nothing known, logs found) the golem remembers its **footprint**: the 2x2 square of logs at T's
level that contains T (`LumberLogic:bottomSquare`), else just T. The footprint is where it replants.

## Felling (from the ground)

1. Walk to T: `Interact.approach(T)` (1-block stand spot, 2-block fallback; see
   [../architecture/interaction.md](../architecture/interaction.md)). Then the golem is "anchored". It never climbs,
   never snaps and never enters the trunk; there is no vertical limit on what it breaks.
2. Next log (`LumberLogic:pickNext`): the lowest log it can reach from where it stands (horizontal distance <=
   `REACH_H` 5.5), else the lowest log overall. For a log farther than 3.5 from T it first walks to a ground cell near
   that log's column (`LumberLogic:standCell`, within 3 blocks, near T's level) and approaches that; if it still is
   out of reach the log goes into `State.unreach` (and is retried once a new tree appears). Everything unreachable:
   the tree is skipped for 1200 ticks.
3. Break: `LumberLogic:breakBlock` is the only code that removes a block. It requires `LumberLogic:breakAllowed`: the
   position is in the detected tree set and within `REACH_H` horizontally of the golem, and it is a log. Time per log
   `LumberLogic:breakTicks(hardness, speed)` = `max(1, ceil(hardness * 30 / speed))` (vanilla correct-tool formula;
   oak log: 30 ticks wooden axe, 10 iron, 8 diamond), the golem looks at the log meanwhile.
4. Durability 1 per log (`hurtAndBreak`, server overload). A worn axe (`nextDamageWillBreak`) is swapped for a fresh
   one from the input chest, the old one going into that chest slot: nothing lost or duplicated. No axe: the golem
   idles and the tree is untouched. Axes are fetched only when there is a tree to fell.
5. Drops go to carry (supply slots first when the same item). A break is refused (`Result.FULL`) unless all drops fit
   (checked on a copy of the inventory); the golem then delivers first (`State.mustDeliver`), walks back
   (`anchored = false`) and continues. Nothing is dropped or lost (D9).

Why lowest-first and reachable-first: logs do not fall when the trunk below is cut, so the order does not matter for
the world; preferring reachable logs avoids shuttling between a near and a far branch.
Why a horizontal reach of 5.5: a mega trunk plus the golem's stand spot reaches the typical branch spread without
moving; farther logs trigger the second walk.

## Leaves

Leaves are **not** broken. After the logs are gone they decay naturally and drop saplings, sticks and apples on the
ground, which the golem collects (below). Why: user spec "fell the tree"; shearing or axing hundreds of leaves would
take minutes and the vanilla decay does it for free. Leaves that block the golem are not handled.

## Collecting drops

When there is no tree to fell, the golem picks up item entities in a box around T (horizontal radius 6, from 1 below
to 4 above T): nearest first, `createPath` + `moveTo` like the Cleaner (see [cleaner.md](cleaner.md)), pickup within
1.5 blocks, same give-up rule (no progress for 200 ticks: that item entity is ignored for 1200 ticks, a null path is
no progress). Any item is taken if at least part of it fits (`LumberLogic:fitsSome`). A sapling goes to the sapling
slot when the same item is there, or into a free supply slot when the golem holds no sapling yet; everything else to
carry. It rescans every 20 ticks while idle, so drops from slowly decaying leaves keep being collected. Carry is
delivered to the output chest when nothing is left to pick up.

## Replanting

After felling (no logs left), `LumberLogic:plant` plants, standing next to T (`Interact.approach(T)`), one sapling per
5 ticks:

- 1x1: one sapling at T.
- 2x2: dark oak and pale oak saplings only grow as a 2x2 group, so they are always planted as four (the remembered
  footprint first, else a free 2x2 square that contains T). Spruce and jungle saplings are planted as four when the
  felled tree was 2x2 and enough saplings are in the slot; otherwise one. Planting waits (and fetches) until the slot
  holds enough saplings. `LumberLogic:plantCells` decides.
- A cell is plantable when it is air or replaceable (no fluid) and `BlockState:canSurvive` holds.

Sapling stock: the input chest tops the sapling slot up to a full stack when it holds fewer than 16 and there is work
(felling, or not yet planted).

Likely why the old code "did not replant" (diagnosed from the code, not reproduced): the shaft exit and the 1.5-block snap left the golem stuck or the column
unfinished, so the replant branch never ran. The new loop has no such state: no logs left and T is free means plant.

## Bone meal

If the input chest holds bone meal and a sapling of ours stands (T or a footprint cell), the golem fetches bone meal
(when it holds fewer than 4, up to 16) and, standing next to T, applies one per 10 ticks with `BoneMealItem.growCrop`
until the tree has grown; the next tick detects the logs and fells it. Without bone meal it waits for natural growth.
Why only the golem's own planted saplings: user choice. Why a fetch threshold: it avoids walking to the chest for
every single bone meal.

## Walking back reliably

All walking goes through `LumberLogic:reach` = `Interact.approach` plus the give-up rule: no progress towards the
target for 200 ticks skips it for 1200 ticks (`State.skip`; tree spot, chest or item); a null path or an airborne wait
is no progress, so waiting is bounded. After every chest visit or delivery the golem is un-anchored and walks back to
T. Why it failed before: it walked to block centres and was snapped onto its stand spot within 1.5 blocks, and the
shaft exit could leave it hanging; `Interact.approach` picks a standable, path-reachable spot and finishes the walk on
the ground.

Chests: `Interact.openChest` (lid visible) after every fetch or delivery; cooldown `Interact.OPEN_TICKS`.

## Loop

Transient state (cooldown, walk state, break progress, known logs, footprint, skip map, unreach set) in a
`WeakHashMap` like the Farmer; persistent state is only `GolemData`, saved after every mutation. A different assigned
tree spot starts a fresh state. Per tick:

1. `tree` or `output` missing/invalid: stop, retry in 40 ticks. `input` is optional.
2. Detect the tree (skipped while the tree spot is in give-up).
3. Input chest: axe / saplings / bone meal wanted and available (`wantsFetch` mirrors the fetch preconditions exactly,
   no endless walking): walk there, fetch, open the lid.
4. Felling (needs an axe): deliver first if `mustDeliver`, else one felling step per tick.
5. No tree: replant, then pick up drops, then bone meal, then deliver carry, else idle (rescan every 20 ticks).

## Tests (`LumberTest`)

- `fellsOakFromTheGround`: 5-log oak with leaves; golem never above T.y + 1.5 and never in the trunk column, all
  leaves unchanged every tick, 5 logs in output, sapling at T, axe damage 5.
- `fellsBranchyTree`: trunk + east, west-diagonal and north branches (13 logs) fully felled; an unconnected log, a
  birch log touching the trunk and an oak log diagonally below T survive every tick.
- `fellsTwoByTwoAndReplantsFour`: 2x2 dark oak trunk with branch logs (18 logs) fully felled, four dark oak saplings
  on the square, four left in the golem.
- `collectsGroundDrops`: item entities (sapling, sticks, one in a far corner) end in supply slot / output chest.
- `bonemealGrowsTheSaplingThenFellsIt`: 64 bone meal in the chest, sapling on T: logs reach the output, bone meal used.
- `fullCarryDeliversMidTreeAndContinues`, `noAxeNoWork`, `wornAxeIsSwapped` (iron axe about to break swapped for a
  diamond axe, the old one lands in the chest).
- `walledInTrunkIsFelledFromTwoBlocks`: all T neighbours stone: the 2-block fallback still fells it.
- `unreachableTreeIsSkipped`: tree inside a closed stone ring: skipped after the give-up time, nothing breaks.
- Unit tests: `breakAllowedRules`, `detectTreeRules` (same kind, diagonal, not below T, unconnected, not-a-log spot),
  `breakTimeFollowsAxeSpeed`, `lumberDeclaration` (filters, inventory size 12, at most 6 rows, assign keys).

## Known limits

- Mid-felling state (known logs, footprint) is transient: after a server restart the footprint falls back to T (a
  2x2 tree is replanted as 1x1, or four dark oak when they are in the slot) and a tree whose T is already gone is not
  found.
- Leaves are never cleared, so they can block the golem's path or a stand spot.
- The tree needs a standable ground spot within reach of T (1 block, or 2 as fallback); otherwise it is skipped.
- Any item in the drop box is picked up, including items a player dropped there.
- With nothing in the supply slots and no saplings in the input chest, saplings that drop from leaves are taken into
  the supply slot (if free) and used for the next planting; other kinds go to carry and the output chest.
- Efficiency/Unbreaking are not specifically handled or tested (durability goes through the vanilla overload).
- Bone meal is only used on the golem's own planted sapling(s), not on grass or other crops.
