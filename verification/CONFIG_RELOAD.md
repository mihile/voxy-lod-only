# Runtime configuration — 0.7.0+1.20.1

2026-10-13. Installable JAR SHA256:
`be90c62c39f32b50bf4cad366a65c86e80d648e64cd65e7f9eec284218764c4e`.
Voxy port.9 and VoxyServer port.1 are unchanged. Java 17 bytecode;
isolated server verification uses the local GraalVM 25.4.

## Commands

`/voxylod radius 64`: immediately changes the automatic player-centered radius
to 64 chunks, persists only `radiusChunks` in `config/voxy-lod-only.json`, and
leaves manual area geometry and other disk edits/properties intact. Valid range
is 1..128 chunks. Active automatic work drains before selecting a new area.
It does not turn automatic scheduling on if it was off.

`/voxylod reload`: validates the edited JSON, cancels and drains old work, then
applies a complete replacement configuration. Invalid JSON/settings preserve the
running configuration. Manual area geometry and manual-pause state survive;
completion checkpoints skip finished positions when the plan is rebuilt. Partial
uncheckpointed work may be repeated. Worker counts refresh with no player-count
transition needed. Disabled configuration still accepts re-enabling via reload.

`/voxylod status` displays `radiusChunks` and `configReloadPending`. A reload has
applied once pending is false and `LOD config applied` appears in the server log.
Explicit stop overrides a pending reload's automatic flag. File automatic settings
otherwise apply, except that an active manual request preserves its runtime flag.
Changing structure mode invalidates opposite-mode completion for overwritten
positions, so returning to the former mode regenerates rather than skipping stale
preview data. No stored LOD is deleted when changing radius/configuration.

Commands require OP level 2. They affect only this addon's settings. Versions up
to 0.6 do not have them: installing the new JAR requires one normal server restart;
subsequent configuration changes do not.

## Files changed

- `LodOnlyConfig.java`: reloadable path/parser, immutable radius snapshot, atomic
  radius-only JSON update preserving other and unknown properties.
- `LodOnlyManager.java`: deferred snapshot application after cancellation drain,
  preserved manual requests/pause, disabled-state cleanup, forced profile refresh,
  pending automatic override and two structure-mode ledgers per world.
- `LodCommands.java`: reload/radius commands and success/failure feedback.
- `CompletionLedger.java`: invalidation and persistence of emptied existing ledgers.
- `TilePlan.java`: exposes the immutable area to reconstruct a manual request.
- `ConfigReloadTest.java`: four behavioral IO/snapshot/invalidation tests.
- `run_server.py --reload-only`, `run_activity.py --reload-policies`: live tests.
- README/DESIGN and version/runners updated; no companion mod edits.

## Final config tests: PASS

26 unit tests passed (16 policy, six area, four reload tests).
Fixture: `build/isolated-20261013-174005`, logs `phase-1.log` and `phase-2.log`.

- Radius 32→64 applied without restart and persisted on disk. Malformed JSON reload
  failed and runtime radius remained 64.
- A 38-chunk manual circle was paused after one completion. Reload changed radius,
  workers 4→1, tile size 32→8, in-flight cap 2→1, tick budget and enabled state.
  The old batches/references drained, the request stayed paused while disabled,
  and an enabled reload plus resume finished exactly 38 previews.
- Radius 24 set while disabled remained on reload and next server boot.
- Structures true→false→true on overlapping position forced regeneration of that
  position while skipping the other 37 completed true-mode positions. Another
  false-mode position remained recorded. Final ledgers contained exactly 38 true
  and one false positions, and all 41 actual generations were accounted for.
- Stop overrode automatic=true in a pending reload; status reached idle with zero
  batches/conversion tasks and reload pending false.
- Two clean server exits, no server ERROR, and no remote vanilla terrain/entity/POI
  region files. Raw logs and final marker remain in the fixture.

An earlier intermediate fixture `...173727` passed a narrower reload test before
opposite-mode invalidation was added; it is not used as final-artifact evidence.

## Live-player configuration test: PASS

Fixture `build/activity-20261013-174156`, client `build/activity-client-20261013-174156`:
the real player stayed connected while radius changed to 12 and an occupied-profile
reload changed workers from one to two. The profile updated with player count
unchanged. Client protocol 2, identified cache path, 127,248 non-air remote LOD
voxels and active renderer were verified without LOD Only installed client-side.
The remote vanilla client chunk remained absent.

Disconnect paused the manual job and reconnect resumed to exactly nine completions.
Automatic-to-explicit-area takeover followed by immediate disconnect retained the
correct manual area; stop drained to idle with zero work/refs. A second empty-server
boot paced nine previews at four/second (2.243s from first observed completion to
finished/idle). Both servers and the client exited zero. No server ERROR, null
network handle or UNKNOWN cache. The offline placeholder account's authentication/
Realms error is separate from the passing local protocol/render checks.

No operational JAR/config/cache/world was modified. Tests run only in fresh fixture
directories. No new FPS or Chunky benchmark is claimed.

Final legacy regression `build/isolated-20261013-174456` passed five boots:
guarded real-world access, 384-section conversion/cancel/rejection/reference cleanup,
Overworld/Nether storage, real-chunk precedence, normal visitation/block persistence,
checkpoint restart skipping, stop during generation and negative coordinates.
All five exits were zero with no server ERROR lines or private FULL region files.
Terrain voxel comparison was not rerun because the terrain generator did not change.
