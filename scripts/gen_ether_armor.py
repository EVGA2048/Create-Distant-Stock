#!/usr/bin/env python3
"""Generate Resonant Quartz armour from Distant Casing textures.

Design rule: keep the familiar diamond-armor silhouette and edge language, but replace the body of
the material with the Distant Casing's centre texture. The cloak is two-stage: solid casing plates
ripple into the active/transparent casing material first; only after the whole suit has activated
does a second ripple erase those transparent plates.
"""

from __future__ import annotations

from pathlib import Path
import math
import zipfile

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "build/moddev/artifacts/neoforge-21.1.231-client-extra-aka-minecraft-resources.jar"
OUT = ROOT / "src/main/resources/assets/distantstock/textures"
INACTIVE = Image.open(ROOT / "src/main/resources/assets/distantstock/textures/block/tower/casing_inactive.png").convert("RGBA")
ACTIVE = Image.open(ROOT / "src/main/resources/assets/distantstock/textures/block/tower/casing_active.png").convert("RGBA")

ITEMS = ["diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots"]
ARMOR = ["diamond_layer_1", "diamond_layer_2"]
ACTIVE_PHASES = 6
PHASES = 12

# Use the casing's own blue-grey frame values. r25's almost-black rim overpowered the plate surface
# and made the suit read as outlined/cartoon armour rather than a machined casing shell.
EDGE_DARK = (97, 120, 131, 255)
EDGE_MID = (133, 153, 162, 255)
EDGE_LIGHT = (210, 222, 227, 255)
VISOR_DARK = (47, 67, 78, 255)
VISOR = (157, 207, 229, 220)
VISOR_HI = (226, 248, 253, 255)


def luminance(rgb: tuple[int, int, int]) -> float:
    r, g, b = rgb
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def is_edge(mask: Image.Image, x: int, y: int) -> bool:
    w, h = mask.size
    return any(
        nx < 0 or ny < 0 or nx >= w or ny >= h or mask.getpixel((nx, ny))[3] == 0
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1))
    )


def casing_sample(texture: Image.Image, x: int, y: int, alpha: bool = True) -> tuple[int, int, int, int]:
    # Use only the *centre* of the casing face. Sampling the full 16x16 block texture drags its
    # own block-edge frame across arbitrary armor UV islands and is what made r24 look like a flat,
    # badly tiled blue suit. The armor's rim is authored separately below.
    r, g, b, a = texture.getpixel((4 + x % 8, 4 + y % 8))
    return (r, g, b, a if alpha else 255)


def build_material(mask: Image.Image, texture: Image.Image, translucent: bool) -> Image.Image:
    """Diamond UV alpha mask + casing centre texture + one-pixel armor-like border."""
    src = mask.convert("RGBA")
    out = Image.new("RGBA", src.size)
    opaque_luma = [luminance((r, g, b)) for r, g, b, a in src.getdata() if a]
    lo = min(opaque_luma, default=0.0)
    hi = max(opaque_luma, default=255.0)
    span = max(1.0, hi - lo)
    for y in range(src.height):
        for x in range(src.width):
            sr, sg, sb, sa = src.getpixel((x, y))
            if sa == 0:
                continue
            source_luma = luminance((sr, sg, sb))
            shade = (source_luma - lo) / span
            if is_edge(src, x, y):
                out.putpixel((x, y), EDGE_MID)
                continue
            if shade <= 0.13 or shade >= 0.82:
                # Use the diamond texture's own bevel/highlight information as the frame mask.
                # This preserves the familiar vanilla armor "包边" instead of drawing a generic
                # rectangle around the whole UV island.
                out.putpixel((x, y), EDGE_LIGHT if shade >= 0.55 else EDGE_DARK)
                continue
            r, g, b, a = casing_sample(texture, x, y, alpha=translucent)
            # Preserve vanilla form shading subtly, while the visible surface is still the casing
            # centre material rather than recoloured diamond pixels.
            factor = 0.92 + shade * 0.12
            out.putpixel((x, y), (
                min(255, round(r * factor)),
                min(255, round(g * factor)),
                min(255, round(b * factor)),
                a if translucent else 255,
            ))
    return out


def ripple_stage(x: int, y: int, w: int, h: int) -> int:
    """Four-pixel plates radiate from the chest/centre like the casing activation wave."""
    cell = 4
    cx = (x // cell) * cell + cell / 2
    cy = (y // cell) * cell + cell / 2
    ox, oy = w * 0.50, h * 0.45
    d = math.hypot((cx - ox) / max(w, 1), (cy - oy) / max(h, 1))
    max_d = math.hypot(0.52, 0.58)
    return 1 + min(ACTIVE_PHASES - 1, int(d / max_d * ACTIVE_PHASES))


def phase_frame(inactive: Image.Image, active: Image.Image, mask: Image.Image, phase: int) -> Image.Image:
    if phase <= 0:
        return inactive.copy()
    if phase >= PHASES:
        return Image.new("RGBA", inactive.size)

    out = inactive.copy() if phase <= ACTIVE_PHASES else active.copy()
    src = mask.convert("RGBA")

    if phase <= ACTIVE_PHASES:
        # Stage 1: solid casing -> active transparent casing, one plate at a time.
        progress = phase
        for y in range(src.height):
            for x in range(src.width):
                if src.getpixel((x, y))[3] == 0:
                    continue
                stage = ripple_stage(x, y, src.width, src.height)
                if stage <= progress:
                    out.putpixel((x, y), active.getpixel((x, y)))
        return out

    # Stage 2: once every plate is the transparent casing material, a second wave removes it.
    vanish = phase - ACTIVE_PHASES
    for y in range(src.height):
        for x in range(src.width):
            if src.getpixel((x, y))[3] == 0:
                continue
            stage = ripple_stage(x, y, src.width, src.height)
            if stage <= vanish:
                out.putpixel((x, y), (0, 0, 0, 0))
    return out


def item_icon(mask: Image.Image, kind: str) -> Image.Image:
    out = build_material(mask, INACTIVE, translucent=False)
    px = out.load()

    def paint(points, color):
        for x, y in points:
            if 0 <= x < 16 and 0 <= y < 16 and mask.getpixel((x, y))[3]:
                px[x, y] = color

    # Keep icons deliberately close to vanilla diamond armour. Only the helmet gets a visible
    # optical visor because it actually implements Create's goggles functionality.
    if kind == "helmet":
        paint([(x, 7) for x in range(4, 12)], VISOR_DARK)
        paint([(x, 6) for x in range(5, 11)], VISOR)
        paint([(6, 6), (9, 6)], VISOR_HI)
    return out


def main() -> None:
    with zipfile.ZipFile(RESOURCES) as jar:
        for name in ITEMS:
            with jar.open(f"assets/minecraft/textures/item/{name}.png") as fp:
                source = Image.open(fp).convert("RGBA")
            kind = name.removeprefix("diamond_")
            target = OUT / "item" / f"ether_casing_{kind}.png"
            target.parent.mkdir(parents=True, exist_ok=True)
            item_icon(source, kind).save(target)
            print(target.relative_to(ROOT))

        for name in ARMOR:
            with jar.open(f"assets/minecraft/textures/models/armor/{name}.png") as fp:
                source = Image.open(fp).convert("RGBA")
            inactive = build_material(source, INACTIVE, translucent=False)
            active = build_material(source, ACTIVE, translucent=True)
            layer = name[-1]
            folder = OUT / "models/armor"
            folder.mkdir(parents=True, exist_ok=True)
            inactive.save(folder / f"ether_casing_layer_{layer}.png")
            for phase in range(PHASES + 1):
                target = folder / f"ether_casing_layer_{layer}_phase_{phase}.png"
                phase_frame(inactive, active, source, phase).save(target)
                print(target.relative_to(ROOT))


if __name__ == "__main__":
    main()
