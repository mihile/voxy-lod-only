# Block-area generation — 0.6.0+1.20.1

2026-10-13. Final installable JAR SHA256:
`6807f26085be0e19c0df87214af189dae6f30402038b28ddf571c7f6d90867fc`.
Voxy port.9 and VoxyServer port.1 remain unchanged. Java 17 bytecode;
runtime server tests use the local GraalVM 25.4.

## Interface

```
/voxylod <centerX> <centerZ> <circle|square> <radiusBlocks> start
/voxylod pause
/voxylod resume
/voxylod stop
/voxylod status
/voxylod automatic on
```

Center X/Z and radius use absolute integer block coordinates. Radius is 0..8192
blocks, not diameter; square side spans roughly twice the radius. Commands require
OP level 2. Execution dimension is the command source's dimension; console can use
`execute in minecraft:the_nether run voxylod ...`. Legacy `/voxylodonly generate`
keeps its original chunk-coordinate syntax.

The area selects chunks with at least one integer block column inside its shape.
This necessarily includes the remainder of each selected 16x16 chunk. Radius zero
selects the containing chunk. World-border/real/saved/completed chunks are skipped.
Neighbor terrain is temporarily generated as needed but is not added to LOD output.

Start turns off automatic scheduling for this run and takes over an automatic job
after its cancellation barrier drains. A second active manual area is rejected.
Pause retains the iterator, pending results and partial section index; resume
continues under the current occupancy profile. Running private calculations finish
safely. Stop cancels queued/current work, turns off automatic scheduling and leaves
stored LOD intact. Restart does not retain the unfinished request: issue the same
start again to skip normally saved completion checkpoints and generate the rest.

## Implementation files

- `GenerationArea.java`: bounded block geometry and exact per-column circle count.
- `TilePlan.java`: lazy near-center rings, bounded buffering, legacy traversal compatibility.
- `LodCommands.java` / `VoxyLodOnly.java`: command tree and feedback.
- `LodOnlyManager.java`: one queued area, pause/resume/stop, cancellation barrier,
  old-owner isolation, and per-job completed/total status including saved NBT skips.
- `GenerationAreaTest.java`: six geometry/traversal tests, alongside 16 existing tests.
- `run_server.py --area-only`: actual command, storage and restart assertions.
- `run_activity.py --area-takeover`: real-player lifecycle and explicit-area takeover.

## Area/storage tests: PASS

Final artifact fixture: `build/isolated-20261013-170755`.
Logs: `phase-1.log`, `phase-2.log`; final marker: `result.txt`.

- 22 unit tests passed, including all legacy policy/tile tests.
- `(2048,2048), circle radius 48`: exactly 38 preview chunks. Paused before any
  completion, resumed, paused at two completions for two seconds with unchanged
  count, then resumed to exactly 38. Stored sample had 31,204 non-air voxels.
- `(-4097,4097), square radius 32`: exactly 25 previews, validating negative block
  coordinate rounding and chunk-boundary inclusion.
- Explicit Nether square radius zero: exactly one preview in Nether.
- Maximum radius 8192 request (1,050,625 candidate chunks) started and was stopped
  while work was running. A replacement one-chunk circle queued while cancellation
  drained; final total was exactly 65, with no output from the cancelled area.
- Status reached idle with zero in-flight batches/conversion tasks and automatic off.
- Unknown shape and radius 8193 were rejected by the command parser.
- After clean shutdown, every checkpoint coordinate exactly matched independently
  calculated circle/square sets plus the two single-chunk requests. No missing or
  extra positions; no remote vanilla terrain/entity/POI region files.
- Second boot repeated the circle: zero new previews, exactly 38 skipped positions.
  Both server exits were zero, with no server ERROR lines.

An earlier harness attempt `build/isolated-20261013-170246` expected the wrong
vanilla error wording for an unknown shape and timed out. It is not counted as a
complete pass. The shape was correctly rejected; the runner now matches the actual
`Incorrect argument for command` response. Test cleanup stopped that server.

## Real-client takeover test: PASS

`build/activity-20261013-170916` used the same artifact with a real Java 17 client
without LOD Only installed client-side. It verified protocol 2, identified cache
root, 127,248 non-air remote LOD voxels and an active renderer while the remote
vanilla client chunk remained absent. Manual pause on disconnect and resume on
reconnect retained exact nine-chunk progress.

While the real player's automatic job was in flight, a new explicit area centered
at (24576,24576) was accepted. Immediate final-player disconnect selected the empty
profile; the new manual area remained paused with its correct center rather than
being discarded by the old automatic owner's lifecycle. Stop drained to idle,
zero batches/tasks. A second boot verified independent four-per-second idle
admission (2.240s from first observed completion to nine completed and idle).
All three exits were zero. No server ERROR, null network handle or UNKNOWN cache.
Raw logs and timestamped samples remain in that fixture's `result.json`.
The offline account's placeholder authentication/Realms errors are separate from
the passing local network and render checks.

## Scope

Final legacy regression `build/isolated-20261013-171150` passed five boots,
including guarded live writes, 384-section conversion cancel/rejection/ref cleanup,
Overworld/Nether generation, real-chunk skipping, no private FULL region files,
checkpoint/LOD persistence, normal block visitation/persistence, shutdown while
generation ran, in-flight real-chunk precedence and negative-coordinate restart.
All five exits were zero with no server ERROR lines. Terrain voxel comparison was
not repeated because the generator code did not change in 0.6.

Existing automatic generation and occupancy configuration remain supported.
This release adds no companion patches and deploys no JAR to operational installs.
It neither deletes nor moves any operational cache/world. Install the new addon
instead of the old addon only after the target server stops.
This verification is functional; no new Chunky speed/FPS comparison is claimed.
