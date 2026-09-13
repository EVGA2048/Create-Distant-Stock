#!/usr/bin/env python3
"""Static compatibility checks against the installed Create jar, not an in-game test."""
import io
import json
import subprocess
import zipfile
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/distantstock"
CREATE = Path.home() / "Documents/minecraft_launcher/.minecraft/versions/ES2_Firmament_1.21.1_9th_9.3.2_SunlightSignal/mods/create-1.21.1-6.0.10.jar"

with zipfile.ZipFile(CREATE) as archive:
    for path in sorted((ASSETS / "models/block").glob("remote_packager*.json")):
        model = json.loads(path.read_text())
        merged = dict(model)
        parent = model["parent"]
        seen = set()
        while parent.startswith("distantstock:"):
            assert parent not in seen, path
            seen.add(parent)
            inherited = json.loads((ASSETS / "models" / (parent.split(":", 1)[1] + ".json")).read_text())
            assert "elements" not in inherited, path
            merged = {**inherited, **merged, "textures": {**inherited.get("textures", {}), **merged.get("textures", {})}}
            parent = inherited["parent"]
        assert parent.startswith("create:block/packager/"), path
        assert "elements" not in model, f"Geometry must remain inherited: {path}"
        assert merged["render_type"] in ("minecraft:cutout", "minecraft:cutout_mipped"), path
        assert merged["textures"]["1"] == "create:block/packager_details", path
    for path in sorted((ASSETS / "textures/block").glob("remote_packager_*_pale.png")):
        original = path.name.replace("remote_packager_", "packager_").replace("_pale.png", ".png")
        source = Image.open(io.BytesIO(archive.read("assets/create/textures/block/" + original))).convert("RGBA")
        target = Image.open(path).convert("RGBA")
        assert target.size == source.size, path
        assert target.getchannel("A").tobytes() == source.getchannel("A").tobytes(), f"Alpha mask changed: {path}"

def bytecode(name):
    return subprocess.check_output(["javap", "-classpath", str(CREATE), "-c", "-p",
                                   "com.simibubi.create.content.logistics.packager." + name], text=True)

renderer = bytecode("PackagerRenderer")
visual = bytecode("PackagerVisual")
assert "getTrayModel(net.minecraft.world.level.block.state.BlockState)" in renderer
assert "PACKAGER_TRAY_REGULAR" in renderer
assert "PackagerRenderer.getTrayModel:" in visual
resources = ROOT / "src/main/resources"
assert 'config="distantstock.mixins.json"' in (resources / "META-INF/neoforge.mods.toml").read_text()
config = json.loads((resources / "distantstock.mixins.json").read_text())
assert "client.PackagerRendererMixin" in config["client"]
print("Packager parity checks PASSED: inherited geometry, original alpha masks, tray hook for both render paths.")
