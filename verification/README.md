# Isolated verification

Test mods are separate source sets and are never bundled in VoxyLodOnly's JAR.
Use disposable local worlds only. The scripts currently reuse the local
VoxyServer port.1 test installation's Fabric launcher/libraries, cached Sodium
and production client arguments. Adapt those reference paths on another machine.
They never launch or replace a production game/server installation.

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
