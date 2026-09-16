#!/usr/bin/env python3
"""Generate the shipped 16 px indicator-lamp textures and baked models."""
import json
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/distantstock"
TEXTURES = ASSETS / "textures/block"
MODELS = ASSETS / "models/block"
BLOCKSTATES = ASSETS / "blockstates"
COLORS = {
    "cyan": {
        "off": ((31, 70, 76), (37, 84, 91), (45, 101, 109)),
        "on": ((43, 98, 106), (49, 111, 120), (63, 139, 148)),
    },
    "orange": {
        "off": ((72, 47, 30), (89, 57, 32), (106, 68, 36)),
        "on": ((105, 66, 34), (124, 77, 37), (148, 94, 45)),
    },
    "red": {
        "off": ((72, 24, 20), (91, 27, 21), (108, 31, 23)),
        "on": ((116, 30, 7), (127, 13, 8), (143, 9, 9)),
    },
    # The powered values are sampled directly from Create's factory-panel
    # and display-link green bulbs.
    "green": {
        "off": ((30, 72, 65), (36, 93, 61), (43, 104, 62)),
        "on": ((43, 104, 62), (47, 112, 60), (63, 142, 67)),
    },
    "white": {
        "off": ((67, 73, 72), (81, 88, 86), (96, 103, 100)),
        "on": ((101, 108, 106), (119, 126, 122), (143, 149, 144)),
    },
}


def _glow(colour):
    """A lit version of one palette colour: brighter, same hue, still saturated."""
    import colorsys
    r, g, b = (c / 255.0 for c in colour)
    h, sat, value = colorsys.rgb_to_hsv(r, g, b)
    value = min(1.0, value * 1.45 + 0.10)
    sat = sat * 0.85
    r, g, b = colorsys.hsv_to_rgb(h, sat, value)
    return (round(r * 255), round(g * 255), round(b * 255))


def save_texture(name, image):
    TEXTURES.mkdir(parents=True, exist_ok=True)
    image.save(TEXTURES / f"{name}.png")


def make_textures():
    pattern = (
        (0, 1, 1, 1, 0),
        (1, 2, 2, 1, 1),
        (1, 2, 2, 2, 1),
        (1, 1, 2, 2, 1),
        (0, 1, 1, 1, 0),
    )
    for name, palettes in COLORS.items():
        for powered in (False, True):
            image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
            pixels = image.load()
            palette = palettes["on" if powered else "off"]
            alpha = 204 if powered else 190
            if powered:
                # 亮着的灯要看着像在发光，而不是「颜色深一点的同一块塑料」。
                #
                # 往白里混是错的做法：混得少没效果（0.25 那次，用户说看不出亮），混得多就发白
                # （0.62 那次，红灯变粉）。发光是**明度上去、饱和度基本留着**，所以走 HSV：
                # 明度抬到接近满、饱和度略降，色相一点不动。配合方块光等级 14，才有一圈能照到
                # 旁边方块的光，而不是一块更浅的塑料。
                palette = tuple(_glow(colour) for colour in palette)
                alpha = 255
            # Dedicated 5x5 atlas patch: one texture pixel per model unit,
            # like the tiny bulb on Create's factory gauge.
            for y, row in enumerate(pattern):
                for x, shade in enumerate(row):
                    pixels[x, y] = (*palette[shade], alpha)
            save_texture(f"indicator_lamp_{name}_{'on' if powered else 'off'}", image)


def box(name, lower, upper, texture, full_uv=False):
    x0, y0, z0 = lower
    x1, y1, z1 = upper
    uvs = {
        "north": [16 - x1, 16 - y1, 16 - x0, 16 - y0],
        "south": [x0, 16 - y1, x1, 16 - y0],
        "west": [z0, 16 - y1, z1, 16 - y0],
        "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0],
        "up": [x0, z0, x1, z1],
        "down": [x0, 16 - z1, x1, 16 - z0],
    }
    if full_uv:
        uvs = {face: [0, 0, 16, 16] for face in uvs}
    return {
        "name": name,
        "from": lower,
        "to": upper,
        "faces": {face: {"texture": f"#{texture}", "uv": uv} for face, uv in uvs.items()},
    }


def write_model(name, data):
    MODELS.mkdir(parents=True, exist_ok=True)
    (MODELS / f"{name}.json").write_text(json.dumps(data, indent=2) + "\n")


def make_models():
    for color in COLORS:
        name = f"{color}_indicator_lamp"
        textures = {
            "base": "create:block/industrial_iron_block",
            "lamp": f"distantstock:block/indicator_lamp_{color}_off",
            "particle": "create:block/industrial_iron_block",
        }
        base = box("thin_andesite_mount", [5, 0, 5], [11, 1, 11], "base")
        lamp = box("single_five_pixel_bulb", [5.5, 1, 5.5], [10.5, 5, 10.5], "lamp")
        for face in lamp["faces"].values():
            face["uv"] = [0, 0, 5, 5]
        write_model(name, {
            "parent": "minecraft:block/block",
            "ambientocclusion": False,
            "render_type": "minecraft:translucent",
            "textures": textures,
            "elements": [base, lamp],
        })
        write_model(f"{name}_lit", {
            "parent": f"distantstock:block/{name}",
            # Only the powered state uses cutout: this preserves the cloudy
            # glass of the unpowered lamp while keeping the lit core crisp.
            "render_type": "minecraft:cutout",
            "textures": {"lamp": f"distantstock:block/indicator_lamp_{color}_on"},
        })
        item = ASSETS / f"models/item/{name}.json"
        item.parent.mkdir(parents=True, exist_ok=True)
        item.write_text(json.dumps({"parent": f"distantstock:block/{name}"}, indent=2) + "\n")


def make_blockstate():
    rotations = {
        ("floor", "north"): {}, ("floor", "east"): {},
        ("floor", "south"): {}, ("floor", "west"): {},
        ("ceiling", "north"): {"x": 180}, ("ceiling", "east"): {"x": 180},
        ("ceiling", "south"): {"x": 180}, ("ceiling", "west"): {"x": 180},
        ("wall", "north"): {"x": 90},
        ("wall", "east"): {"x": 90, "y": 90},
        ("wall", "south"): {"x": 90, "y": 180},
        ("wall", "west"): {"x": 90, "y": 270},
    }
    BLOCKSTATES.mkdir(parents=True, exist_ok=True)
    for color in COLORS:
        name = f"{color}_indicator_lamp"
        variants = {}
        for (face, facing), rotation in rotations.items():
            for lit in (False, True):
                variants[f"face={face},facing={facing},lit={str(lit).lower()}"] = {
                    "model": f"distantstock:block/{name}_lit" if lit else f"distantstock:block/{name}",
                    **rotation,
                }
        (BLOCKSTATES / f"{name}.json").write_text(
            json.dumps({"variants": variants}, indent=2) + "\n"
        )


def main():
    # Clean only the superseded nested-glass experiment generated by this script.
    for path in TEXTURES.glob("indicator_lamp_core_*.png"):
        path.unlink()
    for path in TEXTURES.glob("indicator_lamp_glass_*.png"):
        path.unlink()
    old_frame = TEXTURES / "indicator_lamp_frame.png"
    if old_frame.exists():
        old_frame.unlink()
    make_textures()
    make_models()
    make_blockstate()
    print("Generated five single-cube Create-style indicator lamp colours.")


if __name__ == "__main__":
    main()
