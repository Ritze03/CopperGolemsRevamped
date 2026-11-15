# Butcher profession

Keeps an animal pen at a fixed size: kills the surplus adults with a sword or axe (Looting works), breeds new animals with
food from a chest and delivers the drops to an output chest, in a fixed cycle (breed, kill, deliver). Id `coppergolemsrevamped:butcher`,
`runsVanillaBrain = false`. Closest templates: [cleaner.md](cleaner.md) (area, delivery) and [lumber.md](lumber.md)
(phase machine, drops straight into the carry, tool from a chest, worn-tool swap, `ai/ToolSwing`, hand display).

## Entry points

- `profession/butcher/ButcherProfession:init` declares it. Slot groups (Lumber layout style, see [gui.md](gui.md)): `tool`
  (1 slot, max stack 1, filter `ButcherLogic:isWeapon` = `ItemTags.SWORDS` or `AXES`, shown in the hand), `food` (2 slots
  x64, filter `ButcherLogic:isFood` = the cow/sheep/pig/chicken/goat/rabbit food tags), `carry` (9 x64, any item).
  Inventory indices: `TOOL_SLOT` 0, `FOOD_FROM` 1..2, `CARRY_FROM` 3..11.
- Assigns: `area` (`Kind.AREA`, max 16x16, same key and box rule as the Cleaner: `ButcherLogic:box` calls
  `CleanerLogic:box`, y range = the clicks plus 2 blocks above), `tools` (CHEST, icon `assign_tools`), `food` (CHEST, generic
  chest icon), `output` (CHEST, `assign_output`). Help texts are passed in the declaration (profession lines and per-assign).
- `profession/butcher/ButcherLogic:tick` is the loop; `ButcherLogic:status` feeds the Status tab (`ProfessionStatus`),
  e.g. "Culling cows (11 adults, keeping 8)", "Breeding pigs", "Delivering to the output chest".
- Icon `profession_butcher` on base item `iron_sword` (copper-hilted sword with a ham badge, drawn in
  `tools/icons/make_icons.py:sword`).

## Which animals

`ButcherLogic:FARM_ANIMALS`: cow, mooshroom, pig, sheep, chicken, rabbit, goat. Only animals whose centre is inside the box.

Why a list and not "every `Animal` with breeding food": wild wolves, polar bears and hoglins hit back, cats, parrots,
axolotls and turtles are pets or protected in practice, horses and llamas are mounts. A pen manager must never surprise
the player by killing those.

Never touched (`ButcherLogic:isProtected`): named animals, owned animals (`OwnableEntity` with an owner), leashed,
ridden or rider-carrying animals. Why: never kill a player's pets. Babies (`isBaby`) are never targeted, never fed and
not counted as adults.

## Keep 8 adults per kind

Adults are counted per `EntityType`. Only unprotected adults count towards the 8: `KEEP_ADULTS` = 8 stay alive, the rest
is culled (`ButcherLogic:surplus`). Why per kind: the user's wording ("leave at least 8 grown animals alive") is ambiguous;
per kind is the lead's default (a mixed pen would otherwise lose a whole species). Why protected animals do not count: a
named cow is a pet, not part of the herd, so the herd still keeps 8 killable-class adults next to it.

Kill order (`ButcherLogic:betterVictim`): the animal already being hit, then adults on a breeding cooldown (`getAge() > 0`
for an adult), then the nearest to the golem. Why the cooldown ones first (user request: "always prioritize unbreedable
animals"): they cannot breed for 5 minutes, so culling them keeps the breedable adults in the pen. The count rule (keep 8)
and every exclusion are unchanged. An animal being hit stays the target until it dies, as long as the kind still has a surplus.

## Pushing

The Butcher only pushes animals away; animals do not push it (user request: "He shouldn't be pushed by them. Players should
still be able to push him"). Players and non-animal entities push it as in vanilla. `Entity:push(Entity)` moves both
entities, each only if its `isPushable()` holds, and both directions (the animal's `pushEntities` and the golem's) end in it.
`mixin/EntityPushMixin` marks the Butcher in `ButcherLogic.pushImmune` for the duration of a push between a Butcher and an
`Animal` (`ButcherLogic:butcherAmongAnimal`), and `mixin/LivingEntityPushMixin` makes `isPushable` false for that entity
while marked, so only the animal gets its push. Why not make the golem never pushable: players must still push it. Tests:
`crowdDoesNotMoveTheButcher` (6 cows, frozen golem moves < 0.05), `pushIsOneSidedOnlyAgainstAnimals`.

## Weapon, hits and Looting

- Each hit is one `ToolSwing` swing (12 ticks, the hit lands on the snap tick `ToolSwing#RAISED_TICKS`) if the animal is
  within `REACH_SQR` (2.2 blocks). Damage = 1 + the weapon's attack damage modifiers, then `EnchantmentHelper:modifyDamage`
  (Sharpness) and the item bonus (`ButcherLogic:attackDamage`). A cow (10 health) dies in 2 hits with an iron sword, so a
  kill takes about 25 ticks plus the walk; the weakest sword needs 2 to 3 hits.
- Durability: `Weapon#itemDamagePerAttack` (1 for swords and axes) goes to the real stack in the tool slot
  (`hurtAndBreak`), a broken weapon empties the slot.
- Worn weapon (`nextDamageWillBreak`) or empty slot: only when something is to be culled, the golem fetches one from the tool
  chest; a worn one goes into the freed chest slot (nothing lost), as in Lumber. Without a weapon it does not kill.

### How Looting is applied (the mechanism)

In 26.3 the loot function `enchanted_count_increase` (cow leather and so on) reads the level through
`EnchantmentHelper:getEnchantmentLevel(looting, attacker)` where the attacker is the loot context's `ATTACKING_ENTITY` =
`DamageSource#getEntity`, which looks at the attacker's equipment (main hand). `DamageSource#getWeaponItem` is the direct
attacker's main hand too. The golem's main hand normally holds the display copy ([gui.md](gui.md), `GolemHandDisplay`):
it carries the same enchantments but is synced only every 10 ticks, so right after a weapon swap it is stale or empty.

So `ButcherLogic:strike` puts the real stack from the tool slot into the main hand for the hit
(`setItemSlot(MAINHAND, realStack)`), calls `hurtServer` with the item's damage source (`ItemStack#getDamageSource`), and
puts the display copy back in a `finally`. Death loot is rolled synchronously inside `hurtServer`, so the swap covers it.
Why not just rely on the display copy: stale after a swap. Why not keep the real stack in the hand: the hand must keep
showing a display copy that can never drop (death or statue conversion would drop a real tool).
Test hooks: `ButcherLogic:lastLooting` (the level read at the last strike), `handWasReal`, `strikes`.

XP: `dropExperience` needs `lastHurtByPlayerMemoryTime > 0`, which a golem kill never sets, so a kill gives no orbs. The
golem adds the victim's `getExperienceReward` to its XP store itself, and the breeding orb is taken too; the store is paid
out as XP bottles, see [xp-bottles.md](xp-bottles.md).

## Breeding

Why a pair gets fed and vanilla does the rest: `Animal#setInLove(null)` puts an animal in love mode and vanilla's own
breed goal makes two in-love animals of a kind walk together and spawn the baby, with vanilla cooldowns and age.

- Eligible (`ButcherLogic:eligible`): unprotected adult, `getAge() == 0`, `canFallInLove`, not skipped.
- A kind is bred only while `ButcherLogic:breedAllowed` holds: fewer than `MAX_BABIES` (8) babies and fewer than
  `MAX_POPULATION` (24) animals (adults + babies, protected ones included). Why: so a pen never explodes. Steady state: 8
  adults stay, the babies grow up, the surplus adults are culled and their drops delivered.
- Breeding comes first in the cycle, also for a kind with a surplus. Why: the user asked for the order breed, then kill; the
  surplus is culled in the same cycle anyway.
- Needs two food items in the food slots that the kind accepts (`Animal#isFood`). The first fed animal is the eligible one
  nearest the golem, the second the one nearest to it. One food per animal is consumed, `Animal#setInLove`, then the golem
  walks to the second.
- Food comes from the food chest: `ButcherLogic:fetchFood` takes up to `FOOD_FETCH` (16) of one chest slot into a food slot
  holding the same item, else an empty one, else the first slot is emptied back into the chest first (it checks beforehand
  that the chest can take it). Nothing is fetched when nothing in the chest suits a kind that wants to breed.

## Cycle

Why a phase machine: user request (a cycle "1. Breed 2. Kill", like Lumber) instead of one action per tick in priority order,
which interleaved the jobs. Per-golem transient state in a `WeakHashMap` (a reload restarts at BREED); persistent state is
only `GolemData`. `ButcherLogic:Phase`: **BREED -> KILL -> DELIVER -> (WAIT) -> BREED**. `ButcherLogic:phaseOf` and
`ButcherLogic:phaseListener` (called on every change) are test hooks, as in Lumber.

0. Area or output chest missing: stop, status "Waiting for targets", retry in 40 ticks. (The tool and food chests are
   optional: without them the golem just cannot fetch.)
1. **BREED** (`ButcherLogic:breed`): fetch food if needed, feed every eligible pair once within the caps, then move on. No
   food, nothing eligible or all kinds at cap: the phase is skipped.
2. **KILL** (`ButcherLogic:kill`): fetch or swap the weapon if needed (only with a surplus and no usable weapon), then
   cull the nearest surplus adult, again and again, until none is left or the carry has fewer than `MIN_FREE_CARRY` (2)
   free slots (one kill drops up to two kinds, leather and beef). No weapon: skipped. Why the room rule: the drops go into
   the carry, so a kill without room would drop them on the ground.
3. **DELIVER** (`ButcherLogic:deliver`): whenever the carry holds anything or whole XP bottles are ready, even a little, walk to the output chest
   (`Interact:approach`, `insertAll`, lid animation) once. Leftovers stay in the carry when the output is full.
   KILL always ends in DELIVER, so a kill is never followed by BREED directly.
4. **WAIT**: when the whole cycle did nothing (nothing fed or fetched, no kill, nothing delivered) the golem idles
   `WAIT_TICKS` (100 = 5 s), status "Herd is in balance" (or "Output chest is full"), then starts over. Why 100: nothing in
   a pen changes faster (breeding cooldown 5 min, calves 20 min), so the old 20-tick rescan was wasted work; 5 s still
   notices new animals, food or a weapon quickly. A cycle that did something goes straight to BREED.

Never blocks: no weapon still breeds, no food still kills, a full output keeps the carry.

No ground pickup: the old separate pickup step is gone. Stray drops in the area (items the player threw
in) are left alone: the Cleaner exists for that, and walking after items would break the cycle. Why not "during DELIVER's
walk": it is not on the path, so it is not trivial.

Chest moves are budgeted (16 items per 5 ticks per golem): delivery (carry and XP bottles), food fetch and weapon fetch. A cut visit stays in its phase at the chest and goes on next window; `fetchFood` / `fetchWeapon` then return true (busy), because false means "nothing to fetch". Kill drops captured into the carry are not counted. See [../architecture/transfer-limit.md](../architecture/transfer-limit.md).

### Drops go straight into the carry

Why: user request ("items should just go straight to his inventory, like it does for the Lumber"). Death loot is rolled
inside `hurtServer` -> `dropAllDeathLoot` -> `dropFromLootTable`, whose consumer calls `Entity:spawnAtLocation`; every
overload of it funnels into `spawnAtLocation(ServerLevel, ItemStack, Vec3)`. `mixin/EntityMixin` injects at the head of that
one and calls `ButcherLogic:capture(entity, stack)`: it returns true only while `ButcherLogic:strike` has marked that very
animal as the victim (`captureVictim`, set just around `hurtServer`, server thread), then the stack is kept and no
`ItemEntity` is spawned. After the hit `ButcherLogic:storeCaptured` merges the stacks into the carry
(`TransporterLogic:takeAll`); overflow, which the room rule makes rare, is dropped at the victim as a fallback.

Why this option: the loot is rolled by vanilla with the real weapon in hand, so Looting still applies and no `LootParams`
are rebuilt by hand; nothing is matched or guessed (capturing "items spawned that tick near the victim" could swallow other
items); the mixin is one inert-by-default injector. Dropped equipment (`dropEquipment`) is not captured, farm animals
carry none.

Unreachable animals and items: a chase without getting closer for 200 ticks gives up and ignores the target for 1200 ticks
(same rule as the Cleaner, `ButcherLogic:chase`). Animals flee after a hit (vanilla panic), so a kill sometimes needs a
short chase between hits; a swing already started still lands only if the animal is in reach at the snap.

Freeze: `core/GolemTicker` never calls the tick of a frozen golem ([freeze.md](freeze.md)); `ButcherTest:frozenButcherDoesNothing`.
D9 (no loss or duplication): items only move between slots, chests and carry (kill drops: captured, then stored or, on overflow, dropped); food is shrunk by exactly one per fed animal.

## Tests

`ButcherTest`, see [../architecture/testing.md](../architecture/testing.md). The test pen is 6x6 inside a 2-high stone wall
with the three chests set into the west wall.

## Mending

A Mending weapon is repaired with XP bottles from the **tools chest** and never used below 10 durability: it is parked in the
chest (or swapped for a usable weapon) and the golem culls bare-handed (1 damage per hit) meanwhile. Only a Mending weapon
enables bare hands; "Without a weapon it does not kill" still holds otherwise. See [mending.md](mending.md).
