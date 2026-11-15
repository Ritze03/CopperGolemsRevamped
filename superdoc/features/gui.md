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

`rows = min(6, 1 + Profession:slotRows)`; `slotRows` is the last row used by `Profession:layout` (stacked groups plus separator rows, `beside` groups sharing rows; profession None: 1 row). There are exactly `rows*9` top slots
(cell = row*9 + column, row-major) followed by the 27 + 9 player slots (`addStandardInventorySlots`); the
client desyncs if the count is not `rows*9 + 36`.

| Cell | Content |
|---|---|
| 0 | Profession cycle button |
| 1 | Follow toggle |
| 2..6 | One assign button per `AssignSpec` of the profession, in order (max 5; today's most is 4) |
| 7 | Help button, fixed cell so it never collides with an assign button (that is why assigns stop at cell 6) |
| 8 | Held-item slot (the golem's carried item, take-out only; shows the `held_empty` placeholder when the hand is empty) |
| 9.. | The profession's slots in declaration order; a `SlotGroup` starts on a new row at column 0 and wraps after its `width` cells (default 9), one full filler row separating consecutive stacked groups; a group with `beside(col, row)` instead sits at column `col`, `row` rows below the first row of the previous stacked group (see below) |
| rest | Filler (plain vanilla orange stained glass pane, tooltip hidden) |

Example, Transporter (one group of 9, two assigns): 2 rows, cells 0 profession, 1 follow, 2 and 3 assign
buttons (A, B), 4..6 filler, 7 help, 8 held, 9..17 carry slots; top slots 0..17, player inventory 18..53.
Profession None: 1 row, cells 0, 1 and 7 controls, 2..6 filler, 8 held; top slots 0..8, player inventory 9..44.
Example, Crafter (`template` 9 slots width 3, `input` 9 slots width 3 `beside(4, 0)`, `output` 1 slot `beside(8, 1)`, three
assigns): 4 rows; rows 1..3 hold the template 3x3 at columns 0..2, the input 3x3 at columns 4..6 (input slot `i` directly
beside template cell `i`) and the output at column 8 of row 2, like a crafting table; everything else is filler.
Slot `i` of a width-3 group sits at
row `i / 3`, column `i % 3` of the square, the same order the Crafter reads it as a crafting grid.
Why: the user asked for a real 3x3 "crafting template" in the GUI; packing groups back to back would have drawn it
as a 9-wide strip. Why the separator row between stacked groups: a template directly above another group read as a 3x4
grid in the screenshot review. Why `beside`: template + 3x3 input + output stacked would need 1+3+1+3+1+1 rows, over
the 6-row cap; side by side they need 4 and show which input slot belongs to which grid cell. `beside` is generic (a
column and a row offset on `SlotGroup`, resolved in `Profession:layout`); no profession-specific code lives in `menu/`.
The separator counts toward the 6-row cap, so a group that no longer fits is cut. Groups carry no labels (a chest menu has no room for headings). Rows beyond the 6-row limit are
cut: the shown slots are always a prefix of the declared order and the container is sized to them (`itemCount`).
Cell -> data index is `GolemMenu.itemOfCell`; profession slots are not contiguous in cell space.

Held slot (`GolemMenu.HeldSlot`): `getItem` returns a cached `held_empty` placeholder ("Held item" / "The golem is
holding nothing") while the real main hand is empty, so the cell does not look like an empty slot. The placeholder
never leaves the cell, see the click table under "Click handling". Why: a vision check of in-game screenshots could
not tell the empty held cell from an empty slot.

Slot `mayPlace` = group filter and, when the group has one, its `SlotRule` (per-slot check that sees the golem's stored
list; the Crafter input slot `i` accepts only template cell `i`'s item), `getMaxStackSize` = group `maxStack` (`GolemMenu.GroupSlot`).
`GolemMenu:canTakeItemForPickAll` refuses slots whose group has `maxStack == 1` (the Crafter template). Why: a
double-click collect on a plank stack would otherwise strip the recipe.

## Profession change side effects (`core/GolemService:changeProfession`)

- Stored items: with a player (menu click) they go into the player's main inventory (`GolemService:give`, merge then
  empty slots, not `Inventory#add` which deletes the stack for creative players); the rest drops at the player's feet.
  Without a player (death, statue, tests) they drop at the golem. Why: a drop at the golem was missed by players
  (user report "items get lost"). The held item is untouched.
- Assignments are cleared; the first `Kind.CHEST` assign (declaration order) is pre-filled with
  `GolemData.spawnChest` when that block is still a chest. `spawnChest` is set by `mixin/GolemSpawnMixin` for golems
  built from copper block + carved pumpkin (see vanilla-copper-golem.md section 6), never for egg/statue golems.
  Why: user wanted the chest the golem was built next to to be its first (usually input) chest.
- Name: the profession name becomes the golem's custom name (not "visible", so vanilla shows it only while looked at,
  like a name tag), cleared for None. `GolemData.autoName` remembers the name we set; a different current name is the
  player's (name tag) and is never touched.
- A running `ai/WalkTo` is cancelled.
- Profession None: `GolemTicker:tick` stops navigation and skips the vanilla brain, so the golem stands still
  (follow mode and walk-to still work). Why: the vanilla brain wanders and moves items on its own. Chest Sorter keeps
  `runsVanillaBrain = true`.

## Controls

Control and filler cells are `GolemMenu.IconSlot`s (`mayPlace` and `mayPickup` false) backed by a plain
`SimpleContainer` of icon stacks (`menu/GolemIcon` builds them). Updating an icon live (the follow toggle) is a
`setItem` on that container; vanilla's `broadcastChanges` syncs it on the next tick.

| Control | Click action |
|---|---|
| Profession (cell 0) | left = next, right = previous, in the order None then `Professions.all()`. `GolemService.changeProfession(..., player)` (the player receives the golem's stored items, what does not fit drops at their feet), then `GolemMenuOpener.open` again (layout changes) |
| Follow (cell 1) | `following = !following`, `followPlayer` = clicker UUID when on else null, `GolemData.save`, icon swapped in place |
| Help (cell 7) | `GolemMenu:sendHelp`: chat lines for the current profession, the menu stays open (see Help text below) |
| Assign (cell 2..) | `player.closeContainer()`, then `AssignMode.arm(player, golem, spec.key())` (AREA passes the base key) |

Tooltip: the name says what the control is (`Profession: Transporter`, `Follow: on`, the `AssignSpec` label),
lore says how to use it and shows the current target (`Current: x, y, z` / `a to b` for AREA / `unset`).

## Help text

Every `Profession` carries `help` (2-3 lines: what it does, what the slots are for) and every `AssignSpec` a `help`
(what the target is for). The texts live together in `profession/ProfessionHelp`, which `Professions:register`
attaches to the declaration when it has none (declarations stay editable by others without merge conflicts; a
declaration may still pass its own). The Help button sends `ProfessionHelp:lines`: the profession lines plus one line
per assignment, `<label>: right-click ... , max NxN, larger areas are capped` (sizes come from the `AssignSpec`, never
typed into the text). For None it explains how to pick a profession. The same `ProfessionHelp:prompt` is the chat
prompt when an assign button arms (see [assign-mode.md](assign-mode.md)).
Why chat and not the tooltip: the user asked for explanations of assignments and sizes in chat, a tooltip has no room
for several lines per profession. Icon: `help` on base item `book`.

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
slots are scattered across rows. Groups are tried last-declared first; a group declared `noQuickMove` (the Crafter template) is skipped. Why: shift-click
should fill working storage (Crafter input slots, whose rule routes each item to its cell) and never a recipe template
(maxStack 1), which the user does not want filled by accident; the output group accepts nothing from players.

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
| `assign_tools` | `iron_pickaxe` | iron pickaxe (chest with a pickaxe badge; Farmer tool chest, holds hoes and axes) |
| `assign_seeds` | `wheat_seeds` | wheat seeds (chest with a sprout badge; Farmer seed chest) |
| `assign_tree` | `oak_sapling` | oak sapling (a tree with the pin; Lumber tree) |
| `help` | `book` | book (copper-bound book with a "?") |
| `held_empty` | `glass_bottle` | glass bottle (empty-container look, plain model, unlike the filler pane) |

Why `assign_tools` / `assign_seeds` / `assign_tree` exist: the screenshot review showed the Farmer's two chest buttons
on the same generic chest icon and the Lumber tree button on the copper cube (`assign_block`); each now has its own
icon, base item picked so a vanilla client also sees a hint of the meaning. `tools/icons/make_icons.py` also rewrites
the `tools/icons/preview*.png` contact sheets.

Profession -> icon: `profession_<profession id path>` (`GolemIcon:profession`), `profession_none` when there is no
such icon, so a new profession only needs its texture, model and a `GolemIcon` entry plus a base-item case.
Assign icon: `assign_<AssignSpec.key()>` if `GolemIcon` has one (`GolemIcon:byName`; `input` / `output` so the two
Transporter buttons differ), else by `AssignSpec.kind()`: CHEST, CRAFTING_TABLE, BLOCK, AREA. A new profession with
other keys needs no GUI change.

Filler is not an icon: `GolemIcon:filler` is a plain vanilla orange pane (tooltip hidden, no custom model data, no
override file). Why: the vision check showed the custom filler texture read as an item. Orange (was gray) because the user wanted the filler more visible.

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
