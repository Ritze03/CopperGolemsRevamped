# Freeze

A per-golem switch on the Settings tab, for every profession including None and Chest Sorter: a frozen golem stops all
work and stands still, so a player can safely change its inventory (user request: "All professions should get a
setting for 'Freeze', so the golem stops doing anything and the user can modify its inventory and such").

## Where

- Toggle: Settings tab (3,5), `blue_ice` via `GolemIcon:plain`, glint while on, name "Freeze: on/off", lore "Golem stops
  all work and stands still" / "Use it to safely change its inventory". Click: `menu/GolemMenu:toggleFreeze`. Follow sits at
  (3,3). See [gui.md](gui.md).
- State: `core/GolemData:frozen`, persisted as the optional codec field `frozen` (default false, so old saves load
  unfrozen), saved like `following`.
- Effect: `core/GolemTicker:tick`.
- Status tab: `profession/ProfessionStatus:doing` returns "Frozen", `next` returns "Frozen: unfreeze it in Settings".

## Behaviour while frozen

After the chunk ticket and the hand display are handled (both stay as they are, a frozen golem keeps its chunk loaded
and its hand display), `GolemTicker:tick` does `getNavigation().stop()`, `ai/Interact:closeChest` and returns. So:

- no profession tick, no vanilla brain (Chest Sorter too), no follow mode, no `WalkTo:tick`;
- navigation is stopped every tick, the golem stands still (tests allow 0.5 blocks);
- a lid the golem holds open is closed at once (`closeChest` is what the lid sweep itself calls);
- phase and transient state are kept: profession loops are stateless or keep their counters in transient maps that simply
  stop being advanced, and a pending `WalkTo` stays queued, so unfreezing resumes where it stopped.

Why return before `WalkTo`: a "Show" walk is also movement, and the user wants the golem still.

## Rules chosen (simple, one each)

- **Follow:** freezing turns Follow off (`following = false`, `followPlayer = null`). Turning Follow on while frozen is
  refused with the chat message "Unfreeze the golem first." Why: two states that both own the golem's movement would need a
  priority rule; refusing is the simplest and the message says what to do.
- **Profession change clears Freeze** (`GolemService:changeProfession`). Why: a new profession should start working, and
  the change already hands out the items, so there is nothing left to protect.
- Death, statue conversion and the profession confirm screen are unaffected.

## Tests (`FreezeTest`, `MenuTest:settingsFreezeToggle`)

`frozenTransporterMovesNothing` (full Transporter setup, 200 ticks: chests unchanged, nothing picked up, position within
0.5 blocks), `unfreezingResumesTheWork`, `freezingClosesAnOpenLidAndStops`, `frozenSurvivesSaveLoad` (full entity
save/load, also unfrozen stays unfrozen), `frozenChestSorterDoesNotSort` (and sorts after unfreeze).
`settingsFreezeToggle`: toggle position and look, Follow turns off, Follow refused, Status text, profession change clears it.
