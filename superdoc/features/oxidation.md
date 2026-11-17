# Oxidation: statues keep the golem

An unwaxed copper golem slowly oxidizes and, once fully oxidized, turns into a copper golem statue block at random
(vanilla `CopperGolem#turnToStatue`). The mod keeps everything of a working golem through that: profession,
assignments, settings, every stored item, stored XP and a real held item. Scraping the statue back with an axe revives
the golem with all of it.

Why: user request ("when a golem oxidizes, make it not lose its profession, settings, items and so on"). Before, the
statue conversion dropped the stored items at the spot and the profession and setup were gone with the discarded golem.

## Vanilla (26.3)

- `CopperGolem#turnToStatue(ServerLevel)` places an oxidized statue, calls `CopperGolemStatueBlockEntity#createStatue`
  (stores only the custom name, as the `minecraft:custom_name` component of the block entity), then
  `dropPreservedEquipment` (a held item with a guaranteed drop, e.g. the vanilla Chest Sorter carry), then `discard()`.
  No death hooks run, the entity and its attachments are gone.
- Revival: `WeatheringCopperGolemStatueBlock#useItemOn` with an axe on an UNAFFECTED statue calls
  `CopperGolemStatueBlockEntity#removeStatue(BlockState)` (a fresh golem, only the custom name restored), adds it and
  removes the block. On an older statue the axe just scrapes one stage (`AxeItem`, block change).
- Weathering, waxing, scraping and pose changes swap the block but keep the block entity
  (`CopperGolemStatueBlock#shouldChangedStateKeepBlockEntity`: both states in `BlockTags.COPPER_GOLEM_STATUES`).
- A mined statue's loot table copies only `minecraft:custom_name` from the block entity into the item.

## How the mod keeps it

The golem's `GolemData` attachment moves onto the statue block entity. Fabric data attachments work on block entities
too, and persistent ones are saved with the block entity NBT, so the same `ModAttachments.GOLEM_DATA` type (same
lenient codec) rides along through chunk unloads and every statue block change.

- **Into the statue:** `mixin/CopperGolemMixin:cgr$intoStatue` (before vanilla's `dropPreservedEquipment` in
  `turnToStatue`) -> `core/GolemService:toStatue`: sets `GOLEM_DATA` on the block entity, moves a real held item into
  `ModAttachments.STATUE_HELD` (a display copy is just cleared, see [gui.md](gui.md)), and takes our auto name (the
  profession name) off the statue. Nothing drops. A golem without mod data stores nothing (stays vanilla).
- **Revival:** `mixin/oxidation/CopperGolemStatueBlockEntityMixin` (RETURN of `removeStatue`) ->
  `core/GolemService:fromStatue`: the new golem gets `GOLEM_DATA` and the held item (guaranteed drop, as vanilla sets
  it on a pickup) back, the auto name is set again, the statue keeps nothing. The golem is a new entity (new UUID), so
  per-golem runtime state (walk-to, profession phase) starts fresh and the chunk ticket is taken again on its first
  tick ([chunk-loading.md](chunk-loading.md)).
- **Statue removed otherwise** (mined, blown up, replaced): `mixin/oxidation/StatueRemovalMixin` (HEAD of
  `BlockEntity#preRemoveSideEffects`, the hook vanilla containers drop their contents from) ->
  `core/GolemService:statueRemoved`: stored items, XP (bottles plus remainder orbs, see
  [xp-bottles.md](xp-bottles.md)) and the held item drop at the block. Profession, assignments and settings are lost
  then, as on death. Why drop and not keep them in the item: vanilla keeps no golem data in the statue item (only the
  name), and putting it there would need a loot table override of all eight statue blocks and a statue item that
  carries a whole inventory; dropping keeps every item and matches how containers behave.
- Why the auto name comes off the statue: vanilla copies the custom name into the statue and its mined item; a
  re-placed statue would revive a plain golem named after a profession it no longer has, and the mod would then treat
  that name as the player's (`GolemService:updateName`). A name the player gave stays on the statue.

## Save robustness (`GolemData:CODEC`)

The statue stores the same codec, so this matters here too.

- Every field is lenient: missing (old save) or malformed, it falls back to its default (`save_tools` on, the others
  off/empty). One undecodable stack (an item of a removed mod) becomes empty in place via `GolemData:LENIENT_STACK`,
  the other slots keep their indices; a malformed assignment entry is dropped, the good ones stay
  (`GolemData:tolerant`). Each fallback logs a warning. Why: a strict codec fails the whole attachment and Fabric then
  drops all of it, wiping the golem.
- `GolemData:fitItems` pads `items` to the profession's slot count on load and on every `GolemData:get`; it never
  truncates, a longer list keeps its extras (they are handed out on a profession change, death or a mined statue).
  The Smelter is skipped (`GolemData:OWN_MIGRATION`): its own migration recognises an old 8/9-slot save by the short
  list and moves results out of the furnace buffers. Why central: a short list made a profession tick throw and
  `GolemMenu:stillValid` close the menu at once, so the player could not even switch profession.

## Tests

`OxidationTest` (3): `statueKeepsEverythingAndRevivalRestoresIt` (calls the private `turnToStatue` by reflection; no
item or orb dropped, block entity save/load keeps the data, three axe scrapes and the revival through the real
block/axe calls restore every field, the held item and the name), `minedStatueDropsEverything` (items, filter item,
held item and 2 XP bottles drop, the statue item has no auto name), `plainGolemStatueStaysVanilla`.
`GolemDataTest` covers the codec: `oldSaveGetsDefaults`, `badStackAndFieldAreTolerated`, `longerItemsListKeepsExtras`,
`shortItemsListIsPaddedOnAccess`.
