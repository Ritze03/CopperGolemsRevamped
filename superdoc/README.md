# superdoc index

Agent-facing documentation for the project. Start at the overview.

## Architecture

- [architecture/overview.md](architecture/overview.md): start-here hub: big picture, data flow, module map, where to look for X.
- [architecture/vanilla-copper-golem.md](architecture/vanilla-copper-golem.md): vanilla 26.3 copper golem and Fabric API reference (signatures, mixin targets).
- [architecture/interaction.md](architecture/interaction.md): `ai/Interact`, shared 1-block reach/stand position and visible chest lids, used by all professions.
- [architecture/transfer-limit.md](architecture/transfer-limit.md): the per-golem item transfer budget (16 items per 5 ticks), what counts, how a cut visit continues.
- [architecture/testing.md](architecture/testing.md): GameTests per class, how to run them, registering new test classes, the test-count gotcha.

## Features

- [features/gui.md](features/gui.md): server-side tabbed chest menu, Recipe and Inventory tabs for the Crafter, hand display, CMD icons, resource pack.
- [features/assign-mode.md](features/assign-mode.md): armed next-click target assignment.
- [features/transporter.md](features/transporter.md): Transporter profession and follow mode.
- [features/chest-sorter.md](features/chest-sorter.md): Chest Sorter profession, waxed copper blocks raise the visited-chest limit.
- [features/cleaner.md](features/cleaner.md): Cleaner profession, picks up item entities in an area (max 16x16) into an output chest.
- [features/crafter.md](features/crafter.md): Crafter profession, ghost 3x3 recipe, fetch from input, craft at a table, deliver to output.
- [features/farmer.md](features/farmer.md): Farmer profession, farms a field (max 9x9): a hoe tills, plants and harvests, an axe harvests melons and pumpkins; seeds go to the seed chest.
- [features/butcher.md](features/butcher.md): Butcher profession, keeps 8 adults per kind in an area (max 16x16): culls the surplus with a sword or axe (Looting applies), breeds with food from a chest, delivers the drops.
- [features/lumber.md](features/lumber.md): Lumber profession, fells a whole assigned tree from the ground (flood fill, 2x2 and branches), collects drops, replants, bone meal, axe wear.
- [features/smelter.md](features/smelter.md): Smelter profession, feeds up to 8 furnaces of an area (even split, fuel, collect, XP), never blocks on a full chest, foreign input or unreachable furnace.
- [features/xp-bottles.md](features/xp-bottles.md): golems keep the XP they earn (Smelter furnaces, Butcher kills and breeding) and pay it out as XP bottles (7 XP each), no orbs.
- [features/mending.md](features/mending.md): Lumber, Farmer and Butcher repair Mending tools with XP bottles from the tool chest (7 XP each) and never use a Mending tool below 10 durability (rest in the chest, bare hands or another tool).
- [features/freeze.md](features/freeze.md): Settings-tab Freeze toggle, a frozen golem does nothing and stands still so its inventory can be changed.
- [features/keep-layout.md](features/keep-layout.md): Settings-tab Keep layout toggle for every golem: never empties a stack (leaves 1), never fills empty slots, so hand-laid-out chests stay intact.
- [features/chunk-loading.md](features/chunk-loading.md): profession golems keep a 3x3 chunk area loaded and ticking (persistent ticket), limits.

## Rules and meta

- [claude-instructions/documentation.md](claude-instructions/documentation.md): how to read and maintain these docs.
- [claude-instructions/documentation-version-policy.md](claude-instructions/documentation-version-policy.md): who sets version numbers and dates.
- [meta/TERMINOLOGY.md](meta/TERMINOLOGY.md): project vocabulary.
