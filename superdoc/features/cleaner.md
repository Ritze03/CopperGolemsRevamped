# Cleaner profession

Picks up item entities inside an assigned area and delivers them to an output chest. Id
`coppergolemsrevamped:cleaner`, `runsVanillaBrain = false`, one slot group `carry` (9 slots, 64 each, any item).

## Entry points

- `profession/cleaner/CleanerProfession:init` declares it. Assigns: `area` (`Kind.AREA`, "Assign Area", maxSize 16)
  and `output` (`Kind.CHEST`, "Assign Output Chest"; gets the `assign_output` icon by key).
- `profession/cleaner/CleanerLogic:tick` is the work loop; `CleanerLogic:box` returns the area box (null if unset).

## Box rule

Two clicks are stored as `area_a`/`area_b` (see [assign-mode.md](assign-mode.md)). The box covers x/z min..max
inclusive and y from min(ay,by) to max(ay,by)+2 inclusive (AABB max = coordinate + 1). The footprint is at most
16x16, enforced at assign time by `AssignMode:areaFits`.

Why: user decision D12: "at most a 16x16 footprint; the height is the two clicks' Y range plus 2 blocks above".
The extra 2 blocks catch items lying on or just above the clicked floor blocks.

## Loop

Transient per-golem timers in a `WeakHashMap` (same style as the Transporter); persistent state is only `GolemData`.

1. Area unset, output unset or not a container: stop, retry in 40 ticks.
2. An item entity in the box (its centre inside, see below), alive, no pickup delay, (partly) fits the carry, not
   skipped and (Keep layout only) accepted by the chest: path to the nearest;
   within 1.5 blocks, move as much as fits into carry and discard the entity or leave the remainder on it
   (no duplication). Cooldown 5 ticks.
3. Else carry non-empty: `Interact:approach` to the output chest (1-block stand position, 2-block fallback, see
   [../architecture/interaction.md](../architecture/interaction.md)), `TransporterLogic:insertAll`, and if anything
   moved open the chest lid (`Interact:openChest`, `INSERT`) and wait 20 ticks. Leftovers (chest full) stay and are
   retried every 40 ticks. Item pickup keeps its own 1.5-block range.
4. Else idle, rescan every 20 ticks.

While chasing an item the full-area entity query runs only every `CleanerLogic:RESCAN_TICKS` (10) ticks; in between
the chased entity alone is re-checked against the same filter (alive, fits, inside the box, not skipped, Keep layout).
A pickup or a give-up rescans at once. Why: perf review #11, the area query ran on every walking tick; the cost is that a
nearer item dropped mid-chase is noticed up to 10 ticks later.

When only skipped items remain, step 3 delivers the carry (see "Unreachable items").

Only entities whose centre is inside the box are ever touched: `getEntitiesOfClass` matches any bbox that
*intersects* the box, so the filter adds `box.contains(e.position())` (otherwise an item up to ~0.125 outside the
edge was collected). Pickup reuses `TransporterLogic:takeAll` (via a one-slot
`SimpleContainer`), `insertAll`, `containerAt`, `isEmpty`; nothing is copied.
Items with a pickup delay are skipped so freshly dropped/thrown items are not grabbed mid-air.

Delivery is budgeted (16 items per 5 ticks per golem); a cut delivery stays at the chest and goes on next window. Picking up item entities is not counted. See [../architecture/transfer-limit.md](../architecture/transfer-limit.md).

## Unreachable items (give-up rule)

Transient per-golem state in `CleanerLogic:State`: current `target` UUID, `bestDist` (squared), `stuck` ticks and a
`skip` map UUID -> game time until which the scan ignores that entity.

- `CleanerLogic:chase` runs every tick while walking to an item: distance shrinking by more than 0.1 (squared)
  resets `stuck`; otherwise `stuck` counts up and at 200 ticks (10 s) the golem gives up.
- At each repath `getNavigation().createPath(target, 0)` returning null does not give up: the golem just keeps the
  current navigation and retries at the next repath. The 200-tick no-progress timeout above decides.
- Give-up (`CleanerLogic:skip`): the UUID is ignored for 1200 ticks (1 min); expired entries are purged each scan.
  The golem then picks another item or, with a non-empty carry and no other candidate, delivers.
- A partial path that never gets within pickup range (item on a ledge above the feet; pickup range is measured
  from the feet) is covered by the 200-tick no-progress timeout, not by `isDone()`.
- Test hooks: `CleanerLogic:skipItem` and `CleanerLogic:isSkipped` (public, take golem, UUID, game time).

Why (null paths): a golem configured on its spawn tick is not on the ground yet and gets null paths for its first
ticks; skipping on the first null ignored the nearest items for a minute. Any transient failure (mid-air after a fall,
chunk edge) would do the same. `CleanerTest:pickupWorksFromSpawnTick` covers it.

Why: the scan used to pick the nearest candidate forever, so one item on a ledge, behind a wall or in a 1-block gap
kept the golem walking and it never delivered. ponytail: a no-progress timeout stands in for a real reachability
check; the cost is up to 10 s wasted per unreachable item (then 1 min ignored).

## Keep layout

With the per-golem Keep layout setting on ([keep-layout.md](keep-layout.md)): the delivery (`TransferBudget:insert`) merges into matching stacks of the output chest only, never into empty slots.
The scan also skips item entities the chest cannot take right now (`TransporterLogic:canInsert(chest, stack, true)`):
they stay on the ground. Items already carried that the chest refuses (it changed after the pickup) stay with the golem
and the full-chest handling (retry every `RETRY_TICKS`) applies; they are never dropped.

Why: undeliverable types used to be picked up anyway and piled up in the carry until all 9 slots were full, after which
the golem could collect nothing. Why not drop refused carry back (the Transporter's return to A): the chest is often
outside the area, and items dropped there would never be collected again and despawn; keeping them costs only a slot.
Why only with Keep layout: with it off, a chest that is merely full is the old full-chest case, and holding the items
keeps them from despawning. Tests: `keepLayoutDeliversOnlyOntoMatchingStacks` (diamonds stay on the ground),
`keepLayoutLeavesUndeliverableTypes` (nine refused types nearer than the cobblestone, all left lying).


## Tests (`CleanerTest`)

- `cleansItemsInsideArea`: 4x4 area in one half of the floor, 4 stacks inside (cobblestone 64+20, logs 10,
  diamonds 3), 2 stacks outside; succeeds when the chest holds exactly the inside counts, no item entity is left
  in the box and the outside entities are unchanged. maxTicks 1200.
- `areaLimit16`: AREA spec has maxSize 16; `areaFits` true for 16x16, false for 17x16 and 16x17; `box` height
  is Y range + 2 and null when unset.
- `opensOutputChestWhenDelivering`: the lid is seen open only while the golem is on a 1-block stand position, and is closed at the end.
- `pickupWorksFromSpawnTick`: golem configured on the spawn tick, diamonds 3+ blocks away (pathing needed) must reach the chest.
