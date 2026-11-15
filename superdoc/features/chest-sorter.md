# Chest Sorter profession

Vanilla sorting behaviour (`runsVanillaBrain = true`, no assigns, tick is a no-op, **no slot groups**: the GUI
is just the control row). The one change: the golem visits up to **64** chests before it gives up and wanders
(vanilla: 10).

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
- After visiting 64 chests (vanilla golems: 10) with no match, or when nothing is reachable, the golem
  wanders for 140 ticks before trying again. A fresh golem also waits 60-99 ticks before its first trip.
- The golem must not be following the player, and (Chest Sorter or not) panicking stops the behaviour.

Why: vanilla sorting is the whole feature; the mod only raises the visited limit.

## Entry points

- `profession/chest_sorter/ChestSorterProfession:init` declares it (id `coppergolemsrevamped:chest_sorter`, empty
  slot list).
- `ChestSorterProfession:visitedLimit` returns 64 for a Copper Golem with this profession, 10 for any other body.
- `ChestSorterProfession:migrateLegacyItems` drops items an old save left in the list (see below).
- `mixin/TransportItemsBetweenContainersMixin` applies the limit.

## Visited limit

Why: an earlier version let waxed copper blocks in a GUI slot raise the limit (10 + blocks). The user removed
the whole upgrade mechanic and set a fixed 64. The limit stays per golem (not a global constant change), so
other golems keep vanilla's 10.

## Mixin

`@ModifyConstant(method = "setVisitedBlockPos", constant = @Constant(intValue = 10))` on
`TransportItemsBetweenContainers`; the handler also takes the target's args (`body, level, target`) and
returns `visitedLimit(body)`. `setVisitedBlockPos` has exactly one `10`: the `size() > 10` check. The `50` in
`markVisitedBlockPosAsUnreachable` is a separate limit and is untouched.

Why: vanilla's limit is the bare literal 10 (`MAX_VISITED_POSITIONS` is inlined and unused), so the 11th
distinct chest triggers the 140-tick wander cooldown. The limit must be per golem, so the literal cannot just be changed globally: the mixin consults the body.
Golems without the profession get 10 back, i.e. unchanged vanilla.

The Chest Sorter is not subject to the mod's transfer limit: it runs vanilla's brain (one stack per trip). See [../architecture/transfer-limit.md](../architecture/transfer-limit.md).

## Old saves

Saves from the waxed-block version hold waxed copper blocks in `GolemData.items` index 0. `migrateLegacyItems`
drops them at the golem (`GolemService:dropAll`) and clears the list. Called from `GolemTicker:tick` (once per brain
step, an early return unless a sorter still holds items). Why there and not in `visitedLimit`: `GolemTicker` never calls
`Profession.tick` for vanilla-brain professions, and `visitedLimit` is a getter that the vanilla behaviour queries and
that must not mutate. Until the first tick the items stay in the save, and death or a profession change drops them as
for any profession (no item is lost). `GolemMenu` copes with zero slot groups (Inventory tab: "No inventory").

## Tests (`ChestSorterTest`)

- `visitedLimit`: no profession 10, sorter 64; the declaration has no slots and runs the vanilla brain.
- `mixinAppliesLimit`: a test subclass exposes the protected `setVisitedBlockPos`; with 63 seeded chests the 64th
  does not start the cooldown and the 65th does; a control golem seeded with 10 enters cooldown on the 11th.
  Fails if the mixin is not registered. Gotcha: a freshly spawned golem already carries
  `TRANSPORT_ITEMS_COOLDOWN_TICKS`, so the test erases it first.
- `legacyItemsAreDropped`: blocks in items[0] become item entities at the golem and the list is cleared by one
  `GolemTicker:tick`.
- `menuWithoutSlotGroups`: `GolemMenu` for a sorter and for a golem with no profession are the same 6-row menu
  (54 top slots plus the player inventory).
- `sortsFreshGolem`, `sortsWaxedOxidizedSourceIntoMatchingChest`, `sortsAfterBeingTransporter`: end-to-end.
  Copper chest with 10 cobblestone and a wooden chest (empty, or holding 1 cobblestone) 5 blocks apart; golem
  spawned normally, profession set via `GolemService:changeProfession` (the last one after a Transporter
  stint with navigation active); succeed when the source is empty and the destination holds everything.
  These pass, which shows the brain, mixin and profession switching work.
