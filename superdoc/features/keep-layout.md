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
  (they fetch and return tools).
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
- Chest Sorter (vanilla brain): `mixin/TransportItemsBetweenContainersMixin`, three HEAD injections that only act when the
  body's `GolemData.keepLayout` is set: `doReachedTargetInteraction` (a source qualifies if some slot has something to
  spare, a destination if the held item can merge), `pickUpItems` (first slot with spare items, at most 16, leaves 1) and
  `putDownItem` (`TransporterLogic:insertStack`). Why reimplemented and not redirected: vanilla's `matchesGettingItemsRequirement`
  / `matchesLeavingItemsRequirement` / `addItemsToContainer` are static and know neither the golem nor the flag.

## Tests

`KeepLayoutTest`: `transporterKeepsLayout` (A keeps 1 per slot, B's empty slots stay empty, same-item slots filled),
`offMovesEverythingAndFillsEmptySlots`, `ruleOnContainers` (count-1, merge only, unstackable exemption),
`furnaceSlotsAreExempt`, `chestSorterKeepsLayout`, `chestSorterNeverFillsEmptySlots`. `MenuTest:settingsKeepLayoutToggle`.
