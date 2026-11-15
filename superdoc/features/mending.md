# Mending for the tool professions

Lumber, Farmer and Butcher repair Mending tools with XP bottles from their tool chest and never use a Mending tool down to
its last points. Code: `core/GolemMending` (shared rules), used by `profession/lumber/LumberLogic`,
`profession/farmer/FarmerLogic`, `profession/butcher/ButcherLogic`. Tests: `MendingTest`. Index: [../README.md](../README.md).

Why: user request ("Mending should work for all the professions that use tools: XP bottles in the tool chest repair it, fixed
7 XP; they should never use a Mending tool until it breaks, drop it into the chest before it breaks and use their hands or a
different tool"). Bottles come from [xp-bottles.md](xp-bottles.md) (`GolemXp:XP_PER_BOTTLE` = 7).

## Repair

- Which chest: Lumber the **input chest**, Farmer and Butcher the **tools chest**. Bottles are only ever taken from that
  chest, never from the output chest.
- Conversion: the real vanilla one, `EnchantmentHelper:modifyDurabilityToRepairFromXp(level, stack, 7)` (the `repair_with_xp`
  effect of Mending), which is **14 durability per bottle** in 26.3 (2 per XP). `GolemMending:perBottle` asks the API, so a
  data pack changing the effect is respected. `MendingTest:guardAndConversion` pins the 14.
- A visit repairs the held tool first, then Mending tools lying in the chest (the profession's own tool kind only: axes for
  Lumber, hoes and axes for Farmer, swords and axes for Butcher), until whole, the XP and bottles run out or the
  transfer budget is used (1 item per bottle, so a big repair spans several windows like any cut visit, see
  [../architecture/transfer-limit.md](../architecture/transfer-limit.md)).
- **No XP is wasted** (`GolemMending:repair`). Why: user request ("no XP is ever wasted"); a whole bottle per repair burned
  7 XP for a tool missing 5 points. Repairs pay in XP: the durability missing is converted with the vanilla ratio (`perBottle`
  per 7 XP, `GolemMending:xpFor`, rounded up, the golem never goes past damage 0). The golem's stored XP (`GolemData.xp`, see
  [xp-bottles.md](xp-bottles.md)) is spent first, free of transfer budget; only when it does not cover the damage a bottle is
  taken from the chest (+7 to the store, 1 budget), then spent again. The leftover stays stored for the next repair (saved with
  the golem; dropped like any stored XP on profession change or death, `GolemService:drop`). Example: 5 points missing costs
  3 XP, 4 stay stored; 4 more points missing are paid from the store, no bottle. If no bottle is left the store is still spent
  (partial repair). `canRepair` is true with stored XP alone, so no bottle in the chest is needed.
- Butcher: its kill/breeding XP is the same store, so a repair can use it before it is bottled (vanilla-like: Mending eats XP
  orbs). Bottling the rest (whole 7s, `GolemXp:deliver`) is never blocked by this, only less XP is left to bottle. Lumber and
  Farmer have no bottle slot and never bottle (`GolemXp:fillSlot` is Smelter only, `deliver` Butcher only), so their XP stays
  invisible in the store until used or dropped.
- A tool is only *worth* a visit when it is resting (below) or lacks at least one bottle's worth (`GolemMending:wantsRepair`).
  Why: otherwise the golem would walk back after every hit to burn a bottle on 1 point. Once a repair has started it goes
  to full.
- When it visits: Lumber while felling (logs left), Butcher when a cull is needed, Farmer when the field has work for a tool
  (tillable ground or a fruit block), i.e. exactly when a tool fetch could happen. A chest holding bottles and a damaged
  Mending tool is the extra reason to go there. No bottles, no visit.
- Lumber also repairs **while waiting for the sapling to grow** (BONEMEAL, when not applying bone meal and nothing is left to
  collect): any damaged Mending axe, held or in the input chest, is topped up to full with `LumberLogic:mendVisit`, even when it
  misses less than one bottle's worth. Why: user request, the idle wait is free time, so the "not worth a trip" rule
  (`wantsRepair`) does not apply there. It does not call `GolemMending:repair` (that keeps the rule); it uses only
  `hasMending`, `perBottle` and `bottlesIn`. Budget rules as usual (1 item per bottle). Test: `LumberTest:repairsMendingAxeWhileWaitingForGrowth`.

## Top-up while idle

Why: user request, "while waiting, it should also just repair mending tools instead of waiting". The one-bottle rule above
exists so a golem does not walk back after every hit; idle golems have nothing better to do, so they ignore it.

- Shared entry: `GolemMending:canRepair(..., topUp=true)` / `GolemMending:repair(..., limit, topUp=true)`: any damaged Mending
  tool (held, or of the profession's kind in the chest) is repaired to full while bottles last, whatever it misses.
  Transfer budget 1 per bottle, bottles only from the tool chest, a cut visit stays at the chest and goes on next window.
- Callers, each only in idle time: Lumber `LumberLogic:mendVisit` (waiting for growth, input chest), Butcher
  `ButcherLogic:mendVisit` (cycle did nothing, before WAIT, tools chest), Farmer step 6 of the loop in `FarmerLogic:tick`
  (no field work, tools chest). The felling/cull/field-time rules are unchanged.
- Tests: `LumberTest:repairsMendingAxeWhileWaitingForGrowth`, `MendingTest:butcherTopsUpSwordWhileIdle`,
  `MendingTest:farmerTopsUpHoeWhileIdle`.

## Never break a Mending tool

- `GolemMending:MENDING_SAFE_DURABILITY` = **10**. `GolemMending:canUse(tool)` is false for a Mending tool with 10 or fewer
  points left; every non-Mending tool is always usable (old behaviour: used until it breaks or hits the worn swap).
- Why 10: one use costs 1 point (hit, log, till, fruit), so 2 would already be enough; 10 leaves margin for tools that
  cost more per use or a use that starts just before the check, and is small next to the weakest tool (golden axe 32), so
  little durability sits unused. Mending refills it anyway once bottles arrive.
- A tool that must rest (`GolemMending:mustPark`): the golem goes to the tool chest and (1) repairs it if there are bottles,
  then keeps using it; else (2) swaps it for a usable tool (a non-Mending one, or a Mending one above the threshold; the
  resting tool goes into that chest slot); else (3) puts it into a free chest slot (`GolemMending:park`, 1 item of budget). If
  the chest is full the tool stays in the slot **unused**; it is never dropped on the ground.
- Last line of defence: every wear call goes through `GolemMending:damage`, which refuses a hit that would take a Mending tool
  to 0 (Lumber `breakBlock`, Butcher `strike`, Farmer till and fruit). The decision functions use `canUse` first, so the
  refusal only matters if a caller is ever wrong.
- Lumber's `axeSlot` and Farmer's `usable` return a resting Mending tool as "not a tool", so no path can pick it up.

## Fallback per profession (no usable tool)

| Profession | Without a usable tool |
|---|---|
| Lumber | chops by hand: destroy speed 1 (`breakTicks(hardness, 1)`, 60 ticks per oak log), no wear |
| Butcher | hits by hand for 1 damage (`ButcherLogic:attackDamage` base), status gets ", bare-handed (Mending tool needs repair)" |
| Farmer | harvests mature crops (never needed a tool); does **not** till or break fruit blocks (no hoe/axe) |

Bare-handed work happens **only because of a Mending tool** (`LumberLogic:mendingBlocked`, `ButcherLogic:mendingBlocked`): a
resting Mending tool in the slot, or an empty slot with an unusable Mending tool lying in the tool chest. Why: a golem that
never had a tool still does no tool work, as before ([lumber.md](lumber.md), [butcher.md](butcher.md)); this feature must
not change that.

Farmer detail: a parked hoe empties the slot, so the slot's type is gone; an empty slot takes the first usable hoe or axe from
the chest (see [farmer.md](farmer.md)), which can be a different type than before. Accepted: it is the existing empty-slot
rule.

## Status text

The Butcher shows "Repairing tool with XP bottles" during a repair visit and the bare-handed suffix while culling by hand
(`ButcherLogic:State.doing`). Lumber and Farmer have no per-step status (`profession/ProfessionStatus` shows the phase or a
fixed text), so nothing is shown for them; a status there would need a hook in `ProfessionStatus`.

## D9 (no loss or duplication)

A tool only moves slot to chest slot, never dropped; bottles are shrunk by exactly one per bottle opened (their 7 XP go into the store, then are spent as above); a chest that cannot take the tool leaves it in the slot. The tests assert exactly one Mending tool exists
every tick.

## Tests (`MendingTest`, 12)

`guardAndConversion`; Lumber: `lumberRepairsWithBottles` (damage 20, 5 bottles: 2 used, axe whole then 3 chops),
`lumberNeverBreaksMendingAxeAndChopsByHand` (threshold + 1, no other axe), `lumberSwitchesToPlainAxe`,
`lumberRepairsStoredMendingAxeAndReusesIt` (18 bottles, more than one window); Butcher: `butcherNeverBreaksMendingSword`,
`butcherRepairsWithBottles`; Farmer: `farmerReturnsMendingHoeAndDoesNotTill` (also: ripe wheat is still harvested by hand),
`farmerRepairsStoredMendingHoeAndReusesIt`; idle top-up: `butcherTopsUpSwordWhileIdle`, `farmerTopsUpHoeWhileIdle`; no waste: `mendingWastesNoXp` (5 points missing: bottle used, 4 XP stored; then 4 points from the store with no bottle; XP bottled = spent + stored).
