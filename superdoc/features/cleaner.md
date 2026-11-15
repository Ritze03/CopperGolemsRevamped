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
2. An item entity in the box that is alive, has no pickup delay and (partly) fits the carry: path to the nearest;
   within 1.5 blocks, move as much as fits into carry and discard the entity or leave the remainder on it
   (no duplication). Cooldown 5 ticks.
3. Else carry non-empty: go to the output chest (`TransporterLogic:inReach`) and `TransporterLogic:insertAll`.
   Leftovers (chest full) stay and are retried every 40 ticks.
4. Else idle, rescan every 20 ticks.

Only entities inside the box are ever touched. Pickup reuses `TransporterLogic:takeAll` (via a one-slot
`SimpleContainer`), `insertAll`, `containerAt`, `inReach`, `isEmpty`; nothing is copied.
Items with a pickup delay are skipped so freshly dropped/thrown items are not grabbed mid-air.

## Tests (`CleanerTest`)

- `cleansItemsInsideArea`: 4x4 area in one half of the floor, 4 stacks inside (cobblestone 64+20, logs 10,
  diamonds 3), 2 stacks outside; succeeds when the chest holds exactly the inside counts, no item entity is left
  in the box and the outside entities are unchanged. maxTicks 1200.
- `areaLimit16`: AREA spec has maxSize 16; `areaFits` true for 16x16, false for 17x16 and 16x17; `box` height
  is Y range + 2 and null when unset.
