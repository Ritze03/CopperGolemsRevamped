# Crafter profession

Fetches ingredients from an input chest, crafts at an assigned crafting table and delivers the results to an output
chest. Id `coppergolemsrevamped:crafter`, `runsVanillaBrain = false`.

## Entry points

- `profession/crafter/CrafterProfession:init` declares it. `GolemData.items`: indices 0..8 = `template` (max stack 1,
  any item), 9..17 = `input` (one slot per grid cell, max 64), 18 = `output` (one stack). Constants:
  `CrafterProfession:FIRST_INPUT`, `OUTPUT_SLOT`, `TOTAL_SLOTS` (19).
- Assigns: `input` (`Kind.CHEST`), `table` (`Kind.CRAFTING_TABLE`, gets the `assign_crafting_table` icon),
  `output` (`Kind.CHEST`).
- `profession/crafter/CrafterLogic:tick` is the loop. Helpers: `plan`, `canCraft`, `craft`, `fetch`, `deliver`.
- GUI (Inventory tab): the 9 input slots are ONE 3x3 grid at rows 2..4, columns 1..3 whose cells remember their item
  (the template slots are the memory and are not shown); a crafting arrow at (3,5) names the recipe outcome, the output
  at (3,7) shows a ghost preview while empty. Details and the display-copy safety rules in [gui.md](gui.md).

## The three slot groups

- **Template** (0..8): real items that only define the recipe, never consumed, dropped like any inventory when the
  profession changes (`GolemService:changeProfession`) or the golem dies. Not shown in the GUI (a hidden `SlotGroup`):
  the input cells are its memory. A player sets cell `i` by placing an item into the empty cell (`Cell.Store:set`) and
  clears it with an empty-handed click on the empty cell. Shift-click never fills it (`SlotGroup:noQuickMove`). Why: user spec "a 3x3 crafting template of real items that only defines the recipe; the
  golem doesn't consume them" and D13.
- **Input** (9..17): slot `i` holds only the item of template cell `i` (same item and components,
  `ItemStack.isSameItemSameComponents`); a slot whose template cell is empty stays unused. Enforced for players by
  `SlotGroup:remembers` (`Cell.Store:mayPlace`): a cell with a memory accepts only that item, one without accepts anything and
  learns it; shift-click only fills a cell that already remembers something. A repeater
  (torch, redstone, torch / stone x3) fills six slots: 3 stone stacks, 2 torch stacks, 1 redstone stack.
  Why: user feedback from play-testing: with the old shared 9-slot buffer the golem filled every slot with raw
  ingredients and froze when full; "each slot of their crafting inventory should be for a single item of the grid".
- **Output** (18): one stack, take-out only for players (filter rejects everything). The golem crafts into it. While empty
  the GUI shows `CrafterLogic:preview` (the outcome of `plan`) as an untakeable ghost.

## Recipe matching

`CrafterLogic:plan` builds a `CraftingInput` from the template (cell `i` = grid row `i / 3`, column `i % 3`, one copy
per stack), trimmed with `CraftingInput.ofPositioned(3, 3, ...)` like the vanilla crafting menu, so a recipe drawn
anywhere in the grid matches. The recipe comes from `RecipeManager.createCheck(RecipeType.CRAFTING)` ->
`CachedCheck:getRecipeFor`: it memoises the last matching recipe and still sees a `/reload`. `CraftingRecipe:assemble`
gives the result, `getRemainingItems` the leftovers, mapped back to template cells through `Positioned:left/top`. The
`Plan` carries the result, the 9 template stacks and the 9 per-cell leftovers. No recipe = see "No recipe" below.

## Loop

Stateless decision each tick (`CrafterLogic:decide`) from the slots, the input chest and the plan; only the cooldown and
the walk-progress counters are transient (`WeakHashMap`, like the Transporter). Order of the checks:

1. **Stale input** (`stale`): an input slot holds something that is not its cell's item (the player edited the
   template, or a leftover sits there) -> DELIVER.
2. `canCraft` (every used cell's input slot holds its item, the result fits the output stack, each leftover has a
   place) -> CRAFT: walk to the table, then every 10 ticks one `craft`: 1 item from each used input slot, result
   added to the output stack.
3. Output cannot take another result (full, or holds a different item) -> DELIVER the output (or idle when empty).
4. `canFetch` (a used slot is below its target and the chest holds the item) -> FETCH.
5. Output non-empty (nothing more to craft, e.g. input chest ran dry) -> DELIVER. Else idle.

All walking goes through `Interact:approach` (1-block stand spot, 2-block fallback, see
[../architecture/interaction.md](../architecture/interaction.md)); the input chest, the output chest and the table
are all reached that way. Lids: `Interact:openChest` with `TAKE` at the input chest after a fetch and `INSERT` at the
output chest after a delivery that moved something; the cooldown after either is `Interact.OPEN_TICKS`, so the golem
stands there while the lid is open.
Why this loop: user spec "craft until the output stack is full, then go to the chest; when the output is full, move to
the next block".

**Fetch** (`fetch`): tops each used slot up to `target`: a full stack of the cell item, capped to the crafts that fill
the output (`outRoom / result count`, e.g. sticks: 16 crafts = 16 planks per cell) and to 1 for a cell that leaves a
leftover. Items move one at a time round-robin over the cells. Why round-robin: 8 planks for two cells must end 4 + 4;
the first cell taking all 8 would leave the recipe uncraftable.

**Deliver** (`deliver`): stale input stacks plus the output slot go to the output chest through
`TransporterLogic:insertAll`; what does not fit stays in its slot. A full output chest never freezes the golem: it
keeps the product and retries every 40 ticks (and keeps crafting only while the output stack has room), resuming once
space appears. With nothing to deliver and nothing to craft it idles, retrying every 40 ticks.

**Leftovers** (empty bucket, bottle): the leftover goes into the input slot it came from once that slot is empty
(`target` 1 keeps it empty after one craft); it no longer matches the cell, so the stale rule delivers it with the
next trip. A slot overstocked by a player (more than 1 for a leftover cell) also counts as stale and is delivered, then
refetched as 1. Why: lossless without a place to park leftovers; cost: such recipes (cake, honey block) run one craft
per trip.

**No recipe** (empty or invalid template): every input slot and the output go to the output chest
(`CrafterLogic:decide` is skipped, job `DELIVER_ALL`), then idle. Missing output chest = idle. Why: otherwise
clearing the template strands the items. With a valid recipe but a missing input/output chest or a table block that is
not a crafting table, the golem idles and keeps its items (nothing crafted, nothing lost).

Saved golems from the older 18-slot layout (9 shared buffer slots) are grown to 19 slots on the next tick; their old
buffer items sit in input slots and are delivered by the stale rule.

`GolemData` is saved after every mutation. Order matters for no loss/duplication (D9): items only move slot to slot or
chest to slot through code that leaves the remainder where it came from.

Give-up rule (`CrafterLogic:walkTo`): when `Interact:approach` has not brought the golem closer to the target for 200
ticks (airborne ticks do not count, so a just-spawned golem is not penalised; a null path is just "no progress") the golem
idles 600 ticks and starts over, so an unreachable chest never hard-locks it. `Interact.Approach` also has its own
fallback to the 2-block reach.

Known limits: two template cells of the same item each need their own stack, so a scarce item is split evenly and the
craft count is the smallest slot; a recipe with 9 cells needs all 9 input slots.

## Tests (`CrafterTest`)

- `craftsSticksUntilOutputFull`: 40 planks, sticks template; the output reaches 64 sticks, is delivered, then 16 more;
  the output chest ends with exactly 80 sticks, input chest and golem empty, template untouched.
- `craftsRepeaterWithPerCellSlots`: repeater template (cells 0..5); every tick the input slots hold only their cell's
  item (cells 6..8 empty), 4 of each are seen, 4 repeaters are delivered.
- `fullOutputChestKeepsProductAndResumes`: output chest full of cobblestone; after 400 ticks the golem still holds all
  16 sticks; after one chest slot is freed they are delivered.
- `noCraftWithoutTable`, `noCraftWhenTableReplaced`: nothing crafted, planks conserved.
- `deliversEverythingWhenTemplateCleared`: input and output slots all end in the output chest.
- `staleInputItemsAreDelivered`: wrong items in input slots are delivered, the matching stack stays, nothing lost.
- `lidsOpenAndReachIsOneBlock`: both chest lids seen open from a 1-block spot, the output stack only grows while the
  golem is within 1 block of the table, lids end closed.
- `guiLayoutIsOneRememberingGrid`: one grid at rows 2..4 / cols 1..3 (faded copies for remembered empty cells, memory-less
  cells accept anything), arrow at (3,5) with the outcome in its lore, ghost output at (3,7), filler elsewhere.
- `shiftClickFillsRememberedInputOnly`: shift-clicked planks land in the plank cell's input slot, sticks (no remembering
  cell) stay in the inventory, no memory is set. The click and ghost tests are in `MenuTest`, see [gui.md](gui.md).
- `itemsDropOnProfessionChange`: `changeProfession(..., null)` drops template, input and output items and clears the list.
