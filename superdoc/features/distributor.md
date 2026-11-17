# Distributor profession

Takes items from an input chest and splits them evenly over every inventory in an assigned area. Id
`coppergolemsrevamped:distributor`, `runsVanillaBrain = false`. Code: `profession/distributor/DistributorProfession:init`
(declaration), `profession/distributor/DistributorLogic:tick` (loop).

Why (user request): "Also add a Distributor class, which takes an input and an area of inventories (chests, barrels,
hoppers, droppers and so on) and an input chest. He should then do the following: 8 inventories detected -> get a
multitude of 8 from the input chest, split evenly between inventories. If 7, take a multitude of 7 and split evenly."

## Declaration

- Assigns: `input` (`Kind.CHEST`) and `targets` (`Kind.AREA`, max 16x16, Y is the span of the two corners, scanned at
  most 32 high: the same limits as the Smelter's furnace area).
- Slots: group `carry`, 9 slots (data indices 0..8, max 64, row 3, label at (2,4), first stack shown in the hand), the same
  layout as the Transporter's carry (without the filter group).
- Icon: `profession_distributor` in `menu/GolemIcon`, base item `dropper`. There is no custom texture and no
  `assets/minecraft/items/dropper.json` override, so the picker shows the plain vanilla dropper (an unmatched custom model
  data string falls back to the vanilla model). A custom badge icon can follow like the other professions' (needs a PNG,
  model and override, and the icon table in [gui.md](gui.md)).
- Status (`DistributorLogic:status`, hooked in `profession/ProfessionStatus`): "Distributing to N inventories", "No
  inventories in the area", "No input chest set", "Input has fewer than N of any item", "Returning the leftovers to the
  input chest", "Input chest is full: holding the leftovers".

## Targets

`DistributorLogic:rescan` (every 100 ticks, and right after a target failed to open) collects every block entity in the area
that is a `Container`: chests (also trapped and copper), barrels, hoppers, droppers, dispensers, shulker boxes. Excluded
(`DistributorLogic:isMachine`): the input chest (and its other half), furnaces / blast furnaces / smokers, brewing stands,
crafters, jukeboxes, lecterns, chiseled bookshelves, decorated pots.
N = number of targets.

- Double chest = ONE inventory: the second half is remembered while scanning (`otherHalf`) and the container used is
  `TransporterLogic:containerAt`, the combined container. Why: it is one chest to a player; counting it twice would give it
  twice the share of a single chest and make N wrong.
- Furnaces excluded. Why: furnace slots have meaning (input / fuel / result): a share of iron ingots in the fuel slot or
  into a result slot is wrong, and feeding a furnace is the Smelter's job. The same goes for the other machine-slot
  blocks above.
- Visit order is nearest-first from the input chest (`nearestFirst`, greedy), fixed at the start of each round.

## Loop

Phases (`DistributorLogic.Phase`): FETCH -> DISTRIBUTE -> RETURN -> FETCH.

1. FETCH (input chest): `planFetch` groups the chest's items by `isSameItemSameComponents` and takes
   `floor(takeable / N) * N` of each type, `takeable` respecting [keep-layout.md](keep-layout.md) (`TransporterLogic:takeable`).
   Limited by what still fits the 9 carry slots (simulated, also rounded down to a multiple of N per type). Types with fewer
   than N takeable are left alone; if nothing qualifies the status says so and it retries in 40 ticks. Several types per trip.
2. DISTRIBUTE: per type the share is `carried / N` (`sharesOf`); each inventory of the round gets that share of each type
   (`State.quota`, inserted straight out of the carry slots, so everything stays in `GolemData.items` and a restart loses
   nothing). A target that cannot take its full share (full, or keep layout finds no matching stack) just keeps what fit;
   the golem moves on to the next one. A target that is gone, unloaded or unreachable (200 ticks without progress, then
   skipped for 600) is passed over and a rescan is forced.
3. RETURN: whatever is still carried (the unplaced shares and the remainder) goes back into the input chest with
   `TransferBudget.insert(..., keepLayout)`. If the input chest is full the golem holds the items, shows a status and retries
   every 40 ticks. Why: items must never be stranded in the golem forever (the golem keeps trying to return them) and never
   busy-loop (the cooldown).

If the golem carries items with no plan (after a restart) it distributes them as a fresh round, then returns the rest.
A returned share is picked up again in the next round (the input shrinks until fewer than N of a type remain), so a chest
that is permanently full costs trips but never items.

## Transfer limit and keep layout

All moves are budgeted (16 per 5 ticks, see [../architecture/transfer-limit.md](../architecture/transfer-limit.md)). The take
and the per-type insert use `TransporterLogic.takeAll` / `insertAll` with a limit plus `TransferBudget.spend`, because the
plain `TransferBudget.take` / `insert` wrappers cannot cap a single type. A budget-cut visit stays at the container
(lid refreshed, wait to the next window) and never counts as "full": the golem keeps the target's remaining `State.quota`
and resumes it next window until every type's full share is in, then moves on. A type ends early only when the target
takes less than offered (physically full / no matching stack under keep layout) or the carry has no more of it, so a
full target can never hold the golem forever. The insert loop re-finds the carry slot each pass (`slotOf`) instead of
stepping to the next slot. Why: stepping past a slot that a budget-limited insert left non-empty used to end the type
with quota left, silently truncating the share (2 targets x 32 iron got 16 each per visit) and sending the rest back
to the input for extra rounds. Keep layout: input slots keep 1, targets and the
return only merge into non-empty same-item slots; no matching stack means that target gets nothing and its share returns.
Freeze and Follow work through `GolemTicker` (nothing here).

## Tests (`DistributorTest`)

`eightTargetsTakeMultipleOfEight`, `sevenTargetsTakeMultipleOfSeven`, `doubleChestCountsAsOne`, `fewerThanNTakesNothing`,
`fullTargetReturnsItsShare`, `keepLayoutRespectedOnBothSides`, `shareLargerThanBudgetDeliveredInOneVisit` and
`severalTypesOverBudgetDeliveredInOneVisit` (a per-visit share over the budget: the input chest must never be refilled
mid-cycle, `watchNoRefill`).
