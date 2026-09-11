#!/usr/bin/env python3
"""Render the proposed four-slot signal panel using only shipped model assets.

Create supplies the factory-panel body, bulbs, connection lines, arrows and
industrial-iron mounting surface.  Distant Stock supplies only its existing
remote gauge model.  No new painted texture is used by this preview.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/concepts"))

from create_context import CreateReferences
from render_scene import BG, box, footer, header, load_minecraft_model, move, render, text


OUT = ROOT / "build/art/factory-signal-panel-system-0.3.7.png"
ASSETS = ROOT / "src/main/resources/assets"


class SceneAssets:
    def __init__(self):
        self.create = CreateReferences()

    def create_model(self, name, offset=(0, 0, 0)):
        mesh, textures = load_minecraft_model(
            self.create.model(name), self.create.texture, self.create.model, offset
        )
        return mesh, textures

    def local_model(self, name, offset=(0, 0, 0)):
        namespace, path = name.split(":", 1)
        data = json.loads((ASSETS / namespace / "models" / f"{path}.json").read_text())

        def parent_loader(parent):
            if parent.startswith("minecraft:"):
                return self.create.model(parent)
            pns, ppath = parent.split(":", 1)
            return json.loads((ASSETS / pns / "models" / f"{ppath}.json").read_text())

        def texture_loader(texture):
            tns, tpath = texture.split(":", 1)
            if tns == "create":
                return self.create.texture(texture)
            return Image.open(ASSETS / tns / "textures" / f"{tpath}.png").convert("RGBA")

        return load_minecraft_model(data, texture_loader, parent_loader, offset)


def add_model(scene, textures, loaded):
    mesh, material_map = loaded
    scene.extend(mesh)
    textures.update(material_map)


def panel(assets, scene, textures, x, z, bulb=None):
    add_model(scene, textures, assets.create_model("create:block/factory_gauge/panel_with_bulb", (x, 0, z)))
    if bulb:
        add_model(scene, textures, assets.create_model(f"create:block/factory_gauge/bulb_{bulb}", (x, 0, z)))


def four_slot_bank(assets, scene, textures, x, z):
    # Same slot order and 8x8 footprint as FactoryPanelBlock.PanelSlot.
    panel(assets, scene, textures, x, z, "light")
    panel(assets, scene, textures, x + 8, z, "red")
    panel(assets, scene, textures, x, z + 8, None)
    panel(assets, scene, textures, x + 8, z + 8, "light")


def east_connection(assets, scene, textures, start_x, center_z, pieces, arrow=False):
    for index in range(pieces):
        kind = "arrow_east" if arrow and index == pieces - 1 else "line_east"
        add_model(
            scene,
            textures,
            assets.create_model(
                f"create:block/factory_gauge/connections/{kind}",
                (start_x + index * 8, 0, center_z),
            ),
        )


def build_scene():
    assets = SceneAssets()
    scene = []
    textures = {}

    # Actual Create industrial-iron texture on a deliberately plain mounting bed.
    industrial = assets.create.texture("create:block/industrial_iron_block")
    textures["create:block/industrial_iron_block"] = industrial
    scene.extend(box((-4, -2, -4), (84, 0, 20), "create:block/industrial_iron_block"))
    scene.extend(box((-4, -2, 24), (84, 0, 48), "create:block/industrial_iron_block"))

    # Top: an unmodified Create factory gauge panel feeding the new four-slot bank.
    panel(assets, scene, textures, 0, 4, "light")
    east_connection(assets, scene, textures, 8, 8, 7, True)
    four_slot_bank(assets, scene, textures, 68, 4)

    # Bottom: the existing Distant Stock gauge.  A standard Create panel is used
    # as its output terminal so the visible link language remains 100% Create.
    add_model(scene, textures, assets.local_model("distantstock:block/gauge", (0, 0, 28)))
    panel(assets, scene, textures, 16, 32, "light")
    east_connection(assets, scene, textures, 24, 36, 5, True)
    four_slot_bank(assets, scene, textures, 68, 28)
    return scene, textures


def main():
    scene, textures = build_scene()
    raw = render(
        scene,
        textures,
        size=(1130, 570),
        yaw=24,
        pitch=42,
        center=(40, 4, 22),
        scale=8.0,
        background=BG,
    )

    sheet = Image.new("RGBA", (1240, 760), BG)
    header(
        sheet,
        "FACTORY GAUGE SIGNAL PANEL SYSTEM",
        "Create 6.0.10 factory-panel geometry, bulbs and connection textures / four 8x8 slots per block",
    )
    sheet.alpha_composite(raw, (55, 105))
    draw = ImageDraw.Draw(sheet)
    draw.line((46, 675, 1194, 675), fill="#b7b8ad", width=1)
    text(sheet, (58, 617), "DISTANT GAUGE + CREATE OUTPUT TERMINAL  →  ANDESITE SIGNAL PANEL (4 SLOTS)", 16)
    text(sheet, (58, 642), "CREATE FACTORY GAUGE  →  ANDESITE SIGNAL PANEL (4 SLOTS)", 16)
    text(sheet, (835, 617), "GREEN / RED / OFF / GREEN", 15, "#64706b")
    footer(sheet, " / ALL VISIBLE DEVICE MATERIALS LOADED FROM INSTALLED ASSETS")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(OUT)
    print(OUT)


if __name__ == "__main__":
    main()
