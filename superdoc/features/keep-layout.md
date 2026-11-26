# Keep layout

Two per-golem settings on the Settings tab, available for **every profession** (both default off), so a chest a human
laid out by hand (placeholder items of 1, one slot per item type) keeps its layout:

- **Keep layout (taking)**, `GolemData:keepLayoutTake`: the golem never empties a stack it takes from.
- **Keep layout (storing)**, `GolemData:keepLayoutStore`: the golem never puts anything into an empty slot.

They are independent: either, both or neither.

Why: user request, "a setting for all golems, that never empties a stack. They should always leave one of everything
behind. Empty slots should never be used at all with this mode on. So they respect layouts setup by humans." The
motivating case is a large chest pre-laid-out in rows (one row of sticks, one of dark oak logs), most slots holding a
single placeholder item. Why two settings: user request, split into taking and storing so a chest's layout can be kept
on one side only; an old "on" turns both on so nothing changes for existing golems.

## The rules

For every container a golem takes from or puts into:

- **Taking (on):** never lower a slot below 1 item: at most `count - 1` leaves each slot; slots holding 1 are skipped.
- **Storing (on):** only merge into **non-empty** slots holding the same item and components
  (`ItemStack.isSameItemSameComponents`), up to the max stack size. Nothing goes into an empty slot. If nothing fits,
  the container counts as full and the existing full/blocked handling of the profession applies.

### Exemptions (both settings)

- **Unstackable items (max stack size 1)**, e.g. tools and weapons: taken and returned normally, empty slots allowed.
  Why: a tool chest slot holding one axe could otherwise never be used, which would break Lumber, Farmer and Butcher
  (they fetch and return tools). The Chest Sorter still picks the destination chest by vanilla's rule for them (see below).
- **Furnace slots** (`AbstractFurnaceBlockEntity`: furnace, blast furnace, smoker): the Smelter's feed and collect.
  Why: machine slots, not a human layout. The Smelter's chests do follow the rules.

## Where

- State: `core/GolemData:keepLayoutTake` and `keepLayoutStore`, codec keys `keep_layout_take` and `keep_layout_store`
  (lenient optional, default false). Kept over a profession change (like Follow), not refused while frozen (plain settings).
- **Migration:** old saves have the single key `keep_layout`. The codec reads it only as a fallback: if a new key is
  present it wins, otherwise the field takes the old value (old `true` loads both as on, `false` or absent both as off).
  The old key is never written again (its getter returns empty). Test: `GolemDataTest:legacyKeepLayoutMigrates`.
- Toggles: Settings tab, (2,3) taking (`item_frame`) and (2,5) storing (`glow_item_frame`), both via
  `menu/GolemIcon:plain`, glint while on, names "Keep layout (taking): on/off" and "Keep layout (storing): on/off",
  `menu/GolemMenu:toggleKeepLayoutTake` / `toggleKeepLayoutStore`. See [gui.md](gui.md).
- The rules are implemented once, in `profession/transporter/TransporterLogic`: `takeAll(src, carry, limit, accept, keepLayout)`,
  `insertAll(carry, dst, limit, keepLayout)` and the private `addTo` (the `layoutRule` check holds both exemptions),
  plus the helpers `takeable`, `insertStack`, `canInsert`, `acceptsAny`. Each function has one boolean; the take functions
  (`takeAll`, `takeable`) receive the **taking** flag, the insert and accept functions (`insertAll`, `insertStack`,
  `canInsert`, `acceptsAny`) the **storing** flag. The budgeted wrappers `ai/TransferBudget:take(..., accept, keepLayout)`
  and `TransferBudget:insert(..., keepLayout)` pass it on, see [../architecture/transfer-limit.md](../architecture/transfer-limit.md).
  The older overloads without the flag keep the old behaviour (flag off).
- A profession picks the rules up by passing `data.keepLayoutTake` to take calls and `data.keepLayoutStore` to insert calls.
  "Don't take what the destination can never accept" checks are about the destination, so they follow **storing**,
  while "leave 1 in the slot" follows **taking**:

| Call site | Taking | Storing |
|---|---|---|
| Transporter (`TransporterLogic:tick`) | `TransferBudget.take` from A | `TransferBudget.insert` into B or back to A; `canInsert(B, ...)` filter on the take; `acceptsAny` |
| Cleaner (`CleanerLogic`) | - (takes from the ground) | `insert` into the chest; skips pickups `canInsert` refuses |
| Crafter (`CrafterLogic`) | `chestHas`, `takeable` in fetch / `canFetch` | `insertAll` into the output chest |
| Smelter (`SmelterLogic`) | `takeable` / `removeFrom` of ore and fuel | `TransferBudget.insert` (moveOut) |
| Farmer (`FarmerLogic`) | `seedSlot`, `takeable` (seeds, XP bottles) | `insert` of seeds, harvest, surplus |
| Lumber (`LumberLogic`) | `takeable` (saplings, bone meal, bottles) | `insert` of surplus and output |
| Butcher (`ButcherLogic`) | `chestFood`, `takeable` | `insert` of loot, food handed back, `canMakeRoom` |
| Distributor (`DistributorLogic`) | `planFetch`, `takeAll` from the input | `insertAll` into targets, `insert` of the return |
| `core/GolemMending` | `bottlesIn`, `takeBottle` | - |
| `core/GolemXp:deliver` | - | `insertAll` of bottles |
| Chest Sorter mixin | pick-up leaves 1 (`cgr$pickSlot` with `take`) | destination test, put-down, destination filter of the pick-up |

- A golem with a fixed destination must not take what the destination can never accept, or it carries it forever:
  the Transporter takes only types B accepts and returns a refused carry to A ([transporter.md](transporter.md) "Types B
  refuses"); the Cleaner leaves ground items the output chest has no stack for ([cleaner.md](cleaner.md) "Keep layout").
  Both are storing-side checks.
- Chest Sorter (vanilla brain): `mixin/TransportItemsBetweenContainersMixin`, three HEAD injections, each acting by its
  own flag (`cgr$take` / `cgr$store`; both off = vanilla): `doReachedTargetInteraction` (a source qualifies if `cgr$pickSlot`
  finds a slot, when either flag is on; a destination if `cgr$accepts` the held item, only with storing), `pickUpItems`
  (the `cgr$pickSlot` slot, at most 16, leaves 1 with taking) and `putDownItem` (`TransporterLogic:insertStack`, only with
  storing). Why reimplemented and not redirected: vanilla's `matchesGettingItemsRequirement` / `matchesLeavingItemsRequirement` /
  `addItemsToContainer` are static and know neither the golem nor the flags.
  - `cgr$accepts(container, stack)` (storing rule): stackable = a non-full stack of the same item (`canInsert`); unstackable =
    vanilla's `matchesLeavingItemsRequirement` (container empty, or a slot with `ItemStack.isSameItem`) plus a free slot. Why: the
    exemption is about not blocking tool slots, not about letting an axe into any chest with a free slot; vanilla's sorter
    puts an axe only into an empty chest or one that already holds an axe, and Keep layout must not loosen that.
  - `cgr$pickSlot(body, source, take, store)`: the first slot with items to spare (taking: `takeable`, else any item) and,
    with storing on, whose type some destination accepts; destinations =
    `cgr$destinations`, the chests vanilla's `getTransportTarget` could pick (chest block entities in loaded chunks in the
    shadowed `getTargetSearchArea`, matching the shadowed `destinationBlockType`, not locked, not blocked; reachability
    and the visited memory are not checked). Why skip and not rotate: vanilla never gives a held item back. A place
    failure only drops the target, the golem tries the next destination, and after the visited limit it waits 140 ticks
    and starts over, still holding it. So taking a type no chest accepts would block every later slot for good; skipping
    such types at pick-up time needs no extra state and fits the state machine (no slot = pick-up failure, so the golem
    moves on to the next copper chest or the 140-tick wait). A matching chest that is in range but unreachable still
    blocks, as in vanilla. Without storing the destination side is vanilla, so no filter is applied.

## Tests

`KeepLayoutTest`: `transporterKeepsLayout` (both on: A keeps 1 per slot, B's empty slots stay empty, same-item slots filled),
`takeOnlyLeavesOnePerSlotAndFillsEmptySlots` (A keeps 1, B's empty slots are used), `storeOnlyEmptiesSourceAndNeverFillsEmptySlots`
(A emptied of what B can take, B's empty slots stay empty), `offMovesEverythingAndFillsEmptySlots`, `ruleOnContainers`
(count-1, merge only, unstackable exemption), `furnaceSlotsAreExempt`, `chestSorterKeepsLayout`, `chestSorterNeverFillsEmptySlots`,
`chestSorterUnstackableKeepsVanillaRule` (an axe skips a nearer chest with free slots but only a stick and goes to the chest
holding an axe), `chestSorterSkipsTypesNoChestTakes` (cobblestone in slot 0 with no matching stack anywhere stays, the
sticks behind it are sorted). `GolemDataTest:legacyKeepLayoutMigrates`. `MenuTest:settingsKeepLayoutToggles`. Transporter and
Cleaner: `TransporterTest:keepLayoutLeavesTypesBCannotTake`, `TransporterTest:carryBRefusesGoesBackToA`,
`CleanerTest:keepLayoutLeavesUndeliverableTypes`. The other professions' tests set both flags.
