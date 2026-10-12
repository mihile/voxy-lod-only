# Generation and storage ownership

`Voxy LOD Only` is an independent, dedicated-server Fabric 1.20.1 addon for
Voxy port.8 and VoxyServer port.1. Its generator never requests a vanilla
`ChunkHolder`/`FULL` future, adds a ticket, or serializes a vanilla chunk.
The upstream companion mods remain unchanged.

## Private generation domain

A single worker allocates a 24×24 grid of ProtoChunks for each 4×4 output tile.
The inner 8×8 terrain grid gets noise/surface/carvers. The inner 6×6 grid gets
decorations, including contributions into neighboring output chunks. An
eight-chunk structure/carver halo surrounds terrain. Generator operations use
a WorldGenRegion backed exclusively by this grid, and never advance to FULL
or SPAWN. A local LevelLightEngine owns lighting queues and layers.

Vanilla WorldGenRegion's old-chunk query normally delegates to ChunkMap and
IOWorker, which can create empty region files. PrivateRegion overrides that
query: all its ProtoChunks are new, so blending into old chunks is not attempted.
Likewise, eligibility reads call vanilla's readChunk only if the region file
already exists; its absent-region read would otherwise create empty files.

WorldGenRegion's normal onBlockStateChange call would update the live POI store.
A thread-scoped mixin suppresses that hook during private decorations. Live
block writes, chunk acquisitions, and entity additions are rejected in the
generation scope. This scope does not apply to normal gameplay threads.
No global save cancellation, chunk-unsaved flag modification, or cache deletion
is used. Mods bypassing these interfaces are outside the tested contract.

## Connection to VoxyServer

An accessor obtains the already-created ServerLodEngine. Initialization waits
until the first server tick; Fabric SERVER_STARTED callback order is not a
dependency ordering guarantee. The API dependency is pinned to port.1.

The worker returns only ProtoChunks and local lighting. It holds no Voxy world
reference and never waits for a main-thread callback. The server tick obtains
the current dimension's Voxy engine, converts one section at a time, computes
mips and invokes WorldUpdater.insertUpdate. Existing Voxy save callbacks and
VoxyServer dirty/presence callbacks then persist and stream the data.
References are acquired/released inside the same tick call. Commits yield after
approximately 3 ms between sections, subject to one conversion's runtime.

Existing disk chunks are not replaced by seed previews. Loaded chunks are
checked when selecting candidates and on every commit tick. CHUNK_LOAD events
remember races during the bounded active job even after those chunks unload.
Pending output for those positions is discarded; VoxyServer's real ingest wins.
Markers cover individual completed chunks, not entire partially generated tiles.

## Cancellation and durability

There is one pending tile/future, one ready tile, and no unbounded work queue.
Cancellation drops pending commits and sets the current job's atomic flag.
The private worker checks it between stages/chunks. An already-running vanilla
async noise/biome stage drains before the worker terminates. A new job cannot
reuse the slot until the old generation future completes.

A high-priority MinecraftServer.stopServer HEAD hook drains the generator
before Fabric calls VoxyServer's storage-shutdown callback. The worker owns
no Voxy references, so Voxy shutdown cannot wait on a callback requiring the
stopping tick thread. SERVER_STOPPED writes completion ledgers after storage
flush. A crash can cause repeated preview generation, never a claim that a
partially applied chunk is complete. Generation tasks have no callback that can
apply to a different manager/server after stop.

Automatic jobs belong to a player UUID and ServerLevel. Disconnect, dimension
change, or moving outside the job area cancels them. Players are selected in
rotation. Manual jobs intentionally continue without players. Shader behavior,
exact decoration equivalence with vanilla, and arbitrary modded generators are
not established by these invariants.
