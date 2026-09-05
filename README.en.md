# Create: Distant Stock

Cross-server Create warehouse. Logistics stays on one JVM; the hop is sealed packages.

[中文](README.md) · [English](README.en.md)

[![Release](https://img.shields.io/github/v/release/EVGA2048/DistantStock)](https://github.com/EVGA2048/DistantStock/releases)
[![License](https://img.shields.io/github/license/EVGA2048/DistantStock)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1-blue)](https://neoforged.net/)
[![Create](https://img.shields.io/badge/Create-6.0.10-yellow)](https://modrinth.com/mod/create)

---

**Create: Distant Stock** (机械动力：远仓) is a NeoForge 1.21.1 addon for Create 6. Each side loads the same jar. Admins set `host` / `peer` and an address in toml; players only tune a frequency and type a package address. **Blocks never ask for an IP.** Version is [`gradle.properties`](gradle.properties) and [Releases](https://github.com/EVGA2048/DistantStock/releases).

Mod id: `distantstock`. HTTP **18772**, header `X-DistantStock-Token`. Code uses `self.id` from config — **do not hardcode server names**.

## What it is not

- No third hub process. `host` listens; `peer` connects out.
- Clients never speak HTTP. This server’s io thread polls the other side into a cache.
- Two Stock Link networks stay separate. Only unopened `PackageItem` NBT crosses the wire.
- Not Create: Mobile Packages. No bees. The handheld item is a stock-keeper window; the hop is the linker.
- The main thread never does HTTP and never joins on a reply.

## Pieces

| Name | id | Role |
|------|-----|------|
| Portable Distant Requester | `requester` | Hands or Curios `body`. Right-click a Stock Link to copy freq. **H** opens the menu. |
| Distant Linker | `linker` | Export = freq (ship packages). Import = address only (restore packages). |
| Distant Gauge | `gauge` | Wall requester; same GUI as the portable item. |
| Distant Monitor | `monitor` | Wall dashboard: local/peer TPS and link pressure. |

All three blocks implement Create goggles. Sneak for extra lines. Never show `peer.host`, port, or token.

Same-JVM frequency calls `broadcastPackageRequest`. Otherwise `POST /order`; packed boxes come back as `POST /package`.

## Install

1. Drop `Create-Distant-Stock-<ver>+mc1.21.1.jar` into both `mods/` folders (Create required).
2. Edit `config/distantstock-common.toml`:
   - One side: `self.id = "host"`, `self.bind = "0.0.0.0:18772"`.
   - Other side: `self.id = "peer"`, `peer.host` / `peer.port` pointing at host.
   - **Same `token` on both sides.** Empty token is for dev only.
3. Restart. Missing keys are filled in; existing values are not overwritten.

`debug.demoStock` defaults to `false`. Turn it on only to preview the UI.

## HTTP

Token header required when `token` is set. Server io thread only.

| | Path | |
|--|------|--|
| `GET` | `/stock?freq=` | Cached catalog |
| `GET` | `/status` | Local TPS/MSPT, queue depth, `self.id` |
| `POST` | `/order` | Enqueue `{freq, address, items[]}` |
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
