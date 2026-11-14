# Testing

GameTests live in the `gametest` source set (`src/gametest`, test mod id `coppergolemsrevamped-test`),
registered through the `fabric-gametest` entrypoint list in `src/gametest/resources/fabric.mod.json`.
`./gradlew build` runs them via `check` (`runGameTest`). Fabric GameTest API reference:
[vanilla-copper-golem.md](vanilla-copper-golem.md) section 10.

## Tests

- `TransporterTest:transporterMovesEverything`: 12 stacks (two item types) in chest A, golem spawned
  with `helper.spawn` (AI runs), transporter profession + `input`/`output` assignments; succeeds when A is
  empty and B holds exact counts. `maxTicks = 2400` (a run needs ~1200 ticks, so 2x headroom).
- `GolemDataTest:golemDataSurvivesSaveLoad`: full entity round trip
  (`Entity#saveWithoutId` into `TagValueOutput`, then `Entity#load` of a fresh golem from `TagValueInput`).
  Proves `GolemData` is a persistent attachment, not just that its codec works.
- `GolemDataTest:assignAreaHelpers`: pure `AssignMode:areaFits` / `AssignMode:inRange`.

## Gotchas

- **The reported count is our tests + 1.** Vanilla ships a required test instance
  `minecraft:always_pass` (data pack `test_instance/always_pass.json`) and the Fabric runner runs every
  registered instance, so with N `@GameTest` methods the log says `All N+1 required tests passed`.
  It is not a double registration of our entrypoint.
- The default structure `fabric-gametest-api-v1:empty` is 8x8x8 of air with no floor. Tests that need
  ground place their own (`helper.setBlock` stone at y=0); keep chests within x/z 0..7.
- Entity type constants are in `EntityTypes` (e.g. `EntityTypes.COPPER_GOLEM`), not `EntityType`.
- `inRange` measures to the block centre, so a block exactly 32 away is out of range.

## Why

Why the save/load test goes through a real entity instead of encoding `GolemData.CODEC` directly:
a codec round trip passes even if the attachment was registered without `persistent(...)`; only the
entity path catches that.
