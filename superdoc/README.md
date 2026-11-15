# superdoc index

Agent-facing documentation for the project. Start at the overview.

## Architecture

- [architecture/overview.md](architecture/overview.md): start-here hub: big picture, data flow, module map, where to look for X.
- [architecture/vanilla-copper-golem.md](architecture/vanilla-copper-golem.md): vanilla 26.3 copper golem and Fabric API reference (signatures, mixin targets).
- [architecture/interaction.md](architecture/interaction.md): `ai/Interact`, shared 1-block reach/stand position and visible chest lids, used by all professions.
- [architecture/testing.md](architecture/testing.md): GameTests per class, how to run them, registering new test classes, the test-count gotcha.

## Features

- [features/gui.md](features/gui.md): server-side tabbed chest menu, Recipe and Inventory tabs for the Crafter, hand display, CMD icons, resource pack.
- [features/assign-mode.md](features/assign-mode.md): armed next-click target assignment.
- [features/transporter.md](features/transporter.md): Transporter profession and follow mode.
- [features/chest-sorter.md](features/chest-sorter.md): Chest Sorter profession, waxed copper blocks raise the visited-chest limit.
- [features/cleaner.md](features/cleaner.md): Cleaner profession, picks up item entities in an area (max 16x16) into an output chest.
- [features/crafter.md](features/crafter.md): Crafter profession, ghost 3x3 recipe, fetch from input, craft at a table, deliver to output.
- [features/farmer.md](features/farmer.md): Farmer profession, farms a field (max 9x9): a hoe tills, plants and harvests, an axe harvests melons and pumpkins; seeds go to the seed chest.
- [features/lumber.md](features/lumber.md): Lumber profession, fells a whole assigned tree from the ground (flood fill, 2x2 and branches), collects drops, replants, bone meal, axe wear.
- [features/freeze.md](features/freeze.md): Settings-tab Freeze toggle, a frozen golem does nothing and stands still so its inventory can be changed.
- [features/chunk-loading.md](features/chunk-loading.md): profession golems keep a 3x3 chunk area loaded and ticking (persistent ticket), limits.

## Rules and meta

- [claude-instructions/documentation.md](claude-instructions/documentation.md): how to read and maintain these docs.
- [claude-instructions/documentation-version-policy.md](claude-instructions/documentation-version-policy.md): who sets version numbers and dates.
- [meta/TERMINOLOGY.md](meta/TERMINOLOGY.md): project vocabulary.
