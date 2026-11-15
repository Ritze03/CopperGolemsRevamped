# Copper Golems Revamped

A Fabric mod for Minecraft 26.3 that makes vanilla Copper Golems useful. Right-click a golem with both hands empty to open its menu, pick a profession and assign its targets (chests, a crafting table, a tree, or an area). From then on it works on its own, even while you are far away.

**Server-side only.** Vanilla clients can join, and there is nothing to install on the client. An optional [resource pack](#resource-pack-for-servers) adds custom menu icons; without it the icons fall back to vanilla items.

## Professions

Pick one in the **Profession** tab of the golem menu. Assign targets in the **Targets** tab: press the Assign button, then right-click the block in the world. For areas, right-click two opposite corners; an area that is too large is capped to the maximum size.

- **Transporter** moves everything from an input chest to an output chest, up to 9 full stacks per trip. Assign: input chest, output chest. A Filter tab (9 item slots) with the modes Off, Whitelist and Blacklist picks which items it takes.
- **Chest Sorter** is vanilla copper golem sorting (from a copper chest into chests that already hold the same item), except that it visits up to 64 chests instead of 10 before it gives up. No targets.
- **Cleaner** collects dropped items in an area and delivers them to a chest. Assign: area (max 16x16; the height is the two clicks' Y range plus 2 blocks above), output chest.
- **Crafter** fetches ingredients from an input chest, crafts at a crafting table and delivers the results to an output chest. You set the recipe as a 3x3 grid in the Recipe tab. Assign: input chest, crafting table, output chest.
- **Farmer** farms a field with one tool. With a hoe it tills, plants seeds and harvests mature crops; with an axe it harvests only melons and pumpkins. Worn tools are swapped from the tool chest. Assign: field (max 9x9, click the soil layer), tool chest, seed chest, output chest.
- **Lumber** works one tree in a cycle: plant a sapling, bone meal it, fell the tree with an axe, pick up the drops and deliver them. It only fells logs of the same wood type as the assigned tree. Assign: the tree (its sapling or bottom log), input chest (axes, saplings, bone meal), output chest. Settings tab: "Strip logs" (off by default) makes it strip each log with the axe before breaking it, so you get stripped logs (costs 1 extra axe durability per log).
- **Smelter** feeds up to 8 furnaces, blast furnaces or smokers in an area with items from an input chest and fuel from a fuel chest, and delivers the results. Assign: furnace area (max 16x16), input chest, fuel chest, output chest.
- **Butcher** keeps an animal pen at a fixed size: it breeds animals with food from a chest, kills the surplus adults with a sword or axe (Looting works) and delivers the drops. It keeps 8 adults per animal kind and never touches babies, named, owned, leashed or ridden animals. Handles cows, mooshrooms, pigs, sheep, chickens, rabbits and goats. Assign: area (max 16x16), tool chest, food chest, output chest.

## The menu

The menu has a tab bar in the top row.

- **Status**: what the golem is doing and what it does next, its carried items and tool durability, and stored XP.
- **Targets**: one column per target with Assign, **Show** (the golem walks to the target, or outlines an area) and **Clear**.
- **Recipe** (Crafter only): put one item in each cell of the 3x3 grid to set the recipe. The golem never consumes these items.
- **Inventory**: the golem's working slots (tool, supplies, carry, and so on).
- **Settings**: **Follow** makes the golem follow you and pauses its work; **Freeze** makes it stop all work and stand still so you can safely change its inventory.
- **Profession**: switch profession. Switching asks for confirmation because it clears the targets and hands out all stored items.

The **Help** button prints help for the current tab in chat; the menu stays open.

## General behaviour

- **Chunk loading:** a golem with a profession keeps the chunks around it loaded, so it keeps working while no player is nearby and after a server restart. Setting its profession to None releases them.
- **Work cycles:** professions run in fixed cycles (for example the Smelter collects, delivers, fetches, feeds and fuels; the Butcher breeds, kills and delivers). Golems walk up to a block and open chest lids while they use it.
- **XP bottles:** Smelters and Butchers store the experience they earn and pay it out as Bottles o' Enchanting (7 XP each); no glass bottles are needed and no XP orbs spawn. The Smelter shows them in its ninth slot and delivers them to the output chest; the Butcher delivers them to the output chest.
- **Profession change:** items the golem holds (including XP bottles) are returned to you, and its targets are cleared.
- **Mending:** Lumbers, Farmers and Butchers repair Mending tools with XP bottles (7 XP each) from their tool chest and never use a Mending tool until it breaks: it goes back into the chest before, and the golem uses its hands or another tool.
- **Transfer limit:** each golem moves at most 16 items per 5 ticks (64 per second).

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/server/) 0.20.1 or newer for Minecraft 26.3, and Java 25 or newer.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) 0.166.2+26.3.
3. Put the mod jar into the server's `mods/` folder and start the server.

## Resource pack for servers

The pack gives vanilla clients the custom menu icons. Direct link:

```
https://github.com/Ritze03/CopperGolemsRevamped/releases/latest/download/CopperGolemsRevamped-resourcepack.zip
```

To offer it to players, add this to `server.properties`:

```
resource-pack=https://github.com/Ritze03/CopperGolemsRevamped/releases/latest/download/CopperGolemsRevamped-resourcepack.zip
resource-pack-sha1=<sha1 from the release notes>
require-resource-pack=false
```

The SHA-1 changes with every release and is listed in the release notes. `require-resource-pack=false` keeps the pack optional: players can decline it and still use everything. Players who have the mod installed need no pack, since the jar carries the same assets.

## Building from source

With JDK 25:

```
./gradlew build
```

The outputs are the mod jar and `*-resourcepack.zip` in `build/libs/`. The GameTests run as part of the build.

## License

Copper Golems Revamped is licensed under the GNU Lesser General Public License v3.0 only (LGPL-3.0-only). See COPYING.LESSER and COPYING.
