# Occupancy controls — 0.5.0+1.20.1

Date: 2026-10-13. Artifact SHA256:
`84a7a5e99849752cb4a5d4be2017111f9c76a9b5334283f226a37afeb0c5bc44`.
Companions: Voxy port.9 (`12cb5ce5f0ef90d6e8ed095bcbdd7143f7a81286a79f6bfd78d49beffbd7228b`),
VoxyServer port.1 unchanged. Server JVM: local GraalVM 25.4, four detected
processors, 3GiB heap. Client: Java 17, Sodium 0.5.13.

## Changes

- `LodOnlyConfig.java`: independent `withPlayers`/`withoutPlayers` profiles with
  enabled flag, workers, batch cooldown, commit cap/budget, tick-health threshold
  and preview chunk admission rate. Missing fields inherit legacy explicit values;
  new config files print full profiles. Existing config files are not rewritten.
- `LodOnlyManager.java`: reads player presence on server ticks, resizes the owned
  generation/conversion pool, pauses without discarding the cursor or partially
  committed chunk, and keeps cancellation/shutdown draining. Running tasks
  finish safely. Optional `continueAutomaticWhenEmpty` retains the last player's
  already requested area, but cannot invent another center in an empty server.
  Owners use player-object identity, preventing same-UUID reconnects from
  inheriting delayed automatic work. Fully completed automatic areas wait
  at least 100 ticks before scanning again and do not spam completion logs.
- `ChunkPacer.java`: monotonic spacing of new preview chunk write admissions,
  without a catch-up burst. Partial writes continue under the tick budget.
- `fabric.mod.json`: explicitly server environment. Clients use Voxy + VoxyServer
  + Sodium without this addon. Integrated servers remain unsupported.
- `CorePolicyTest.java`: legacy JSON inheritance, independent profiles, validation
  and monotonic pacing checks. Total unit tests: 16, all passed.
- `ClientVerification.java` / `run_activity.py`: real occupancy/reconnect tests;
  verification code is not bundled into the installation JAR.
- README/config example: restart-required configuration and status fields.

## Final activity test: PASS

Server fixture: `build/activity-20261013-162840`.
Client fixture: `build/activity-client-20261013-162840`.
Raw logs and timestamped samples: fixture `result.json`, `server.log`,
`server-idle.log`, `client.log`.

1. Client without LOD Only connected via protocol 2, saw 127,248 non-air Voxy
   voxels and an active renderer for a remote target absent from vanilla client
   FULL chunks. Cache root was identified rather than UNKNOWN.
2. Occupied profile: one owned worker, 1ms tick budget, two new preview writes/sec.
   A nine-chunk task paused at one completed chunk after disconnect. The count
   remained exactly one over a two-second pause.
3. Real reconnect finished exactly nine previews and 216 sections, without
   losing progress or duplicating completion count.
4. A new automatic task (radius eight, beyond native FULL coverage) began.
   With continuation enabled, final-player disconnect retained the task paused.
   `automatic off` cancelled it; status reached idle with zero retained batches
   and zero conversion tasks.
5. Second boot enabled the empty profile: four workers, 10ms budget, four new
   preview writes/sec. Another nine previews finished; first observed completion
   to idle was 2.194 seconds, consistent with eight more admissions at >=250ms
   spacing plus tick/polling overhead.
6. No vanilla region/entity/POI files in the private target areas, no server ERROR,
   no `Network handle null`, no UNKNOWN client cache. Two server exits and the
   graphical client's exit were all zero.

The offline account's placeholder token logs an authentication/Realms error;
the local protocol and graphical lifecycle checks passed independently.
These checks establish occupancy controls and renderer integration, not shader
image fidelity or a new FPS/performance benchmark.

## Existing server regression

Existing server regression runner also passed five boots with the final JAR:
`build/isolated-20261013-163117`. This covered 384-section conversion with
cancel/rejection/ref cleanup, real-chunk skipping, Overworld/Nether preview
storage, no private FULL region files, cancellation, restart/checkpoint skipping,
normal visitation/block persistence, shutdown during generation, in-flight
real-chunk precedence and negative coordinates. All five exits were zero and
there were no server ERROR lines. Generator terrain comparison was skipped in
this run because its code did not change; prior 0.4 voxel comparison is separate.

## Harness development attempts

`build/activity-20261013-162031` and `...162450` passed manual pause/reconnect but
are not counted as complete passes. Their automatic-job expectation selected,
respectively, a previously completed area and an area entirely covered by native
FULL loading. Correct skipping meant no new previews. The harness now uses a
new area and radius eight, and checks only the requested private region
coordinates rather than normal player-visited regions.
The first attempt also exposed the completed-area scan/log repetition corrected
in the final addon. Failed harness cleanup terminated those test clients; no
operational server/game files were modified.

## Installation/configuration

Use [the full example](../config-examples/voxy-lod-only.json) as
`config/voxy-lod-only.json` after shutting down the target server. Example values
are occupied workers=1/rate=10/budget=1ms and empty workers=4/unlimited/budget=10ms;
set occupied `enabled=false` to pause while players are present.
Profile values override old top-level values. `/voxylodonly status` displays
mode, enabled flag, worker limit, rate and paused state. Occupancy switching
occurs every server tick; editing config requires a restart.

Rate controls apply to preview admissions. VoxyServer's native ingest, storage
threads and streaming are outside this scheduler. At most two batches are
prepared ahead, so this is not a uniform whole-process CPU quota. Pause holds
bounded results in memory; cancel releases them. No production deployment,
world/cache deletion, movement or migration was performed.
