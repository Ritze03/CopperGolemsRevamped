# Assign mode

An "Assign ..." button in the golem GUI arms the player's next block right-click(s) as that golem's
target. Server-side only; see the [vanilla copper golem reference](../architecture/vanilla-copper-golem.md)
for the golem API.

## Flow

1. GUI closes its menu, then calls `AssignMode.arm(player, golem, key)` (AREA: the spec's base key).
2. `arm` checks the key exists in the golem's current profession `assigns`; else message, no-op.
   Otherwise stores one `Pending` per player (arming again replaces it) and prints a prompt built by `ProfessionHelp:prompt` (what to click and what it is for, e.g. "Right-click two opposite
   corners of the area to clean, max 16x16, larger areas are capped").
3. Main-hand block right-clicks of that player are consumed (`InteractionResult.SUCCESS`, so the
   target block's own GUI never opens) and validated; off-hand events are ignored.
4. On success the position goes into `GolemData.assignments` and `GolemData.save` is called.
5. The golem walks to the target as visual confirmation: next to the block (nearest free horizontal neighbour), or once
   around the area outline (see Walk-to below).

## Validation per `Kind`

- `CHEST`: block is a `ChestBlock` (wooden, trapped and copper chests all are; `CopperChestBlock extends ChestBlock`).
- `CRAFTING_TABLE`: `Blocks.CRAFTING_TABLE`.
- `BLOCK`: any non-air block.
- `AREA`: two clicks on non-air blocks. A second corner that makes the footprint larger than `maxSize` is capped,
  not refused: `AssignMode:clampCorner` moves it toward the first corner on each axis (first + sign(d) * (max - 1), Y
  kept), the capped corner is stored and the player gets "Area capped to NxN (max)". Stored as `key_a`, `key_b`
  (written once the second corner is in). Other kinds store under `key`.
  The limit applies to X/Z only; Y is unbounded (the Cleaner adds 2 blocks above the higher corner, see
  [cleaner.md](cleaner.md)). Each profession's limit is its `AssignSpec` `maxSize`. `AssignMode:areaFits` is the pure
  size check (still used by tests and logic that re-validates stored areas).
  Why capped: user decision, "cap out at the max size ... rather than decline the area".
- Wrong block, out of range: message, stays armed.

## Walk-to (visual confirmation)

After a successful assignment `AssignMode:click` starts `ai/WalkTo` on the golem: a queue of stand positions run by
`GolemTicker:tick` before follow mode and the profession tick, until done. Chest/table/block: one stand position,
the standable horizontal neighbour of the block nearest to the golem (free feet and head space, sturdy floor), nothing
happens when there is none. Area: the 4 corners of the (capped) rectangle in order and back to the first
(`WalkTo:outline`), each on the highest standable position near the clicked Y range. Arrived = within 1 block
horizontally. Each waypoint has its own timeout (`WalkTo.TIMEOUT_TICKS` = 200) and is skipped when `moveTo` finds no
path, so an unreachable corner never blocks. Transient (weak map, not saved); a profession change cancels it.
Why: the user wanted the golem to show what it was just given ("walks right in front of it", "walk the outline once").

## Cancel / timeout / range

- Sneak + right-click on a block: cancel. This is a `UseBlockCallback`, so sneak-clicking air does nothing
  and the 30 s timeout still applies.
- Timeout 30 s (600 ticks, `AssignMode.TIMEOUT_TICKS`), checked on click and every server tick (the
  player gets "Assignment timed out."). The first AREA click restarts the timer.
- Range: golem position to block centre <= 32 blocks (`AssignMode.MAX_RANGE`), for each AREA corner.
- Cancelled automatically if the golem is dead, removed or in another dimension, or if its profession
  id differs from arming time.
- Pending state is dropped on player disconnect and cleared on `ServerLifecycleEvents.SERVER_STOPPING`
  (the static map holds strong golem references, which must not leak into another world).

## Entry points

- `AssignMode:init` registers `UseBlockCallback.EVENT`, `ServerTickEvents.END_SERVER_TICK`,
  `ServerPlayConnectionEvents.DISCONNECT`, `ServerLifecycleEvents.SERVER_STOPPING`.
- `AssignMode:arm`; pure helpers `AssignMode:areaFits`, `AssignMode:clampCorner`, `AssignMode:inRange`, `AssignMode:matches`.

## Why

- 32 blocks = the vanilla copper golem container search radius; farther targets would not match
  what a golem can find with vanilla logic.
- Server-side only: the server holds all state. The server consuming the click means the chest menu
  never opens; the client only plays the arm swing.
