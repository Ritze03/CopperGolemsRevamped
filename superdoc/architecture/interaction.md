# Interaction helper (`ai/Interact`)

Shared by every profession: where a golem stands to touch a block, and the visible chest lid. A profession
only calls two methods, `Interact.approach` while walking and `Interact.openChest` when it moves items.
Adopted by every profession that moves items or walks to a block: Transporter, Cleaner, Crafter, Farmer and Lumber
(chests and, for the non-chest targets, `approach` only); see "Adopting". The old 2-block `TransporterLogic:inReach`
is gone; the 2-block rule survives only as the `nearReach` fallback.

## Reach rule

User rule: golems get within 1 block of what they interact with, 2 blocks only if that is impossible. Lumber reaches up the
trunk from the ground, so the break check for a tree log is its own horizontal rule (`LumberLogic:breakAllowed`,
`LumberLogic:REACH_H`); the walking still goes through `approach`. Target P, golem feet block F, offset = F - P. A stand position is one of:

| Stand spot (offset) | Meaning |
| --- | --- |
| 4 orthogonal neighbours at P.y (dy 0) | normal case |
| 4 orthogonal neighbours at P.y-1 | target diagonally one block up |
| 4 orthogonal neighbours at P.y+1 | target diagonally below (golem on a step); ranks lower |
| P.above() (dy +1, same x/z) | golem stands on the target, interacts below it; ranks lower |

Not stand spots: the 4 horizontal diagonals (that is 2 blocks away).

A spot is valid when `Interact:canStand` holds (bounding box free of collision, solid support below; checked with
the golem's real `getBbWidth/Height`) and navigation can finish a path to it (`createPath(spot, 0)`, `Path#canReach`).
Standing on a chest is allowed (vanilla golems do too: its collision shape is not empty).

## API

All static, `com.coppergolemsrevamped.ai.Interact`.

- `boolean approach(ServerLevel, CopperGolem, BlockPos target, Interact.Approach st, double speed)`: call once per
  tick while the golem should be at `target`. Returns true when the golem is in reach and standing still (navigation
  stopped): do the work now. Otherwise it drives navigation and returns false. It does not set a look target.
  - `Interact.Approach` is the per-golem walk state (one field in the profession's transient `State`, create it
    with `new Interact.Approach()`). It resets itself when `target` changes. Call `st.reset()` after the work at the
    target is finished (only on success; on "nothing to do, retry later" keep it, so a fallback stays in force).
  - Fallback: if no reachable 1-block spot is found 3 repaths in a row (60 ticks), or the golem makes no progress
    towards the chosen spot for 60 ticks (that spot is blacklisted, the next one is tried), `approach` switches to the
    legacy behaviour for that target: walk to the block centre and accept `nearReach` (horizontal <= 2.0 to the
    centre, target 2 below to 1 above the feet). `st.isLegacy()` reports it.
- `boolean inReach(CopperGolem, BlockPos target)`: strict 1-block rule (feet on one of the spots above).
- `boolean nearReach(CopperGolem, BlockPos target)`: the 2-block fallback rule.
- `@Nullable BlockPos standPos(ServerLevel, CopperGolem, BlockPos target)`: best reachable 1-block spot, nearest to
  the golem first; null if none. One path search per candidate, so call at repath rate (`approach` does).
- `boolean canStand(ServerLevel, CopperGolem, BlockPos feet)`.
- `void openChest(ServerLevel, CopperGolem, BlockPos chest, Interact.Action)`: open the lid now, close it later
  (below). `Action.TAKE` sets state `GETTING_ITEM`, `Action.INSERT` sets `DROPPING_ITEM`. No-op if the block is not a
  container. Calling again for the same chest only extends the timer.
- `void closeChest(CopperGolem)`, `boolean isOpen(CopperGolem)`; `int OPEN_TICKS = 20`.

Why `approach` owns the whole walk and not just a position: every caller had the same copy-pasted
"repath every 20 ticks / when done" block, and the fallback needs timers and a blacklist, which belong with the walk
state. A caller shrinks to one `if (!approach(...)) return;`.

Why a no-progress timeout and a "3 nulls in a row" rule: the same reasoning as the Cleaner give-up rule (see
[../features/cleaner.md](../features/cleaner.md)): `createPath` returns null for a few ticks on the spawn tick or
mid-air, which must not trigger the fallback; a golem that cannot actually get there must not walk against a wall forever.

## The chest lid

`openChest` does what vanilla's `TransportItemsBetweenContainers` interaction does (see
[vanilla-copper-golem.md](vanilla-copper-golem.md) section 4): `golem.setOpenedChestPos(pos)`, then
`container.startOpen(golem)` on the block entity at `pos`, then the golem state. The block entity's
`ContainerOpenersCounter` plays the open sound and drives the lid (and a barrel's `OPEN` blockstate). For a double
chest only the half at `pos` is opened; the renderer takes the larger lid openness of the two halves.

Close: a server-tick hook (`ServerTickEvents.END_SERVER_TICK`, registered lazily on the first `openChest`, so no
profession has to call anything per tick and nothing needs registering at startup) closes the lid when the
golem's timer ran out (`OPEN_TICKS` = 20 ticks after the last `openChest`), the golem is farther than 3 blocks from the
chest, it is dead or removed, it changed dimension, or it opens a different container. `SERVER_STOPPING` closes all.
Closing is `stopOpen` then `clearOpenedChestPos` then state `IDLE`.

Why the opener count cannot leak:
- `ContainerOpenersCounter#recheckOpeners` (a block tick scheduled every 5 ticks while count > 0) recounts the
  entities near the chest whose `ContainerUser#hasContainerOpen` is true and overwrites `openCount` with that number.
  `CopperGolem#hasContainerOpen` is true only while `openedChestPos` equals the chest (or its double-chest partner),
  which is why `setOpenedChestPos` is mandatory and why a removed or dead golem is dropped from the count by itself.
- `decrementOpeners` decrements blindly, so `Interact` only calls `stopOpen` when `getEntitiesWithContainerOpen()` still
  lists the golem (a recheck may have dropped it already); otherwise the counter would go negative.
- The counter is not saved, so an unload or stop cannot leave a chest "open" (a barrel's blockstate is fixed by the
  first recheck/`SERVER_STOPPING`).

Why a global map in `Interact` and not per-profession state: the close must outlive a profession change and a
profession tick that stops running (cooldown, profession swapped), and every profession needs identical handling.

Behaviour contract for callers: the lid stays open `OPEN_TICKS`, so after a successful move set the profession
cooldown to `Interact.OPEN_TICKS` (Transporter and Cleaner do) so the golem stands there while it is open and the
animation state plays; do not open on a failed take/insert (empty source, full target), or an idle golem would
open and close the chest every retry.

A transfer cut short by `TransferBudget` (see [transfer-limit.md](transfer-limit.md)) keeps the lid open the same way: call `openChest` again and wait `TransferBudget.waitTicks`, instead of `OPEN_TICKS`.

## Adopting it in a profession

1. Add `final Interact.Approach walk = new Interact.Approach();` to the profession's `State`.
2. Replace a hand-written `if (!inReach(...)) { moveTo...; return; }` with
   `if (!Interact.approach(level, golem, pos, s.walk, SPEED)) return;`, then `setLookAt` as before.
3. After a take/insert that moved items: `Interact.openChest(level, golem, chestPos, Interact.Action.TAKE|INSERT)`,
   cooldown `Interact.OPEN_TICKS`, and `s.walk.reset()`.
4. For non-chest targets (crops, crafting table, the tree to plant at) use `approach` only. Lumber additionally
   checks `LumberLogic:breakAllowed` before breaking a log (see [../features/lumber.md](../features/lumber.md)).

Callers: `TransporterLogic:tick`, `CleanerLogic:tick`, `CrafterLogic:tick`/`CrafterLogic:walkTo`, `FarmerLogic:tick`,
`LumberLogic:tick`/`LumberLogic:breakBlock`.

Tests: `TransporterTest` (`standPosPicksNearestOneBlockSpot`, `reachRules`, `openChestClosesAfterTimer`,
`killedGolemDoesNotLeakOpener`, `walkingAwayClosesChest`, `transporterStandsCloseAndOpensLids`,
`walledOffChestUsesTwoBlockFallback`), `CleanerTest:opensOutputChestWhenDelivering`,
`CrafterTest:lidsOpenAndReachIsOneBlock`, `FarmerTest:seedsGoToSeedChestRestToOutput` (lid opened and closed),
`FarmerTest:axeHarvestsFruitLeavesStems` (1-block reach at each harvest), `LumberTest:walledInTrunkIsFelledFromTwoBlocks`
(2-block fallback). The `Interact` unit tests live in `TransporterTest` because a new test class must be listed in
`src/gametest/resources/fabric.mod.json`.
