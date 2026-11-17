# CopperGolemsRevamped

Fabric mod for Minecraft 26.3 that makes Copper Golems useful: right-click a golem with empty hands to pick a profession (Chest Sorter, Lumber, Crafter, Transporter, Farmer, Cleaner, Smelter, Butcher, Distributor) and assign its targets.

<!-- superdoc:start v4 -->
## Docs: read before you touch, update after you change

Full discipline — what to read, when to update, how to record the *why* — lives in the
always-loaded `superdoc/claude-instructions/documentation.md` below; this is a pointer, not
a restatement.

Reference docs — plain links, read the one relevant to your task on demand:

- **[superdoc/architecture/overview.md](superdoc/architecture/overview.md)** — codebase
  navigation map (module map, data flow, "where to look for X"). **Start here to navigate
  the code.**

## Always in context (force-loaded, mandatory)

`@` is reserved for the must-always-know set. Everything above is a plain link: an
optional, on-demand read.

- **Terminology** — @superdoc/meta/TERMINOLOGY.md — project vocabulary; use these meanings,
  ask before acting on an undefined term, and keep it current.
- **Documentation discipline** — @superdoc/claude-instructions/documentation.md — read
  before you touch, update after you change, record design rationale (the *why*).
- **Version policy** — @superdoc/claude-instructions/documentation-version-policy.md — how
  this project bumps doc/version markers.

Path base: `CLAUDE.md` uses repo-root-relative paths (`@superdoc/...` and the link targets
above, written as real markdown links, never code spans: `map.py` ignores code spans, so a hub
named only in one gets no inbound link and is reported ORPHAN); links *inside* docs are
docs-relative. Precedence: `CLAUDE.md` is authoritative for
rules, docs are the reference — reconcile to `CLAUDE.md` on conflict. `@`-budget:
force-load a file only if its absence would let the agent do the wrong thing on *any*
task.
<!-- superdoc:end -->
