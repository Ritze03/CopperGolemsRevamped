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
2. An item entity in the box (its centre inside, see below), alive, no pickup delay, (partly) fits the carry and not
   skipped: path to the nearest;
   within 1.5 blocks, move as much as fits into carry and discard the entity or leave the remainder on it
   (no duplication). Cooldown 5 ticks.
3. Else carry non-empty: go to the output chest (`TransporterLogic:inReach`) and `TransporterLogic:insertAll`.
   Leftovers (chest full) stay and are retried every 40 ticks.
4. Else idle, rescan every 20 ticks.

When only skipped items remain, step 3 delivers the carry (see "Unreachable items").

Only entities whose centre is inside the box are ever touched: `getEntitiesOfClass` matches any bbox that
*intersects* the box, so the filter adds `box.contains(e.position())` (otherwise an item up to ~0.125 outside the
edge was collected). Pickup reuses `TransporterLogic:takeAll` (via a one-slot
`SimpleContainer`), `insertAll`, `containerAt`, `inReach`, `isEmpty`; nothing is copied.
Items with a pickup delay are skipped so freshly dropped/thrown items are not grabbed mid-air.

## Unreachable items (give-up rule)

Transient per-golem state in `CleanerLogic:State`: current `target` UUID, `bestDist` (squared), `stuck` ticks and a
`skip` map UUID -> game time until which the scan ignores that entity.

- `CleanerLogic:chase` runs every tick while walking to an item: distance shrinking by more than 0.1 (squared)
  resets `stuck`; otherwise `stuck` counts up and at 200 ticks (10 s) the golem gives up.
- At each repath `getNavigation().createPath(target, 0)` returning null gives up immediately.
- Give-up (`CleanerLogic:skip`): the UUID is ignored for 1200 ticks (1 min); expired entries are purged each scan.
  The golem then picks another item or, with a non-empty carry and no other candidate, delivers.
- A partial path that never gets within pickup range (item on a ledge above the feet; pickup range is measured
  from the feet) is covered by the 200-tick no-progress timeout, not by `isDone()`.
- Test hooks: `CleanerLogic:skipItem` and `CleanerLogic:isSkipped` (public, take golem, UUID, game time).

Why: the scan used to pick the nearest candidate forever, so one item on a ledge, behind a wall or in a 1-block gap
kept the golem walking and it never delivered. ponytail: a no-progress timeout stands in for a real reachability
check; the cost is up to 10 s wasted per unreachable item (then 1 min ignored).

## Tests (`CleanerTest`)

- `cleansItemsInsideArea`: 4x4 area in one half of the floor, 4 stacks inside (cobblestone 64+20, logs 10,
  diamonds 3), 2 stacks outside; succeeds when the chest holds exactly the inside counts, no item entity is left
  in the box and the outside entities are unchanged. maxTicks 1200.
- `areaLimit16`: AREA spec has maxSize 16; `areaFits` true for 16x16, false for 17x16 and 16x17; `box` height
  is Y range + 2 and null when unset.
