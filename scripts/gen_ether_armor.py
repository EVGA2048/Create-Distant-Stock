#!/usr/bin/env python3
"""Generate Resonant Quartz armour from Distant Casing textures.

Design rule (V5, "机壳框格"): treat the armour as if it were built out of Distant Casing blocks.
Every UV face of the vanilla diamond atlas is painted as one small casing panel — a one-pixel dark
rim, a light bevel inside it on the two lit sides, the casing's quiet centre in the middle — and the
only ornament on the whole suit is a single 2x2 active-casing window on the chest. The vanilla
silhouette is untouched: every layer keeps the diamond atlas's alpha byte for byte, so the armour
still reads as diamond armour at a glance and only the material has changed.

The cloak is two-stage: solid casing plates ripple into the active/transparent casing material
first; only after the whole suit has activated does a second ripple erase those transparent plates.
"""

from __future__ import annotations

from pathlib import Path
import math
import zipfile

from PIL import Image, ImageColor


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "build/moddev/artifacts/neoforge-21.1.231-client-extra-aka-minecraft-resources.jar"
OUT = ROOT / "src/main/resources/assets/distantstock/textures"
INACTIVE = Image.open(ROOT / "src/main/resources/assets/distantstock/textures/block/tower/casing_inactive.png").convert("RGBA")
ACTIVE = Image.open(ROOT / "src/main/resources/assets/distantstock/textures/block/tower/casing_active.png").convert("RGBA")

ITEMS = ["diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots"]
ARMOR = ["diamond_layer_1", "diamond_layer_2"]
ACTIVE_PHASES = 6
PHASES = 12

# Colours are the casing textures' own values, so the armour cannot drift away from the block before
# anything else does. Frame and bevel are the casing_inactive border; the window is casing_active.
FRAME_LIGHT = "#8599a2"
FRAME_DARK = "#758a94"
CORNER = {"tl": "#758a94", "tr": "#667c87", "bl": "#667c87", "br": "#5b727e"}
BEVEL_LIGHT = ["#c5d4da", "#d4e2e7", "#c5d4da", "#b8c8cf"]
BEVEL_DARK = ["#a9bac2", "#a9bac2", "#97a9b2", "#a9bac2"]
CENTRE = [(184, 200, 207), (192, 207, 214), (197, 210, 217),
          (205, 218, 223), (210, 222, 227), (214, 226, 230)]
GROOVE = "#617883"
ETHER, ETHER_GLINT, ETHER_LOW = "#b7e0ed", "#e8fbff", "#acdcf1"
ICON = [(91, 114, 126), (117, 138, 148), (133, 153, 162),
        (169, 186, 194), (197, 212, 218), (214, 226, 230)]

# The white-ish tones the active casing samples down to, for the translucent stage of the cloak.
ACTIVE_CENTRE = [(150, 190, 205), (170, 205, 218), (186, 216, 228),
                 (203, 228, 237), (218, 238, 244), (232, 246, 250)]

def net(u: int, v: int, w: int, h: int, d: int) -> dict[str, tuple[int, int, int, int]]:
    """Unwrap one box onto the atlas: side faces are (depth) wide, the front and back are (width).

    Written out rather than tabulated because the side faces are *not* the same width as the front
    and back — the body is eight wide and four deep, so its side faces are four texels across, and
    a table that got that wrong would paint the rim of one face over its neighbour.
    """
    return {
        "east":  (u, v + d, u + d, v + d + h),
        "north": (u + d, v + d, u + d + w, v + d + h),
        "west":  (u + d + w, v + d, u + 2 * d + w, v + d + h),
        "south": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
        "up":    (u + d, v, u + d + w, v + d),
        "down":  (u + d + w, v, u + d + 2 * w, v + d),
    }


NETS = {
    "head": net(0, 0, 8, 8, 8),
    "body": net(16, 16, 8, 12, 4),
    "arm":  net(40, 16, 4, 12, 4),
    "leg":  net(0, 16, 4, 12, 4),
}
STEPS = {(0, -1): "up", (0, 1): "down", (-1, 0): "left", (1, 0): "right"}


def pixels(im: Image.Image):
    return im.getdata()


def luminance(c: tuple[int, int, int]) -> float:
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def ramp(src: Image.Image, palette):
    """Map the atlas's own light-to-dark range onto a colour ramp.

    Vanilla's armour art carries all its form shading in luminance, so keeping the *ordering* of
    that shading and only replacing the ends is what lets a flat block texture keep the armour's
    readable shape. Anything cheaper — one flat fill, or a straight tint — loses the volume and the
    suit reads as a cardboard cut-out.
    """
    values = sorted(luminance(c) for c in pixels(src) if c[3])
    low, high = values[0], values[-1]

    def pick(c):
        t = (luminance(c) - low) / max(1, high - low)
        return palette[min(len(palette) - 1, int(t * (len(palette) - 0.01)))]
    return pick


def paint(im: Image.Image, xy, color) -> None:
    """Recolour an existing texel, keeping its alpha."""
    im.putpixel(xy, ImageColor.getrgb(color)[:3] + (im.getpixel(xy)[3],))


def build_material(mask: Image.Image, centre) -> Image.Image:
    """Each UV face becomes a small casing panel: dark rim, light bevel, quiet centre.

    The rings are measured inwards from each face rectangle *and* from the vanilla cut-outs, so the
    helmet opening and the boot cuffs pick up a rim too and read as cuts in a casing plate rather
    than holes with a raw edge. Faces narrower than eight pixels get no bevel ring: at four pixels
    across, a rim plus a bevel leaves no surface at all and the arms turn into solid outline.
    """
    im = Image.new("RGBA", mask.size)
    pick = ramp(mask, centre)
    for x in range(mask.width):
        for y in range(mask.height):
            c = mask.getpixel((x, y))
            if c[3]:
                im.putpixel((x, y), pick(c) + (c[3],))

    for faces in NETS.values():
        for x0, y0, x1, y1 in faces.values():
            w, h = x1 - x0, y1 - y0

            def inside(p, x0=x0, y0=y0, x1=x1, y1=y1):
                return x0 <= p[0] < x1 and y0 <= p[1] < y1 and mask.getpixel(p)[3] > 0

            cells = [(x, y) for x in range(x0, x1) for y in range(y0, y1) if inside((x, y))]
            if not cells:
                continue
            bevel = min(w, h) >= 8
            ring = {}
            for p in cells:
                if any(not inside((p[0] + dx, p[1] + dy)) for dx, dy in STEPS):
                    ring[p] = 0
            for p in cells:
                if p not in ring and any(ring.get((p[0] + dx, p[1] + dy)) == 0 for dx, dy in STEPS):
                    ring[p] = 1
            for (x, y), k in ring.items():
                if k == 1 and not bevel:
                    continue
                towards = {name for (dx, dy), name in STEPS.items()
                           if (not inside((x + dx, y + dy)) if k == 0
                               else ring.get((x + dx, y + dy)) == k - 1)}
                light = bool(towards & {"up", "left"})
                dark = bool(towards & {"down", "right"})
                if k == 0:
                    vertical = "t" if "up" in towards else "b" if "down" in towards else ""
                    horizontal = "l" if "left" in towards else "r" if "right" in towards else ""
                    color = (CORNER[vertical + horizontal] if vertical and horizontal
                             else FRAME_LIGHT if light else FRAME_DARK)
                else:
                    color = (BEVEL_DARK if dark and not light else BEVEL_LIGHT)[(x + y) % 4]
                paint(im, (x, y), color)

    assert im.getchannel("A").tobytes() == mask.getchannel("A").tobytes()
    return im


def core(im: Image.Image, x: int, y: int) -> None:
    """A 2x2 lit window in a groove: the casing_active centre at armour scale."""
    for dx in range(-1, 3):
        for dy in range(-1, 3):
            if not im.getpixel((x + dx, y + dy))[3]:
                continue
            if dx in (-1, 2) or dy in (-1, 2):
                paint(im, (x + dx, y + dy), GROOVE)
    for (dx, dy), color in (((0, 0), ETHER_GLINT), ((1, 0), ETHER),
                            ((0, 1), ETHER_LOW), ((1, 1), ETHER)):
        if im.getpixel((x + dx, y + dy))[3]:
            paint(im, (x + dx, y + dy), color)


# The ripple lights each armour part from the point on that part nearest the chest, so the wave
# leaves the chest first and the limbs follow it out. Parts are matched to the plates they own.
PART_OF = {}
for _part, _faces in NETS.items():
    for _x0, _y0, _x1, _y1 in _faces.values():
        for _x in range(_x0, _x1):
            for _y in range(_y0, _y1):
                PART_OF[(_x, _y)] = _part
PART_BOUNDS = {
    part: (min(f[0] for f in faces.values()), min(f[1] for f in faces.values()),
           max(f[2] for f in faces.values()), max(f[3] for f in faces.values()))
    for part, faces in NETS.items()
}
ORIGIN = (23.5, 23.5)   # between the chest plates, where the window is


def ripple_stage(x: int, y: int, w: int, h: int) -> int:
    """Which of the six active stages a texel belongs to: nearest the chest lights first.

    Distance is measured *inside the armour part the texel belongs to*, from the corner of that part
    closest to the chest. Measuring against the whole atlas instead would leave the legs, which sit
    at the opposite end of the texture, permanently past the last stage — three quarters of the
    leggings would never light up at all. Per-part distance also keeps the wave at a constant speed:
    a four-pixel plate is one step whether it is on the helmet or on a boot, rather than the small
    parts switching over in one frame while the chest takes six.

    Stages run nearest-first, the same order the second ripple erases in, so the suit lights from
    the chest outwards and then goes dark from the chest outwards with no dead frame in between.
    """
    part = PART_OF.get((x, y))
    if part is None:
        return 1
    x0, y0, x1, y1 = PART_BOUNDS[part]
    ax = min(max(ORIGIN[0], x0), x1)      # the part's own point nearest the chest
    ay = min(max(ORIGIN[1], y0), y1)
    cx = (x // 4) * 4 + 2                  # four-pixel plates, sampled at their centre
    cy = (y // 4) * 4 + 2
    d = math.hypot((cx - ax) / max(1, x1 - x0), (cy - ay) / max(1, y1 - y0))
    far = math.hypot(max(ax - x0, x1 - ax) / max(1, x1 - x0),
                     max(ay - y0, y1 - ay) / max(1, y1 - y0))
    return ACTIVE_PHASES - min(ACTIVE_PHASES - 1, int(d / max(far, 1e-6) * ACTIVE_PHASES))


def phase_frame(inactive: Image.Image, active: Image.Image, mask: Image.Image, phase: int) -> Image.Image:
    if phase <= 0:
        return inactive.copy()
    if phase >= PHASES:
        return Image.new("RGBA", inactive.size)

    out = inactive.copy() if phase <= ACTIVE_PHASES else active.copy()
    src = mask.convert("RGBA")

    if phase <= ACTIVE_PHASES:
        # Stage 1: solid casing -> active transparent casing, one plate at a time.
        for y in range(src.height):
            for x in range(src.width):
                if src.getpixel((x, y))[3] == 0:
                    continue
                if ripple_stage(x, y, src.width, src.height) <= phase:
                    out.putpixel((x, y), active.getpixel((x, y)))
        return out

    # Stage 2: once every plate is the transparent casing material, a second wave removes it.
    vanish = phase - ACTIVE_PHASES
    for y in range(src.height):
        for x in range(src.width):
            if src.getpixel((x, y))[3] == 0:
                continue
            if ripple_stage(x, y, src.width, src.height) <= vanish:
                out.putpixel((x, y), (0, 0, 0, 0))
    return out


def item_icon(mask: Image.Image, kind: str) -> Image.Image:
    """Icon: the same casing ramp over the vanilla diamond icon's own shading.

    The icon is deliberately shallow — no frame, no bevel — because at sixteen pixels the panel
    treatment reads as noise. Only the chestplate carries the window, so the set is recognisable by
    silhouette and the chest stays the piece that names the machine.
    """
    pick = ramp(mask, ICON)
    out = mask.copy()
    for x in range(16):
        for y in range(16):
            c = mask.getpixel((x, y))
            if c[3]:
                out.putpixel((x, y), pick(c) + (c[3],))
    if kind == "chestplate":
        core(out, 7, 9)
    assert out.getchannel("A").tobytes() == mask.getchannel("A").tobytes()
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
            # Solid plates keep the inactive casing's frame and centre; the active plates keep the
            # same frame but swap the centre for the translucent ether material, which is what makes
            # the window appear to spread across the suit before it vanishes.
            inactive = build_material(source, CENTRE)
            active = build_material(source, ACTIVE_CENTRE)
            core(inactive, 23, 23)   # chest front face spans x20-27, y20-31
            core(active, 23, 23)
            layer = name[-1]
            folder = OUT / "models/armor"
            folder.mkdir(parents=True, exist_ok=True)
            inactive.save(folder / f"ether_casing_layer_{layer}.png")
            for phase in range(PHASES + 1):
                target = folder / f"ether_casing_layer_{layer}_phase_{phase}.png"
                phase_frame(inactive, active, source, phase).save(target)
                print(target.relative_to(ROOT))

        # 64×32 elytra texture. Left wing texOffs(22, 0) 10×20×2, right wing mirrored.
        # Layout: u=22 starts the wing box (d=2 → east 22-24, front 24-34, west 34-36, back 36-46,
        # up 24-34×0-2, down 34-44×0-2). Vanilla elytra is mostly feature-less geometry, so paint
        # every texel with the inactive casing material.
        wings = Image.new("RGBA", (64, 32))
        dummy = Image.new("RGBA", (64, 32), (128, 128, 128, 255))
        material = build_material(dummy, CENTRE)
        # Left wing box spans u22-46, v0-22 (width=10, height=20, depth=2 unfolded). Right wing
        # is the same box mirrored horizontally, u0-24 on the same v. Fill both with the casing
        # material, preserving alpha=0 where vanilla's elytra is transparent.
        with jar.open("assets/minecraft/textures/entity/elytra.png") as fp:
            vanilla_wings = Image.open(fp).convert("RGBA")
        for x in range(64):
            for y in range(32):
                if vanilla_wings.getpixel((x, y))[3] > 0:
                    wings.putpixel((x, y), material.getpixel((x % material.width, y % material.height)))
        target = OUT / "models/armor/ether_casing_wings.png"
        wings.save(target)
        print(target.relative_to(ROOT))


if __name__ == "__main__":
    main()
