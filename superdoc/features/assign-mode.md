# Assign mode

An "Assign ..." button in the golem GUI arms the player's next block right-click(s) as that golem's
target. Server-side only; see the [vanilla copper golem reference](../architecture/vanilla-copper-golem.md)
for the golem API.

## Flow

1. GUI closes its menu, then calls `AssignMode.arm(player, golem, key)` (AREA: the spec's base key).
2. `arm` checks the key exists in the golem's current profession `assigns`; else message, no-op.
   Otherwise stores one `Pending` per player (arming again replaces it) and prints a prompt.
3. Main-hand block right-clicks of that player are consumed (`InteractionResult.SUCCESS`, so the
   target block's own GUI never opens) and validated; off-hand events are ignored.
4. On success the position goes into `GolemData.assignments` and `GolemData.save` is called.

## Validation per `Kind`

- `CHEST`: block is a `ChestBlock` (wooden, trapped and copper chests all are; `CopperChestBlock extends ChestBlock`).
- `CRAFTING_TABLE`: `Blocks.CRAFTING_TABLE`.
- `BLOCK`: any non-air block.
- `AREA`: two clicks on non-air blocks; footprint `|ax-bx|+1` and `|az-bz|+1` each <= `maxSize`,
  else "Area too large" and the player stays armed for the second corner. Stored as `key_a`, `key_b`
  (written only when both corners are valid). Other kinds store under `key`.
- Wrong block, out of range, area too large: message, stays armed.

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
- `AssignMode:arm`; pure helpers `AssignMode:areaFits`, `AssignMode:inRange`, `AssignMode:matches`.

## Why

- 32 blocks = the vanilla copper golem container search radius; farther targets would not match
  what a golem can find with vanilla logic.
- Server-side only: the server holds all state. The server consuming the click means the chest menu
  never opens; the client only plays the arm swing.
