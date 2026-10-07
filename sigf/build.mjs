// BeamCraft (DJtsdk, GPL-3.0): real Minecraft 1.21.1 running hidden next to BeamNG.drive, Steve passed through into
// BeamNG's world (Fabric mod over 127.0.0.1 UDP 47820/47821 + TCP 47822 for the HUD frames, and a BeamNG Lua mod).
// Rehosted on SIGFAI/beamcraft-djtsdk (mirror of the tag commit = the Corresponding Source of both binaries).
//
// BeamNG.drive loads mods from its user folder (%LOCALAPPDATA%\BeamNG\BeamNG.drive\current\mods), which no recipe root
// reaches. So BeamNG starts with `-userpath {app}/userfolder`: its whole user folder is this mashup's own SIGF folder,
// the mod zip goes to <userpath>/current/mods (the layout NG64's installer reads from startup.ini's UserPath:
// <UserPath>\current\mods), and Restore deletes it with the block art the Minecraft side exports there at runtime.
//   node library/beamcraft-djtsdk/build.mjs       (outputs: library/lib.mjs)
import { mrpack, resolveFabricApi } from '../../orchestrator/src/recipe.js';
import { asset, card, dl, emit, pinned, player, rawAt } from '../lib.mjs';

const UP = {
  repo: 'https://github.com/DJtsdk/BeamCraft', tag: 'v0.1.0', commit: '6cf6bbbcfd9d14d2df3cc6e219dc6721694cc354',
  license: 'GPL-3.0-only', authors: ['DJtsdk'],
  jar: { file: 'beamcraft-0.1.0.jar', sha256: 'f80e0c685f41794268c75b9207489b944582142452677c9c119d35128ffa86ac' }, // = GitHub digest
  zip: { file: 'beamcraft.zip', sha256: 'db1d05f5d7dbb803b7b52f0f262354e5e8293a1387e30b965ec6b07cdfcded6a' }, // = GitHub digest, = beamng/ at the tag
};
const MC = { mc: '1.21.1', loader: '0.19.3', fabricApi: '0.116.17+1.21.1', java: '21' }; // fabric/gradle.properties at the tag
const ID = 'beamcraft-djtsdk', VERSION = '0.1.0', NAME = 'BeamCraft';
const TAGLINE = 'Get out of your car in BeamNG.drive as Steve: build with real Minecraft blocks that cars crash into, and punch cars until they dent.';
const USER = '{app}/userfolder';

const rel = (f) => `${UP.repo}/releases/download/${UP.tag}/${f}`;
const jar = await pinned(rel(UP.jar.file), UP.jar.sha256);
const bng = asset(UP.zip.file, await pinned(rel(UP.zip.file), UP.zip.sha256)); // copied as is: BeamNG mounts the zip
const license = await rawAt(UP.repo, UP.commit, 'LICENSE');
const fabricApi = await resolveFabricApi(MC.fabricApi, MC.mc);
if (!fabricApi?.download) throw new Error(`Fabric API ${MC.fabricApi} not resolved on Modrinth`);
const pack = asset(`${ID}.mrpack`, mrpack({ name: NAME, summary: TAGLINE, versions: MC, versionId: VERSION, fabricApi,
  jars: [{ name: UP.jar.file, data: jar }], extra: [{ name: 'overrides/licenses/BeamCraft-LICENSE.txt', data: license }] }));
const assets = [bng, pack];

const make = (urls, set) => {
  const mp = set.find(a => a.name.endsWith('.mrpack'));
  return {
    id: `sigf/${ID}`,
    version: VERSION,
    name: NAME,
    tagline: player(ID).tagline ?? TAGLINE,
    how_to_play: player(ID).howToPlay,
    kind: 'passthrough',
    games: [
      { game: 'beamng', role: 'host', label: 'BeamNG.drive', engine: 'BeamNG.drive (Torque3D-based, x64) + BeamNG Lua mod (GE extension, UI app)', apps: { steam: '284160' }, runtime: '0.39 (tested by the author)' },
      { game: 'minecraft', role: 'guest', label: 'Minecraft', engine: 'Minecraft Java 1.21.1 + Fabric mod beamcraft (Java)', mc: MC.mc, loader: `fabric@${MC.loader}`, java: MC.java },
    ],
    requires: [
      { id: 'fabric-loader', version: MC.loader },
      { id: 'fabric-api', version: MC.fabricApi, note: 'in the Minecraft pack (downloaded from Modrinth)' },
    ],
    install: [
      // BeamNG's own user folder for this mashup (-userpath below); the zip is mounted as is, never unpacked.
      { game: 'beamng', strategy: 'profile', files: [
        { src: bng.name, dst: `${USER}/current/mods/${bng.name}`, unpack: false, ...dl(bng, urls) },
      ] },
      { game: 'minecraft', strategy: 'mrpack', pack: { src: mp.name, ...dl(mp, urls) } },
    ],
    // Minecraft first: its mod opens the HUD channel (TCP 47822) at client init; BeamNG then finds it on any map.
    launch: [
      { game: 'minecraft', wait: 'port:47822' },
      { game: 'beamng', args: ['-userpath', USER] },
    ],
    files: set.map(a => ({ name: a.name, ...dl(a, urls) })),
    source: {
      repo: UP.repo, license: UP.license, upstream_license: UP.license, tag: UP.tag, commit: UP.commit,
      hosted: `https://github.com/SIGFAI/${ID}`,
    },
    media: {},
    built_by: { author: UP.authors[0], authors: UP.authors, packaged_by: 'SIGF' },
    idea_by: UP.authors[0],
    built_at: '2026-10-07T00:00:00.000Z',
    // Never installed together (the app refuses either order): captiencelovesarch's BeamCraft (not packaged yet): the same mod id beamcraft.
    conflicts: ['sigf/beamcraft'],
    ...card(UP.repo),
    notes: player(ID).notes,
  };
};

emit({ slug: ID, version: VERSION, assets, make });
