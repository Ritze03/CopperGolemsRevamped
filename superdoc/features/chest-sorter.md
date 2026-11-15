# Chest Sorter profession

Vanilla sorting behaviour (`runsVanillaBrain = true`, no assigns, tick is a no-op) plus one GUI slot that
holds up to 64 waxed copper blocks. Each block raises the number of chests the golem visits before it
gives up and wanders: limit = **10 + N**, so 0 blocks = 10 (vanilla), 64 blocks = 74.

## Entry points

- `profession/chest_sorter/ChestSorterProfession:init` declares it (id `coppergolemsrevamped:chest_sorter`,
  slot group `waxed` = 1 slot, max 64, `data.items` index 0).
- `ChestSorterProfession:visitedLimit` returns the limit for a body (10 unless it is a Copper Golem with this
  profession and a waxed block in slot 0).
- `ChestSorterProfession:isWaxedCopperBlock` is the slot filter.
- `mixin/TransportItemsBetweenContainersMixin` applies the limit.

## Which blocks count

The four waxed full copper blocks, any oxidation stage: `Items.COPPER_BLOCK.waxed()` (`unaffected`, `exposed`,
`weathered`, `oxidized`). Unwaxed blocks, cut copper, raw copper blocks and other items are rejected. No item
tag covers exactly these, so the filter collects them from the collection record.

## Mixin

`@ModifyConstant(method = "setVisitedBlockPos", constant = @Constant(intValue = 10))` on
`TransportItemsBetweenContainers`; the handler also takes the target's args (`body, level, target`) and
returns `visitedLimit(body)`. `setVisitedBlockPos` has exactly one `10`: the `size() > 10` check. The `50` in
`markVisitedBlockPosAsUnreachable` is a separate limit and is untouched.

Why: vanilla's limit is the bare literal 10 (`MAX_VISITED_POSITIONS` is inlined and unused), so the 11th
distinct chest triggers the 140-tick wander cooldown. The user asked for "+1 chest per waxed copper block".
The limit must be per golem, so the literal cannot just be changed globally: the mixin consults the body.
Golems without the profession get 10 back, i.e. unchanged vanilla.

## Tests (`ChestSorterTest`)

- `visitedLimitScalesWithWaxedBlocks`: no profession 10, sorter with 0/5/64 blocks 10/15/74.
- `mixinAppliesLimit`: a test subclass exposes the protected `setVisitedBlockPos`; with 3 blocks and 12
  seeded chests the 13th does not start the cooldown and the 14th does; a control golem seeded with 10
  enters cooldown on the 11th. Fails if the mixin is not registered. Gotcha: a freshly spawned golem already
  carries `TRANSPORT_ITEMS_COOLDOWN_TICKS`, so the test erases it first.
- `waxedSlotFilter`: accepts the four waxed blocks, rejects unwaxed/raw/ingot/cobblestone/empty; `count == 1`,
  `maxStack == 64`.
