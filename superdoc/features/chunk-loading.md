# Chunk loading

Every golem with a profession keeps the chunks around it loaded and entity-ticking, so it keeps
working while no player is near. Code: `chunk/GolemChunkLoader`. Index: [../README.md](../README.md).

## Behaviour

- One ticket at the golem's chunk, radius 3 = ticket level 30. Vanilla levels:
  `ChunkLevel#ENTITY_TICKING_LEVEL` is 31, so the golem's chunk and its 8 neighbours (3x3) tick
  entities; the next ring is block-ticking only.
- Only golems with a profession (`core/GolemService:profession` not null) hold a ticket. Follow mode
  does not change that (a following golem with a profession keeps loading; harmless).
- The ticket moves with the golem: `GolemChunkLoader:keep` runs first in `core/GolemTicker:tick` and
  re-adds the ticket when the chunk changed, then drops the old spot.
- Released (`GolemChunkLoader:release`): profession set to None (`core/GolemService:changeProfession`,
  and the ticker for a golem that has none), golem removed for good (killed, discarded, converted to a
  statue, dimension change), via `ServerEntityEvents.ENTITY_UNLOAD` when
  `Entity#getRemovalReason().shouldSave()` is false. A plain chunk unload (`UNLOADED_*`, which saves)
  never releases.
- `changeProfession` calls `keep` right away, so the chunks load without waiting for a tick.

## Why this API

`TicketType` in 26.3 is a record registered in `BuiltInRegistries.TICKET_TYPE`; its public constructor
`TicketType(long timeout, int flags)` accepts a custom type. A type with `FLAG_PERSIST` is saved natively
by the level's `TicketStorage` (a `SavedData`, codec writes the registry name of the type), and tickets
loaded from disk are activated on level load. So no own `SavedData` is needed: after a restart the
ticket is back, the golem's chunks load and the golem ticks and refreshes it.
Why not `ServerLevel#setChunkForced`: it is the same persisted force-load as `/forceload`; removing it
for a golem could undo a player's forceload of that chunk.

Flags: `PERSIST | LOADING | SIMULATION | KEEP_DIMENSION_ACTIVE`. The last one matters: without a player
or forced chunk a dimension stops ticking, which would freeze a golem in the Nether.

Registry sync: Fabric registry sync syncs an explicit list of registries (sounds, fluids, blocks, items,
entity types, ...); ticket types are not on it, so no client needs the entry.

Why a timeout (600 ticks) with a refresh (every 100 ticks): a ticket whose golem vanished without our
hook (world edit, crash, an old save with a golem at another chunk) expires by itself instead of pinning
chunks forever. Timeouts only count down for loaded chunks.

Why reference counting (`USERS`): tickets of one type and level at one chunk merge into one, so two golems
in a chunk share it; it is removed only when the last holder leaves. Why the `HELD` map is runtime
only: after a restart the first golem tick re-adds the ticket, nothing needs saving beyond the vanilla storage.

## Limits

- 9 chunks per working golem are loaded and ticking; many golems in far-apart places add up.
- Only the golem's own 3x3 area is loaded, not the chunk of every assigned target. A target more than
  about 16 blocks outside that area relies on the golem walking there, which moves the area with it.
- A golem that moved to another chunk since the last world save leaves a stale saved ticket: it
  expires after 30 s of its chunk being loaded; if the golem's own chunk is not among the loaded ones it
  stays asleep until a player comes near.
- A golem removed without a removal event (world edit) leaves a ticket that expires by timeout.

## Tests

`src/gametest/.../ChunkLoadTest`: ticket present at the golem's chunk (level <= 30), moves across a chunk
border, released on None and on discard, kept while a second golem still shares it, and a
`TicketStorage.CODEC` round trip of our ticket type (the restart case; a real restart cannot be a GameTest).
