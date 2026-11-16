# Transfer limit

Every golem moves at most **16 items per 5 ticks** (64 per second) between itself and containers.
Code: `ai/TransferBudget`. Index: [../README.md](../README.md).

Why: user request ("all golems should have an item input/output limit of 16 per 5 ticks"). Chest visits are no longer
instant; a 256-item haul takes seconds, like a hopper line.

## The budget

- `TransferBudget.ITEMS_PER_WINDOW = 16`, `WINDOW_TICKS = 5`. A window is `gameTime / 5`; the budget refills to 16 when a
  new window starts. One budget per golem, take and insert together (a golem that inserts 16 cannot also take in that window).
- Transient: a `WeakHashMap` keyed by golem, never saved. A reload just starts with a full budget.
- API: `remaining`, `exhausted`, `spend(n)`, `waitTicks(level)` (a profession cooldown that wakes the golem on the first
  tick of the next window), and the budgeted wrappers `take` / `insert` around `TransporterLogic.takeAll` / `insertAll`
  (both take an optional `keepLayout` flag, see [../features/keep-layout.md](../features/keep-layout.md); both have a `limit` overload; the plain boolean versions are unlimited and used only for uncounted moves).

## What counts

Counted (spend budget): every item moved between a golem and a container block, both directions:
Transporter take/insert, Cleaner insert, Distributor fetch/distribute/return, Crafter fetch/deliver, Lumber and Farmer fetch (axe, tool, saplings, seeds,
bone meal) and delivery (including surplus to the input/seed chest), Smelter chest fetch/fuel fetch/deliver/return **and**
the moves between its buffers and the furnaces (feed, fuel, collect), Butcher delivery (carry and XP bottles), food and
weapon fetch. A tool swap counts as one item.

Not counted, on purpose:
- Item entities picked up from the ground (Cleaner pick-up, Lumber drops): not a container transfer.
- Harvest and kill drops captured straight into the carry (Lumber logs, Farmer crops, Butcher drops).
- Creating XP bottles (`GolemXp:fillSlot`), moves inside the golem (top-up of supply slots, crafting, XP store).
- Player clicks in the golem menu: the limit is golem-only.
- Chest Sorter: it runs vanilla's brain (one stack per trip, vanilla `TransportItemsBetweenContainers`), which we leave
  untouched. Why: the vanilla behaviour is its own pacing and the mod only sets the visited-chest limit there.

## A visit spans ticks (the main risk)

When the budget runs out mid-transfer the golem must not treat that as "chest full" or "chest empty": that would
trigger give-ups, skips, early returns or WAIT. The rule in every profession: after a budgeted call, if the budget is
used up **and** work remains, the visit is *cut short*: stay at the container, refresh the lid
(`Interact.openChest`), set the cooldown to `TransferBudget.waitTicks`, keep the step/phase unchanged, and run the same
step again next window. A transfer that ended with budget left is judged as before (full / empty).

Per profession:
- **Transporter**: `State.loading` keeps the golem at chest A until a take ends with budget left, so a partly loaded
  carry is not delivered early. Delivery just repeats until the carry is empty.
- **Cleaner**: delivery repeats; the pick-up scan is unchanged.
- **Crafter**: `State.sticky` (FETCH or DELIVER) overrides `decide` while that work remains; otherwise a half-filled
  input could make the golem walk to the table. `fetch` is limited per call, round-robin kept.
- **Lumber**: `deliverStep` does not set `inputTried` / `outputTried` while cut short (those are the one-visit-per-chest
  rule). `State.topUp` makes a cut supply fetch continue to its full target (64 saplings, 16 bone meal) instead of
  stopping above the low mark (`wantsFetch`).
- **Farmer**: same idea, `State.seedTopUp` (`SEED_TARGET` 64); `seedChestFull` is only set after a visit that was not cut.
- **Smelter**: the current `Work` stays (`State.pending`) and is not marked `served`; the FETCH / FUEL_FETCH plan is kept
  in `State.moves` so the even split over the furnaces survives the cut; `collect` pays the furnace XP with the last part;
  full-chest blocks (`outBlockedUntil` etc.) are not set for a cut visit.
- **Distributor**: `DistributorLogic` takes and inserts with an explicit per-call limit (`TransporterLogic.takeAll` / `insertAll` + `TransferBudget.spend`, so a single item type or share can be capped); the FETCH plan (`State.plan`) and the current inventory's quota (`State.quota`) survive a cut, the visit repeats next window and a cut visit never counts as "target full".
- **Butcher**: `deliver` stays in the DELIVER phase; `fetchFood` / `fetchWeapon` return busy (`atChest`) instead of false,
  because false means "nothing to fetch, move on".

## Tests

`TransferLimitTest`: a Transporter moves 256 items A to B with at most 16 per 5-tick window (chest totals sampled every
tick) and keeps A's lid open; a Smelter fetch of 64 ore for 8 furnaces completes with 8 per furnace. Other tests'
tick budgets were raised where chest visits got slower, see [testing.md](testing.md).
