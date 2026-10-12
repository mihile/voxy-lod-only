# Voxy LOD Only 0.1.0 validation

2026-10-13, KST. All launches used isolated game directories/worlds.
Minecraft 1.20.1, Fabric Loader 0.17.2, Fabric API 0.92.6,
Java 17.0.18; builds used JDK 21 / Gradle 8.12 / Loom 1.10.4.
Dependencies: Voxy port.8-33c0dff7, VoxyServer 1.2.4+1.20.1-port.1.

Tested installable artifact SHA-256:

`78670de0d83dadc7b758a1cc252211bd62b9a80496e533b14b7f3f8e5949de49`

## Build and policy tests

- `gradlew build`: PASS.
- Six JUnit tests, zero failures/errors: negative-coordinate planning, bounded
  coverage, radius validation, checkpoint restart/seed mismatch, malformed
  checkpoint rejection, and thread-scoped live-world guard cleanup.
- The remapped release JAR was used in every production server test.
- Release JAR contains no test classes, dependency JARs, or companion-mod classes.

## Five-phase dedicated-server test

Disposable vanilla noise world, seed 424242, offline loopback port 25587.

| Check | Result |
|---|---|
| Live block writes, direct server chunk acquisition, entity spawn in generation scope | All rejected |
| Existing spawn chunk | Skipped; no seed preview applied |
| Overworld chunk square (127..129, 127..129) | Nine previews, 216 converted sections |
| Nether chunk (128,128) | One preview, 16 converted sections |
| Target Overworld chunk LOD | 31,204 non-air voxels, skylight present |
| Target Nether chunk LOD | 18,700 non-air voxels, no skylight |
| LOD-only regions after clean shutdown | No remote vanilla terrain/entity/POI `.mca` files |
| Restart | Same target LOD counts recovered from storage; checkpoints skipped completed previews |
| Cancellation during negative-coordinate generation | Worker drained; no preview committed after cancellation |
| Normal visit and block edit | Vanilla gold-block marker persisted across restart |
| Stop with generation in flight | Generator stopped before Voxy storage shutdown; exit 0 |
| Real visit while private generation was in flight | Preview discarded; generated=0, skipped=1 |
| In-flight visit marker after restart | Preserved |
| Completed negative chunk (-128,-128) | One preview; skipped after restart |

Runner result: `ALL_SERVER_TESTS PASS`. All five servers exited cleanly.

## Graphical client and VoxyServer stream

Fresh production-mappings Java 17 client, Sodium 0.5.13 and NVIDIA RTX 3080.
Voxy LOD Only was installed on the server only. The client used Voxy and
VoxyServer's existing protocol; no addon client code was required by the product.

- Nine previews generated near chunk (256,256) before the viewer connected.
- Viewer moved to chunk (240,256), sixteen chunks west of the target.
- Target chunk was absent from the vanilla client chunk cache.
- The corresponding distant Voxy section data contained 127,271 non-air voxels.
- Correct renderer WorldEngine, nonzero uploaded geometry and frame execution
  confirmed by renderer inspection/debug output.
- Normal per-address client cache selected; no UNKNOWN root / Network handle null.
- Automatic generation added 60 further previews while the viewer was connected.
- After disconnect: no client Voxy session; addon state=idle, error=none.
- Client and server exited with code 0; `GRAPHICAL_TEST PASS`.

Optional Iris was absent and the offline token generated an expected auth/Realms
failure; neither prevented connecting to the disposable offline server.

## Limits

No Prominence production instance, production world, existing UNKNOWN cache,
or installed mod JAR was changed. Full modpack/custom generator compatibility,
shader images, pixel equivalence with normal decoration, sustained load and FPS
improvements have not been established. This is an initial dedicated-server addon.

Raw reproducible evidence stays under the local project's ignored build/ folder;
see verification/README.md for the scripts, result files and log locations.

## 0.2.0 scheduler validation (2026-10-13)

Artifact SHA-256: `58c10af86de838a6fd74a1eaab0c2c8532575f477ee2b1bd46a3cf584b7a4f28`.
Build remains Java 17 bytecode; the server was tested on the supplied Oracle
GraalVM 25.4.4.1.1+1.1 (Java 25.0.4.1.1), with the same Voxy/Fabric versions.

- Build and nine JUnit policy tests: PASS. Added larger-tile coverage, nested
  guards and replacement-checkpoint persistence tests.
- Final five-phase GraalVM server fixture: `build/isolated-20261013-042159`.
  `ALL_SERVER_TESTS PASS`; all five processes exited 0, without server errors.
- Serial vs three-worker generation at tile sizes 4 and 8, Overworld and Nether:
  all 13,107,200 output voxels matched in block state, biome, skylight and blocklight.
- Existing chunk skip, private write/entity/loader guards, no remote vanilla
  region files, Overworld/Nether LOD persistence, negative-coordinate checkpoint
  restart, cancellation, stop with work in flight, normal block-save preservation,
  and concurrent real-chunk visit priority: PASS.
- The first GraalVM restart run exposed a transient Windows AccessDeniedException
  replacing a checkpoint. The final artifact retries atomic replacement for a
  bounded 620ms total backoff without deleting the old record; the clean rerun
  above passed. LOD/world data were not deleted or moved to repair the failure.
- NoiseExecutorMixin keeps private noise/biome work on owned stage lanes because
  1.20.1 otherwise chooses Util.backgroundExecutor despite the passed executor.
  Its argument modifier preserves the original executor outside private generation.
- Final graphical integration: GraalVM 25.4 server + Java 17 Voxy client,
  `GRAPHICAL_TEST PASS`, client/server exit 0. Nine manual previews; target absent
  from vanilla client chunks but 127,271 distant non-air Voxy voxels present,
  renderer identity/geometry/frame execution confirmed. Automatic generation
  reached 200 previews total; after disconnect state=idle, inFlight=0, error=none.
  No Network handle null/UNKNOWN cache initialization failure. Optional Iris was
  absent and offline auth/Realms messages were expected. No pixel/FPS claim.

Scheduler/JVM measurements and individual resource samples are documented in
[the scheduler report](benchmark/SCHEDULER_REPORT.md). Intermediate build trials
are kept locally but excluded from the final artifact's comparison table.


## 0.3.0: Chunky analysis and reduced duplicate generation (2026-10-13)

Final artifact SHA-256: `cb68150c0d1e54d808625c73d1637c8c01f84e658a098d55560e3a5ebb79cf72`.
The companion Voxy port.8 and VoxyServer port.1 binaries were not modified.

- Gradle build and 10 JUnit policy tests: PASS, including request-relative tile
  coverage at negative/boundary coordinates and maximum size 32.
- GraalVM 25.4 server generator fixture: `build/isolated-20261013-052659`, five clean boots,
  3 GiB max heap. Serial versus three-worker generation at sizes 4/8/16/32,
  Overworld/Nether: **222,298,112 block/biome/sky-light/block-light comparisons** PASS.
- Full versus one-corner eligible output (both opposite corners) at all tested
  sizes: PASS. An initial missing neighbor-decoration lighting contribution was
  found in Nether size 8; decoration halo is now 2 and terrain halo 3.
- A later size-32 Nether full/cropped comparison had different block results.
  Final code limits rectangle cropping to Overworld and retains complete patches
  elsewhere. The manager also caps non-Overworld patches at 8 and preserves the
  original world grid there to avoid expensive full 32-sized generation for tiny
  requests. These cases now match the full patch; this is a conservative
  restriction, not a claim that all modded decoration contexts can be cropped.
- A cold parallel structure-generation case at coordinates 256 reproduced
  ConcurrentModificationException in StructureTemplate.Palette.blocks' lazy
  HashMap. The constructor now installs ConcurrentHashMap for this shared cache.
  64 cold palettes / 64 block keys / 3 concurrent workers retain identical cache
  contents. The previously failing shutdown-during-generation case now passes.
- Private-world guards, saved/loaded chunk skip, remote Overworld and Nether LOD,
  cancellation, shutdown while generating, checkpoint restart, normal-visit
  marker persistence, real-chunk-wins race and negative coordinates: PASS.
  No remote vanilla terrain/entity/POI region files were created, no pending
  private generation or Voxy references after shutdown, server ERROR count zero.
- Performance and functional tests are separate. The performance runs fix
  GraalVM, 4 logical CPUs and 4 GiB heap; functional tests do not use that CPU
  affinity. See [Chunky comparison](benchmark/CHUNKY_OPTIMIZATION.md) for measured
  output and exact configuration. Existing config values remain unchanged on
  upgrade; new defaults are tile 32 / commit chunk cap 16 / commit budget 3ms.

Operational servers, client instances, caches and JARs were not replaced.
Normal FULL Minecraft decoration equivalence at every coordinate and complete
Prominence modpack/FPS behavior are not claimed by these isolated tests.

The final manager's non-Overworld tile cap was then rechecked in
`build/isolated-20261013-053357` with the five-phase server workflow, skipping
only the unchanged generator comparison: PASS, no server errors or remote
region files. The generator/cache comparison above applies to the unchanged
final generation code.

Graphical Overworld check: client `build/graphical-client-20261013-053131`,
server `build/isolated-20261013-052659` (the same final Overworld generator and
palette mixin, before the non-Overworld manager cap). Remote vanilla client
chunk absent, **127,248 non-air distant Voxy voxels**, geometry and frame render
execution positive, no UNKNOWN/network-null error. Disconnect left state idle,
inFlight=0, error=none; server/client exit codes both zero. Offline test-account
401/Realms errors and optional missing-Iris warnings were expected. This checks
the render path, not pixel-perfect shader correctness or an FPS benchmark.


Final parallel-conversion pipeline validation:

- `build/isolated-20261013-055352`: five server boots PASS with final JAR.
  The 384 packed sections (including all mip levels) match sequential Voxy
  conversion on the same mapper. Cancellation before queued work starts,
  executor rejection, exactly-once lease release and bounded 16-chunk producer
  window tests PASS. Immediate repeated jobs no longer announce completion
  before conversion/ref cleanup. The unchanged terrain comparison was skipped.
- `build/isolated-graphics-20261013-055952`, client
  `build/graphical-client-20261013-060015`: final-JAR graphical check PASS,
  remote vanilla chunk absent, 127,248 non-air Voxy voxels and active geometry/
  render frame execution. Disconnect: idle, inFlight=0, conversionTasks=0,
  error=none. Both process exit codes 0. Server errors 0; client offline-account
  authentication errors and optional-Iris warnings as described above.
- One earlier reuse of the closed fixture produced a RocksDB LOCK open failure
  and VoxyInstance's existing exception-path lock leak, ending in a watchdog.
  The initiating file lock remains unexplained; the fresh-path copy passed.
  This failed run is not counted as a success or used in performance results.
  [Separate Voxy core note and prompt](benchmark/VOXY_STORAGE_LOCK_NOTE.md).

The final 0.3 result uses background conversion. Intermediate measurements
in `build/optimization/20261013-050514` and `20261013-053627` use different JARs
and are excluded from the final performance table.
