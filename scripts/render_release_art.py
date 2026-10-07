#!/usr/bin/env python3
"""Render README/release artwork from shipped Minecraft models and textures.

The images produced here deliberately contain no labels or explanatory copy. README text lives in
Markdown, while the artwork stays a reproducible view of the models that actually ship with the
mod. Requires Pillow, NumPy and the Create jar configured in ``scripts/render_block.py``.
"""

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from render_block import render


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/release/assets"

CANVAS = "#e8e5dc"
KEY = "#172c33"
LINE = "#d4d1c8"


def cutout(model: str, size=(720, 720), scale=16, yaw=30, pitch=25) -> Image.Image:
    """Render one shipped model and remove only the renderer's exact chroma-key background."""
    pixels = np.array(render(model, yaw=yaw, pitch=pitch, size=size, scale=scale, bg=KEY))
    pixels[np.all(pixels[:, :, :3] == (23, 44, 51), axis=2), 3] = 0
    image = Image.fromarray(pixels)
    bbox = image.getbbox()
    return image.crop(bbox) if bbox else image


def place(canvas: Image.Image, model: str, box, *, scale=16, yaw=30, pitch=25) -> None:
    """Render a model into a box with a restrained soft shadow."""
    x, y, w, h = box
    model_image = cutout(model, scale=scale, yaw=yaw, pitch=pitch)
    model_image.thumbnail((w, h), Image.Resampling.LANCZOS)

    px = x + (w - model_image.width) // 2
    py = y + (h - model_image.height) // 2

    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    alpha = model_image.getchannel("A").filter(ImageFilter.GaussianBlur(8))
    shadow_piece = Image.new("RGBA", model_image.size, (40, 45, 43, 56))
    shadow_piece.putalpha(alpha.point(lambda value: value * 52 // 255))
    shadow.alpha_composite(shadow_piece, (px + 8, py + 12))
    canvas.alpha_composite(shadow)
    canvas.alpha_composite(model_image, (px, py))


def panel(width: int, height: int) -> Image.Image:
    """Neutral Create-adjacent presentation background, with no text or decorative branding."""
    image = Image.new("RGBA", (width, height), CANVAS)
    draw = ImageDraw.Draw(image)
    draw.line((0, height - 2, width, height - 2), fill=LINE, width=2)
    return image


def save_rgb(image: Image.Image, name: str) -> None:
    image.convert("RGB").save(OUT / name, optimize=True)


def render_icon() -> None:
    """Project icon: real remote parcel model framed by simple geometric shipping arrows."""
    icon = Image.new("RGBA", (1024, 1024), KEY)
    draw = ImageDraw.Draw(icon)
    draw.line([(160, 334), (160, 152), (716, 152)], fill="#d6b572", width=40)
    draw.polygon([(716, 94), (802, 152), (716, 210)], fill="#d6b572")
    draw.line([(864, 690), (864, 872), (308, 872)], fill="#73c0c8", width=40)
    draw.polygon([(308, 814), (222, 872), (308, 930)], fill="#73c0c8")
    parcel = cutout("distantstock:item/remote_package", (1024, 1024), 34)
    parcel.thumbnail((880, 880), Image.Resampling.LANCZOS)
    icon.alpha_composite(parcel, ((1024 - parcel.width) // 2, (1024 - parcel.height) // 2 - 24))

    for size in (1024, 512, 256, 128, 64):
        icon.resize((size, size), Image.Resampling.LANCZOS).convert("RGB").save(
            OUT / f"distantstock-icon-{size}.png", optimize=True
        )
    icon.resize((256, 256), Image.Resampling.LANCZOS).convert("RGB").save(
        ROOT / "src/main/resources/distantstock-icon.png", optimize=True
    )


def render_overview() -> None:
    image = panel(1440, 500)
    entries = [
        ("distantstock:item/requester", (15, 60, 220, 360), 18, 32, 24),
        ("distantstock:block/gauge_lit", (230, 55, 230, 370), 17, 30, 26),
        ("distantstock:block/remote_packager", (455, 55, 235, 370), 17, 30, 25),
        ("distantstock:block/remote_dock_cyan", (680, 55, 235, 370), 17, 30, 25),
        ("distantstock:block/monitor_green_online", (910, 55, 235, 370), 17, 30, 24),
        ("distantstock:block/logger_idle", (1140, 55, 270, 370), 17, 30, 24),
    ]
    for model, box, scale, yaw, pitch in entries:
        place(image, model, box, scale=scale, yaw=yaw, pitch=pitch)
    save_rgb(image, "machines.png")


def render_logistics() -> None:
    image = panel(1200, 340)
    entries = [
        ("distantstock:item/requester", (0, 20, 220, 285), 18),
        ("distantstock:block/gauge_lit", (220, 20, 235, 285), 17),
        ("distantstock:block/remote_packager", (455, 20, 245, 285), 17),
        ("distantstock:block/remote_dock_cyan", (700, 20, 245, 285), 17),
        ("distantstock:block/remote_redstone_requester", (945, 20, 245, 285), 17),
    ]
    for model, box, scale in entries:
        place(image, model, box, scale=scale)
    save_rgb(image, "logistics.png")


def render_tower() -> None:
    image = panel(1200, 340)
    entries = [
        ("distantstock:block/tower/tower_core", (40, 20, 260, 285), 17),
        ("distantstock:block/tower/tower_coupler_middle", (320, 20, 260, 285), 17),
        ("distantstock:block/tower/ether_resonator", (610, 20, 260, 285), 17),
        ("distantstock:block/tower/tower_casing_active", (900, 20, 260, 285), 17),
    ]
    for model, box, scale in entries:
        place(image, model, box, scale=scale)
    save_rgb(image, "tower.png")


def render_control() -> None:
    image = panel(1200, 340)
    entries = [
        ("distantstock:block/monitor_green_online", (0, 20, 200, 285), 17),
        ("distantstock:block/logger_idle", (195, 20, 210, 285), 17),
        ("distantstock:block/stack_light_floor_111", (400, 20, 190, 285), 17),
        ("distantstock:item/red_wall_sounder", (580, 20, 190, 285), 17),
        ("distantstock:item/diagnostic_frogport", (765, 20, 210, 285), 17),
        ("distantstock:item/cache_frogport", (975, 20, 215, 285), 17),
    ]
    for model, box, scale in entries:
        place(image, model, box, scale=scale)
    save_rgb(image, "control.png")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    render_icon()
    render_overview()
    render_logistics()
    render_tower()
    render_control()
    print(OUT)


if __name__ == "__main__":
    main()
