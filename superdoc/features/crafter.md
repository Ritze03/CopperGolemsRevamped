# Crafter profession

Fetches ingredients from an input chest, crafts at an assigned crafting table and delivers the results to an output
chest. Id `coppergolemsrevamped:crafter`, `runsVanillaBrain = false`.

## Entry points

- `profession/crafter/CrafterProfession:init` declares it. Slot groups: `template` (9 slots, max stack 1, any item,
  GUI width 3 = a 3x3 square, see [gui.md](gui.md)) and `buffer` (9 slots x 64, width 9). `GolemData.items` indices
  0..8 are the template, 9..17 the buffer.
- Assigns: `input` (`Kind.CHEST`), `table` (`Kind.CRAFTING_TABLE`, gets the `assign_crafting_table` icon),
  `output` (`Kind.CHEST`).
- `profession/crafter/CrafterLogic:tick` is the loop. Public helpers: `recipeFor`, `plan`, `craftOnce`, `fetchSets`,
  `fetch`, `sets`, `buffer` / `storeBuffer`.

## Template: defines the recipe, never consumed

The template holds real items (the user can put any item in; `maxStack` 1). It only says which recipe to craft. The
golem never takes anything out of it, and it is dropped like any other inventory when the profession changes
(`GolemService:changeProfession`) or the golem dies.
Why: user spec: "a 3x3 'crafting template' of real items that only defines the recipe; the golem doesn't consume
them" and D13: "stored, never consumed, and dropped like any other inventory".

## Recipe matching

`CrafterLogic:templateInput` builds a `CraftingInput` from template slot `i` = grid row `i / 3`, column `i % 3`
(one copy of each stack), trimmed with `CraftingInput.ofPositioned(3, 3, ...).input()` like the vanilla crafting menu
does, so a recipe drawn anywhere in the grid matches. The recipe comes from the server's recipe manager:
`RecipeManager.createCheck(RecipeType.CRAFTING)` -> `CachedCheck:getRecipeFor(input, ServerLevel)`. That check
memoises the last matching recipe (an unchanged template costs one `matches` call) and still sees a `/reload`, which
a hand-rolled content cache would not. `CraftingRecipe:assemble(input)` gives the result,
`CraftingRecipe:getRemainingItems(input)` the remainders (e.g. empty buckets for milk). No recipe (or an empty
template) = the golem idles and retries every 40 ticks.

Ingredient matching is `ItemStack.isSameItemSameComponents` against the template's stacks, not item-only.
Why: the result was assembled from the template's exact stacks; fetching identical stacks guarantees the same
result and keeps variants (dyed armor, potions) from being mixed. Cost: a template item with components needs that
exact variant in the input chest.

## Loop

Stateless decision each tick (`CrafterLogic:decide`) from the buffer, the input chest and the recipe; only timers and
the walk-progress counters are transient (`WeakHashMap`, like the Transporter). Idle when the input or output is not
a container, the table block is not a crafting table, no table is assigned or there is no recipe.

1. Buffer holds a full ingredient set and one craft's products fit: CRAFT. Walk to the table (`inReach`), then every
   10 ticks remove one set of ingredients and add the result plus remainders to the buffer.
2. Otherwise the buffer holds a non-ingredient item (results, remainders, strays): DELIVER to the output chest with
   `TransporterLogic:insertAll`. Ingredients stay for the next craft, leftovers (chest full) go back to the
   buffer and are retried every 40 ticks.
3. Otherwise `fetchSets` > 0: FETCH. k = as many crafts as the input holds (counting ingredients already in the buffer)
   and the buffer can hold, with room kept for one craft's products so the buffer cannot clog with ingredients.
   Exactly k sets are taken, no more.
4. Else idle.

No valid recipe (empty or invalid template) with a valid output chest and a non-empty buffer: the whole buffer goes to
the output (`CrafterLogic:deliverAll`; with no recipe everything counts as product), then idle. Missing output = idle.
Why: otherwise changing or clearing the template strands the buffered items. With a valid recipe but an invalid
table the golem does not craft; its items stay in the buffer / input chest. A golem that is airborne (just spawned)
waits instead of treating the failed `createPath` as unreachable (that cost a 600-tick idle).

`GolemData` is saved after every mutation. Order matters for no loss/duplication (D9): the buffer is edited as a
copy and written back in one go; items that do not fit anywhere return to the source.

Give-up rule: walking uses `golem.getNavigation()`; a missing path or 200 ticks without getting closer makes the golem
idle for 600 ticks and then try again, so an unreachable chest never hard-locks it.

Known limits (ponytail): a recipe whose product is also one of its own ingredient items cannot be delivered (the
product counts as ingredient stock); a recipe with 9 distinct ingredient types leaves no buffer room for the result
and idles; a stray item a player puts into the buffer is delivered to the output chest.

## Tests (`CrafterTest`)

- `craftsSticksFromTemplate`: input 8 oak planks, template = oak planks in slots 0 and 3 (vertical pair = sticks),
  succeeds when the output holds exactly 16 sticks and nothing else, input is empty, template slots 0 and 3 still hold
  1 plank, the other template slots and the buffer are empty. maxTicks 1200.
- `noCraftWithoutTable`: same, table unassigned; after 300 ticks the output is empty and planks are conserved
  (input + buffer == 8).
- `bufferDropsOnProfessionChange`: buffer 5 planks + 3 sticks, template 2 planks; `changeProfession(..., null)` drops
  7 planks and 3 sticks, items cleared.
- `noCraftWhenTableReplaced`: table block replaced by stone right after setup; output stays empty, planks conserved
  (input + buffer == 8).
- `deliversBufferWhenTemplateCleared`: empty template, buffer 5 planks + 3 sticks; all of it ends in the output.
- `templateSlotHoldsOne`: builds a `GolemMenu` for a mock player; template slot max stack 1, `canTakeItemForPickAll`
  false for it and true for a buffer slot, buffer max 64, row 4 is filler.
- `templateDropsOnProfessionChange`: `changeProfession(..., null)` drops the 2 template planks as item entities and
  clears `GolemData.items`.
