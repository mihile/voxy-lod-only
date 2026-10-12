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
