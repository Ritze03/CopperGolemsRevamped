# Lumber profession

Fells one assigned tree and replants it. Needs an axe, which wears out. Id `coppergolemsrevamped:lumber`,
`runsVanillaBrain = false`.

## Entry points

- `profession/lumber/LumberProfession:init` declares it. Slot groups (3 groups, so 5 slot rows + the control row = the
  6-row maximum, see [gui.md](gui.md)): `tools` (2 slots, max stack 1, filter axe or shears; the golem finds the axe
  and the shears in either slot), `sapling` (1 slot x64, filter `ItemTags.SAPLINGS`), `carry` (9 x64, any item).
  Inventory indices: `TOOL_FROM` 0..1, `SAPLING_SLOT` 2, `CARRY_FROM` 3..11.
- Why one `tools` group: `Profession.SlotGroup` has one filter per group and a fourth group would need 7 rows.
- Assigns: `tree` (`Kind.BLOCK`, "Assign Tree"), `input` (CHEST, "Assign Input Chest"), `output` (CHEST,
  "Assign Output Chest"). Icons: `assign_input` / `assign_output` by key, `assign_block` for the tree (see gui.md).
- `profession/lumber/LumberLogic:tick` is the loop. Public helpers: `LumberLogic:breakAllowed`,
  `LumberLogic:breakTicks`, `LumberLogic:nextBlock`, `LumberLogic:isTreeBlock`, `LumberLogic:isSapling`.

## The tree spot

"Assign Tree" stores the clicked block (`Kind.BLOCK`, see [assign-mode.md](assign-mode.md)) as T. Click the sapling
or the **bottom trunk block**; the column straight up from T is the tree. Clicking again overwrites it, which is how
the tree is cleared and set anew. Click a block that is not air (the assign mode rejects air).

Why: user spec "one assigned tree, set on first assignment, can be cleared and redone"; D15.

## Break rule

The golem breaks **only the block directly in front of it or straight above it**: `LumberLogic:breakAllowed(feet,
facing, pos)` is true only for `feet + facing` and `feet + up`. `LumberLogic:breakBlock` is the only code that
removes a block and starts with that check (plus "is a log or leaves"). Diagonals, sides, below and two blocks ahead
are never broken.

Why (D15): the user's spec "mines only the one block in front of it or straight above it". Read as: it breaks the
log in front of it (T), steps into the gap, then breaks straight up and climbs the column it has cleared. Branches
and leaves outside the column are not in front of / above the golem, so they are left. A branchy tree therefore
keeps its branches; the user may correct this reading.

## Felling

`LumberLogic:nextBlock(level, T, airTopY)` is the lowest log/leaves block of the column from T upwards. T and the
already cleared part of the column (air up to the highest block this golem has cleared, `State.clearedTopY`) may be
air; above that the column must be contiguous logs/leaves, so the golem stops at the first air or other block.

1. Stand on a free, supported horizontal neighbour of T (`standPos`, nearest to the golem), facing T.
2. Break T (the block in front). Step into the gap: the golem is moved (`snapTo`) onto the broken block's position.
3. Break the block above, step up into it, repeat. While in the shaft the golem is held in place every tick (it has
   no floor; gravity would otherwise sag it). The golem is about one block tall and fits the 1x1 shaft.
4. Column done (or axe lost / carry full): the golem is moved out of the shaft onto a neighbour of T, then replants.

Moving along the shaft is a controlled position change inside the column the golem itself cleared, not a block break.
Path goals count as reached within about a block, so the golem is snapped onto the stand spot once within
`STAND_SNAP` (1.5) of it.

- **Break time**: `LumberLogic:breakTicks(hardness, speed)` = `max(1, ceil(hardness * 30 / speed))`, the vanilla
  formula for the correct tool (the 100-divisor for wrong tools is ignored; no enchantments, no water/air modifiers).
  `speed` is `ItemStack:getDestroySpeed(state)` of the tool in use: oak log (hardness 2.0) takes 30 ticks with a
  wooden axe (speed 2), 10 with iron (6), 8 with diamond (8). Leaves (0.2) take 6 ticks with an axe (speed 1) and 1
  with shears (15).
- **Durability**: each broken log costs the axe 1 (`hurtAndBreak(1, level, null, ...)`, server overload). Leaves cost
  the axe nothing. A worn axe (`nextDamageWillBreak`) is swapped for a better one from the input chest, the old one
  going into that chest slot, so nothing is lost or duplicated. Without a replacement the axe breaks on its last hit.
- **No axe, no work**: the golem idles (the tree is untouched). Axes are only fetched when there is a tree to fell.
- **Shears**: optional. With shears, leaves in the column are sheared (drop the leaves block, shears lose 1);
  without, they are broken with the axe and drop their normal items (saplings, sticks, apples).
- **Drops** go to carry via `Block:getDrops` with the tool in use; a break is refused (`Result.FULL`) unless all
  drops fit (checked on a copy of the inventory), then the golem delivers first (`State.mustDeliver`, leaving the
  shaft) and continues. Nothing is dropped on the ground or lost (D9).

## Saplings

The sapling slot is topped up to a full stack from the input chest when it holds fewer than 16 and there is work
(tree to fell or spot to plant): the first sapling stack in the chest, afterwards only the same item. Saplings that
drop from leaves merge into the sapling slot when they are the same item, else they go to carry.
Replanting: when the column is cleared and T is air, the golem stands next to T and plants one sapling from the slot
(`BlockState:canSurvive` must hold, otherwise T is ignored for 1200 ticks). A sapling at T is waited for (it grows by
itself, no bone meal). Any 1x1 sapling works; the logs it grows into are felled as usual.

Why "the stack can be swapped in the GUI": the sapling slot is a normal slot with a sapling filter; the golem
only ever takes the item that is in it.

## Loop

Transient state (cooldown, walk target, break progress, skip map, cleared height) in a `WeakHashMap` like the Farmer;
persistent state is only `GolemData`, saved after every mutation. Per tick:

1. `tree` or `output` missing/invalid: stop, retry in 40 ticks. `input` is optional.
2. Not continuing to fell while in the shaft: climb out.
3. Input chest: axe/shears/saplings wanted and available (`wantsFetch` mirrors the fetch preconditions exactly):
   walk there, fetch, 5-tick cooldown.
4. Felling (needs an axe): deliver first if `mustDeliver`, else one felling step per tick (progress counted down
   per block).
5. Replant when the column is gone and T is air.
6. Otherwise carry non-empty: walk to the output chest and `TransporterLogic:insertAll` over the carry slots only.
7. Else idle, rescan every 20 ticks.

Give-up as in the Farmer: no progress for 200 ticks toward a target (tree stand spot or chest) skips it for 1200
ticks; a null path is no progress. `LumberLogic:isSkipped` is the test hook.

Robustness rules (QC fixes):

- **Airborne wait is bounded.** While airborne `LumberLogic:walk` counts the same 200 ticks and then skips the
  target. Why: the golem used to wait forever when floating in water (same fix as `FarmerLogic:walkTo`).
- **A denied break never leaves the golem hanging.** `Result.DENIED` in `LumberLogic:fell` only clears the pending
  break, with no cooldown. Why: `tick` returns early on a cooldown, so the golem would not be held or climb out for
  40 ticks and would drop up to ~6 blocks in the shaft (fall damage). Without a cooldown the next tick either holds
  it (still felling) or exits the shaft (tree gone / block changed).
- **No stand spot is not a lock.** When leaving the shaft finds no free neighbour of T, T is skipped for 1200 ticks
  and a hanging golem is lowered to T's level (no fall); a golem already on T's level carries on as idle/deliver.
  Why: `exitShaft` did nothing and the golem repeated it every tick forever.
- **Tools come one at a time.** `LumberLogic:fetchableTool` only offers chest slots with exactly one item. Why: a
  stacked axe (possible via commands) kept `wantsFetch` true while the fetch took nothing, a refetch loop.
- **State follows the tree.** The transient `State` stores the tree spot it was built for; `tick` starts a fresh
  state when the assigned tree differs. `GolemService:changeProfession` does not clear the map. Why: after a
  profession change the golem would keep an old `clearedTopY`, skip entries and `mustDeliver`; a different tree spot
  is the case where that matters, and a same-tree return only repeats one harmless delivery.

## Tests (`LumberTest`)

- `fellsOakDeliversAndReplants`: 5-log oak with 2 leaves on top and persistent side leaves, input chest iron axe +
  8 saplings. Output holds exactly 5 oak logs, T is an oak sapling, the column above T is air, the axe has damage 5,
  side leaves unchanged on every tick.
- `onlyBreaksFrontOrAbove`: unit table for `breakAllowed` (front and above true; two ahead, side, behind, below,
  diagonals, above-ahead, own block false; facing up denies the side), then a world run where side branches and
  stone around the trunk (stand position side, beside and above the stand position) are checked present every tick
  while the tree is felled.
- `fullCarryDeliversMidTreeAndContinues`: carry full (8 stacks cobblestone, 63 logs): the golem delivers while
  half way up, comes back, finishes, replants; output holds all 512 cobblestone and 67 logs.
- `guardDeniesWrongPosition`: `LumberLogic:breakBlock` with a real golem two blocks from a log and diagonal to one
  returns DENIED and both survive; the log in front is broken (control that the hook works).
- `deniedInShaftDoesNotDrop`: stone replaces the next block while the golem hangs in the shaft: it never sags for
  more than one tick, takes no damage, ends outside the column and the sapling is planted. (The DENIED branch of
  `fell` itself is a race that the loop re-evaluation normally pre-empts; this test covers the exit/hold guarantee.)
- `noStandSpotDoesNotLock`: all four neighbours of T are stone: after 300 ticks T is skipped and nothing broke.
- `noAxeNoWork`: only saplings and shears around: the tree is intact after 300 ticks.
- `breakTimeFollowsAxeSpeed`: 26.3 axe speeds on oak log (2 / 6 / 8) and the tick formula.
- `lumberDeclaration`: slot filters, inventory size 12, at most 6 GUI rows, assign keys and kinds.

## Known limits

- 2x2 trees (dark oak, 2x2 spruce/jungle) are out of scope: with a single assigned spot a 1x1 sapling never grows
  one, and the golem only fells the single column over T. Any 1x1 sapling works.
- Branchy trees keep their branches and the leaves outside the column (see the break rule); only the trunk column
  and the contiguous logs/leaves straight above it are cleared. Trees whose trunk leans leave the leaning part.
- Mid-felling state (`clearedTopY`) is transient: if the server restarts while the golem is half way up, the cleared
  lower part is air and the logs above are no longer found; they stay as floating logs.
- If T is assigned as something other than sapling/log (e.g. dirt), the golem does nothing there.
- Efficiency/Unbreaking are not specifically handled or tested (durability goes through the vanilla overload).
- With an empty sapling slot and no saplings in the input chest, saplings that drop from leaves go to carry and are
  delivered to the output chest, not planted (the slot is only filled from the input chest or by hand in the GUI).
- The tree must have a free, supported neighbour of T to stand on; otherwise T is ignored for 1200 ticks.
