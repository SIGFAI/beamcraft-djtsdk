# BeamCraft

**Play real Minecraft inside BeamNG.drive.**

BeamCraft runs an actual Minecraft (Java Edition, Fabric) in the background and passes Steve through to BeamNG.drive. Inspired by [SkyCraft](https://github.com/chasmlol/SkyCraft), which does the same for Skyrim.

Get out of any car and you *are* Steve, walking around BeamNG's maps. Minecraft does the movement, the items, the inventory and the combat. BeamNG shows the world, and your blocks become real objects that cars crash into.

## Features

- **Get out of a car as Steve.** `F` to get in or out, `Tab` to switch cars, like BeamNG's own walking mode.
- **Minecraft's own HUD in BeamNG.** Hotbar, hand, offhand, crosshair, chat and the inventory are rendered by Minecraft and overlaid on BeamNG. The creative search works too. `F5` shows the Minecraft-rendered Steve.
- **Hit cars.** Punching a car dents it, and stronger weapons do more damage. Attack cooldown, Sharpness and critical hits all count.
- **TNT and other explosions** damage nearby cars.
- **Blocks you place exist in BeamNG**, with real Minecraft textures and true shapes (stairs, slabs, fences, doors...), and cars collide with them. Levers, redstone dust, torches, rails and flowers show up too, with no collision.
- **Steve collides with BeamNG's real geometry.** Walls, bridges, overpasses, buildings and cars all stop him, at their exact shape, not as 1 m blocks.
- **Cars can hit Steve.** Getting run over hurts.
- **Boats float and Steve swims** on BeamNG's seas and lakes.
- **Lighting follows BeamNG's time of day.**
- **Works on any map.** Each BeamNG map gets its own area of the Minecraft world, so builds on different maps don't overlap.

## Requirements

| | |
|---|---|
| BeamNG.drive | recent version (tested on 0.39) |
| Minecraft Java Edition | **1.21.1** |
| [Fabric Loader](https://fabricmc.net/use/) | 0.16 or newer |
| [Fabric API](https://modrinth.com/mod/fabric-api) | for 1.21.1 |
| Java | 21 or newer (the Minecraft launcher's bundled Java is fine) |

Both games run at the same time on the same PC, so you need a reasonably strong machine.

## Installing

1. Download `beamcraft-<version>.jar` and `beamcraft.zip` from the [latest release](../../releases/latest).
2. **Minecraft:** create a Fabric 1.21.1 instance (with Prism Launcher, MultiMC, or the Fabric installer), add Fabric API, and put `beamcraft-<version>.jar` in its `mods` folder.
3. **BeamNG.drive:** put `beamcraft.zip` (don't unzip it) in BeamNG's mods folder:
   `%LOCALAPPDATA%\BeamNG\BeamNG.drive\current\mods`
4. Start Minecraft first and leave it on the title screen.
5. Start BeamNG.drive and load any map in Freeroam. Minecraft connects by itself, creates or opens a creative world called **BeamCraft**, and moves its window out of the way.
6. Get out of your car (`F`). You're Steve.

Install or update the BeamNG mod only while BeamNG is closed.

## Controls (on foot)

| Key | Action |
|---|---|
| `W` `A` `S` `D` | walk |
| `Space` | jump / fly up |
| `Left Shift` | sneak / fly down |
| `Left Ctrl` | sprint |
| Left mouse | attack, break blocks, hit cars |
| Right mouse | use, place blocks, open doors |
| Mouse wheel / `1`-`9` | hotbar |
| `E` | inventory (`E` or `Esc` closes it) |
| `Q` | drop item |
| `X` | swap hands |
| `T` / `/` | chat / command |
| `F5` | first / third person |
| `F` | get in the nearest car |
| `Esc` | close the Minecraft screen, or BeamNG's menu |

Every key can be rebound in BeamNG's controls settings (category *Gameplay*, actions named "BeamCraft: ...").

## Settings

- **BeamNG side:** `%LOCALAPPDATA%\BeamNG\BeamNG.drive\current\settings\beamcraft\settings.json` is created on first run. It sets look sensitivity, FOV, hit strength, explosion strength, the overlay resolution, and the frame caps used while on foot or in a Minecraft screen.
- **Minecraft side:** `config/beamcraft.properties`. `windowMode=offscreen` (the default) keeps Minecraft's window out of sight. `show` keeps it visible, which is handy for debugging.

### Other mods

- **Sodium, FerriteCore** and similar performance mods work fine.
- **Lithium:** set `mixin.entity.collisions.movement=false` in `config/lithium.properties`. Otherwise Lithium replaces the collision code BeamCraft uses, and Steve walks through cars.

## How it works

```
 Minecraft (Fabric mod)                         BeamNG.drive (Lua mod)
 ─────────────────────                          ─────────────────────
 Steve's physics, items, inventory   ◄── UDP ──  keys, mouse, camera look
 block changes, hits, explosions     ── UDP ──►  blocks → TSStatic objects
 HUD/inventory frames (PNG)          ── TCP ──►  overlay UI app
 collision boxes for Steve           ◄── UDP ──  BeamNG geometry sampled
                                                  around Steve, nearby cars
```

- Minecraft runs off-screen and only renders the HUD, hand and Steve. Frames are read back asynchronously, compressed and streamed to BeamNG, which shows them in a transparent UI app.
- BeamNG samples its own collision geometry on a 25 cm grid around Steve: floors, walls, pillars, the undersides of bridges. It sends this to Minecraft as collision boxes, so Steve walks on the exact surface. BeamNG's ground is also copied into Minecraft as invisible barrier blocks, so you have something to place blocks on.
- Every block in Minecraft is mirrored into BeamNG. Its real block model and textures are exported from Minecraft into BeamNG's user folder (`art/beamcraft`).
- Each BeamNG map is mapped to its own 20,000-block patch of the Minecraft world.

## Building from source

```sh
# Minecraft mod (needs a JDK 21+)
cd fabric
./gradlew build                 # -> fabric/build/libs/beamcraft-<version>.jar

# BeamNG mod
python tools/package.py         # -> dist/beamcraft.zip
python tools/package.py "%LOCALAPPDATA%/BeamNG/BeamNG.drive/current/mods"   # build + install
```

`tools/gen_art.py` regenerates the fallback block and Steve models in `beamng/art`. `tools/fake_beamng.py` is a small fake BeamNG for testing the Minecraft side on its own.

## Known limitations

- Single player only.
- Mobs aren't shown in BeamNG, so they're turned off.
- Blocks you place become solid for **cars** only after BeamNG rebuilds its collision. That's done in batches, because on big maps it takes a moment. Steve's own collisions are always up to date.

## License

[GPL-3.0](LICENSE). Not affiliated with Mojang, Microsoft or BeamNG GmbH. Minecraft is a trademark of Mojang Studios, and BeamNG.drive is a trademark of BeamNG GmbH.
