# Lumber profession

Works one assigned tree in a fixed cycle (plant, bone meal / wait and collect, fell, deliver), felling it from the ground. Needs an
axe, which wears out. Id `coppergolemsrevamped:lumber`, `runsVanillaBrain = false`.

## Entry points

- `profession/lumber/LumberProfession:init` declares it. Slot groups (placed by cell in the Inventory tab, see [gui.md](gui.md); the axe is also
  shown in the golem's hand): `tool` (1 slot, max stack 1, filter axe), `supplies` (2 slots x64, filter
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
   Broken-axe handling: `LumberLogic:breakBlock` reads the axe slot once before `hurtAndBreak` and clears that slot
   index afterwards. Why: `axeSlot` finds the slot by testing for an axe, so after the axe broke it returned -1 and
   `items.set(-1, ...)` crashed the server (2026-11-15). Never call a slot-finder again after the stack may have
   changed. The golem then idles or fetches a new axe from the input chest (`LumberTest:brokenAxeEmptiesSlotAndRefetches`).
5. Drops go to carry (supply slots first when the same item). A break is refused (`Result.FULL`) unless all drops fit
   (checked on a copy of the inventory); the golem then delivers first (`State.mustDeliver`), walks back
   (`anchored = false`) and continues. Nothing is dropped or lost (D9).

Why lowest-first and reachable-first: logs do not fall when the trunk below is cut, so the order does not matter for
the world; preferring reachable logs avoids shuttling between a near and a far branch.
Why a horizontal reach of 5.5: a mega trunk plus the golem's stand spot reaches the typical branch spread without
moving; farther logs trigger the second walk.

## Chopping animation

Each log takes whole swings of `ai/ToolSwing` (12 ticks each, at least `LumberLogic.MIN_SWINGS` = 2), the break lands right
after the last chop: iron axe 24 ticks (base 10), wood 36 (30), diamond 24 (8), bare hand 60. While felling, the golem is
**held** in one interaction state (`ToolSwing.HELD` = GETTING_ITEM) via `ToolSwing:hold`, with no IDLE pulses, so the vanilla
client plays its 3 s looping chest-interaction animation uninterrupted. The per-chop hit sound and crack overlay
(`destroyBlockProgress`, stage grows per swing) keep the 12-tick cadence (phase 10 of each swing); break timings are unchanged.
Between logs `ToolSwing:clearCrack` removes only the crack, so the state carries over to the next log without a restart. A walk
to another standing spot longer than 3 ticks lets the stale-swing hook end the state (IDLE, normal walk arms), and the next
log starts the loop again; shorter walks (a step) keep it. Delivering mid-tree, fetching an axe, giving up and a finished tree
call `ToolSwing:end` at once (IDLE). Abandoned work is cleaned by the hook after 3 ticks. The resting two-hands look is
untouched (IDLE). The Farmer and Butcher still use the pulsed `ToolSwing:tick` (raised 10, IDLE 2).
Why held: the user asked for the continuous interaction loop instead of restarting it (the earlier pulse restarted the
animation every swing, which read as a snap). Why GETTING_ITEM: of the four loops (`CopperGolemAnimation` NOITEM_GET, NOITEM_NOGET,
ITEM_DROP, ITEM_NODROP) it keeps both arms overhead longest (about 0.45 s to 2.9 s of the 3 s, right arm swinging between
-56 and -129 degrees, left arm swaying), while NOITEM_NOGET (GETTING_NO_ITEM) rests its arms from 2.2 s and the two drop
loops lower them at 1.5 to 2.3 s or turn sideways; the only cost is one short arm reset at the 3 s loop point. Why walks end it: the chest pose is
wrong for a longer walk, and the 3-tick grace avoids flicker on a one-step shuffle. The vanilla model cannot render a swing,
see [vanilla-copper-golem.md](../architecture/vanilla-copper-golem.md) "Animations the client can show". Cost: at most +16
ticks (0.8 s) per log over the pure vanilla formula (whole swings). Test: `LumberTest:chopsHoldTheInteractionStateAndReturnsToIdle`,
`toolSwingRulesAndCleanup`, `toolSwingHoldAndCleanup`.

## Strip logs (setting)

Lumber-only toggle on the Settings tab (4,4), "Strip logs: on/off", icon `oak_log` off / `stripped_oak_log` on, default **off**, persisted as
`GolemData:stripLogs` (codec field `strip_logs`, old saves load off). With it on the golem strips each log with its axe before
breaking it, so the carry and the output chest get stripped logs. User request. See [gui.md](gui.md) for the toggle.

- How: `LumberLogic:strippedState` asks the axe's own `DataComponents.BLOCK_TRANSFORMER` (vanilla's data-driven `axe` transformer,
  26.3 has no `AxeItem.STRIPPABLES` any more) for the new state, so every wood vanilla strips works (logs, wood, stems, hyphae) with the
  axis kept, and nothing is hardcoded. Only a result that is in `#logs` and a different block counts (the axe transformer also scrapes
  and unwaxes copper; those never apply to a log). No stripped form (a stripped log) means a normal break.
- Where: `LumberLogic:fell`, as a visible step before the break. For each new target log with a strip available (`strippedState` not
  null) the drops of the stripped state must fit the carry (else `mustDeliver`, the log is untouched: no stripped log is ever left
  unfellable), then `LumberLogic:strip` sets the stripped state (axis kept), plays the strip sound and spends 1 durability. The
  stripped log then stands for `STRIP_TICKS` (8) ticks while the golem holds its arms up (one `ToolSwing.tick` pulse, no crack, no
  hit sound), then the normal break (`breakBlock`, which no longer strips) runs with the normal break time. A stripped log has no
  stripped form, so it is never stripped twice (`fellsAlreadyStrippedTree`).
  Why visible, not atomic (the earlier design): the user wanted the strip to be seen so the behaviour is self-explaining. 8 ticks is
  long enough to read the changed texture, short enough not to slow a tree noticeably.
- Same tree: `LumberLogic:woodKind` maps a log to its stripped block (vanilla's axe mapping, via the default axe's
  `BLOCK_TRANSFORMER`), a stripped log maps to itself. `detectTree` compares kinds, so a plain log and its stripped form are one
  tree. Why: a log stripped but not yet broken (delivery trip, reload, interruption) stays in the tree set and is felled
  (`fellsMixedPlainAndStrippedTree`). `State:stripPos` keeps the just-stripped log as the next target across a delivery trip.
- Timing: strip tick, then `STRIP_TICKS`, then the break (whole swings, still one hold).
- Durability: strip 1 plus break 1 = 2 per log, both through `GolemMending.damage`. Strips only when the axe is usable
  (`canUse`), is not on its last point (`nextDamageWillBreak`: then the log is just broken) and, for Mending, has more than 2 left.
  Bare hands (a resting Mending axe, no axe) never strip. A Mending axe is therefore never broken by a strip.
- DELIVER is unchanged: stripped logs are ordinary items for the output chest.

## Leaves

Leaves are **not** broken. After the logs are gone they decay naturally and drop saplings, sticks and apples on the
ground, which the golem collects (below). Why: user spec "fell the tree"; shearing or axing hundreds of leaves would
take minutes and the vanilla decay does it for free. Leaves that block the golem are not handled.

## Collecting drops (waiting, and PICKUP)

Since 2026-11 the golem does **not** wait after the last log: FELL goes straight to DELIVER (the logs are already in the
carry), then PLANT, then BONEMEAL. Drops are collected **while the sapling grows**, in BONEMEAL whenever it is not applying
bone meal (no bone meal at all, or between two applications). Before this change BONEMEAL without bone meal only idled and
a separate PICKUP phase after FELL waited up to 150 ticks (`PICKUP_QUIET`) for leaf drops, which the user saw as an idle wait.
Why collect while waiting: leaves decay for a long time after the felling, so the stragglers (saplings, sticks, apples) land
during the growth wait anyway; the walk to the chests is not delayed. Items collected while waiting stay in the carry
and go out at the next DELIVER (after the next felling); a full carry just stops the collecting (`fitsSome`), nothing is lost
(FELL delivers mid-tree when needed). Rule while a sapling stands (`LumberLogic:collect` with the footprint): a drop inside
the footprint (the danger area) is walked towards over the safe ring (1..2 blocks from the footprint) and picked up from there at
`DANGER_PICKUP_RANGE_SQR` (3 blocks; covers the far corner of a 2x2 footprint from the opposite corner of the adjacent ring, 2.83);
the golem never stands in the footprint. A drop whose path (`LumberLogic:crosses`) passes the sapling volume and has no detour is
likewise picked up at that range from a safe cell instead of skipped. Why: user request, "they can also just suck up the items at a
greater range when they are in a dangerous area": rather than leaving drops on or behind the sapling, the golem pulls them in from
a safe distance. An item whose path
(`LumberLogic:crosses`) passes the sapling volume (`inVolume`) is first re-routed over a waypoint on the ring 1..2 blocks from the footprint (`LumberLogic:detour`, repeated per repath until the drop is reached) and skipped for `SKIP_TICKS` only when no such waypoint exists and the drop is out of the extended range. Why: pathing over a sapling cell
is possible (no collision) and the trunk growing around the golem would suffocate it; the pathfinder walks straight through a sapling cell, so without the detour a drop on the far side
of the sapling (now in range, one block from it) would always be skipped; a skipped item costs nothing, it is
retried after the skip or the next cycle. Why no margin around the footprint (user decision: "if he's not standing directly in
the sapling, it is fine, a single block next to it is fine"): only the trunk cells suffocate, and the old 2-block margin made
the golem ignore player-dropped saplings and random apples lying next to the sapling. After a pickup the golem walks back to its waiting spot (`standOutside`), so the growth logic is unchanged.
Test: `LumberTest:collectsDropsWhileWaitingForGrowth`.

### Bone meal must not out-run the leaves (sapling loss, 2026-11)

User report from a live server: "Lumber golems keep losing all of their saplings." Their theory, confirmed by the code and
by tests that failed before the fix: with bone meal the tree is up in a few applications, and `LumberLogic:tickBoneMeal` used to
apply bone meal first and collect only when it did not (`done` cooldown between applications returns from the tick, so the
"collect between applications" never ran). Saplings from decaying leaves therefore lay on the ground until they despawned
after 5 minutes, while every tree cost 1 sapling (4 for 2x2): the stock drained and the input chest ran dry. A second leak: items
within the old 2-block margin of the footprint were never collected while a sapling stood (the margin is gone, see "Why no margin").
Rules now, in order, every tick of BONEMEAL:
1. **Collect first**: every collectable drop in the box (same rules: never enter the footprint, skip paths crossing the sapling)
   before any bone meal. The pickup-delay filter is gone (leaf drops carry a 10-tick delay and bone meal would otherwise run in it).
2. **Low stock waits**: when saplings in supply slots + carry + input chest are at most what the next planting needs (1, or 4
   for a 2x2 footprint), no bone meal is applied until a spare sapling is in hand, no drop was seen for `PICKUP_QUIET` (150
   ticks, counted from entering BONEMEAL or the last drop) or `PICKUP_MAX` passed. The golem waits (Mending, standing outside).
   Why: it keeps "no idle wait after felling" (full stock: no wait at all) but, when the next planting is at risk, it grows
   the tree at the leaves' own pace so their drops can arrive. Threshold "at or below the need" so one spare always remains.
Tests: `LumberTest:dropsAreCollectedBeforeBoneMeal`, `lowSaplingStockWaitsForDropsBeforeBoneMeal`, `boneMealCyclesKeepTheSaplingStock`.
DELIVER's sapling routing was checked and is fine (supply slots first, surplus to the input chest, nothing deleted).

Mending while waiting: after collecting, if the input chest holds XP bottles and any Mending axe (held or in the chest) is
damaged, the golem walks to the chest and repairs it to full (`LumberLogic:mendVisit`), even for a few missing points
(the felling-time rule only visits when a whole bottle's worth is missing), then goes back to waiting. Why: user request,
the wait is free time. Details in [mending.md](mending.md). Test: `LumberTest:repairsMendingAxeWhileWaitingForGrowth`.

PICKUP still exists, but only as the cleanup phase: a fresh state with no tree, PLANT that cannot plant (no saplings, a 2x2 site
waiting for four) and a vanished sapling go through it, the same collection and end rule as below. Picking up around T:

The box: the golem picks up item entities in a box around T (horizontal radius 8 (`ITEM_RADIUS`, equal to `TREE_RADIUS` so large oak and 2x2 jungle canopies are covered), from 1 below to 4 above T):
nearest first, `createPath` + `moveTo` like the Cleaner (see [cleaner.md](cleaner.md)), pickup within 1.5 blocks, same
give-up rule (no progress for 200 ticks: that item entity is ignored for 1200 ticks, a null path is no progress). Any
item is taken if at least part of it fits (`LumberLogic:fitsSome`). A sapling goes to the sapling slot when the same item
is there, or into a free supply slot when the golem holds no sapling yet; everything else to carry.

End rule (`LumberLogic:tickPickup`): PICKUP ends when no pickable item was seen in the box for `PICKUP_QUIET` = 150
ticks, or `PICKUP_MAX` = 1200 ticks after it began. Why: leaves decay on random ticks, so after the felling drops
trickle in for a long time, one every few ticks while many leaves remain and ever slower later. 150 ticks (7.5 s)
bridges those gaps without waiting for the last leaf; a straggler is simply picked up in the next cycle (the box is the
same). 1200 ticks (1 min, like the skip time) bounds a constant stream (a farm dropping items nearby). A fresh state
that finds no tree skips the wait when nothing lies there. Items that do not fit are not "seen", so a full carry cannot
hold the phase.

## Replanting (PLANT)

`LumberLogic:plant` plants, standing next to the footprint but never in it (see Standing outside), one sapling per
5 ticks:

- 1x1: one sapling at T.
- 2x2: dark oak and pale oak saplings only grow as a 2x2 group, so they are always planted as four (the remembered
  footprint first, else a free 2x2 square that contains T). Spruce and jungle saplings are planted as four when the
  felled tree was 2x2 and enough saplings are in the slot; otherwise one. Planting waits (and fetches) until the slot
  holds enough saplings. `LumberLogic:plantCells` decides.
- A site that was ever 2x2 stays 2x2: the golem plants only when it holds 4 saplings of a kind that can grow 2x2 (spruce,
  jungle, dark oak, pale oak) and fetches from the input chest as needed. It never falls back to one sapling; with fewer
  it cycles through PICKUP and DELIVER (saplings may drop from leaves) and tries again. Why: user rule, a 2x2 site must
  not turn into a 1x1 tree. The size is learned when a 2x2 trunk is first detected at T or four are planted and kept in
  `GolemData.assignments` under `LumberProfession:SITE_2X2` (value = the tree spot). Why there: that map is persisted and
  cleared on a profession change, and a marker whose spot differs from the current tree spot is void, so reassigning
  the tree resets the size without a new `GolemData` field.
- A cell is plantable when it is air or replaceable (no fluid) and `BlockState:canSurvive` holds.

Sapling stock: in PLANT the input chest tops the sapling slot up to a full stack when it holds fewer than 16.

Likely why the old code "did not replant" (diagnosed from the code, not reproduced): the shaft exit and the 1.5-block snap left the golem stuck or the column
unfinished, so the replant branch never ran. The new loop has no such state: no logs left and T is free means plant.

## Bone meal (BONEMEAL)

If the input chest holds bone meal and a sapling of ours stands (T or a footprint cell), the golem fetches bone meal
(when it holds fewer than 4, up to 16) and, standing next to T, applies one per 10 ticks with `BoneMealItem.growCrop`
until the tree has grown; the next tick detects the logs and the cycle moves to FELL. Without bone meal it waits for natural growth.
Why only the golem's own planted saplings: user choice. Why a fetch threshold: it avoids walking to the chest for
every single bone meal.

## Standing outside the sapling

The golem never stands in the grow footprint (T for 1x1, the 2x2 square for 2x2) while planting, bone-meal or waiting:
a growing trunk would suffocate it. `LumberLogic:standOutside` picks a standable, path-reachable ground cell by the
horizontal (Chebyshev) distance to the footprint (`LumberLogic:pickSpot`; `Interact.approach` cannot exclude cells and
also offers "standing on top"): 1..2 blocks (`WORK_MIN`/`WORK_MAX`) to plant, bone-meal, wait for natural growth and during PICKUP while a sapling
stands (adjacent is fine, so the reach rule holds). A golem inside the footprint
steps out first. With no such cell it widens the range; if it stands outside already it stays, if it is inside and
cannot leave it gives the tree up after 200 ticks. Why 1..2 also for waiting: leaves do not suffocate in vanilla, only trunk
cells do, and the user decided that standing one block beside a sapling is safe (the former 2..3 waiting margin is gone). Known limit: while chasing drops the pathfinder may cross a sapling cell (saplings do not collide).

## Walking back reliably

All walking goes through `LumberLogic:reach` = `Interact.approach` plus the give-up rule: no progress towards the
target for 200 ticks skips it for 1200 ticks (`State.skip`; tree spot, chest or item); a null path or an airborne wait
is no progress, so waiting is bounded. After every chest visit or delivery the golem is un-anchored and walks back to
T. Why it failed before: it walked to block centres and was snapped onto its stand spot within 1.5 blocks, and the
shaft exit could leave it hanging; `Interact.approach` picks a standable, path-reachable spot and finishes the walk on
the ground.

Chests: `Interact.openChest` (lid visible) after every fetch or delivery; cooldown `Interact.OPEN_TICKS`.


## Loop: the phase machine

Transient state (phase, cooldown, walk state, break progress, known logs, footprint, skip map, unreach set, stand spot)
in a `WeakHashMap` like the Farmer; persistent state is only `GolemData`, saved after every mutation. A different
assigned tree spot starts a fresh state. `tree` or `output` missing/invalid: stop, retry in 40 ticks (`input` is
optional). `LumberLogic:tick` detects the tree and runs the current `LumberLogic.Phase` (test hooks
`LumberLogic:phaseOf`, `LumberLogic:phaseListener`):

1. **PLANT**: a sapling or tree already there skips it (sapling -> BONEMEAL, tree -> FELL). Else fetch saplings from the
   input chest (`wantsFetch`, below 16 in hand), then plant (1x1 or 2x2). Nothing plantable -> PICKUP.
2. **BONEMEAL**: fetch bone meal (below 4 in hand, up to 16), collect drops first, then (unless sapling stock is low, see above) apply one per 10 ticks. Otherwise (no bone meal, or low stock) wait for natural growth (outside the footprint). The sapling disappearing -> PICKUP. A tree appearing -> FELL (checked every tick).
3. **FELL**: fetch / swap the axe at the input chest (worn axe swap), fell the whole tree. A full carry delivers mid-tree
   (`State.mustDeliver`, nothing lost, D9) and the felling continues. Tree gone -> DELIVER at once.
4. **PICKUP** (cleanup only, see above): collect drops, end rule above, then DELIVER.
5. **DELIVER** (`LumberLogic:deliverStep`), always, even with a small carry (nothing to carry = no trip): carried saplings
   and bone meal first top up the supply slots (`topUpSupplies`); the surplus saplings / bone meal go to the **input
   chest**, everything else (logs, sticks, apples, other items) and whatever the input chest could not take (not assigned,
   full, skipped) to the **output chest**. One visit per chest per delivery; items that do not fit stay in the carry and
   are retried in the next cycle, nothing is lost. Lids open via `Interact.openChest`. Then PLANT. Chest moves are budgeted (16 items per 5 ticks): a cut delivery or fetch stays at the chest and goes on next window, see [../architecture/transfer-limit.md](../architecture/transfer-limit.md).

Why a strict cycle (user rule): earlier the golem went from picking up straight back to planting and never went through the
whole loop; now every cycle ends at the chests before it plants again. Fresh state (first tick, restart, reassignment):
a standing tree starts at FELL, otherwise at PICKUP (clean up leftovers, deliver, then plant). A given-up tree (skip)
leaves PLANT / BONEMEAL / FELL: with a carry it delivers, else it idles until the skip ends.

## Tests (`LumberTest`)

- `fellsOakFromTheGround`: 5-log oak with leaves; golem never above T.y + 1.5 and never in the trunk column, all
  leaves unchanged every tick, 5 logs in output, sapling at T, axe damage 5.
- `fellsBranchyTree`: trunk + east, west-diagonal and north branches (13 logs) fully felled; an unconnected log, a
  birch log touching the trunk and an oak log diagonally below T survive every tick.
- `fellsTwoByTwoAndReplantsFour`: 2x2 dark oak trunk with branch logs (18 logs) fully felled, four dark oak saplings
  on the square, four left in the golem.
- `collectsGroundDrops`: (fresh start in PICKUP) item entities (sapling, sticks, one in a far corner) end in supply slot / output chest.
- `bonemealGrowsTheSaplingThenFellsIt`: 64 bone meal in the chest, sapling on T: logs reach the output, bone meal used.
- `fullCycleOrder`: phases recorded by the listener: PICKUP, DELIVER, PLANT, BONEMEAL, FELL, DELIVER, PLANT, BONEMEAL, with
  FELL always followed by DELIVER (no wait) and DELIVER by PLANT; surplus birch saplings (put in the carry) end in the input chest, logs in the output.
- `repairsMendingAxeWhileWaitingForGrowth`: axe with 5 damage, 5 bottles in the input chest, sapling and no bone meal: axe whole, one bottle used.
- Sapling loss: `dropsAreCollectedBeforeBoneMeal`, `lowSaplingStockWaitsForDropsBeforeBoneMeal`, `boneMealCyclesKeepTheSaplingStock` (see "Bone meal must not out-run the leaves").
- `collectsDropsWhileWaitingForGrowth`: sapling, no bone meal; drops spawned once in BONEMEAL (2 sticks, 2 apples, a sapling, plus a stick next to T that is left alone) end in the carry while it is still a sapling; never in the sapling volume.
- `twoByTwoSiteWaitsForFourSaplings`: marked 2x2 site, 3 spruce saplings plant nothing, a 4th plants all four.
- `neverStandsInTheSapling` (bone meal run: never in T's column while a sapling stands, health unchanged) and
  `stepsOutOfTheSapling` (spawned inside, waits 1..2 blocks away).
- `fullCarryDeliversMidTreeAndContinues`, `noAxeNoWork`, `wornAxeIsSwapped` (iron axe about to break swapped for a
  diamond axe, the old one lands in the chest).
- `walledInTrunkIsFelledFromTwoBlocks`: all T neighbours stone: the 2-block fallback still fells it.
- `unreachableTreeIsSkipped`: tree inside a closed stone ring: skipped after the give-up time, nothing breaks.
- Strip logs: `stripLogsGivesStrippedLogs` (5 stripped logs, damage 10), `stripLogsIsVisibleStep` (a stripped log stands in the tree for at least 8 ticks per log), `fellsMixedPlainAndStrippedTree`, `stripLogsOffByDefault` (plain logs, damage 5), `stripLogsNeverBreaksMendingAxe`,
  `strippedStateFollowsVanilla` (mapping, axis, wood/stems/hyphae, no strip for stripped log / bare / off / last point), `fellsAlreadyStrippedTree`,
  `stripLogsSurvivesSaveLoad`; the toggle: `MenuTest:settingsStripLogsToggleIsLumberOnly`.
- Unit tests: `breakAllowedRules`, `detectTreeRules` (same kind, diagonal, not below T, unconnected, not-a-log spot),
  `breakTimeFollowsAxeSpeed`, `lumberDeclaration` (filters, inventory size 12, at most 6 rows, assign keys).

## Known limits

- Mid-felling state (known logs, footprint) is transient: after a server restart the footprint falls back to T (a
  2x2 tree is replanted as 1x1, or four dark oak when they are in the slot) and a tree whose T is already gone is not
  found.
- Leaves are never cleared, so they can block the golem's path or a stand spot.
- The tree needs a standable ground spot within reach of T (1 block, or 2 as fallback); otherwise it is skipped.
- Any item in the drop box (not next to a growing sapling, see above) is picked up, including items a player dropped there.
- With nothing in the supply slots and no saplings in the input chest, saplings that drop from leaves are taken into
  the supply slot (if free) and used for the next planting; other kinds go to carry and the output chest.
- Efficiency/Unbreaking are not specifically handled or tested (durability goes through the vanilla overload).
- Bone meal is only used on the golem's own planted sapling(s), not on grass or other crops.

## Mending

A Mending axe is repaired with XP bottles from the **input chest** and never used below 10 durability: it is parked in the
input chest (or swapped for a usable axe) and the golem chops by hand meanwhile. Only a Mending axe enables bare hands; a
golem that never had an axe still does no work. See [mending.md](mending.md).
