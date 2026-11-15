# Chest Sorter profession

Vanilla sorting behaviour (`runsVanillaBrain = true`, no assigns, tick is a no-op) plus one GUI slot that
holds up to 64 waxed copper blocks. Each block raises the number of chests the golem visits before it
gives up and wanders: limit = **10 + N**, so 0 blocks = 10 (vanilla), 64 blocks = 74.

## Setup rule (what vanilla requires)

Chest Sorter adds nothing to the sorting itself, so a setup that does not match vanilla's rule looks like
"does nothing". The rule (`CopperGolemAi`, `TransportItemsBetweenContainers`):

- **Source**: a copper chest (`BlockTags.COPPER_CHESTS`: any oxidation stage, waxed or not) holding items.
  The golem takes up to 16 of the first non-empty slot.
- **Destination**: only a regular `Chest` or `TrappedChest`. Barrels, shulker boxes, hoppers and copper chests
  are never destinations.
- The destination accepts the item only if it is **empty or already holds the same item type** in some slot
  (components ignored). A chest full of other things is skipped, and the golem tries the next one.
- Both within 32 blocks horizontally and 8 vertically; not locked or blocked (block above a chest), a double
  chest counts as one.
- After visiting 10 chests (plus waxed blocks in the slot) with no match, or when nothing is reachable, the golem
  wanders for 140 ticks before trying again. A fresh golem also waits 60-99 ticks before its first trip.
- The golem must not be following the player, and (Chest Sorter or not) panicking stops the behaviour.

Why: vanilla sorting is the whole feature; the mod only raises the visited limit.

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
- `sortsFreshGolem`, `sortsWaxedOxidizedSourceIntoMatchingChest`, `sortsAfterBeingTransporter`: end-to-end.
  Copper chest with 10 cobblestone and a wooden chest (empty, or holding 1 cobblestone) 5 blocks apart; golem
  spawned normally, profession set via `GolemService:changeProfession` (the last one after a Transporter
  stint with navigation active); succeed when the source is empty and the destination holds everything.
  These pass, which shows the brain, mixin and profession switching work.
