# BeamCraft

Get out of your car in BeamNG.drive as Steve: build with real Minecraft blocks that cars crash into, and punch cars until they dent.

**BeamCraft is made by [DJtsdk](https://github.com/DJtsdk).** All credit for the mod goes to them.

- Original project: https://github.com/DJtsdk/BeamCraft
- Report bugs and ask questions there: https://github.com/DJtsdk/BeamCraft/issues
- Upstream release packaged here: [v0.1.0](https://github.com/DJtsdk/BeamCraft/releases/tag/v0.1.0) (commit [`6cf6bbb`](https://github.com/DJtsdk/BeamCraft/tree/6cf6bbbcfd9d14d2df3cc6e219dc6721694cc354))

> **Beta.** Nobody at SIGF has played this build yet. Back up your saves.
> Bugs in the mod itself go to the author's issue tracker above; problems with the one-click install go to this repository's issues.

## What you need

- **BeamNG.drive** ([Steam](https://store.steampowered.com/app/284160/)): 0.39 (tested by the author).
- **Minecraft**: Java Edition 1.21.1.
- Windows and the [SIGF app](https://sigf.ai). The app installs fabric-loader 0.19.3, fabric-api 0.116.17+1.21.1 for you.

## Install

In the SIGF app, open **BeamCraft** in the catalog, press **Install**, then **Play**. **Restore** puts your game folders back exactly as they were.
The app follows `mashup.json` in this repository: every download is pinned by sha256. The files come from the release [`v0.1.0`](../../releases/tag/v0.1.0).

### How to play

- BeamNG.drive as usual, but on foot you are Minecraft's Steve: walk any map, place and break blocks, fight with Minecraft weapons and TNT.
- Press Play: Minecraft starts first and hides itself, then BeamNG.drive. Load any map in Freeroam and BeamCraft connects by itself.
- F gets out of the car or into the nearest one, Tab switches cars. On foot: WASD, Space jump, Left Shift sneak, Left Ctrl sprint, F5 third person.
- Left mouse attacks, breaks blocks and dents cars; right mouse places blocks and opens doors. E inventory, Q drop, T chat, / commands.
- Every key can be rebound in BeamNG's controls under Gameplay, actions named BeamCraft.

### Good to know

- You need BeamNG.drive on Steam (Windows) and Minecraft: Java Edition. BeamNG.drive starts with its own SIGF user folder (-userpath): your usual settings, mods and saves are not in it, and Restore deletes it.
- Single player only. Mobs are off. Placed blocks become solid for cars after a short batch rebuild. With Lithium installed in the pack, Steve walks through cars.
- Never together with the other BeamCraft (captiencelovesarch): both are called BeamCraft and use the mod id beamcraft. Restore one before installing the other.
- Beta (first release, tested by the author on BeamNG.drive 0.39 and Minecraft 1.21.1): report bugs to the author with the Report a bug link.

## Not together with BeamCraft (captiencelovesarch)

Another BeamCraft (captiencelovesarch/beamcraft, Linux only) uses the same names (Minecraft mod id `beamcraft`, BeamNG extension `beamcraft`). Install only one of them.

## What this repository holds

1. The upstream source tree at tag `v0.1.0`, commit [`6cf6bbbcfd9d14d2df3cc6e219dc6721694cc354`](https://github.com/DJtsdk/BeamCraft/tree/6cf6bbbcfd9d14d2df3cc6e219dc6721694cc354), every file unchanged (same git blobs). Upstream's own `README.md` is there, unchanged; GitHub shows this file (`.github/README.md`) first.
2. Added by SIGF in the same commit: this file, and `sigf/` (the scripts that built the release assets, for reference: they run inside the SIGF repository).
3. `mashup.json`, the SIGF app recipe (the next commit).
4. The release `v0.1.0` (its tag is the first commit):

| Asset | Size | sha256 | What it is |
|---|---|---|---|
| `beamcraft.zip` | 102636 B | `db1d05f5d7dbb803b7b52f0f262354e5e8293a1387e30b965ec6b07cdfcded6a` | upstream's BeamNG.drive mod from release `v0.1.0`, unchanged (sha256 `db1d05f5...ded6a`, identical to `beamng/` in this tree); the app copies it, not unpacked, into this mashup's own BeamNG user folder (`-userpath`). |
| `beamcraft-djtsdk.mrpack` | 92364 B | `d38fadb351bca48428c25841616f8dfd076d4111c362dc34534f542ec2975f0f` | the Minecraft side for Minecraft 1.21.1 with Fabric Loader 0.19.3: upstream's `beamcraft-0.1.0.jar` from release `v0.1.0`, unchanged (sha256 `f80e0c68...86ac`, built from `fabric/` in this tree), with upstream's GPL-3.0 LICENSE; Fabric API 0.116.17+1.21.1 is a Modrinth download link, not stored here. |

## Licenses

| Part | License | Where |
|---|---|---|
| BeamCraft (all of the upstream tree, the mod zip and the jar) | GPL-3.0-only (this tree is their Corresponding Source) | `LICENSE` |
| Fabric API (downloaded from Modrinth by the app, not stored here) | Apache-2.0 | https://github.com/FabricMC/fabric |

## Why this repository exists

The SIGF app (https://sigf.ai) installs mods from recipes (`mashup.json`) whose downloads are pinned release files. This repository makes BeamCraft installable in one click, credited to DJtsdk. If you are the author and want anything changed or taken down, open an issue here.
