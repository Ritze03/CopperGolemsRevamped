# Testing

GameTests live in the `gametest` source set (`src/gametest`, test mod id `coppergolemsrevamped-test`),
registered through the `fabric-gametest` entrypoint list in `src/gametest/resources/fabric.mod.json`.
`./gradlew build` runs them via `check` (`runGameTest`). Fabric GameTest API reference:
[vanilla-copper-golem.md](vanilla-copper-golem.md) section 10. The client GameTest (below) is
not part of `check`.

## Tests

- `TransporterTest:transporterMovesEverything`: 12 stacks (two item types) in chest A, golem spawned
  with `helper.spawn` (AI runs), transporter profession + `input`/`output` assignments; succeeds when A is
  empty and B holds exact counts. `maxTicks = 2400` (a run needs ~1200 ticks, so 2x headroom).
- `GolemDataTest:golemDataSurvivesSaveLoad`: full entity round trip
  (`Entity#saveWithoutId` into `TagValueOutput`, then `Entity#load` of a fresh golem from `TagValueInput`).
  Proves `GolemData` is a persistent attachment, not just that its codec works.
- `GolemDataTest:assignAreaHelpers`: pure `AssignMode:areaFits` / `AssignMode:inRange`.
- `ChestSorterTest:visitedLimitScalesWithWaxedBlocks`, `mixinAppliesLimit`, `waxedSlotFilter`: Chest Sorter
  limit scaling, the mixin applying it, and the waxed-slot filter (see [chest-sorter.md](../features/chest-sorter.md)).
- `CleanerTest:cleansItemsInsideArea`, `areaLimit16`, `partialPickupConservesItems`, `borderItemIgnored`,
  `skipRuleExpires`, `deliversWhenOnlySkippedItemsRemain`, `unreachableItemDoesNotBlock`,
  `pickupWorksFromSpawnTick`: Cleaner pickup/delivery, area limit, item conservation, border and give-up rules, and
  configuring on the spawn tick (see [cleaner.md](../features/cleaner.md)).
- `CrafterTest:craftsSticksFromTemplate`, `noCraftWithoutTable`, `templateDropsOnProfessionChange`,
  `bufferDropsOnProfessionChange`, `noCraftWhenTableReplaced`, `deliversBufferWhenTemplateCleared`,
  `templateSlotHoldsOne`: Crafter loop, drops and menu slot rules (see [crafter.md](../features/crafter.md)).

Total: `@GameTest` methods counted with `grep -rc '@GameTest' src/gametest` (TransporterTest 1, GolemDataTest 2,
ChestSorterTest 3, CleanerTest 8, CrafterTest 7 = 21), so the log says `All 22 required tests passed` (see Gotchas).

## Client tests (headless sway only)

`GolemGuiClientTest` (`fabric-client-gametest` entrypoint, `enableClientGameTests = true` in
`build.gradle`) launches the real client, creates a singleplayer world, spawns a golem next to the
player, opens `GolemMenuOpener:open` on the server thread and screenshots the chest menu six times:
no profession, Transporter with items and an assignment, the Transporter first control slot hovered (tooltip),
Chest Sorter (12 waxed copper blocks), Cleaner (area and output assigned) and Crafter (template, buffer, three assigns).
Window 1280x720, GUI scale 3. Screenshots land in `build/run/clientGameTest/screenshots/`.
Task: `runClientGameTest`. It is not wired into `check`/`build` (the `runGameTest` server task is).

**Every client launch runs inside its own headless sway, never on the real display.**
Why: user rule 2026-11-15, "Ingame test (if any) always using headless sway!".

```sh
RT=$HOME/tmp/cgr-sway-run; mkdir -p $RT; chmod 700 $RT; : > <scratch>/sway.conf
# private compositor, own runtime dir, nothing inherited from the real session
XDG_RUNTIME_DIR=$RT env -u WAYLAND_DISPLAY -u DISPLAY -u SWAYSOCK -u I3SOCK \
  WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_HEADLESS_OUTPUTS=1 WLR_RENDERER=pixman \
  ccorral -- sway -c <scratch>/sway.conf &
# a wayland-N socket and sway-ipc*.sock appear in $RT
export XDG_RUNTIME_DIR=$RT SWAYSOCK=$(ls $RT/sway-ipc*.sock) WAYLAND_DISPLAY=wayland-1
unset DISPLAY
swaymsg output HEADLESS-1 resolution 1280x720
env | grep -E 'DISPLAY|XDG_RUNTIME'   # verify: only the private ones
JAVA_HOME=<jdk-25> ccorral -- ./gradlew runClientGameTest
pkill -f "sway -c <scratch>/sway.conf"   # when done; leave nothing running
```

Kill only your own sway, by matching your private config/runtime path (as above). Never a broad `pkill sway`:
other projects run their own headless sways. `pkill -f` patterns can also match the invoking shell itself, so
keep the pattern specific (or kill by the PID of the sway you started).

Notes: the `wayland-N` number may differ (read it from `$RT`). With `DISPLAY` unset the client uses the
Wayland driver (SDL, GL via software rendering) and renders fine under pixman. Harmless log noise:
narrator (`flite`) and OpenAL device failures, `Illegal option value 0 for Anisotropic Filtering`.
If GL fails, try `WLR_RENDERER=gles2` or Xwayland of that sway; never fall back to the real display.
The sway "Failed to bind socket ... X0" error is harmless (another X server owns `:0`).

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
