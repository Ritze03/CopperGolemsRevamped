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

`src/gametest/.../ChunkLoadTest`: ticket present at the golem's chunk (level <= 30), moves to another chunk, released on None and on discard, kept while a second golem still shares it, and a
`TicketStorage.CODEC` round trip of our ticket type (the restart case; a real restart cannot be a GameTest).

Why `ticketMovesWithGolem` teleports the golem 40 blocks up (no gravity) into the next chunk: GameTest
structures are packed side by side, so the next chunk lies inside another test's bounds, and that test's
`GameTestInfo#succeed` discards every entity in its bounds. The golem was discarded before its first
tick, so `keep` never ran ("no ticket at ..." in about 1 of 4 runs, depending on which neighbour finished
on the same tick). It was a test race, not a production bug. Tests that move entities must keep them out
of other tests' bounds. (A far chunk held by a test-own ticket was tried: it never became entity-ticking.)

Why `ticketMovesWithGolem` has `maxTicks = 3000`: it waits for the next chunk to be entity-ticking before
teleporting. GameTests tick as fast as the server can, but chunk generation/promotion runs on worker threads;
in a batch of 50 parallel tests that sometimes took longer than the old 200 ticks (~1 s), giving about 1 failure
in 3 full runs ("waiting for the next chunk to tick on tick 202"). The ticket logic was not at fault (the ticket
is added the same tick). The wait is for a condition, so the large bound only matters when the server is slow.
