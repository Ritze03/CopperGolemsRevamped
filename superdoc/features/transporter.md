# Transporter profession and follow mode

Transporter golems move everything from chest A (`input` assignment) to chest B (`output`
assignment), up to 9 full stacks per trip. Vanilla signatures behind this are in
[vanilla-copper-golem.md](../architecture/vanilla-copper-golem.md).

## Entry points

- `profession/transporter/TransporterProfession:init` declares it (id `coppergolemsrevamped:transporter`,
  slot group `carry` = 9 slots, max 64, `data.items` indices 0..8; assigns `input`, `output`;
  `runsVanillaBrain = false`; tick = `TransporterLogic::tick`).
- `profession/transporter/TransporterLogic:tick` is the work loop; static helpers below.
- `ai/FollowMode:tick` is follow mode.

## Work loop (`TransporterLogic:tick`, one call per server tick)

1. Cooldown running: decrement, return.
2. Either assignment missing, or either block no longer a container: stop navigation, retry in 40 ticks.
3. Target = A when carry is empty, else B.
4. Not in reach: `moveTo(target centre, speed 1.0)`, re-path every 20 ticks or when navigation is done.
   No stuck recovery, it keeps re-pathing.
5. In reach: stop, look at chest, then
   - carry empty: `takeAll(A, carry)`; nothing moved (A empty) -> wait 40 ticks; else wait 5.
   - carry non-empty: `insertAll(carry, B)`; leftovers stay in carry and mean B is full -> wait 40; else wait 5.
6. `GolemData.save` after each take/insert.

Reach (`TransporterLogic:inReach`): horizontal distance to chest centre <= 2.0 and
`chestY - golemBlockY` in [-2, +1] (vanilla reach: chest 2 blocks below to 1 above).

Why: navigation is driven by the profession itself because the vanilla brain does not tick for a
Transporter (`GolemTicker` skips it). `PathNavigation` is ticked in `Mob#serverAiStep` before
`customServerAiStep`, independent of the brain, so `moveTo` works while the brain is skipped.

## State

- Persistent: `data.items` (carry, survives reload) and `data.assignments`.
- Transient: cooldown and re-path timers in `TransporterLogic`'s `WeakHashMap<CopperGolem, State>`.
  After a reload the golem simply recomputes from carry and assignments.

## Item helpers (static, usable from a GameTest)

- `takeAll(Container src, NonNullList<ItemStack> carry): boolean` merges same-item stacks (normal max
  stack size), then fills empty slots; shrinks the source.
- `insertAll(NonNullList<ItemStack> carry, Container dst): boolean` same into a container
  (`canPlaceItem`, container max stack size respected); remainder stays in `carry`.
- `containerAt(Level, BlockPos): @Nullable Container`, `inReach(CopperGolem, BlockPos)`, `isEmpty(list)`.

Why a hand-written merge loop instead of `HopperBlockEntity.addItem(from, to, stack, dir)`: that
method has hopper side effects (cooldown, `from`-sided logic) and needs a source container; the
loop is small and deterministic.

## Double chests

`containerAt` uses `ChestBlock.getContainer(ChestBlock, BlockState, Level, BlockPos, boolean ignoreBlocked)`
with `ignoreBlocked = true`, which returns a combined container for double chests, so both halves
count. Non-chest blocks fall back to `level.getBlockEntity(pos) instanceof Container`.

## Not done

Chest open/close animation (`ContainerUser`) and showing the carried item in the golem's hand were
skipped: the vanilla empty-hand interaction throws the held item, which would duplicate carry.

## Follow mode (`ai/FollowMode:tick`)

GUI toggle sets `data.following` and `data.followPlayer`; the golem follows that player.
- Player not found in the golem's level (offline, other dimension, spectator): stand still, flag kept.
- Distance > 3: `moveTo(player, 1.0)`, path recomputed at most every 10 ticks.
- Distance <= 2: stop navigation and look at the player. No teleport.

Why: the profession pauses while following because `GolemTicker` calls `FollowMode.tick` instead of the
brain or the profession tick whenever `data.following` is set; nothing in the profession code
needs to know. The transient timers are not shared with the Transporter, so resuming starts clean.
