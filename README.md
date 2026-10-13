# Voxy LOD Only

**English** | [한국어](README.ko.md)

**Generate distant terrain LODs on your server without saving full chunk files for those areas.**

For **Minecraft 1.20.1 · Fabric · dedicated servers**. VoxyServer sends the generated
LOD data to clients, and Voxy renders the distant landscape.
Areas players actually visit still generate and save normal Minecraft chunks.

## Installation

Stop the server, then place `VoxyLodOnly-…jar` from the
[releases](https://github.com/mihile/voxy-lod-only/releases) in its `mods` folder.
Also install:

- [Voxy 1.20.1 port.8](https://github.com/mihile/voxy/releases/tag/v0.2.20-1.20.1-port.8)
- [VoxyServer 1.2.4+1.20.1-port.1](https://github.com/mihile/VoxyServer/releases/tag/v1.2.4-1.20.1-port.1)
- Fabric API **0.92.6+1.20.1**, Fabric Loader **0.17.2 or newer**, Java **17 or newer**

Clients need **Voxy + VoxyServer + Sodium**.
**Install Voxy LOD Only on the server only.** Singleplayer is not supported.

## Usage

By default, the mod automatically generates LODs within a **32-chunk radius**
around players in the Overworld. You can also select an area manually or change
the automatic radius without restarting. Commands require operator permissions.

| Command | Action |
| --- | --- |
| `/voxylod radius 64` | Set and save the automatic generation radius to 64 chunks |
| `/voxylod 0 0 circle 2048 start` | Generate a 2,048-block radius around X=0, Z=0 in the current dimension |
| `/voxylod pause` / `/voxylod resume` | Pause / resume a manual job |
| `/voxylod stop` | Stop the job and disable automatic generation |
| `/voxylod automatic on` | Enable automatic generation around players again |
| `/voxylod status` | Show progress |
| `/voxylod reload` | Apply changes from the configuration file |

Area shapes are `circle` and `square`. The automatic radius is measured in
**chunks, up to 128**; manually selected areas use **blocks, up to 8,192**.
Both values are radii, not diameters.

Settings are stored in `config/voxy-lod-only.json`. Worker counts, generation rates,
and pausing can be configured separately for occupied and empty servers.

Existing saved chunks are skipped. To convert an existing world to LODs, use
VoxyServer's `/voxyserver import existing all` command.
Visible terrain depends on available LOD data, VoxyServer's streaming range,
and the client's render distance.

## More information

- [Full usage and configuration](docs/USAGE.md) (Korean)
- [Building from source](docs/BUILD.md) (Korean)
- [Validation results and known limitations](docs/VALIDATION.md) (Korean)

MIT License · Minecraft, Fabric, Voxy, and VoxyServer have their own separate licenses.
