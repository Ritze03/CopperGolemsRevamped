# Stored XP and XP bottles

Butchers and Smelters keep the experience they earn in a per-golem store and pay it out as Bottles o' Enchanting
(`Items.EXPERIENCE_BOTTLE`). No orbs are ever spawned by a golem's work. Code: `core/GolemXp` (conversion), `GolemData:xp`
(the store), `profession/smelter/SmelterLogic`, `profession/butcher/ButcherLogic`, `mixin/AnimalMixin`.

## Rules

- `GolemData.xp`: an int, optional codec field `xp` defaulting to 0, saved like the other fields.
- `GolemXp:XP_PER_BOTTLE` = 7. Why 7: a thrown Bottle o' Enchanting gives 3 to 11 XP, 7 on average, so the conversion is
  roughly XP-neutral. Whole bottles only; the remainder stays stored.
- **No glass bottles are needed.** The golem creates the bottles from its XP. Why: user decision ("remove the bottle part from
  the input chest, he should just spawn the XP bottles in"); a bottle input chest was considered first and dropped.
- Why bottles and not orbs: user request ("XP bottles would feel more vanilla"); orbs on the ground are lost, bottles can be
  collected, stored and used.
- A full output chest keeps the XP stored; nothing is lost. The store saturates at `Integer.MAX_VALUE` (no other cap).

## Where XP comes from

- **Smelter**: each furnace keeps storing its XP the vanilla way (`recipesUsed`). When the golem collects a result
  (`SmelterLogic:collect`) it takes the furnace's XP out (`SmelterLogic:takeFurnaceXp`: recipe XP x count, the fraction is
  rounded up with that probability, the same as vanilla's `createExperience`), adds it to the store and clears the map through
  `mixin/AbstractFurnaceAccessor`. So the XP never piles up in a furnace the golem serves, and no orb spawns. This holds for every
  furnace type. A player taking results out of a furnace by hand still gets vanilla XP.
- **Butcher kill**: `ButcherLogic:strike` adds `getExperienceReward(level, golem)` of the victim (1 to 3 for a farm animal) when
  it died, as if a player had killed it. Vanilla gives none (needs a recent player hit), so without this the XP was lost.
- **Butcher breeding**: vanilla spawns the 1 to 7 XP breeding orb in `Animal:finalizeSpawnChildFromBreeding` even without a
  player (only the advancement needs one), so these orbs did appear. `mixin/AnimalMixin` redirects the `addFreshEntity` of that
  orb to `ButcherLogic:takeBreedingXp`: for a pair a Butcher fed (`ButcherLogic.fedBy`, remembered for 1200 ticks when
  `feed` sets the animal in love) the value goes into that golem's store and no orb spawns. Any other breeding is untouched.

## Delivery

- **Smelter**: slot 8 (`SmelterProfession:XP_SLOT`) is the XP bottle slot, at (3,8). Its own slot group `xp` (take-only for
  players: filter `s -> false`, like the Crafter output), no label (Why: user found the label redundant, the bottles in the slot are enough). Each tick `GolemXp:fillSlot` converts
  whole bottles from the store into real items there (up to a full stack, only while the slot is empty or holds bottles); the
  remainder and anything that does not fit stays stored. `SmelterLogic:deliverSlots` includes slot 8, so `DELIVER` takes the
  bottles to the output chest together with the 3 shared result slots (slots 9..11, row 4 cols 3..5, see
  [smelter.md](smelter.md)). Why a slot: the user wanted to see the bottle count in the slot at the right end of the
  furnace row. Why the results got their own 3 slots (user request: output slots "for reliability/speed"): the bottle slot
  stays exactly this one job, and the furnace buffers hold input only.
  Old data with another item in slot 8 is never lost: `SmelterLogic:sweepXpSlot` moves it into the result slots (and
  `DELIVER` takes whatever is left there), then the bottles follow.
- **Butcher**: no bottle slot; at DELIVER (`ButcherLogic:deliver`, also entered with an empty carry when whole bottles are
  ready) `GolemXp:deliver` creates `floor(xp / 7)` bottles and inserts them into the output chest with the carry; the store is
  reduced only by what was inserted. Why simpler than a slot: the Butcher's inventory has no spare slot and the carry is for loot.
- **Profession change / death**: `GolemService:drop` gives the whole bottles to the player (like the items; at the golem
  without a player) and spawns the remainder below 7 as orbs at the player (or the golem), then the store is 0. Freeze does not
  matter.

Bottle delivery to a chest is part of the golem's transfer budget (`GolemXp:deliver` has a `limit`); creating the bottles is not. See [../architecture/transfer-limit.md](../architecture/transfer-limit.md).

## Keep layout

With Keep layout (storing) on ([keep-layout.md](keep-layout.md)), `GolemXp:deliver` passes `keepLayoutStore` to `TransporterLogic:insertAll`: bottles only merge into non-empty bottle stacks of the output chest. With none, the chest counts as full and the XP stays stored.

## Status tab

When `xp > 0` the Status tab shows an `experience_bottle` icon "Stored XP: N" (`menu/GolemViews:status`), one more generic
cell before the Next compass (at most 4 cells for these professions, which `statusColumns` supports).

## Tests (`XpTest`, plus `SmelterTest`/`ButcherTest`)

`smelterMakesBottlesFromFurnaceXp` (14 ore on a furnace and a blast furnace: bottle in the output, no orbs within 16 blocks,
furnace maps empty, remainder < 7), `smelterXpSlotDeliversAndKeepsOldItems`, `smelterFullOutputKeepsTheXp`,
`butcherKillsFillTheStoreAndMakeBottles`, `butcherTakesTheBreedingXp`, `butcherFullOutputKeepsTheXp`,
`butcherDeliversBottles`, `professionChangeGivesTheBottles`, `xpSurvivesSaveLoad`.

## Use as Mending repair

Bottles in a tool profession's tool chest repair its Mending tools, 7 XP each (14 durability via the vanilla conversion). The
repair spends `GolemData.xp` first and opens a bottle (+7 to the store) only when that is short, so no XP is wasted (user:
"no XP is ever wasted"); the leftover stays in the store. Butcher kill/breeding XP can be used this way before it is bottled.
See [mending.md](mending.md).
