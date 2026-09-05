# Create: Distant Stock

Cross-server Create warehouse. Logistics stays on one JVM; the hop is sealed packages.

[中文](README.md) · [English](README.en.md)

[![Release](https://img.shields.io/github/v/release/EVGA2048/Create-Distant-Stock)](https://github.com/EVGA2048/Create-Distant-Stock/releases)
[![License](https://img.shields.io/github/license/EVGA2048/Create-Distant-Stock)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1-blue)](https://neoforged.net/)
[![Create](https://img.shields.io/badge/Create-6.0.10-yellow)](https://modrinth.com/mod/create)

---

**Create: Distant Stock** (机械动力：远仓) is a NeoForge 1.21.1 addon for Create 6. Each server loads the same jar. The warehouse listens; outer servers connect to it (star: one warehouse, many survival worlds). Players only tune a frequency and type a package address. **Blocks never ask for an IP.** Version is [`gradle.properties`](gradle.properties) and [Releases](https://github.com/EVGA2048/Create-Distant-Stock/releases).

Mod id: `distantstock`. HTTP **18772**, header `X-DistantStock-Token`. Code uses `self.id` from config — **do not hardcode server names**.

## What it is not

- No third hub process. Warehouse `self.id=host` listens; outer servers connect only to the warehouse.
- Clients never speak HTTP. This server’s io thread polls the other side into a cache.
- Two Stock Link networks stay separate. Only unopened `PackageItem` NBT crosses the wire.
- It does not use Mobile Packages' drone logic. The Distant Dock only borrows the transport-bee port's industrial bay language; transport remains a cross-server package hop.
- No remote packager. The warehouse uses a vanilla Create packager.
- The main thread never does HTTP and never joins on a reply.

## Pieces

| Name | id | Role |
|------|-----|------|
| Portable Distant Requester | `requester` | Hands or Curios `body`. Join from the open-network list. **H** opens the menu. |
| Distant Dock | `dock` | Export = freq (ship packages). Import = address only (restore packages). |
| Distant Request Desk | `gauge` | Floor ticker; same GUI as the portable item. |
| Distant Monitor | `monitor` | Wall dashboard: local/peer TPS and link pressure. |
| Distant Stock Manual | `manual` | Creative tab. `giveManual` (default on) gives one on first join. |

All three blocks implement Create goggles. Live data only; the tutorial is the manual. Never show `peer.host`, port, or token.

Same-JVM frequency calls `broadcastPackageRequest`. Otherwise `POST /order`; packed boxes come back as `POST /package`.

## Install

1. Drop `Create-Distant-Stock-<ver>+mc1.21.1.jar` into both `mods/` folders (Create required).
2. In-game as **OP**, open link setup (writes this server’s `config/distantstock-common.toml` and restarts the listener):
   - `/distantstock`, or sneak-click a Distant Monitor.
   - **Host**: self id, listen `0.0.0.0:18772`, client list `id@host:port` (comma), password.
   - **Client**: unique self id, listen (needed to receive packages), warehouse `host@host:port`, **same password**.
   - Toml still works: `self.role` / `self.id` / `peers` / `token`. Legacy `peer.host` / `peer.port` count as one `id=peer`.
3. Missing keys are filled in; existing values are not overwritten.

`debug.demoStock` defaults to `false`. Turn it on only to preview the UI.

## HTTP

Token header required when `token` is set. Server io thread only.

| | Path | |
|--|------|--|
| `GET` | `/stock?freq=` | Cached catalog |
| `GET` | `/status` | Local TPS/MSPT, queue depth, `self.id` |
| `POST` | `/order` | Enqueue `{freq, address, from, items[]}`. `from` is who gets the package back. |
| `POST` | `/package` | Enqueue package NBT |

`503` means the queue is full; the peer retries.

## Build

Java 21. `compileOnly` against a local Create 6.0.10 jar (`build.gradle`).

```bash
./gradlew jar
# → build/Create-Distant-Stock-<ver>+mc1.21.1.jar
```

## License

[MIT](LICENSE).

The handheld model layout is adapted from [Create: Mobile Packages](https://github.com/tom5454/Create-Mobile-Packages) (**MIT**, Tim Heidler). Wood remapped to stripped warped stem; antenna UVs kept. Stock-keeper GUI textures are read from the `create` namespace at runtime and are not bundled.
