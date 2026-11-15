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
5. After a chest/table/block assign the golem walks next to the block as visual confirmation (see Walk-to below).
   An area assign only stores the area and confirms in chat.

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
`GolemTicker:tick` before follow mode and the profession tick, until done. Chest/table/block (`WalkTo:toBlock`, also
the menu's Show button): driven by `Interact:approach`, the same walk the professions use (1-block stand position,
2-block fallback, see [../architecture/interaction.md](../architecture/interaction.md)); it ends only when `Interact`
counts the golem in reach, or after `WalkTo.BLOCK_TIMEOUT_TICKS` (600). Why: the old "within 1 block of a stand
position" check stopped the golem too early (user: "it stops too early", should be the transporter's algorithm).
Area assigns start no walk. `WalkTo:outline` (4 corners of the rectangle in order and back
to the first, each on the highest standable position near the clicked Y range) is kept only for the menu's Targets-tab
"Show" button. Outline arrived = within 1 block
horizontally. Each waypoint has its own timeout (`WalkTo.TIMEOUT_TICKS` = 200) and is skipped when `moveTo` finds no
path, so an unreachable corner never blocks. After the final arrival (block in reach, or the outline's last corner) the golem stands still for
`WalkTo.HOLD_TICKS` (60 = 3 s): navigation stopped, looking at the block centre, and only then does the walk end and the
profession resume. A walk that gives up or times out does not hold. Freeze still wins (`GolemTicker` returns early).
Why hold: the user asked for it ("he should stay there for 3 seconds") so the shown target is clear.
Transient (weak map, not saved); a profession change cancels it.
Why: the user wanted the golem to show what it was just given ("walks right in front of it"). The automatic area
outline walk was removed at the user's request ("remove the walking around the marked area thingy again").

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
