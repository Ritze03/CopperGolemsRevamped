# Butcher profession

Keeps an animal pen at a fixed size: kills the surplus adults with a sword or axe (Looting works), breeds new animals with
food from a chest, collects the drops and delivers them to an output chest. Id `coppergolemsrevamped:butcher`,
`runsVanillaBrain = false`. Closest templates: [cleaner.md](cleaner.md) (area, pickup, delivery) and [lumber.md](lumber.md)
(tool from a chest, worn-tool swap, `ai/ToolSwing`, hand display).

## Entry points

- `profession/butcher/ButcherProfession:init` declares it. Slot groups (Lumber layout style, see [gui.md](gui.md)): `tool`
  (1 slot, max stack 1, filter `ButcherLogic:isWeapon` = `ItemTags.SWORDS` or `AXES`, shown in the hand), `food` (2 slots
  x64, filter `ButcherLogic:isFood` = the cow/sheep/pig/chicken/goat/rabbit food tags), `carry` (9 x64, any item).
  Inventory indices: `TOOL_SLOT` 0, `FOOD_FROM` 1..2, `CARRY_FROM` 3..11.
- Assigns: `area` (`Kind.AREA`, max 16x16, same key and box rule as the Cleaner: `ButcherLogic:box` calls
  `CleanerLogic:box`, y range = the clicks plus 2 blocks above), `tools` (CHEST, icon `assign_tools`), `food` (CHEST, generic
  chest icon), `output` (CHEST, `assign_output`). Help texts are passed in the declaration (profession lines and per-assign).
- `profession/butcher/ButcherLogic:tick` is the loop; `ButcherLogic:status` feeds the Status tab (`ProfessionStatus`),
  e.g. "Culling cows (11 adults, keeping 8)", "Breeding pigs", "Collecting drops".
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

Kill order: the nearest surplus adult to the golem first (cheapest walking). An animal being hit stays the target until it
dies, as long as the kind still has a surplus.

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

No XP: `dropExperience` needs `lastHurtByPlayerMemoryTime > 0`, which a golem kill never sets, so animals killed by the
golem give no orbs. Breeding orbs from vanilla's breed goal stay on the ground and are ignored.

## Breeding

Why a pair gets fed and vanilla does the rest: `Animal#setInLove(null)` puts an animal in love mode and vanilla's own
breed goal makes two in-love animals of a kind walk together and spawn the baby, with vanilla cooldowns and age.

- Eligible (`ButcherLogic:eligible`): unprotected adult, `getAge() == 0`, `canFallInLove`, not skipped.
- A kind is bred only while `ButcherLogic:breedAllowed` holds: fewer than `MAX_BABIES` (8) babies and fewer than
  `MAX_POPULATION` (24) animals (adults + babies, protected ones included). Why: so a pen never explodes. Steady state: 8
  adults stay, the babies grow up, the surplus adults are culled and their drops delivered.
- A kind with a surplus and a weapon is not bred (the golem would feed animals it is about to kill); without a weapon a
  surplus never comes down, so breeding still goes on within the caps.
- Needs two food items in the food slots that the kind accepts (`Animal#isFood`). The first fed animal is the eligible one
  nearest the golem, the second the one nearest to it. One food per animal is consumed, `Animal#setInLove`, then the golem
  walks to the second.
- Food comes from the food chest: `ButcherLogic:fetchFood` takes up to `FOOD_FETCH` (16) of one chest slot into a food slot
  holding the same item, else an empty one, else the first slot is emptied back into the chest first (it checks beforehand
  that the chest can take it). Nothing is fetched when nothing in the chest suits a kind that wants to breed.

## Loop

Per-golem transient state in a `WeakHashMap`; persistent state is only `GolemData`. One action per tick, in priority order:

1. Area or output chest missing: stop, status "Waiting for targets", retry in 40 ticks. (The tool and food chests are
   optional: without them the golem just cannot fetch.)
2. Drops (`ItemEntity` inside the box, no pickup delay, fits the carry): chase the nearest, pick up within 1.5 blocks via
   `CleanerLogic:pickUp` / `fits`.
3. Fetch a weapon (only with a surplus and no usable weapon).
4. Cull the nearest surplus adult: needs a weapon and at least `MIN_FREE_CARRY` (2) free carry slots (one kill drops up to
   two kinds, leather and beef). Why: drops that cannot be picked up stay on the ground and are never lost, but a golem that
   keeps killing with a full carry would just litter the pen; with a full output chest it stops culling once the carry fills.
5. Breed (see above).
6. Deliver the carry to the output chest (`Interact:approach`, `insertAll`, lid animation); leftovers stay and are retried
   every 40 ticks.
7. Idle, status "Herd is in balance", rescan every 20 ticks.

Never blocks: no weapon still breeds and collects, no food still culls and collects, a full output keeps the carry.

Unreachable animals and items: a chase without getting closer for 200 ticks gives up and ignores the target for 1200 ticks
(same rule as the Cleaner, `ButcherLogic:chase`). Animals flee after a hit (vanilla panic), so a kill sometimes needs a
short chase between hits; a swing already started still lands only if the animal is in reach at the snap.

Freeze: `core/GolemTicker` never calls the tick of a frozen golem ([freeze.md](freeze.md)); `ButcherTest:frozenButcherDoesNothing`.
D9 (no loss or duplication): items only move between slots, chests and carry; food is shrunk by exactly one per fed animal.

## Tests

`ButcherTest`, see [../architecture/testing.md](../architecture/testing.md). The test pen is 6x6 inside a 2-high stone wall
with the three chests set into the west wall.
