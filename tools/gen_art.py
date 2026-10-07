"""Generates BeamNG art for BeamCraft: one cube shape per Minecraft map colour, and a blocky Steve.

Run: python tools/gen_art.py   (writes into beamng/art/beamcraft)
"""
import json
import os
import random
import struct
import zlib

ROOT = os.path.join(os.path.dirname(__file__), "..", "beamng", "art", "beamcraft")
BLOCKS = os.path.join(ROOT, "blocks")
STEVE = os.path.join(ROOT, "steve")
ART_PATH = "/art/beamcraft"


# ------------------------------------------------------------------ PNG

def write_png(path, w, h, pixels):
    """pixels: list of (r, g, b, a) rows-major."""
    raw = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *pixels[y * w + x]) for x in range(w)) for y in range(h))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def block_texture(path):
    """64x64 grey 'pixel art' noise (16x16 texels, 4px each) with a darker 1-texel rim."""
    rnd = random.Random(1234)
    texels = [[0.0] * 16 for _ in range(16)]
    for y in range(16):
        for x in range(16):
            v = 0.86 + rnd.random() * 0.14
            if x == 0 or y == 0 or x == 15 or y == 15:
                v *= 0.72
            texels[y][x] = v
    px = []
    for y in range(64):
        for x in range(64):
            g = int(255 * texels[y // 4][x // 4])
            px.append((g, g, g, 255))
    write_png(path, 64, 64, px)


# ------------------------------------------------------------------ Collada

def box_faces(x0, y0, z0, x1, y1, z1):
    """Six quads (4 corners each, CCW seen from outside) with their normals."""
    return [
        ((0, 0, 1), [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]),
        ((0, 0, -1), [(x0, y1, z0), (x1, y1, z0), (x1, y0, z0), (x0, y0, z0)]),
        ((1, 0, 0), [(x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)]),
        ((-1, 0, 0), [(x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1)]),
        ((0, 1, 0), [(x1, y1, z0), (x0, y1, z0), (x0, y1, z1), (x1, y1, z1)]),
        ((0, -1, 0), [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)]),
    ]


def geometry_xml(gid, material, boxes):
    pos, nrm, uv, p = [], [], [], []
    for box in boxes:
        for normal, corners in box_faces(*box):
            n = len(nrm) // 3
            nrm.extend(normal)
            for i, c in enumerate(corners):
                v = len(pos) // 3
                pos.extend(c)
                t = len(uv) // 2
                uv.extend([(0, 0), (1, 0), (1, 1), (0, 1)][i])
                p.extend([v, n, t])
    quads = len(p) // 12
    fmt = lambda a: " ".join("%g" % round(x, 6) for x in a)
    return f"""    <geometry id="{gid}-mesh" name="{gid}">
      <mesh>
        <source id="{gid}-pos"><float_array id="{gid}-pos-array" count="{len(pos)}">{fmt(pos)}</float_array>
          <technique_common><accessor source="#{gid}-pos-array" count="{len(pos)//3}" stride="3"><param name="X" type="float"/><param name="Y" type="float"/><param name="Z" type="float"/></accessor></technique_common></source>
        <source id="{gid}-nrm"><float_array id="{gid}-nrm-array" count="{len(nrm)}">{fmt(nrm)}</float_array>
          <technique_common><accessor source="#{gid}-nrm-array" count="{len(nrm)//3}" stride="3"><param name="X" type="float"/><param name="Y" type="float"/><param name="Z" type="float"/></accessor></technique_common></source>
        <source id="{gid}-uv"><float_array id="{gid}-uv-array" count="{len(uv)}">{fmt(uv)}</float_array>
          <technique_common><accessor source="#{gid}-uv-array" count="{len(uv)//2}" stride="2"><param name="S" type="float"/><param name="T" type="float"/></accessor></technique_common></source>
        <vertices id="{gid}-vtx"><input semantic="POSITION" source="#{gid}-pos"/></vertices>
        <polylist count="{quads}" material="{material}-material">
          <input offset="0" semantic="VERTEX" source="#{gid}-vtx"/>
          <input offset="1" semantic="NORMAL" source="#{gid}-nrm"/>
          <input offset="2" semantic="TEXCOORD" source="#{gid}-uv" set="0"/>
          <vcount>{" ".join(["4"] * quads)}</vcount>
          <p>{" ".join(map(str, p))}</p>
        </polylist>
      </mesh>
    </geometry>
"""


def write_dae(path, node_name, parts):
    """parts: list of (material, [boxes])."""
    effects = "".join(f"""    <effect id="{m}-effect"><profile_COMMON><technique sid="common"><lambert>
      <diffuse><color sid="diffuse">0.8 0.8 0.8 1</color></diffuse></lambert></technique></profile_COMMON></effect>
""" for m, _ in parts)
    materials = "".join(f"""    <material id="{m}-material" name="{m}"><instance_effect url="#{m}-effect"/></material>
""" for m, _ in parts)
    geoms = "".join(geometry_xml(f"{node_name}_{i}", m, boxes) for i, (m, boxes) in enumerate(parts))
    nodes = "".join(f"""      <node id="{node_name}_{i}_a2" name="{node_name}_{i}_a2" type="NODE">
        <instance_geometry url="#{node_name}_{i}-mesh"><bind_material><technique_common>
          <instance_material symbol="{m}-material" target="#{m}-material"/>
        </technique_common></bind_material></instance_geometry>
      </node>
""" for i, (m, _) in enumerate(parts))
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1">
  <asset><contributor><authoring_tool>BeamCraft gen_art.py</authoring_tool></contributor>
    <unit meter="1" name="meter"/><up_axis>Z_UP</up_axis></asset>
  <library_effects>
{effects}  </library_effects>
  <library_materials>
{materials}  </library_materials>
  <library_geometries>
{geoms}  </library_geometries>
  <library_visual_scenes>
    <visual_scene id="Scene" name="Scene">
{nodes}    </visual_scene>
  </library_visual_scenes>
  <scene><instance_visual_scene url="#Scene"/></scene>
</COLLADA>
"""
    with open(path, "w", encoding="utf-8") as f:
        f.write(xml)


def material(name, rgb, alpha=1.0, color_map=None, translucent=False):
    stage = {"diffuseColor": [round(c / 255, 4) for c in rgb] + [alpha], "specularPower": 1}
    if color_map:
        stage["colorMap"] = color_map
    m = {"name": name, "mapTo": name, "class": "Material", "Stages": [stage, {}, {}, {}],
         "materialTag0": "beamng", "materialTag1": "BeamCraft"}
    if translucent:
        m["translucent"] = True
        m["translucentBlendOp"] = "LerpAlpha"
        m["translucentZWrite"] = False
    return m


# ------------------------------------------------------------------ main

def main():
    os.makedirs(BLOCKS, exist_ok=True)
    os.makedirs(STEVE, exist_ok=True)

    with open(os.path.join(os.path.dirname(__file__), "mapcolors.json")) as f:
        colors = {int(k): v for k, v in json.load(f).items()}

    block_texture(os.path.join(BLOCKS, "block_d.png"))
    mats = {}
    for cid, value in colors.items():
        rgb = ((value >> 16) & 255, (value >> 8) & 255, value & 255)
        name = f"beamcraft_c{cid}"
        if cid == 0 or value == 0:
            # MapColor.CLEAR: glass, ice-like blocks. Show them as pale see-through cubes.
            mats[name] = material(name, (200, 230, 255), 0.35, translucent=True)
        else:
            mats[name] = material(name, rgb, color_map=f"{ART_PATH}/blocks/block_d.png")
        write_dae(os.path.join(BLOCKS, f"c{cid}.dae"), f"beamcraft_c{cid}", [(name, [(0, 0, 0, 1, 1, 1)])])
    with open(os.path.join(BLOCKS, "main.materials.json"), "w") as f:
        json.dump(mats, f, indent=2)

    # Steve, 1 texel = 1.8 m / 32. Origin at his feet, facing +Y.
    s = 1.8 / 32
    leg_h, body_h = 12 * s, 12 * s
    hw, d = 4 * s, 2 * s  # half body width, half depth
    head = 4 * s  # half head size
    top = leg_h + body_h
    parts = {
        "beamcraft_steve_shoes": [(-hw, -d, 0, 0, d, 2 * s), (0, -d, 0, hw, d, 2 * s)],
        "beamcraft_steve_pants": [(-hw, -d, 2 * s, 0, d, leg_h), (0, -d, 2 * s, hw, d, leg_h)],
        "beamcraft_steve_shirt": [(-hw, -d, leg_h, hw, d, top),
                                  (-2 * hw, -d, top - 4 * s, -hw, d, top), (hw, -d, top - 4 * s, 2 * hw, d, top)],
        "beamcraft_steve_skin": [(-2 * hw, -d, leg_h, -hw, d, top - 4 * s), (hw, -d, leg_h, 2 * hw, d, top - 4 * s),
                                 (-head, -head, top, head, head, top + 8 * s)],
        "beamcraft_steve_hair": [(-head - 0.005, -head - 0.005, top + 6.5 * s, head + 0.005, head + 0.005, top + 8 * s + 0.005),
                                 (-head - 0.005, -head - 0.005, top + 3 * s, head + 0.005, -head + s, top + 8 * s)],
        "beamcraft_steve_eyewhite": [(-3 * s, head, top + 3 * s, -2 * s, head + 0.006, top + 4 * s),
                                     (2 * s, head, top + 3 * s, 3 * s, head + 0.006, top + 4 * s)],
        "beamcraft_steve_eye": [(-2 * s, head, top + 3 * s, -1 * s, head + 0.006, top + 4 * s),
                                (1 * s, head, top + 3 * s, 2 * s, head + 0.006, top + 4 * s)],
        "beamcraft_steve_mouth": [(-1 * s, head, top + 1 * s, 1 * s, head + 0.006, top + 2 * s)],
    }
    rgb = {
        "beamcraft_steve_shoes": (70, 70, 70), "beamcraft_steve_pants": (58, 52, 140),
        "beamcraft_steve_shirt": (0, 170, 170), "beamcraft_steve_skin": (198, 145, 108),
        "beamcraft_steve_hair": (60, 40, 25), "beamcraft_steve_eyewhite": (240, 240, 240),
        "beamcraft_steve_eye": (70, 60, 160), "beamcraft_steve_mouth": (110, 60, 45),
    }
    write_dae(os.path.join(STEVE, "steve.dae"), "beamcraft_steve", list(parts.items()))
    with open(os.path.join(STEVE, "main.materials.json"), "w") as f:
        json.dump({m: material(m, rgb[m]) for m in parts}, f, indent=2)
    print(f"wrote {len(colors)} block shapes and Steve to {os.path.normpath(ROOT)}")


if __name__ == "__main__":
    main()
