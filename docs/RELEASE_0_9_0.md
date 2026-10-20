# Voxy LOD Only 0.9.0 - Minecraft 1.20.1

Stable release for Fabric dedicated servers, Java 17 or newer.

- Bound generation, pending results, conversion, and cancellation draining with a shared resource budget. Generation yields to tick and heap pressure, with hysteresis and cooperative checkpoints.
- Share automatic work between players, avoid overlapping in-flight requests, and retire stale requests without immediately reusing their resource reservations.
- Use conservative worker/tile defaults and expose queue, memory reservation, cancellation, and pause diagnostics through `/voxylod status`.
- Reduce repeated density graph, preliminary height, palette, and light-context work while retaining the structure generation path.
- Correct six Integrated Villages 1.3.2 jigsaw references in two cabin intersections from `cabin_village/villager_random` to the existing `cabin_village/mobs/villager_random` pool. Other template NBT and pool selection weights are preserved.

Install **Voxy LOD Only on the server only**. Use [Voxy 1.20.1 port.8](https://github.com/mihile/voxy/releases/tag/v0.2.20-1.20.1-port.8), [VoxyServer port.2](https://github.com/mihile/VoxyServer/releases/tag/v1.2.4-1.20.1-port.2), Fabric API 0.92.6+1.20.1, and Fabric Loader 0.17.2 or newer. Clients need Voxy, VoxyServer, and Sodium. Replace the previous addon JAR rather than keeping two versions installed.

Existing LOD storage and completion records remain supported. LOD-only generation does not save distant vanilla terrain, entity, or POI region files; normally visited chunks retain normal Minecraft behavior. Existing configuration values are retained; new conservative defaults do not overwrite your chosen values.

Release validation: Java 17 target build, 54 passing unit tests, product JAR contents and companion version predicate checked. Historical isolated flight/accuracy runs in [the validation report](../verification/FLIGHT_PERFORMANCE.md) apply to the recorded candidate hashes. The final release has not repeated the entire modpack flight matrix, and no production server files or running world were changed during packaging.

Download the ordinary JAR for installation; `-sources.jar` is source code. `SHA256SUMS.txt` lists both assets.
