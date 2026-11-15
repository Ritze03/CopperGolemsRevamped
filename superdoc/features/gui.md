# Golem GUI

Server-side-only chest-style menu for configuring a golem, plus an optional resource pack for custom icons.
Vanilla clients (no mod) can join and use it. Fabric/vanilla signatures used here:
[vanilla-copper-golem.md](../architecture/vanilla-copper-golem.md) (sections 8 and 9).

Why server-side only: user request (2026-11-15), so players do not have to install the mod. It rules out a
custom `MenuType`, custom payloads, a client screen and anything in a synced registry: a vanilla client would
reject the unknown registry entry or payload. The mod has no client code (`src/client` holds no Java);
`fabric.mod.json` keeps `"environment": "*"` because the singleplayer integrated server needs the mod.

The design was approved as an interactive HTML simulator (tabs, targets, remembering crafter grid); this page
describes what the mod implements from it.

## Opening

A main-hand right-click on a copper golem with BOTH hands empty: `mixin/CopperGolemMixin:cgr$emptyHand` calls
`menu/GolemMenuOpener:open` (server), which calls `ServerPlayer#openMenu` with a `SimpleMenuProvider`.
Title: `Golem — <Profession name>` (`None` without a profession). The menu opens on the **Status** tab (user request).

Why: both hands, not just the main one. Vanilla tries the main hand first and the off hand only if the
main one passes, so opening the GUI on an empty main hand alone would swallow off-hand honeycomb, axe or
shears use. Any item in either hand stays vanilla (decision D4).

## Structure: one 6-row menu, tabs switched in place

`menu/GolemMenu` extends `AbstractContainerMenu` directly with `MenuType.GENERIC_9x6` always, so the client builds
its normal chest screen. There are exactly 54 top slots (cell = row*9 + column, row-major) followed by the 27 + 9
player slots (`addStandardInventorySlots`); the client desyncs if the count is not 54 + 36.

The 54 top slots are fixed `GolemMenu.ViewSlot` objects. Each routes `getItem` / `set` / `remove` / `mayPlace` /
`mayPickup` / `getMaxStackSize` / `setChanged` to the `Cell` currently at its index (`Cell.Icon`: a control, label or
filler; `Cell.Store`: a view of one `GolemData.items` slot). A tab switch is `GolemMenu:showTab`: set the tab, rebuild
the cell array (`GolemViews:build`), and vanilla's `broadcastChanges` sends the slots whose contents changed.
Why in place: a vanilla client re-centres the mouse cursor when a new menu opens, which made every tab click jump the
pointer. The menu is only reopened when the title must change (a profession switch).
Code split: `GolemMenu` (slots, clicks, quick-move, state and control actions), `GolemViews` (the cells of each tab),
`Cell`, `Tab` (labels, lore and help lines), `GolemIcon`.
The views are rebuilt after every control click (a Recipe cell click rebuilds too: the arrow lore names the outcome)
and every 10 ticks on the Status tab (live values). Profession-specific data comes from the `Profession`
declaration only (slot groups, labels, assigns) and from `profession/ProfessionStatus` (decision D6).

### Tab bar (row 0, every tab)

There is **no Back button**: players close the menu with Esc or E like any chest (user request). Row 0 starts as black
stained glass panes (user request: "to indicate it's another part of the UI"); `GolemViews:bar` then places the tabs
of `Tab:forProfession` from column 0 going right, with black panes between the last tab and Help at column 8
(user request: "move the tabs all the way to the left, with only the help staying rightbound"):

| Cell | Content |
|---|---|
| 5..7 (cols after the tabs; 6..7 with six tabs) | Black pane filler |
| 0..4 (five tabs) | Status (`icon_status`), Targets (`assign_block`), Inventory (`icon_inventory`), Settings (`icon_settings`), Profession (the current profession's icon). The active tab has the glint |
| 0..5 (six tabs) | Status, Targets, **Recipe** (plain `knowledge_book`), Inventory, Settings, Profession |
| 8 | Help (`help`): `GolemMenu:sendHelp` sends the tab's help to chat, the menu stays open |

Which professions get the sixth tab is declared, not coded in `menu/`: `Tab.RECIPE` appears only when
`Profession:hasRecipeTab` is true, i.e. when a slot group is a ghost recipe (`SlotGroup:recipeAt`, see "Crafter").
Why: user wanted Recipe directly before Inventory and, with Back gone, the bar has room for a sixth tab; a declaration
keeps D6 (no profession ids in `menu/`).

Rows 1..5 are the tab content on orange stained-glass filler (tooltip hidden).

### Status

(2,4) profession icon named `<Profession> — <doing>`. Row 3: `Carrying N items` (bundle; counts every shown slot that
is not a 1-stack tool slot; omitted for professions without slots), for a profession with a `tool` group the tool with
`Durability x/y` or `No tool`, `Holding X` when the golem's real main hand holds something (vanilla Chest Sorter carry,
read-only; a hand display copy never counts), and a `Next: ...` line (compass). Columns: 1 icon [4], 2 [3,5], 3 [3,4,5],
4 [1,3,5,7]. The texts come from `profession/ProfessionStatus` (a default per profession; live where the logic exposes
a value cheaply: Lumber's `LumberLogic:phaseOf`, the Crafter's recipe; "Waiting for targets" while an assign is missing;
"Following you"; "Walking to a target"). Why small: user asked not to build a status framework (YAGNI).

### Targets

One column per `AssignSpec`: 1 spec col 4, 2 cols [3,5], 3 cols [2,4,6], 4 cols [1,3,5,7]. Row 2 the assign button
(lore: how to click, `Max NxN` for areas, then `Current: x, y, z` / `a to b (WxD)` or `Not set`); clicking closes the menu
and arms `AssignMode` (see [assign-mode.md](assign-mode.md)). Row 3 **Show** (spyglass): `WalkTo:toBlock` (block) or
`WalkTo:outline` (area), or a chat line "<target> is not set." Row 4 **Clear** (barrier): removes `key` (areas:
`key_a` and `key_b`), saves, confirms in chat. No specs (None, Chest Sorter): (3,4) a book "This profession needs no
targets". More than 4 specs are not shown (none exist; `everyProfessionHasHelp` asserts at most 4).

### Inventory

The slot groups of the profession, placed by explicit cells (see "Slot layout" below), each with an optional label icon
(name + lore, non-interactive; a `\n` in the declared lore starts a new line). Lumber: label (1,3) "Axe" with the tool slot (2,3); label (1,5) "Supplies" with slots
(2,5),(2,6); label (4,4) "Carry" with nine slots across row 5. Farmer: "Tool" (1,3) / slot (2,3), "Seeds" (1,5) / slot (2,5),
"Carry" (4,4) / row 5. Transporter and Cleaner: "Carry" (2,4) with nine slots in row 3. Crafter: see below. None and Chest
Sorter: (3,4) a book "No inventory". A ghost-recipe group (`SlotGroup:recipe`) is never shown here, only on the Recipe tab.

### Settings

(3,3) the Follow toggle (`follow_on` / `follow_off`, glint while on): `following = !following`, `followPlayer` = clicker UUID when
on else null, `GolemData.save`; refused with a chat message while frozen. (3,5) the **Freeze** toggle (`blue_ice` through
`GolemIcon:plain`, glint while on, name "Freeze: on/off", `GolemMenu:toggleFreeze`): all professions, rules in
[freeze.md](freeze.md).

### Profession and the confirm screen

Row 3: None, then `Professions.all()` in registration order, up to 9 per row centred (rounded left: 8 entries use cols 0..7,
7 use 1..7), more than 9 wrap to row 4 (`GolemViews:professions`; why: Smelter and Butcher made the old fixed cols 1..7 too small). The current one has the glint and the green lore "Current profession". Clicking another one: from None
it switches at once; otherwise it shows the **confirm screen** (`GolemMenu:choose`):

- No tab bar (all filler except): (2,2) current profession, (2,4) arrow "Switch to X", (2,6) the new profession,
  (4,3) red apple "Cancel" (back to the Profession tab, nothing changed), (4,5) lime dye "Confirm" (lore: "Items go to your
  inventory", "Targets are cleared"), which runs `GolemService.changeProfession(..., player)` and reopens the menu on the
  Profession tab (`GolemMenuOpener:open(player, golem, Tab.PROFESSION)`, new title).
- The confirm screen is a state of the same menu (`confirming` + `pending`), so the title stays `Golem — <current>`.
- Switching to None from a working profession also confirms.

Why confirm: a switch clears the targets and hands out every stored item, which is easy to trigger by accident.

## Slot layout comes from the declaration

`Profession.SlotGroup` carries its own placement: `at(row, col)` puts slot `i` at row `row + i / width`, column
`col + i % width` of the menu (rows 1..5, `width` default 9). `labeled(row, col, icon, name, lore)` adds the label
icon cell. `Professions:register` calls `Profession:validateLayout`, which throws when a shown cell or label leaves rows
1..5 / columns 0..8 or two overlap, with one board for the Inventory tab and one for the Recipe tab (recipe groups and
previewing groups on the Recipe board, everything but recipe groups on the Inventory board). A group without `at` is
**hidden**: it still reserves its data slots but has no cell. Other group flags:

- `recipeAt(row, col)`: a ghost recipe. The group is the profession's memory, not items; it is shown only on the Recipe
  tab (see Crafter), is never quick-moved into, and its slots are not handed out on a profession change (see below).

- `remembers(offset)`: slot `k` remembers its item in data slot `k - offset` (see Crafter below).
- `noQuickMove()`: shift-click never fills it. `shownInHand()`: the first non-empty stack is shown in the golem's hand
  (see "Hand display"). `previewing(Preview)`: while the single slot is empty it shows the preview ghost.
- `Label.icon` is a `GolemIcon` name or a vanilla item id (`minecraft:bone_meal`); `GolemIcon:label` resolves it.

Why explicit cells and not the old stacked/`beside` layout: the simulator places each group by hand (centred columns,
labels above slots); computing that from stacking rules would have needed more special cases than the cells themselves.
No profession-specific code lives in `menu/` (decision D6).

`Cell.Store` mapping: `GolemMenu` builds `new GolemItemsContainer(golem, profession.inventorySize())` (a live view that
saves on every change; out-of-range slots read empty and a write past the list drops the stack at the golem) and a Store
per shown slot with its data index.

## Crafter: a Recipe tab and an Inventory tab

Data model unchanged: `GolemData.items` 0..8 `template`, 9..17 `input`, 18 `output` ([crafter.md](crafter.md)). The template
slots are the recipe memory (declared `recipeAt(2, 1)`); the 9 input slots are the real ingredients. Each lives on its own
tab at (2..4, 1..3). Why two tabs: user request, "there has to be a menu for the recipe and one for the inventory. Right
now it's kinda hard to use": one grid was both recipe and inventory, setting the recipe meant placing a real item and
forgetting it needed a hidden empty-hand gesture.

### Recipe tab (`GolemViews:recipe`)

- The 3x3 ghost grid at (2..4, 1..3) shows the nine template slots: a set cell is a display copy of its item
  ("<Item> (recipe)", `GolemIcon:ghost`), an unset cell is empty. Every cell is a `Cell.Icon` with `pickupOnly`.
- **Click with an item on the cursor** (left or right) sets the cell to a one-count copy (`GolemMenu:setRecipeCell`); the
  cursor stack is neither consumed nor moved. **Click with an empty hand** clears a set cell. Nothing else does anything:
  shift-click (`pickupOnly`), drag, pick-all, clone, swap, throw are swallowed or refused, so no real item is ever created
  or deleted (D9). Why a ghost copy and not a real stack: the user asked for the way recipe and filter slots work in many
  mods, and a memory that can be set by a click must never be takeable.
- (1,2) label "Recipe" (lore "Click with an item to set a cell (the item is not used up)" / "Click with an empty hand to
  clear it"). (3,5) the arrow (`craft_arrow`, "Crafts") with the outcome ("Stick x4") or "No valid recipe" as lore, (3,7)
  the ghost preview of the outcome ("<Item> (preview)", never takeable) from `CrafterLogic:preview`. Both come from the
  previewing output group's label and cell.
- Migration: none. Whatever the template slots hold is the recipe. A stored stack bigger than one (only possible through
  hand-edited data, the mod always writes one) is not deleted by a click: the surplus is given to the player
  (`GolemService:give`, rest dropped) before the cell is overwritten.

### Inventory tab

- The 9 input slots are a 3x3 grid at (2..4, 1..3). An empty input cell whose recipe cell is set shows the faded
  "<Item> (remembered, empty)" copy and accepts only that item (same item and components, `Cell.Store:mayPlace`); it is not
  takeable. An input cell whose recipe cell is **not** set is an empty slot that refuses everything.
  Why: the recipe is now set only on the Recipe tab, so placing an item no longer sets the memory (it used to) and the
  empty-hand "forget" gesture is gone from this tab.
- (1,2) label "Ingredients" (lore "Filled by the golem from its input chest; you can add items too"), (3,5) a plain
  arrow (no lore), (3,7) the output, take-only; the ghost preview shows while it is empty.
- Shift-click from the player fills only cells whose recipe cell matches (`GolemMenu:moveIntoProfession` uses `mayPlace`).
- The output group and its arrow are shared with the Recipe tab; the recipe group never appears here.

### Display copies never become real items

Server-side "faded" does not exist, so a cell shows a display copy through `ViewSlot:getItem` (the same trick the removed
held cell used). Every copy is a marked (`core/DisplayItems`, custom data `cgr_display`) one-off stack that is never
stored. Safety rules, all in `GolemMenu:clicked` / `ViewSlot`:

- A cell that currently shows a copy (`Cell.Store:placeholder`) handles only PICKUP itself (place a fitting item; an empty
  cursor does nothing); every other input on it (QUICK_MOVE, CLONE, PICKUP_ALL, SWAP, THROW) is dropped. Creative
  CLONE and double-click collect therefore cannot copy it.
- `mayPickup` is `!real().isEmpty()`, `canTakeItemForPickAll` refuses placeholders and 1-stack groups, `quickMoveStack`
  returns EMPTY for a cell without a real stack, drag (QUICK_CRAFT) goes to vanilla: `canItemQuickReplace` refuses a
  different-component stack and the ghost output has `mayPlace` false.
- Icon cells (controls, labels, filler) swallow CLONE, SWAP, THROW, PICKUP_ALL. QUICK_CRAFT is always passed on (swallowing
  it corrupts the drag state).
- `MenuTest:ghostCellsCannotBeTaken` runs every input on a ghost output and a faded cell and asserts that no marked stack
  reaches the inventory or cursor and the item count is conserved; `recipeCellOtherClicksConserveItems` does the same for
  the Recipe tab's cells (every click type except the setting PICKUP, plus a drag).

## Click handling (`GolemMenu:clicked`)

- On an icon cell: `PICKUP` and `QUICK_MOVE` run the control (if any), everything else is dropped (see above); for a
  `pickupOnly` icon (Recipe cells) `QUICK_MOVE` is dropped too.
- Trust check before running a control: `stillValid` (golem alive, within 8 blocks via `GolemMenu:inRange`, profession
  unchanged, stored list not shrunk). The Recipe cell controls run behind it as well; the placeholder placing checks it too.
- Everything else (golem slots, player inventory) is vanilla `super.clicked`.
- A vanilla client shows the clicked icon on the cursor until the server's correction arrives; unavoidable.
- `openMenu` closes any open container first and `removed()` returns the cursor stack, so reopening from inside
  `clicked` is safe.

`quickMoveStack`: icon cell or cell without a real stack -> nothing; a golem slot -> player inventory (hotbar end first);
player slot -> the Inventory tab's working slots only via `GolemMenu:moveIntoProfession` (merge pass into matching stacks,
then empty slots, honouring `mayQuickPlace` and the slot max; it does nothing on other tabs). Groups are tried last-declared
first; a group declared `noQuickMove` is skipped; the output group accepts nothing from players. It replaces
`moveItemStackTo`, whose cell range must be contiguous.
`canTakeItemForPickAll` refuses a slot whose group has `maxStack == 1` (tools) and any placeholder.

## Hand display (golems visibly hold their tool)

`core/GolemHandDisplay:sync`, called by `GolemTicker` every 10 ticks, shows a one-item copy of the first non-empty stack of a
`shownInHand()` group in the golem's main hand: Lumber, Farmer and Butcher their tool, Transporter and Cleaner the first carried stack.
It is display only and built so it can never duplicate or reach a player:

- The copy carries the `DisplayItems` marker and the hand's drop chance is set to 0 (`Mob#setDropChance`): death and statue
  conversion (`dropPreservedEquipment`, loot drops) drop nothing from it. The real stack stays in `GolemData.items` and drops
  through `GolemService:dropAll` as before. The marker is persisted with the entity, so after a reload the copy is still
  recognised and refreshed or cleared.
- A real held item (vanilla Chest Sorter carry, `setGuaranteedDrop`) always wins and is never overwritten; a vanilla-brain
  profession shows nothing (`runsVanillaBrain`).
- `GolemService:changeProfession` calls `releaseHand` first: a display copy is only cleared; a real held item goes to the
  player like the stored items (D9; the menu has no held cell any more, so this is how a carried item comes back), or drops at
  the golem without a player. The copy is never given out.
- Cleared (drop chance back to the vanilla default) whenever there is nothing to show.
- Vanilla's `CopperGolem#mobInteract` throws the golem's main-hand item at any empty-handed player, which also happens
  for the off-hand pass while the main hand holds a block. `CopperGolemMixin:cgr$emptyHand` returns PASS for an empty
  hand when the golem's hand is a display copy, so the copy is never thrown (that would duplicate the tool). This is why
  the older versions skipped the hand display; a real carried item is still thrown like vanilla.
  `MenuTest:vanillaThrowNeverSeesTheDisplayCopy` covers it.
- `MenuTest:handToolIsDisplayOnlyAndNeverDrops` kills a golem with an axe and counts the drops: exactly the stored axe.

## Profession change side effects (`core/GolemService:changeProfession`)

- The main hand first (see above), then stored items: with a player (menu click) they go into the player's main inventory
  (`GolemService:give`, merge then empty slots, not `Inventory#add` which deletes the stack for creative players); the rest
  drops at the player's feet. Without a player (death, statue, tests) they drop at the golem. Why: a drop at the golem was
  missed by players (user report "items get lost").
- Freeze is cleared (`GolemData:frozen`, see [freeze.md](freeze.md)); the golem starts the new profession.
- Ghost recipe slots (`SlotGroup:recipe`) are not handed out: their one-item copies are memory, giving them out would
  create items from nothing (D9). A surplus above one in such a slot is real and is given out (`GolemService:drop`).
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

## Help text

Every `Profession` carries `help` (2-3 lines: what it does, what the slots are for) and every `AssignSpec` a `help`
(what the target is for). The texts live together in `profession/ProfessionHelp`, which `Professions:register`
attaches to the declaration when it has none (declarations stay editable by others without merge conflicts; a
declaration may still pass its own). The Help button sends the current tab's lines (`Tab.help`) plus profession lines where
they fit: the Targets tab adds `ProfessionHelp:assignLines` (one line per assignment,
`<label>: right-click ... , max NxN, larger areas are capped`; sizes come from the `AssignSpec`, never typed into the text),
the Inventory and Recipe tabs add the profession's own help (slot explanations; the Crafter's explains both tabs). The same `ProfessionHelp:prompt` is the chat prompt when an assign button arms (see [assign-mode.md](assign-mode.md)).
Why chat and not the tooltip: the user asked for explanations of assignments and sizes in chat, a tooltip has no room
for several lines per profession. Icon: `help` on base item `book`.

## Stale menus close

`GolemMenu` snapshots the profession and the declared inventory size when opened. `GolemMenu:stillValid` returns false when
the golem's current `GolemData.profession` differs, `GolemData.items` is shorter than the declared size, or the
player is out of range; vanilla then closes the menu next tick and returns the cursor stack.
Why: only the player who changes the profession gets a reopened menu; any other viewer keeps the old layout,
and an item placed in a slot that no longer exists would vanish (decision D9, no item is ever lost).

## Icons

An icon is a vanilla base item with `DataComponents.CUSTOM_MODEL_DATA` = one string `coppergolemsrevamped:<icon>`
(`menu/GolemIcon`). Names come from `DataComponents.ITEM_NAME` (not `CUSTOM_NAME`, which renders italic), lore
lines are explicitly non-italic gray, the other icons
hide `ATTRIBUTE_MODIFIERS` (tool base items), an "on" toggle gets `ENCHANTMENT_GLINT_OVERRIDE`.
`GolemIcon:plain` is the same for a vanilla item with no custom model (bundle, compass, spyglass, barrier, apple, lime dye,
arrow, book in the Status, Targets and confirm views, `blue_ice` for Freeze, `knowledge_book` for the Recipe tab and label).
Why plain for those two: a vanilla item already reads right and needs no texture, model or override file.

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
| `profession_smelter` | `furnace` | furnace (copper furnace with a flame) |
| `profession_butcher` | `iron_sword` | copper-hilted sword with a ham badge |
| `follow_on`, `follow_off` | `lead` | lead (glint when on) |
| `assign_chest`, `assign_input`, `assign_output`, `icon_inventory` | `chest` | chest (its override copies vanilla's special seasonal model) |
| `assign_block` | `target` | target block |
| `assign_area` | `map` | empty map |
| `assign_tools` | `iron_pickaxe` | iron pickaxe (chest with a pickaxe badge; Farmer tool chest, holds hoes and axes) |
| `assign_seeds` | `wheat_seeds` | wheat seeds (chest with a sprout badge; Farmer seed chest) |
| `assign_fuel` | `coal` | coal (chest with a coal badge; Smelter fuel chest) |
| `assign_tree` | `oak_sapling` | oak sapling (a tree with the pin; Lumber tree) |
| `help` | `book` | book (copper-bound book with a "?") |
| `icon_status` | `writable_book` | book and quill (copper clipboard; Status tab) |
| `icon_settings` | `comparator` | comparator (copper cog; Settings tab) |
| `craft_arrow` | `spectral_arrow` | spectral arrow (grey crafting arrow; the Crafter's arrow) |

Why these base items: each hints at the meaning for a vanilla client without the pack (arrow = crafts, book and
quill = status, comparator = settings, chest = inventory). The old Back icon (`icon_back`, base `arrow`: model,
texture, generator code, `arrow.json` override) was removed with the Back button. Two icons on one base item is one `select` with several cases.
The removed `held_empty` icon (glass bottle) went with the held cell.

Why `assign_tools` / `assign_seeds` / `assign_tree` exist: the screenshot review showed the Farmer's two chest buttons
on the same generic chest icon and the Lumber tree button on the copper cube (`assign_block`); each now has its own
icon, base item picked so a vanilla client also sees a hint of the meaning. `tools/icons/make_icons.py` generates every
texture (including the tab-bar icons, drawn for the simulator and ported 1:1, pixel-identical) and rewrites the
`tools/icons/preview*.png` contact sheets.

Profession -> icon: `profession_<profession id path>` (`GolemIcon:profession`), `profession_none` when there is no
such icon, so a new profession only needs its texture, model and a `GolemIcon` entry plus a base-item case.
Assign icon: `assign_<AssignSpec.key()>` if `GolemIcon` has one (`GolemIcon:byName`; `input` / `output` so the two
Transporter buttons differ), else by `AssignSpec.kind()`: CHEST, CRAFTING_TABLE, BLOCK, AREA. A new profession with
other keys needs no GUI change.

Filler is not an icon: `GolemIcon:filler` is a plain vanilla orange pane (tooltip hidden, no custom model data, no
override file), `GolemIcon:barFiller` the black one. Why: the vision check showed the custom filler texture read as an
item. Orange (was gray) because the user wanted the filler more visible; black beside the tab bar so the bar reads as
another part of the UI.

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
- The confirm screen cannot change the window title in place (vanilla has no packet for it), so it keeps `Golem — <current>`.
- Unverified: the menu was compiled and the build's GameTests pass, but no vanilla client was run against the tabbed
  menu (in-place switching relies on `broadcastChanges` resyncing every changed slot; the client's own prediction of the
  clicked icon is corrected the same way as before).
