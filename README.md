# P2P Compat

Unified compatibility addon for [P2P Channel Network](https://github.com/Kalloer1/p2p): extra channel media and host-mod bridges. Each integration activates only when its host mod is present — install only what you need.

## Repository layout

| Branch | Minecraft | Loader |
| --- | --- | --- |
| `1.21.1` | 1.21.1 | NeoForge 21.1.x |
| `1.20.1` | 1.20.1 | Forge 47.4.x |

## Integrations

- **Mekanism** — gas & heat channel media
- **Applied Energistics 2** — ME channel bridge
- **Ars Nouveau** — source media
- **Botania** — mana channel media
- **JEI / EMI** — drag gases (or filled tanks) into FLUID-channel filter markers

## Requirements

- Minecraft `1.20.1` + Forge `47.4.x`
- [P2P Channel Network](https://github.com/Kalloer1/p2p) `1.4.0` or later
- Host mods optional — each integration loads only when its host mod is installed

## Building

Host-mod development jars are intentionally **not** bundled in this repository (redistribution restrictions). Drop the dev jars listed in `build.gradle` into `libs/` before building:

```bash
./gradlew build
```

## License

GPL-3.0-only. See [LICENSE](LICENSE).
