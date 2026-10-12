# Generation and storage ownership

`Voxy LOD Only` is an independent, dedicated-server Fabric 1.20.1 addon for
Voxy port.8 and VoxyServer port.1. Its generator never requests a vanilla
`ChunkHolder`/`FULL` future, adds a ticket, or serializes a vanilla chunk.
The upstream companion mods remain unchanged.

## Private generation domain

The coordinator allocates a private grid for each output tile (default maximum
32×32; 4×4, 8×8 and 16×16 remain configurable). Before allocation the requested eligible
positions are reduced to their bounding rectangle within the tile. The manager
anchors bounded tiles at the requested area minimum rather than a fixed world
grid, avoiding artificial splits of small requests. Decoration centers extend
two chunks around output so adjacent light-source chunks receive their neighbor
decorations too; terrain extends three to support those decorators. A complete
default tile has a 54×54 ProtoChunk grid, 38×38 terrain grid for noise/surface/
carvers, and 36×36 decoration grid. Partial tiles retain the same dependency
halos around a smaller output rectangle. Interior ineligible positions cannot
be removed: neighboring eligible chunks may need their terrain/decorations.
Only eligible output positions are committed. Cropping is limited to Overworld;
other dimensions retain the complete patch context because the Nether 32×32
full/cropped decoration comparison showed different block results. The manager
caps these patches at 8 and preserves their fixed world grid, retaining the old
context without allocating a full 32 patch for one requested Nether chunk.
Decorations include contributions
into neighboring output chunks. An
eight-chunk structure/carver halo surrounds terrain. Generator operations use
a WorldGenRegion backed exclusively by this grid, and never advance to FULL
or SPAWN. A local LevelLightEngine owns lighting queues and layers.

A bounded stage executor processes independent chunks in parallel: structure
starts/references, biomes, noise, surface and carvers. A barrier drains every
task before advancing the generation stage, including on failure/cancellation.
Workers claim the next chunk through one stage-local atomic cursor, avoiding
fixed-lane imbalance. The read-only neighbor map uses primitive long keys;
each terrain chunk's immutable 17×17 neighborhood list is allocated once and
shared between its separate stage-specific WorldGenRegions. Both maps are
complete before worker submission; workers never mutate the maps/lists.
Decoration remains in stable z/x order because it writes to neighbor chunks;
lighting also remains local and serial. Auto parallelism leaves one detected
processor available, with a cap of eight stage workers. The coordinator uses
a separate executor, so waiting for stage tasks cannot exhaust the stage pool.
Vanilla asynchronous sub-stages use an inline executor inside their stage lane.
Minecraft 1.20.1's NoiseBasedChunkGenerator otherwise ignores the supplied
executor and uses Util.backgroundExecutor. A scoped argument modifier selects
inline execution only while the private generation guard is active; normal
chunk generation keeps its original executor. This prevents nested-pool waits
and preserves the guard during the actual noise/biome computation.
Vanilla ServerLevel's constructor initializes the shared structure-placement
state before this manager is started; stage submission publishes the initialized
state and private chunk grid to the workers.
StructureTemplate.Palette has a separate lazy per-block HashMap cache shared
by template users. Cold parallel structure creation reproduced a
ConcurrentModificationException there. Its constructor now initializes a
ConcurrentHashMap via a narrow mixin, preserving the original block list and
filtering function. This also protects overlap with normal world generation;
a private-only lock could not protect native callers of the same template.

Vanilla WorldGenRegion's old-chunk query normally delegates to ChunkMap and
IOWorker, which can create empty region files. PrivateRegion overrides that
query: all its ProtoChunks are new, so blending into old chunks is not attempted.
Likewise, eligibility reads call vanilla's readChunk only if the region file
already exists; its absent-region read would otherwise create empty files.

WorldGenRegion's normal onBlockStateChange call would update the live POI store.
A thread-scoped mixin suppresses that hook during private decorations. Live
block writes, chunk acquisitions, and entity additions are rejected in the
generation scope. This scope does not apply to normal gameplay threads.
Guards are nested and established on each stage worker. During decorations,
vanilla structure-entity lookups through ServerLevel.structureManager are
redirected to the current private WorldGenRegion (e.g. swamp-hut cat checks).
That region context is restored on scope exit; live-world acquisition remains
rejected, and ordinary gameplay threads are unaffected.
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
the configured budget (default 3 ms) between sections, subject to one
conversion's runtime. Voxy mapping/updates remain on the server tick thread.

Existing disk chunks are not replaced by seed previews. Loaded chunks are
checked when selecting candidates and on every commit tick. CHUNK_LOAD events
remember races during the bounded active job even after those chunks unload.
Pending output for those positions is discarded; VoxyServer's real ingest wins.
Markers cover individual completed chunks, not entire partially generated tiles.

## Cancellation and durability

At most two batches, including the currently applied batch, are in flight;
the limit can be reduced to one. Generation overlaps the previous batch's
tick-thread application. Submission cooldown controls the producer rather than
forcing an idle gap after every commit. There is no unbounded work queue.
Cancellation drops pending commits and sets the current job's atomic flag.
The private worker checks it between stages/chunks. An already-running vanilla
async noise/biome stage drains before the worker terminates. A new job cannot
reuse the slot until all old batch futures drain. The coordinator drains its
stage tasks before the stage executor shuts down.

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


## Bounded LOD conversion pipeline

PreparedLodBatch converts palettes/biomes/light and computes mips on the same
bounded stage executor as terrain generation. Voxy's existing native ingest
service already uses these converter APIs on worker threads. The producer
window holds at most 16 chunks of packed results (about 14 MiB for 384-block
height), not an entire large tile. Each section owns a fresh packed array.
WorldUpdater.insertUpdate stays on the server thread, after checking for real
chunks each tick, within the existing 3ms section budget.

Each prepared batch holds one WorldEngine lease across all its tasks/results.
Closing stops additional submission, workers poll cancellation between sections,
and the lease releases exactly once after all submitted workers drain. A closed
batch remains attached to the old job until drained, preventing reuse on a new
job. Shutdown drains the coordinator and shared stage executor before Voxy shuts
its world engines. Workers never apply world updates or touch a later job.
