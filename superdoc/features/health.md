# Health

Every copper golem, with or without a profession, takes damage only from players, and slowly heals.

## Rules

- **Damage immunity:** `mixin/GolemDamageMixin` hooks `LivingEntity#isInvulnerableTo(ServerLevel, DamageSource)` (checked
  first by `hurtServer`) and returns true for a `CopperGolem` unless `source.getEntity()` is a `Player`. That covers player
  melee and projectiles a player shot (arrows, tridents, thrown items), because the attacking entity of a projectile hit
  is its owner. Suffocation, fall, fire, lava, drowning, mob attacks (incl. skeleton arrows), explosions not caused by a
  player, lightning, cactus and goat rams are all ignored.
- **Exception:** damage types tagged `DamageTypeTags.BYPASSES_INVULNERABILITY` (out of world / void, `/kill`) still apply, so
  a golem that falls into the void or is killed by command does not live forever.
- **Regeneration:** `mixin/CopperGolemMixin:cgr$regen` (HEAD of `customServerAiStep`) heals 1 HP (half a heart) when
  `tickCount % 600 == 0` and health is below max, i.e. 1 HP per 30 seconds. It runs for every golem, also a frozen one
  (health is not work). No NBT: the modulo on the entity's own tick count is the timer.

## Why

- Working golems died of "suffocated in a wall" on a live server: trees, melons and similar blocks grow into the edge of a
  golem's eye box (the check reads the blocks around the eye position, which reaches into neighbouring blocks), so a golem
  standing next to a growing tree took damage every tick.
- Vanilla copper golems have 12 max HP and never regenerate, so any such damage was permanent and fatal in the end.
- Decision (user): immune to everything except player damage; players can still hurt a golem, and it then regenerates
  slowly.
- Why all golems, not only ones with a profession: simplest rule, and an unemployed golem is a future worker.

A golem a tree, melon or pumpkin grows into is moved out by the ticker, see [golem-safety.md](golem-safety.md).

## Tests (`HealthTest`, 5)

`suffocationDoesNothing` (golem inside stone for 100 ticks), `nonPlayerDamageIsIgnored` (fall, lava, fire, drowning, wall,
mob, explosion, lightning, cactus, magic, generic), `playerDamageHurts` (melee and projectile with a player owner),
`voidAndKillStillKill`, `regeneratesOneHpPerThirtySeconds`.
