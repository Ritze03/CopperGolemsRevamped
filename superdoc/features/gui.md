# Golem GUI

Menu, screen and serverbound payloads for configuring a golem. Fabric/vanilla signatures used here:
[vanilla-copper-golem.md](../architecture/vanilla-copper-golem.md) (sections 8 and 9).

## Opening

An empty main-hand right-click on a copper golem: `mixin/CopperGolemMixin:cgr$emptyHand` calls
`menu/GolemMenuOpener:open` (server). That builds a `GolemMenuData` snapshot and calls
`ServerPlayer#openMenu` with an `ExtendedMenuProvider`. The menu type is
`menu/ModMenus:GOLEM` (an `ExtendedMenuType<GolemMenu, GolemMenuData>`); the client screen is
registered in `client/CopperGolemsRevampedClient:onInitializeClient`.

`GolemMenuData(golemId, Optional<Identifier> profession, boolean following, Map<String,BlockPos> assignments)`
is the opening data; its `STREAM_CODEC` lives on the record. The client builds its menu from it, so
the layout is identical on both sides.

## Layout comes from the declaration

`menu/GolemMenu` computes every slot position from the profession (`profession/Profession`), with no
profession-specific code, so a new profession shows up with zero GUI changes:

- top row: profession cycle button + follow button; then one full-width button per `AssignSpec`
  (label from the spec, current value appended: `x, y, z`, `a to b` for AREA, or `unset`);
- the held-item slot (slot index 0);
- one section per `SlotGroup` (heading = capitalised group key, 9 columns, wraps to more rows),
  slot `mayPlace` = group filter, `getMaxStackSize` = group `maxStack` (`GolemMenu.GroupSlot`);
- player inventory + hotbar.

Slot order: held (0), profession slots in declaration order, player inventory, hotbar. The screen
(`client/GolemScreen`) reads all y coordinates from the menu; background is plain fills (no texture).

Containers: server side `menu/GolemItemsContainer` is a live view over `GolemData.items` that calls
`GolemData.save` on every change (size = declared inventory size; out-of-range slots read empty);
`menu/GolemHeldContainer` views the main hand. Client side uses plain `SimpleContainer`s synced by
vanilla slot sync. The held slot is take-out only (`mayPlace` false): the vanilla brain owns what the
golem carries. Writes to the main hand call
`setGuaranteedDrop(MAINHAND)` like vanilla pickup.

`quickMoveStack`: held or profession slot -> player inventory (hotbar end first); player slot ->
profession slots only, via `moveItemStackTo`, which honours `mayPlace` and the slot max stack (the held
slot refuses placement so it is skipped). Returns empty when nothing moved.

The follow state is mirrored to the client with a `DataSlot` (server reads `GolemData.following`),
so the button label updates without reopening.

## Payloads (`network/GolemPayloads`, registered in `network/ModNetworking:init`, all serverbound)

| Payload | Fields | Server action |
|---|---|---|
| `SelectProfession` | `int golemId`, `Optional<Identifier> profession` (empty = none) | `GolemService.changeProfession`, then `GolemMenuOpener.open` again |
| `ToggleFollow` | `int golemId` | toggles `GolemData.following`; on: `followPlayer` = sender UUID, off: null; save |
| `AssignTarget` | `int golemId`, `String key` (AssignSpec base key) | `player.closeContainer()` then `AssignMode.arm(player, golem, key)` |

Validation (`ModNetworking:resolve`, run on the server thread via `ctx.server().execute`): the sender's
open container must be a `GolemMenu` for that same entity id, the entity must be a live `CopperGolem`
within 8 blocks (`GolemMenu:inRange`). Then: profession id must be registered (and different from the
current one, so a duplicate packet cannot wipe assignments); assign key must be in the current
profession's `assigns()`. Anything else is ignored silently.

## Why reopen on profession change

The slot layout (sections, assign buttons, container size) depends on the profession, and a menu's
slot list is fixed at construction. Rebuilding the menu is simpler and safer than mutating slots and
resyncing; the carried cursor stack is returned to the player by the close. Follow toggle does not
reopen (layout unchanged).

## Notes

- Strings are `Component.literal` for now (no lang file yet).
- Unverified: handler threading is not relied on (everything is wrapped in `server().execute`);
  the client screen was compiled but never run headlessly.
