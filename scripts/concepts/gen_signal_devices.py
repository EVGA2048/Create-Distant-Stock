#!/usr/bin/env python3
"""Generate implementation-ready brass lamp and lamp-signal-link concept assets."""
import json
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/distantstock_concept"
TEXTURES = ASSETS / "textures/block"
MODELS = ASSETS / "models/block"


def save(name, image):
    TEXTURES.mkdir(parents=True, exist_ok=True)
    image.save(TEXTURES / f"{name}.png")


def box(name, lower, upper, texture, top_texture=None):
    x0, y0, z0 = lower
    x1, y1, z1 = upper
    uvs = {
        "north": [16-x1, 16-y1, 16-x0, 16-y0],
        "south": [x0, 16-y1, x1, 16-y0],
        "west": [z0, 16-y1, z1, 16-y0],
        "east": [16-z1, 16-y1, 16-z0, 16-y0],
        "up": [x0, z0, x1, z1],
        "down": [x0, 16-z1, x1, 16-z0],
    }
    faces = {face: {"texture": f"#{texture}", "uv": uv} for face, uv in uvs.items()}
    if top_texture:
        faces["up"] = {"texture": f"#{top_texture}", "uv": [0, 0, 16, 16]}
    return {"name": name, "from": lower, "to": upper, "faces": faces}


def write_model(name, data):
    MODELS.mkdir(parents=True, exist_ok=True)
    (MODELS / f"{name}.json").write_text(json.dumps(data, indent=2) + "\n")


def make_textures():
    neutral = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    pattern = (
        (0, 1, 1, 1, 0),
        (1, 2, 2, 1, 1),
        (1, 2, 2, 2, 1),
        (1, 1, 2, 2, 1),
        (0, 1, 1, 1, 0),
    )
    palette = ((65, 70, 68), (78, 84, 81), (96, 102, 98))
    pixels = neutral.load()
    for y, row in enumerate(pattern):
        for x, shade in enumerate(row):
            pixels[x, y] = (*palette[shade], 190)
    save("brass_signal_lamp_off", neutral)

    base = Image.new("RGBA", (16, 16), "#66543a")
    d = ImageDraw.Draw(base)
    d.line((0, 0, 15, 0), fill="#aa8c58")
    d.line((0, 1, 0, 15), fill="#897148")
    d.line((0, 15, 15, 15), fill="#44392b")
    d.line((15, 1, 15, 15), fill="#514431")
    d.rectangle((2, 2, 13, 13), outline="#806a45")
    d.point((2, 2), fill="#c0a16b")
    d.point((13, 13), fill="#3d352b")
    save("lamp_signal_link_base", base)

    face = Image.new("RGBA", (16, 16), "#343a38")
    d = ImageDraw.Draw(face)
    d.rectangle((0, 0, 15, 15), outline="#69706b")
    d.rectangle((3, 3, 12, 12), outline="#786744")
    d.rectangle((6, 6, 9, 9), fill="#222826")
    d.line((7, 4, 8, 4), fill="#883126")       # north / red
    d.line((11, 7, 11, 8), fill="#95612f")     # east / orange
    d.line((7, 11, 8, 11), fill="#3d7045")     # south / green
    d.line((4, 7, 4, 8), fill="#356b73")       # west / cyan
    d.point((1, 1), fill="#9da29a")
    d.point((14, 14), fill="#202523")
    save("lamp_signal_link_face", face)


def make_models():
    lamp_base = box("brass_mount", [5, 0, 5], [11, 1, 11], "base")
    bulb = box("colourless_five_pixel_bulb", [5.5, 1, 5.5], [10.5, 5, 10.5], "lamp")
    for face in bulb["faces"].values():
        face["uv"] = [0, 0, 5, 5]
    write_model("brass_signal_lamp", {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "render_type": "minecraft:translucent",
        "textures": {
            "base": "create:block/brass_casing",
            "lamp": "distantstock_concept:block/brass_signal_lamp_off",
            "particle": "create:block/brass_casing",
        },
        "elements": [lamp_base, bulb],
    })
    for color in ("cyan", "orange", "red", "green", "white"):
        write_model(f"brass_signal_lamp_{color}", {
            "parent": "distantstock_concept:block/brass_signal_lamp",
            "textures": {"lamp": f"distantstock:block/indicator_lamp_{color}_on"},
        })

    textures = {
        "base": "distantstock_concept:block/lamp_signal_link_base",
        "face": "distantstock_concept:block/lamp_signal_link_face",
        "dark": "distantstock_concept:block/dark",
        "red": "distantstock:block/indicator_lamp_red_on",
        "orange": "distantstock:block/indicator_lamp_orange_on",
        "green": "distantstock:block/indicator_lamp_green_on",
        "cyan": "distantstock:block/indicator_lamp_cyan_on",
        "particle": "distantstock_concept:block/lamp_signal_link_base",
    }
    def channel(name, lower, upper, texture):
        element = box(name, lower, upper, texture)
        for face in element["faces"].values():
            face["uv"] = [0, 0, 2, 2]
        return element

    elements = [
        box("display_link_profile", [2, 0, 2], [14, 2, 14], "base"),
        box("raised_housing", [3, 2, 3], [13, 4, 13], "base"),
        box("four_way_channel_face", [4, 4, 4], [12, 4.5, 12], "dark", "face"),
        channel("north_channel", [7, 4.5, 3.5], [9, 5, 5.5], "red"),
        channel("east_channel", [10.5, 4.5, 7], [12.5, 5, 9], "orange"),
        channel("south_channel", [7, 4.5, 10.5], [9, 5, 12.5], "green"),
        channel("west_channel", [3.5, 4.5, 7], [5.5, 5, 9], "cyan"),
    ]
    write_model("lamp_signal_link", {
        "parent": "minecraft:block/block",
        "ambientocclusion": True,
        "textures": textures,
        "elements": elements,
    })


def main():
    make_textures()
    make_models()
    print("Generated brass signal lamp and four-way lamp signal link concepts.")


if __name__ == "__main__":
    main()
