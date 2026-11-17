# Keep layout

A per-golem setting on the Settings tab, available for **every profession** (default off): the golem never empties a
stack and never puts anything into an empty slot of a container, so a chest a human laid out by hand (placeholder
items of 1, one slot per item type) keeps its layout.

Why: user request, "a setting for all golems, that never empties a stack. They should always leave one of everything
behind. Empty slots should never be used at all with this mode on. So they respect layouts setup by humans." The
motivating case is a large chest pre-laid-out in rows (one row of sticks, one of dark oak logs), most slots holding a
single placeholder item.

## The rule (when on)

For every container a golem takes from or puts into:

- **Take:** never lower a slot below 1 item: at most `count - 1` leaves each slot; slots holding 1 are skipped.
- **Insert:** only merge into **non-empty** slots holding the same item and components
  (`ItemStack.isSameItemSameComponents`), up to the max stack size. Nothing goes into an empty slot. If nothing fits,
  the container counts as full and the existing full/blocked handling of the profession applies.

### Exemptions

- **Unstackable items (max stack size 1)**, e.g. tools and weapons: taken and returned normally, empty slots allowed.
  Why: a tool chest slot holding one axe could otherwise never be used, which would break Lumber, Farmer and Butcher
  (they fetch and return tools). The Chest Sorter still picks the destination chest by vanilla's rule for them (see below).
- **Furnace slots** (`AbstractFurnaceBlockEntity`: furnace, blast furnace, smoker): the Smelter's feed and collect.
  Why: machine slots, not a human layout. The Smelter's chests do follow the rule.

## Where

- State: `core/GolemData:keepLayout`, codec key `keep_layout` (optional, default false, so old saves load as off).
  Kept over a profession change (like Follow), not refused while frozen (a plain setting).
- Toggle: Settings tab (2,4) between Follow (2,3) and Freeze (2,5), vanilla `item_frame` via `menu/GolemIcon:plain`,
  glint while on, name "Keep layout: on/off", `menu/GolemMenu:toggleKeepLayout`. See [gui.md](gui.md).
- The rule is implemented once, in `profession/transporter/TransporterLogic`: `takeAll(src, carry, limit, accept, keepLayout)`,
  `insertAll(carry, dst, limit, keepLayout)` and the private `addTo` (the `layoutRule` check holds both exemptions),
  plus the helpers `takeable`, `insertStack`, `canInsert`. The budgeted wrappers `ai/TransferBudget:take(..., accept, keepLayout)`
  and `TransferBudget:insert(..., keepLayout)` pass it on, see [../architecture/transfer-limit.md](../architecture/transfer-limit.md).
  The older overloads without the flag keep the old behaviour (flag off).
- A profession picks the rule up by passing `data.keepLayout` to those calls. The Transporter does
  (`TransporterLogic:tick`); the other professions are wired in their own changes.
- A golem with a fixed destination must not take what the destination can never accept, or it carries it forever:
  the Transporter takes only types B accepts and returns a refused carry to A ([transporter.md](transporter.md) "Types B
  refuses"); the Cleaner leaves ground items the output chest has no stack for ([cleaner.md](cleaner.md) "Keep layout").
- Chest Sorter (vanilla brain): `mixin/TransportItemsBetweenContainersMixin`, three HEAD injections that only act when the
  body's `GolemData.keepLayout` is set: `doReachedTargetInteraction` (a source qualifies if `cgr$pickSlot` finds a slot,
  a destination if `cgr$accepts` the held item), `pickUpItems` (the `cgr$pickSlot` slot, at most 16, leaves 1) and
  `putDownItem` (`TransporterLogic:insertStack`). Why reimplemented and not redirected: vanilla's `matchesGettingItemsRequirement`
  / `matchesLeavingItemsRequirement` / `addItemsToContainer` are static and know neither the golem nor the flag.
  - `cgr$accepts(container, stack)`: stackable = a non-full stack of the same item (`canInsert`); unstackable = vanilla's
    `matchesLeavingItemsRequirement` (container empty, or a slot with `ItemStack.isSameItem`) plus a free slot. Why: the
    exemption is about not blocking tool slots, not about letting an axe into any chest with a free slot; vanilla's sorter
    puts an axe only into an empty chest or one that already holds an axe, and Keep layout must not loosen that.
  - `cgr$pickSlot(body, source)`: the first slot with items to spare whose type some destination accepts; destinations =
    `cgr$destinations`, the chests vanilla's `getTransportTarget` could pick (chest block entities in loaded chunks in the
    shadowed `getTargetSearchArea`, matching the shadowed `destinationBlockType`, not locked, not blocked; reachability
    and the visited memory are not checked). Why skip and not rotate: vanilla never gives a held item back. A place
    failure only drops the target, the golem tries the next destination, and after the visited limit it waits 140 ticks
    and starts over, still holding it. So taking a type no chest accepts would block every later slot for good; skipping
    such types at pick-up time needs no extra state and fits the state machine (no slot = pick-up failure, so the golem
    moves on to the next copper chest or the 140-tick wait). A matching chest that is in range but unreachable still
    blocks, as in vanilla.

## Tests

`KeepLayoutTest`: `transporterKeepsLayout` (A keeps 1 per slot, B's empty slots stay empty, same-item slots filled),
`offMovesEverythingAndFillsEmptySlots`, `ruleOnContainers` (count-1, merge only, unstackable exemption),
`furnaceSlotsAreExempt`, `chestSorterKeepsLayout`, `chestSorterNeverFillsEmptySlots`, `chestSorterUnstackableKeepsVanillaRule`
(an axe skips a nearer chest with free slots but only a stick and goes to the chest holding an axe),
`chestSorterSkipsTypesNoChestTakes` (cobblestone in slot 0 with no matching stack anywhere stays, the sticks behind it are
sorted). `MenuTest:settingsKeepLayoutToggle`. Transporter and Cleaner: `TransporterTest:keepLayoutLeavesTypesBCannotTake`,
`TransporterTest:carryBRefusesGoesBackToA`, `CleanerTest:keepLayoutLeavesUndeliverableTypes`.
