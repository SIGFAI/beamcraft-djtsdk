"""Zips the BeamNG side into beamcraft.zip (and installs it into BeamNG's mods folder if given).

Usage: python tools/package.py [path/to/BeamNG/userfolder/current/mods]
"""
import os
import shutil
import sys
import zipfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "beamng")
OUT = os.path.join(ROOT, "dist", "beamcraft.zip")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
count = 0
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for folder, _, files in os.walk(SRC):
        for name in files:
            path = os.path.join(folder, name)
            z.write(path, os.path.relpath(path, SRC).replace(os.sep, "/"))  # BeamNG needs forward slashes
            count += 1
print(f"packed {count} files into {os.path.normpath(OUT)}")

if len(sys.argv) > 1:
    dest = os.path.join(sys.argv[1], "beamcraft.zip")
    shutil.copyfile(OUT, dest)
    print(f"installed to {dest}")
