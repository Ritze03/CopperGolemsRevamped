# Golem safety

Ways a golem used to leave its workplace without a trace, and what the mod does about each. Context: on a live server
golems "randomly disappeared"; an audit found these paths. User decision: "Fix all of these. Stop oxidation entirely."
(oxidation itself is in [oxidation.md](oxidation.md); portals and the Transporter far-walk in
[chunk-loading.md](chunk-loading.md) and [transporter.md](transporter.md)).

## Removal log line

`chunk/GolemChunkLoader:logRemoval`, called from the `ServerEntityEvents.ENTITY_UNLOAD` handler in `init` for a copper
golem whose `RemovalReason` does not save (killed, discarded, changed dimension; a plain chunk unload is silent). INFO
line: name, UUID, profession, dimension, block position, reason. Why: at the next disappearance the log says what
happened (a statue conversion, a portal, a kill) instead of nothing. A golem without mod data logs `profession=none`
and no attachment is created.

## Chest Sorter home

The Chest Sorter runs the vanilla brain (`TransportItemsBetweenContainers`), which searches around the golem's current
position. With no copper chest in range it strolls at random, and our chunk ticket keeps it ticking, so it drifted off
for good. `GolemData:home` (a `GlobalPos`, lenient optional codec field `home`) is set to the golem's position in
`GolemService:changeProfession` when the Chest Sorter is chosen (cleared for other professions).
`ChestSorterProfession:walkHome`, called by `GolemTicker` before the vanilla brain tick: beyond 24 blocks horizontal
or 8 vertical from home the golem walks back (navigation, no teleport) and the vanilla brain is skipped that tick. A
golem with no home (old save) or a home in another dimension gets its current position as home on the first tick.
- Why hysteresis: the walk continues until the golem is within 12 blocks, otherwise the brain would stroll it out
  again at once.
- Why legs of 14 blocks: a path request to a target 40 blocks away finds no path; each leg is a waypoint toward home.
- Why the current position, not the nearest copper chest: simplest, and the sorter is placed where it should work.

## Follow auto-off

`ai/FollowMode:tick`: if the followed player is absent (offline, another dimension, spectator, or farther than 32
blocks) for 600 ticks (30 s) in a row, `GolemData.following` becomes false and the golem resumes work. The counter is
transient and resets whenever the player is present. Why: a golem following an offline player stood idle forever, held
loaded by its ticket. `followPlayer` is left as it is.

## Dismount

`core/GolemTicker:tickUnsafe`, first thing: a golem with a profession that is a passenger (a minecart or boat picked it
up) calls `stopRiding()`. Why: a ridden golem does not walk, so it was carried off and stuck.

## Step out of a block

`core/GolemTicker:stepOut`, checked every 20 ticks per golem (staggered by entity id) for all copper golems when
`isInWall()`. Golems take no non-player damage ([health.md](health.md)), so one that a tree, melon or pumpkin grew
into stayed hidden inside the block. It is moved (`snapTo`, navigation stopped) to the nearest position within 2
blocks, same Y first, then 1 up, where a golem-sized box has no collision and the block below has a collision shape.
Nothing found: it stays and is checked again.

## Tests

`GolemSafetyTest` (3): `followTurnsOffWhenPlayerAbsent` (still on at tick 300, off by 800),
`workingGolemDismounts` (a minecart), `golemInsideBlockStepsOut`. `ChestSorterTest`: `sorterWalksBackHome` (home 40
blocks away; the test's own 8x8 floor is all that exists since neighbouring tests share the world, so it asserts the
golem walked more than 4 blocks toward home), `sorterWithoutHomeGetsOne`. `OxidationTest:golemsNeverOxidize`. The removal
log line has no test.
