# Isolated verification

## Structure warnings and entity work (0.8)

`lodonlytest structurecompat` checks scoped preview suppression and a positive
ordinary-pig placement control. In a real pack it loads every BetterArcheology/
Integrated Villages template, compares all blocks and other NBT to the original
resource through the same active loader (including ModernFix's DFU/cache), and checks that only the intended terminal/absent optional references
change while valid entities remain identical.

For the paired pack run, use the same installed companion JARs and seed:

```powershell
$env:PYTHONPATH = "$PWD\build\verification-python"
# psutil can be installed into that project-only directory for the resource monitor.
$env:VOXY_TEST_JAR = 'C:\path\to\installed-voxy.jar'
$env:VOXY_SERVER_TEST_JAR = 'C:\path\to\installed-voxyserver.jar'
$env:VOXY_LOD_TEST_JAR = "$PWD\build\libs\VoxyLodOnly-0.7.0+1.20.1.jar"
python verification/run_modpack.py --structure-compat --compat-baseline --fingerprint-repeats 2 --soak-seconds 2
$env:VOXY_LOD_TEST_JAR = "$PWD\build\libs\VoxyLodOnly-0.8.0+1.20.1.jar"
python verification/run_modpack.py --structure-compat --fingerprint-repeats 2 --soak-seconds 2
```

The forced native structure fingerprint checks pieces at positive/negative
coordinates; the terrain fingerprint covers 1,474,560 output block/biome/light
voxels in Overworld and Nether. Block properties are sorted before hashing, and
per-chunk block, biome and light hashes are recorded separately. BetterNether's
surface providers use ThreadLocalRandom, so compare baseline repeat runs before
attributing a changed Nether digest to the patch. Compare inventory hashes and
warning counts. The fixed run also verifies exactly 26 terminal repairs and 146
absent optional references in the recorded pack. Runs copy the operational pack
read-only, start a fresh world on a separate port, and never copy its live world.
They permit only exact already-observed startup error bodies and retain all logs.
This is a functional equivalence test, not a speed/FPS benchmark.

Test mods are separate source sets and are never bundled in VoxyLodOnly's JAR.
Use disposable local worlds only. The scripts currently reuse the local
VoxyServer port.1 test installation's Fabric launcher/libraries, cached Sodium
and production client arguments. Adapt those reference paths on another machine.
They never launch or replace a production game/server installation.

To test a separately built companion Voxy, set `VOXY_TEST_JAR` to its absolute
path before running either script. The default still uses `libs/voxy.jar`.
The server script also accepts `VOXY_STORAGE_TEST_JAR` for Voxy's separate
failure-injection verification mod and waits for its PASS result on each boot.
These options copy only into new disposable fixtures; they do not replace the
reference libraries or operational installations.

```powershell
.\gradlew.bat build remapVerificationJar remapVerificationClientJar
python verification/run_server.py
python verification/run_graphical.py
```

`run_server.py` creates a fresh timestamped fixture under build/ on loopback
port 25587 with an offline Java 17 server and seed 424242. It checks guarded
live writes; skipping real spawn chunks; Overworld and Nether generation;
LOD presence/non-air data; no remote terrain/entity/POI region files after
shutdown; cancellation; LOD persistence and checkpoints on restart; normal
vanilla visitation and block persistence; stopping with generation in flight;
discarding an in-flight preview when the actual chunk is visited; completed
negative-coordinate generation and its persistence.
Assertions fail the runner if the checks do not pass.

## Runtime config reload (0.7)

`python verification/run_server.py --reload-only` verifies radius persistence,
invalid JSON rejection with old runtime settings retained, paused-manual progress
across disable/re-enable and tile/worker/batch-cap changes, structure-mode
roundtrips on overlapping positions, stop overriding pending automatic reload,
checkpoint persistence for both modes and radius retention on a second boot.

`python verification/run_activity.py --area-takeover --reload-policies` verifies
radius and worker changes while an actual player stays connected, followed by
disconnect/reconnect, manual progress, automatic takeover and complete stop cleanup.

## Block-area commands (0.6)

`python verification/run_server.py --area-only` checks circle/square block geometry,
pause before completion and during partial application, resume, negative block
coordinates, an explicit Nether command, maximum-radius admission then stop,
replacement while old cancellation drains, invalid syntax/radius rejection,
and checkpoint skipping on a second boot. After shutdown, the test compares
every completed checkpoint coordinate to independently calculated circle/square
sets, rejecting missing or extra positions and remote FULL region files.

`python verification/run_activity.py --area-takeover` also verifies switching a
real player's in-flight automatic job to an explicit block area, followed by
disconnect and occupancy-profile pause, without the old owner cancelling the
new queued area. It then stops the area and checks full cleanup.

## Occupancy profiles (0.5)

`run_activity.py` copies a closed graphical fixture into a fresh directory and
launches a real Voxy/VoxyServer/Sodium client **without the LOD Only addon**.
It verifies remote rendering, one-worker play mode, empty-server pause without
progress loss, reconnect/resume with exactly nine requested previews, explicit
last-player continuation, cancel/drain while paused, and a second server boot
with four-worker empty-server generation paced at four new chunk writes/second.
It rejects target vanilla region files, UNKNOWN cache roots, null-network errors
and server errors. Both servers and the graphical client must exit cleanly.

```powershell
$env:VOXY_TEST_JAR = 'C:\path\to\voxy-port.9.jar'
$env:VOXY_LOD_TEST_JAVA = 'C:\Java\graalvm-25.4.4.1.1+1.1\bin\java.exe'
$env:VOXY_ACTIVITY_FIXTURE = 'C:\path\to\closed-graphical-fixture'
python verification/run_activity.py
```

The default fixture is the recorded 0.4 graphical fixture; it already contains
the (256,256) remote LOD used by the renderer check. Do not point it at a running
world. An offline client with the test account's placeholder token may log an
authentication/Realms failure, which is separate from the local protocol test.
The results and raw server/client logs remain in `build/activity-<timestamp>`.

The parallel verifier compares every output voxel's block state, biome and
sky/block light between one and three stage workers, for tile sizes 4 and 8
in Overworld and Nether. This checks stage barriers and neighbor-write ordering;
it does not establish arbitrary modded world-generation thread safety.

`run_graphical.py` uses that fixture and creates a separate fresh Java 17
graphical client. It generates LODs at chunk (256,256), teleports the viewer
sixteen chunks west, and checks that the distant target is absent from vanilla
client chunks but present in Voxy storage. It checks renderer engine identity,
debug geometry/frame information, automatic generation while connected, and
idle state after disconnect. The client uses the remapped installable JARs and
an offline test token of 0. It requires a graphical Windows session.

Artifacts/logs:

- build/test-results/test/ : six core policy tests.
- build/verification-fixture.txt : server fixture path.
- <fixture>/phase-*.log and result.txt : server phases.
- build/graphical-server.log and graphical-client.log : client/server integration.
- build/graphical-client-directory.txt : fresh client path with lod-only-client-result.txt.
- build/graphical-result.txt : graphical test result.

These tests do not compare rendered pixels, shader output, or prove performance
under the complete Prominence modpack.

## 0.4 verification

The server runner selects 0.4 by default; `VOXY_LOD_TEST_JAR` can select another
addon JAR. The generator comparison now uses the retained-neighbor serial
reference and four-worker context release at sizes 4/8/16/32 in Overworld/Nether.

`run_modpack.py` reads the current server's mods/configuration into a new
`build/modpack-*` fixture with a fresh world. It never copies or edits the
production world, updates installed JARs or stops the operational server.
CrashAssistant is excluded from that fixture to avoid unrelated helper/GUI
side effects. Exact pre-Done error bodies from the source log are recorded and
classified as existing pack errors; other errors still fail the runner.
Only requested native dimensions are checked for remote FULL storage; the
pack's own custom arena worlds are listed separately.

```powershell
$env:VOXY_TEST_JAR = 'C:\path\to\voxy-port.9.jar'
$env:VOXY_LOD_TEST_JAVA = 'C:\Java\graalvm-25.4.4.1.1+1.1\bin\java.exe'
python verification/run_server.py
python verification/run_graphical.py
python verification/run_modpack.py --source C:\Prominence_2_4.1.3 --soak-seconds 180
```

The modpack runner uses 6 GiB heap and four logical CPUs for a functional smoke
test. It is separate from the identical 4 GiB CPU/heap Chunky comparison.
