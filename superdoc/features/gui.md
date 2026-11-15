# Golem GUI

Server-side-only chest-style menu for configuring a golem, plus an optional resource pack for custom icons.
Vanilla clients (no mod) can join and use it. Fabric/vanilla signatures used here:
[vanilla-copper-golem.md](../architecture/vanilla-copper-golem.md) (sections 8 and 9).

Why server-side only: user request (2026-11-15), so players do not have to install the mod. It rules out a
custom `MenuType`, custom payloads, a client screen and anything in a synced registry: a vanilla client would
reject the unknown registry entry or payload. The mod has no client code (`src/client` holds no Java);
`fabric.mod.json` keeps `"environment": "*"` because the singleplayer integrated server needs the mod.

## Opening

A main-hand right-click on a copper golem with BOTH hands empty: `mixin/CopperGolemMixin:cgr$emptyHand` calls
`menu/GolemMenuOpener:open` (server), which calls `ServerPlayer#openMenu` with a `SimpleMenuProvider`.
Title: `Copper Golem`, plus an em dash and the profession name when one is set.

Why: both hands, not just the main one. Vanilla tries the main hand first and the off hand only if the
main one passes, so opening the GUI on an empty main hand alone would swallow off-hand honeycomb, axe or
shears use. Any item in either hand stays vanilla (decision D4).

## Layout comes from the declaration

`menu/GolemMenu` extends `AbstractContainerMenu` directly and uses a vanilla chest type
(`MenuType.GENERIC_9x1..9x6`, chosen from the row count), so the client builds its normal chest screen. No
profession-specific code: a new profession shows up with zero GUI changes (decision D6).

`rows = min(6, 1 + sum over slot groups of ceil(count / width) + (groups - 1))` (`Profession:slotRows`, which includes the separator rows; profession None: 1 row). There are exactly `rows*9` top slots
(cell = row*9 + column, row-major) followed by the 27 + 9 player slots (`addStandardInventorySlots`); the
client desyncs if the count is not `rows*9 + 36`.

| Cell | Content |
|---|---|
| 0 | Profession cycle button |
| 1 | Follow toggle |
| 2..7 | One assign button per `AssignSpec` of the profession, in order (max 6) |
| 8 | Held-item slot (the golem's carried item, take-out only; shows the `held_empty` placeholder when the hand is empty) |
| 9.. | The profession's slots in declaration order; every `SlotGroup` starts on a new row at column 0 and wraps after its `width` cells (default 9); one full filler row separates consecutive groups |
| rest | Filler (plain vanilla gray stained glass pane, tooltip hidden) |

Example, Transporter (one group of 9, two assigns): 2 rows, cells 0 profession, 1 follow, 2 and 3 assign
buttons (A, B), 4..7 filler, 8 held, 9..17 carry slots; top slots 0..17, player inventory 18..53.
Profession None: 1 row, cells 0 and 1 controls, 2..7 filler, 8 held; top slots 0..8, player inventory 9..44.
Example, Crafter (`template` 9 slots width 3, `buffer` 9 slots width 9, three assigns): 6 rows; rows 1..3 hold the
template as a 3x3 square in columns 0..2 (columns 3..8 filler), row 4 is the filler separator, row 5 the buffer. Slot `i` of a width-3 group sits at
row `i / 3`, column `i % 3` of the square, the same order the Crafter reads it as a crafting grid.
Why: the user asked for a real 3x3 "crafting template" in the GUI; packing groups back to back would have drawn it
as a 9-wide strip. Why the separator row: a template directly above the buffer read as a 3x4 grid in the screenshot
review. The separator counts toward the 6-row cap, so a group that no longer fits is cut. Groups carry no labels (a chest menu has no room for headings). Rows beyond the 6-row limit are
cut: the shown slots are always a prefix of the declared order and the container is sized to them (`itemCount`).
Cell -> data index is `GolemMenu.itemOfCell`; profession slots are not contiguous in cell space.

Held slot (`GolemMenu.HeldSlot`): `getItem` returns a cached `held_empty` placeholder ("Held item" / "The golem is
holding nothing") while the real main hand is empty, so the cell does not look like an empty slot. The placeholder
never leaves the cell, see the click table under "Click handling". Why: a vision check of in-game screenshots could
not tell the empty held cell from an empty slot.

Slot `mayPlace` = group filter, `getMaxStackSize` = group `maxStack` (`GolemMenu.GroupSlot`).
`GolemMenu:canTakeItemForPickAll` refuses slots whose group has `maxStack == 1` (the Crafter template). Why: a
double-click collect on a plank stack would otherwise strip the recipe.

## Controls

Control and filler cells are `GolemMenu.IconSlot`s (`mayPlace` and `mayPickup` false) backed by a plain
`SimpleContainer` of icon stacks (`menu/GolemIcon` builds them). Updating an icon live (the follow toggle) is a
`setItem` on that container; vanilla's `broadcastChanges` syncs it on the next tick.

| Control | Click action |
|---|---|
| Profession (cell 0) | left = next, right = previous, in the order None then `Professions.all()`. `GolemService.changeProfession`, then `GolemMenuOpener.open` again (layout changes) |
| Follow (cell 1) | `following = !following`, `followPlayer` = clicker UUID when on else null, `GolemData.save`, icon swapped in place |
| Assign (cell 2..) | `player.closeContainer()`, then `AssignMode.arm(player, golem, spec.key())` (AREA passes the base key) |

Tooltip: the name says what the control is (`Profession: Transporter`, `Follow: on`, the `AssignSpec` label),
lore says how to use it and shows the current target (`Current: x, y, z` / `a to b` for AREA / `unset`).

## Click handling (`GolemMenu:clicked`)

- On a control or filler cell: `PICKUP` and `QUICK_MOVE` run the control (if any); every other input is
  dropped (`CLONE` would copy the icon for creative players; `SWAP`, `THROW`, `PICKUP_ALL` do nothing).
  `QUICK_CRAFT` is always passed to `super.clicked` - swallowing it corrupts the drag state; the slots refuse
  placement anyway.
- Trust check before running a control: `stillValid` (golem alive, within 8 blocks via `GolemMenu:inRange`,
  profession unchanged, stored list not shrunk).
- Everything else (golem slots, player inventory) is vanilla `super.clicked`.
- A vanilla client shows the clicked icon on the cursor until the server's correction arrives; unavoidable.
- `openMenu` closes any open container first and `removed()` returns the cursor stack, so reopening from inside
  `clicked` is safe.

Held cell (`GolemMenu:clicked`, `HeldSlot`): with the hand EMPTY every input except `QUICK_CRAFT` is dropped before
`super.clicked`; `mayPickup` is false and `mayPlace` always false, so drag (`QUICK_CRAFT`) cannot place or take it,
and `PICKUP_ALL` (double-click elsewhere) skips it because it checks `mayPickup`. `quickMoveStack` returns EMPTY for
it. With a real item held, all inputs are vanilla and take-out only (`mayPlace` false); `remove` / `set` hit the real
`GolemHeldContainer`. Why a `getItem` override and not a swapped slot: a menu's slot list is fixed, and the override
keeps one slot object whose sync (`broadcastChanges` reads `getItem`) shows the placeholder or the real item.

`quickMoveStack`: control / filler -> nothing; held or profession slot -> player inventory (hotbar end first);
player slot -> profession slots only via `GolemMenu:moveIntoProfession`, which walks the profession cells itself
(merge pass into matching stacks, then empty slots, both honouring `mayPlace` and the slot max). It replaces
`moveItemStackTo`, whose cell range must be contiguous and would hit control / filler / held cells now that profession
slots are scattered across rows. Groups are tried last-declared first. Why: shift-click should fill working storage
(Crafter buffer) before a recipe template (maxStack 1), which the user does not want filled by accident.

## Containers

`menu/GolemItemsContainer` is a live view over `GolemData.items` that calls `GolemData.save` on every change
(out-of-range slots read empty and refuse placement; `setItem` past the stored list drops the stack at the golem).
`menu/GolemHeldContainer` views the main hand; the held slot is take-out only (`mayPlace` false) because the
vanilla brain owns what the golem carries. Writes call `setGuaranteedDrop(MAINHAND)` like vanilla pickup.

## Stale menus close

`GolemMenu` snapshots the profession and slot count when opened. `GolemMenu:stillValid` returns false when
the golem's current `GolemData.profession` differs, `GolemData.items` is shorter than the slot count, or the
player is out of range; vanilla then closes the menu next tick and returns the cursor stack.
Why: only the player who changes the profession gets a reopened menu; any other viewer keeps the old layout,
and an item placed in a slot that no longer exists would vanish (decision D9, no item is ever lost).

## Icons

An icon is a vanilla base item with `DataComponents.CUSTOM_MODEL_DATA` = one string `coppergolemsrevamped:<icon>`
(`menu/GolemIcon`). Names come from `DataComponents.ITEM_NAME` (not `CUSTOM_NAME`, which renders italic), lore
lines are explicitly non-italic gray, the other icons
hide `ATTRIBUTE_MODIFIERS` (tool base items), an "on" toggle gets `ENCHANTMENT_GLINT_OVERRIDE`.

The mod jar and the resource pack both ship `assets/minecraft/items/<base>.json`, overriding the vanilla item
definition with a `minecraft:select` on `minecraft:custom_model_data` (string index 0): one case per icon using
that base item, and `fallback` = the vanilla definition's `model` object copied verbatim. The icon model is
`assets/coppergolemsrevamped/models/item/gui/<icon>.json` (parent `minecraft:item/generated`, layer0 texture
`coppergolemsrevamped:item/gui/<icon>`, a 16x16 PNG).

Why: CMD-select instead of the `item_model` component. A client without the pack that receives an unknown
`item_model` renders the missing-model cube; with CMD-select and a vanilla fallback it just sees the genuine base
item, so the pack is optional (decision D18).

| Icon | Base item | Fallback look |
|---|---|---|
| `profession_none` | `light_gray_dye` | dye |
| `profession_transporter` | `chest_minecart` | chest minecart |
| `profession_chest_sorter` | `comparator` | comparator |
| `profession_cleaner` | `hopper` | hopper |
| `profession_crafter`, `assign_crafting_table` | `crafting_table` | crafting table |
| `profession_farmer` | `iron_hoe` | iron hoe |
| `profession_lumber` | `iron_axe` | iron axe |
| `follow_on`, `follow_off` | `lead` | lead (glint when on) |
| `assign_chest`, `assign_input`, `assign_output` | `chest` | chest (its override copies vanilla's special seasonal model) |
| `assign_block` | `target` | target block |
| `assign_area` | `map` | empty map |
| `held_empty` | `glass_bottle` | glass bottle (empty-container look, plain model, unlike the filler pane) |

Profession -> icon: `profession_<profession id path>` (`GolemIcon:profession`), `profession_none` when there is no
such icon, so a new profession only needs its texture, model and a `GolemIcon` entry plus a base-item case.
Assign icon: `assign_<AssignSpec.key()>` if `GolemIcon` has one (`GolemIcon:byName`; `input` / `output` so the two
Transporter buttons differ), else by `AssignSpec.kind()`: CHEST, CRAFTING_TABLE, BLOCK, AREA. A new profession with
other keys needs no GUI change.

Filler is not an icon: `GolemIcon:filler` is a plain vanilla gray pane (tooltip hidden, no custom model data, no
override file). Why: the vision check showed the custom filler texture read as an item.

Adding an icon: enum entry in `GolemIcon` (base item must match), model JSON, a case in the base item's override
file (create the file by copying the vanilla `items/<base>.json` `model` into `fallback`; the vanilla files are in
the client jar), texture PNG. Prefer base items whose vanilla definition is a plain `minecraft:model`.

## Resource pack for vanilla clients

`./gradlew build` also produces `build/libs/coppergolemsrevamped-<version>-resourcepack.zip` (task
`resourcePackZip`: the mod's `assets/` plus a generated `pack.mcmeta` with `min_format` / `max_format` 97; a lone
`pack_format` above 64 is an error in 26.x). Clients with the mod need nothing: the jar carries the same assets.
To give vanilla clients the icons, host the zip at an https URL and set in `server.properties`:

```
resource-pack=<https URL of the zip>
resource-pack-sha1=<sha1 of the zip>
require-resource-pack=false   # optional: true kicks players who decline
```

The pack is optional because every icon falls back to a recognisable vanilla item.

## Notes

- Strings are `Component.literal` for now (no lang file yet).
- Unverified: the menu was compiled and the build's GameTests pass, but no vanilla client was run against it.
