<p align="center">
  <img src="docs/release/assets/distantstock-icon-256.png" alt="Create: Distant Stock" width="128">
</p>

<h1 align="center">Create: Distant Stock</h1>
<p align="center">Connect Create warehouses, factories and parcel logistics across Minecraft servers.</p>

<p align="center">
  <a href="README.md">简体中文</a> ·
  <a href="https://github.com/EVGA2048/Create-Distant-Stock/releases">Downloads</a> ·
  <a href="docs/wiki/README.md">Gameplay Wiki</a> ·
  <a href="https://github.com/EVGA2048/Create-Distant-Stock/issues">Issues</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.1-5c8845" alt="Minecraft 1.21.1">
  <img src="https://img.shields.io/badge/Loader-NeoForge-d78c44" alt="NeoForge">
  <img src="https://img.shields.io/badge/Create-6.0.x-85b8be" alt="Create 6.0.x">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-c8af75" alt="MIT License"></a>
</p>

> [!IMPORTANT]
> **Distant Stock is still under active development and multiplayer testing.** Features, recipes, balance, network protocol and save data may continue to change, so back up important worlds before updating. You are welcome to join the project discussion group for testing and feedback; reproducible bugs should be reported through [Issues](https://github.com/EVGA2048/Create-Distant-Stock/issues) with logs and reproduction steps.

![Create: Distant Stock overview](docs/release/assets/machines.png)

**Create: Distant Stock** extends Create with cross-server warehousing, ordering and physical parcel transport.

It is deliberately not an infinite remote inventory. Every warehouse remains a real, independent Create logistics network: the source warehouse receives the order and packs it, Distant Docks hand the parcel to Transerver, and the destination server returns it to local Create belts, chain conveyors, Frogports and sorting systems.

```text
Source Create warehouse
        ↓
Remote request / automatic resupply
        ↓
Distant Packager → Distant Dock
        ↓
──────────── Transerver ────────────
        ↓
Destination Distant Dock → local Create parcel logistics
```

This makes Distant Stock suitable for a central warehouse, dedicated production servers, survival servers and large multiplayer factories: the cross-server layer moves the parcel to the other server, while Create still decides how that parcel is produced, packed and routed locally.

## Highlights

- **Browse and order across servers:** choose a member warehouse from a portable terminal or fixed request desk.
- **Real Create parcel transport:** Distant Packagers create physical parcels, Distant Docks transfer them between servers, and Create addresses take over again after arrival.
- **Automatic resupply and redstone requests:** Distant Gauges order against local stock targets; Distant Redstone Requesters fire on a rising redstone edge.
- **Distant networks and member warehouses:** join codes organize shared warehouses without erasing each Create network's own ownership, locking or inventory boundaries.
- **Interlink Tower infrastructure:** rotationally powered multiblocks provide device coverage, capacity and chunk loading, with optional Ether costs for shipping.
- **Monitoring and industrial signalling:** monitors, loggers, stack lights, sounders and signal panels can form a practical factory control room.
- **Diagnostics and buffering:** Diagnostic and Cache Frogports help recover from bad addresses and blocked local routes.
- **Persistent hand-off state:** orders, parcels, acknowledgements, retries, returns and quarantine paths are tracked to reduce silent loss and duplicate delivery during interruptions.

## Devices and items

### Requesting, automation and cross-server transport

![Distant logistics devices](docs/release/assets/logistics.png)

| Device | Purpose |
| --- | --- |
| **Portable Distant Terminal** | Creates or joins a Distant Network, selects member warehouses, browses stock, places orders and pairs other devices. |
| **Request Desk** | A fixed request terminal for warehouse counters, factory consoles and shared pickup points. |
| **Distant Gauge** | Watches a local Create inventory target and automatically requests the shortfall from a remote member warehouse. |
| **Distant Redstone Requester** | Remote version of Create's Redstone Requester; a rising redstone edge triggers one configured request. |
| **Distant Packager** | Remote variant of Create's Packager that turns cross-server orders into actual Distant Parcels. |
| **Distant Dock** | The port where a parcel leaves or enters a server; multiple receiving docks may share one receiving address. |
| **Distant Parcel** | Preserves Create parcel contents and local address data while carrying cross-server routing information. |

Distant Stock separates the cross-server destination from the Create parcel address. A receiving address chooses the group of Distant Docks where the parcel lands; after it leaves that dock, Create's own address system handles local delivery.

### Interlink Towers

![Interlink Tower components](docs/release/assets/tower.png)

An Interlink Tower is built from an **Interlink Tower Base, consecutive Tower Couplers and an Ether Resonator on top**. Rotational power enters through the bottom of the base. Distant Casing forms the tower skirt and provides the visual/service shell around the core.

Tower height determines its tier. Higher tiers carry more devices, reach farther and allow a larger chunk-loading square:

| Tier | Couplers | Max devices | Activation radius | Chunk-loading limit | Stress impact |
| --- | ---: | ---: | ---: | ---: | ---: |
| I | 5 | 8 | 32 blocks | 1×1 | 256 |
| II | 7 | 16 | 40 blocks | 3×3 | 512 |
| III | 9 | 32 | 48 blocks | 3×3 | 1024 |
| IV | 11 | 48 | 64 blocks | 5×5 | 2048 |
| V | 13 | 64 | 80 blocks | 5×5 | 4096 |
| VI | 15 | 96 | 112 blocks | 7×7 | 8192 |
| VII | 17 | 128 | 144 blocks | 7×7 | 16384 |

With the default configuration, each outgoing cross-server parcel costs the sending tower **250 mB of Ether**. Server owners can disable the per-parcel charge or change the amount. Continuous tower standby consumption defaults to `0 mB/s`.

### Monitoring, control and diagnostics

![Monitoring and diagnostic devices](docs/release/assets/control.png)

| Device | Purpose |
| --- | --- |
| **Distant Monitor** | Shows local performance, Transerver link state, order/parcel queues and Interlink Tower status including stress, chunk loading, Ether and traffic. |
| **Event Logger** | Records system events, shows alarms and prints event receipts; paper rolls are required for printing. |
| **Stack Light + Condition Linker** | Sends independent red/yellow/green states and a buzzer-enable signal to an industrial status light. |
| **Red / Orange Wall Sounders** | Redstone-controlled visual and audible alarms for faults, shortages, stoppages or other factory states. |
| **Signal Lamps and Panels** | Cyan, orange, red, green, white and brass signal lamps for standalone or quarter-panel installations. |
| **Diagnostic Frogport** | Probes chain-conveyor addresses with Ping parcels and catches unroutable parcels as a diagnostic/quarantine exit. |
| **Cache Frogport** | Temporarily takes over a blocked business address, stores real parcels and releases them again at a controlled rate after recovery. |

Diagnostic Frogports expose a bottom-only extraction path for bad parcels. Cache Frogports hold 54 real inventory slots rather than a hidden infinite queue. See the [device guide](docs/wiki/设备.md) and [monitoring guide](docs/wiki/监控与日志.md) for the complete recovery behaviour.

### Materials and other content

Distant Stock includes its own production chain around **Ender Dust, Ether Quartz, Polished Ether Quartz, incomplete and completed Ether Mechanisms, Molten Amethyst and Ether fluid**. Fluids are available in buckets and bottles; recipes and processing steps are documented in the [materials guide](docs/wiki/材料与制作.md).

The mod also includes Resonant Quartz armour, the Distant Stock manual, event receipts, logger paper rolls and Ping parcels used by route diagnostics.

## How Distant Networks are organized

Distant Stock does not expose every Create logistics network on a server to every player. The normal flow is:

```text
Create / join a Distant Network from a terminal
        ↓
Visit a local warehouse and use the terminal on a Distant Dock already bound to Create
        ↓
That Create warehouse becomes a member of the Distant Network
        ↓
Terminals, request desks, gauges and redstone requesters
only choose sources from that member list
```

One Create logistics network can belong to only one formal Distant Network at a time. An eight-character join code grants initial access; after joining, devices retain the formal network identity, so rotating the join code does not disconnect existing members.

## Installation and dependencies

| Component | Current requirement |
| --- | --- |
| Minecraft | **1.21.1** |
| Loader | **NeoForge**; the current development/test environment uses 21.1.231 |
| Java | **21** |
| Required mods | **Create 6.0.x**, **Transerver 0.1.x** |
| Install on | Clients and every server participating in Distant Stock |

Download the JAR from [GitHub Releases](https://github.com/EVGA2048/Create-Distant-Stock/releases) and place it in `mods/` together with Create, [Transerver](https://github.com/EVGA2048/Transerver) and their dependencies. The current NeoForge dependency metadata requires Transerver on clients as well as servers.

Actual cross-server transport also requires an administrator to run a **Transerver Router** and configure the participating server nodes. Players work with Distant Network join codes, member warehouses and receiving addresses in-game rather than entering other servers' IP addresses.

### Optional integrations

- **Curios:** allows the portable terminal to be found/carried through a Curios slot.
- **Create: Deployer:** enables Distant gauges and signal lamps on compatible extended panel boards.
- **Create: FluidLogistics:** adds remote fluid parcel support.

None of these is required for the basic parcel network.

## Quick start

For a first working route:

1. Build functioning Create warehouse logistics on both servers.
2. Build and power the Interlink Towers; with default billing enabled, supply Ether to the sending tower.
3. Use a Distant Dock item on the local Create logistics link before placing the dock, binding it to that warehouse.
4. Create a Distant Network on one portable terminal and join it from the other server with the same eight-character join code.
5. Use each joined terminal on its local Distant Dock to register the corresponding Create warehouse as a network member.
6. Create a receiving address on the destination side, add the receiving dock to it, and select that destination on the source side.
7. Choose a source warehouse and item from the receiving terminal or request desk and place the first order.

See the [step-by-step quick start](docs/wiki/快速开始.md) for the full interaction sequence and troubleshooting notes. The gameplay wiki is currently written primarily in Chinese.

## Interruptions and parcel recovery

Distant Stock maintains persistent custody state for cross-server parcels and includes acknowledgements, duplicate-message handling, retries, returns and quarantine paths. When the destination server is offline, a receiving dock is blocked, an address is wrong or local routing fails, the system prefers to retain the parcel and expose a fault instead of silently consuming it.

These mechanisms reduce the risk of loss and duplicate delivery, but the mod is still in active testing; regular backups are recommended on important servers. A useful bug report should include:

- Distant Stock, Create, NeoForge and Transerver versions;
- relevant logs from both sides of the connection;
- reliable reproduction steps;
- screenshots or recordings when they help explain the state.

Remove authentication tokens, addresses and other secrets before sharing logs.

## Documentation

- [Gameplay Wiki](docs/wiki/README.md)
- [Quick Start](docs/wiki/快速开始.md)
- [Devices](docs/wiki/设备.md)
- [Interlink Towers](docs/wiki/互通塔.md)
- [Materials and Crafting](docs/wiki/材料与制作.md)
- [Monitoring and Logging](docs/wiki/监控与日志.md)
- [Server Operations](docs/wiki/服务器与运维.md)
- [Troubleshooting](docs/wiki/常见问题与排障.md)

## Building from source

JDK 21 is required. The current development build uses a local Create JAR from the test modpack and reads the Transerver build from an adjacent `../Transerver` repository, so a fresh development environment must adjust those paths in `build.gradle` first.

```bash
./gradlew build verifyWireCodec verifyParcelOwnership
```

The JAR is written as:

```text
build/Create-Distant-Stock-<version>+mc1.21.1.jar
```

## README and release artwork

The project icon and README device illustrations are rendered offline by the repository's Python renderer from the **actual shipped JSON models and PNG textures**. They do not use generative imagery, and the image files contain no added device names, version strings or promotional copy; captions stay in Markdown.

```bash
python3 scripts/render_release_art.py
```

The renderer requires Pillow, NumPy and the Create JAR configured in `scripts/render_block.py`.

## Thanks and license

Thank you to **MUL, KNaMg_Rana, w4yw and BSGM** for feedback and suggestions during early development and multiplayer testing.

Thanks to the **Create** team for the machinery, kinetics and logistics systems. Parts of the portable terminal model layout and Bee Port material artwork are adapted from Tim Heidler's [Create: Mobile Packages](https://github.com/timplay33/Create-Mobile-Packages) under its MIT license; the bundled [third-party license](src/main/resources/META-INF/LICENSE-mobile-packages.txt) is retained.

Create: Distant Stock itself is released under the [MIT License](LICENSE).
