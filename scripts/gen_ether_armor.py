#!/usr/bin/env python3
"""Generate Resonant Quartz armour from the ether quartz it is named for.

Design rule (V7, "磨制石英 / glass"): the suit is a shell of polished ether quartz, and it is
see-through. Two things follow from that and neither is decorative.

Material. The ramp is polished_ether_quartz.png's own eight colours, verbatim, #35536e .. #ffffff,
with nothing padded on either end. An earlier pass invented two darker steps to get more contrast
and put the suit below the darkest colour in the material it is supposed to be made of.

Shading. The shell is one continuous surface. Each face takes a base from its ORIENTATION, a bevel
runs along every edge worth +-0.7 per lit/shadow edge, a shoulder eases that off one texel in, a
slow gradient crosses each face, and the plates carry a hem where they stop. That field is then
quantised onto a 25-step ramp interpolated from the eight poles -- nine steps was too coarse, and
quantising onto it drew a hard line at every step while dithering traded those lines for mottle.

Opacity. Vanilla armour is RenderType.cutout, where alpha is binary, so a half-transparent texel
renders opaque. HumanoidArmorLayerMixin forces entityTranslucent for anything under
`textures/models/armor/ether_casing_layer_`, which is what the cloak phases already rely on. With
that in place coverage can be authored: plate is thinner than its own bevels and hems, so the
shape is carried by the edges and the player's skin reads through the flats.

The first attempt at that took it much too far -- plate at 40 of 255, chosen off a concept render
whose mannequin is flat grey. Against real skin and open sky the plates disappeared and only the
edges survived, so the suit read as a wireframe and the wings as an empty frame; see ALPHA_FACE.

The cloak is two-stage, unchanged in structure: the glass shell ripples into a thinner, ether-tinted
state one plate at a time, and only once the whole suit has activated does a second ripple erase it.
"""
from __future__ import annotations

import io
import math
import sys
import zipfile
from pathlib import Path

from PIL import Image, ImageColor

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "build/moddev/artifacts/neoforge-21.1.231-client-extra-aka-minecraft-resources.jar"
OUT = ROOT / "src/main/resources/assets/distantstock/textures"

ITEMS = ["diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots"]
ARMOR = ["diamond_layer_1", "diamond_layer_2"]
ACTIVE_PHASES = 6
PHASES = 12

# ---------------------------------------------------------------- the material
# Exactly the eight colours of polished_ether_quartz.png, dark to light, nothing added.
GLASS = ["#35536e", "#507b9b", "#699dbc", "#8dbbd6",
         "#b6d8e8", "#d9ebee", "#f3f6e9", "#ffffff"]
GLINT = "#ffffff"
# The cloak's stage one. Thinner than the shell and pulled toward the ether condensate, so the suit
# reads as activating rather than as simply fading.
ETHER_ACTIVE = "#b7e0ed"
ACTIVE_FACE, ACTIVE_GAIN = 18, 62

# ---------------------------------------------------------------- the two states
# A plain suit is colourless glass and the player reads as unarmoured from across a field. An
# enchanted one is the same shell lit through with ether, and it is blue all the way down. That is
# the whole of the enchantment feedback -- there is no vanilla glint on this armour, and there cannot
# be one, see the note in EtherCasingArmorItem.getArmorTexture. Every number that differs between
# the two states lives here; nothing downstream knows which one it is drawing.
CLEAR = dict(
    # Base ramp step per face orientation, light from the front-upper-left. Shifted one step up the
    # ramp from where the shading maths alone would put it: the mid poles are saturated blues
    # (#699dbc, #8dbbd6), and glass over skin should add light, not colour.
    ORIENT={"up": 7, "north": 5, "west": 5, "east": 4, "south": 3, "down": 2},
    BEVEL_LIP=2.20,        # per lit (up/left) or shadow (down/right) edge; corners take the sum
    CHAMFER=1.50,          # the groove one texel in from every rim, where the ground edge steps
                           # down onto the flat. It is what makes an edge read as machined rather
                           # than as drawn, and it is most of the close-range reading.
    ALPHA_FACE=62,         # 24% -- flat coverage, see the note below
    ALPHA_WING=185,
    GLASS_FLOOR=1.2,       # floor on the tone field, not a ramp index
    SPAR_OUTER=(6.75, -0.25),   # the leading edge, root tone then per-unit fall to the tip
    SPAR_INNER=(5.35, -0.25),   # its groove, one texel in
)

IMBUED = dict(
    # The same shell, imbued. Back to the tone field the suit wore before it was made colourless:
    # saturated and lower, so the whole surface sits in the blue half of the ramp.
    #
    # Coverage goes up with it, and by more than taste would suggest. Over skin the suit reads as
    # blue only once it can out-vote the warm tone underneath -- with this ramp's blue the crossover
    # is at about forty percent, because the poles are muted (#699dbc is only eighty-three points
    # from red to blue) and a quarter coverage leaves the composite warmer in red than in blue. That
    # is why the suit at ALPHA_FACE read as blue before and does not now. The trade is deliberate:
    # an unenchanted suit is invisible at range and an enchanted one is not, so the choice to be
    # seen belongs to the player.
    ORIENT={"up": 6, "north": 4, "west": 4, "east": 3, "south": 2, "down": 1},
    BEVEL_LIP=1.40,
    CHAMFER=1.00,
    ALPHA_FACE=105,
    ALPHA_WING=200,
    GLASS_FLOOR=2.5,
    SPAR_OUTER=(1.5, 0.9),
    SPAR_INNER=(2.7, 0.9),
)

STATES = (("", CLEAR), ("_imbued", IMBUED))


def use(state: dict) -> None:
    """Load one state's numbers into the module. The drawing code only ever reads globals."""
    globals().update(state)


use(CLEAR)             # so importing the module is enough to draw something sane


BEVEL_SHOULDER = 0.30  # how far the bevel eases into the plate before it is flat
HEM_DROP = 1.1         # how far a plate's turned-under edge falls
# Where a plate stops. Without one the suit is skin-tight collar to sole and reads as a bodysuit.
# Layer 1 owns the chestplate, the sleeves and the boots; layer 2's lower half sits inside them.
HEM_ROWS = {(1, "body"): 2, (1, "arm"): 2, (1, "leg"): 2}
ALPHA_SPEC = 255       # the sky-facing specular is the only solid texel on the suit

NETS = dict(
    head=dict(east=(0, 8, 8, 16), north=(8, 8, 16, 16), west=(16, 8, 24, 16), south=(24, 8, 32, 16),
              up=(8, 0, 16, 8), down=(16, 0, 24, 8)),
    body=dict(east=(16, 20, 20, 32), north=(20, 20, 28, 32), west=(28, 20, 32, 32), south=(32, 20, 40, 32),
              up=(20, 16, 28, 20), down=(28, 16, 36, 20)),
    arm=dict(east=(40, 20, 44, 32), north=(44, 20, 48, 32), west=(48, 20, 52, 32), south=(52, 20, 56, 32),
             up=(44, 16, 48, 20), down=(48, 16, 52, 20)),
    leg=dict(east=(0, 20, 4, 32), north=(4, 20, 8, 32), west=(8, 20, 12, 32), south=(12, 20, 16, 32),
             up=(4, 16, 8, 20), down=(8, 16, 12, 20)),
)
STEPS = {(0, -1): "up", (0, 1): "down", (-1, 0): "left", (1, 0): "right"}


def show(path: Path):
    """Print a path relative to the repo when it is inside one; the preview harness redirects OUT."""
    try:
        return path.relative_to(ROOT)
    except ValueError:
        return path


def interp(palette, n):
    """Resample a ramp to n even steps by mixing neighbours. No hue enters that the material did
    not already have -- every new step is a blend of two adjacent poles."""
    out = []
    for i in range(n):
        t = i / (n - 1) * (len(palette) - 1)
        a = int(t)
        b = min(len(palette) - 1, a + 1)
        fr = t - a
        ca, cb = ImageColor.getrgb(palette[a]), ImageColor.getrgb(palette[b])
        out.append(tuple(round(ca[k] + (cb[k] - ca[k]) * fr) for k in range(3)))
    return out


FINE = interp(GLASS, 25)


def texel(f):
    """A tone field value in pole space (0 .. len(GLASS)-1) onto the 25-step ramp.

    Normalising by the pole count rather than by the ramp length is the whole point -- f arrives on
    the eight-pole scale, and reading it as a ramp index would pin the light end of the suit to
    step seven of twenty-five and wash the shading out.
    """
    k = int(f / (len(GLASS) - 1) * (len(FINE) - 1) + 0.5)
    return FINE[max(0, min(len(FINE) - 1, k))]


def hem_drop(layer, part, rows_up):
    n = HEM_ROWS.get((layer, part), 0)
    return 0.0 if rows_up >= n else HEM_DROP * (1.0 - rows_up / n)


def glaze(src: Image.Image, layer: int, opaque: bool = False) -> Image.Image:
    """Shade one armour layer. Alpha rides on how structural each texel is unless `opaque`."""
    im = Image.new("RGBA", src.size)
    for x in range(src.width):
        for y in range(src.height):
            if src.getpixel((x, y))[3]:
                im.putpixel((x, y), ImageColor.getrgb(GLASS[3]) + (255 if opaque else ALPHA_FACE,))

    for part, table in NETS.items():
        for name, (x0, y0, x1, y1) in table.items():
            inside = lambda p: x0 <= p[0] < x1 and y0 <= p[1] < y1 and src.getpixel(p)[3] > 0
            cells = {(x, y) for x in range(x0, x1) for y in range(y0, y1) if inside((x, y))}
            if not cells:
                continue
            base = ORIENT[name]
            w, h = x1 - x0, y1 - y0
            shoulder = min(w, h) >= 8          # a four-wide limb has no room for one

            def sides(p):
                return {d for (dx, dy), d in STEPS.items() if (p[0] + dx, p[1] + dy) not in cells}

            lip = {p for p in cells if sides(p)}
            lip_sign = {p: (1 if sides(p) & {"up", "left"} else 0)
                           - (1 if sides(p) & {"down", "right"} else 0) for p in lip}

            for (x, y) in sorted(cells):
                depth = min(x - x0, x1 - 1 - x, y - y0, y1 - 1 - y)
                if depth == 0:
                    e = sides((x, y))
                    bevel = BEVEL_LIP * (len(e & {"up", "left"}) - len(e & {"down", "right"}))
                elif shoulder:
                    bevel = BEVEL_SHOULDER * sum(lip_sign.get((x + dx, y + dy), 0) for dx, dy in STEPS)
                else:
                    bevel = 0.0
                # One texel in from the rim, across it the whole way round, sits a groove: the shadow
                # where the ground edge steps down onto the flat. Without it the lit rim is a drawn
                # outline and the suit reads as a sketch; with it the edge reads as a chamfer and the
                # suit reads as something machined. It is also where nearly all of the close-range
                # information lives, because the flats themselves carry almost no coverage at all.
                groove = -CHAMFER if shoulder and depth == 1 else 0.0
                hem = hem_drop(layer, part, y1 - 1 - y)
                u = (x - x0) / max(1, w - 1)
                v = (y - y0) / max(1, h - 1)
                f = base + bevel + groove - hem + 0.9 - 1.8 * (0.62 * u + 0.38 * v)
                # Flat on purpose: coverage is the far read and tone is the near read, and letting
                # one carry the other is what made every earlier pass either heavy or invisible.
                a = 255 if opaque else ALPHA_FACE
                im.putpixel((x, y), texel(max(f, GLASS_FLOOR)) + (a,))

            if name == "up" and min(w, h) >= 8:
                for (x, y) in sorted(cells):
                    if (x - x0, y - y0) in ((3, 2), (4, 2), (3, 3), (4, 3), (5, 2), (2, 2)):
                        im.putpixel((x, y), ImageColor.getrgb(GLINT) + (255 if opaque else ALPHA_SPEC,))
    return im


def active_layer(layer: Image.Image, mask: Image.Image) -> Image.Image:
    """The cloak's stage one: same silhouette, thinner, tinted to the ether condensate."""
    im = Image.new("RGBA", layer.size)
    for x in range(mask.width):
        for y in range(mask.height):
            if mask.getpixel((x, y))[3] == 0:
                continue
            a = layer.getpixel((x, y))[3]
            # Carry the shell's coverage across, scaled down -- the plates keep their shape as they
            # thin out, so the wave still has something to travel along.
            out = ACTIVE_FACE + int(a * ACTIVE_GAIN / max(1, ALPHA_SPEC))
            im.putpixel((x, y), ImageColor.getrgb(ETHER_ACTIVE) + (min(255, out),))
    return im


# ------------------------------------------------------------------ the cloak
# The ripple lights each part from the point on that part nearest the chest, so the wave leaves the
# chest first and the limbs follow it out.
PART_OF = {}
for _part, _faces in NETS.items():
    for _x0, _y0, _x1, _y1 in _faces.values():
        for _x in range(_x0, _x1):
            for _y in range(_y0, _y1):
                PART_OF[(_x, _y)] = _part
PART_BOUNDS = {part: (min(f[0] for f in faces.values()), min(f[1] for f in faces.values()),
                      max(f[2] for f in faces.values()), max(f[3] for f in faces.values()))
               for part, faces in NETS.items()}
ORIGIN = (23.5, 23.5)   # between the chest plates


def ripple_stage(x: int, y: int) -> int:
    """Which of the six active stages a texel belongs to; nearest the chest lights first.

    Distance runs *inside the armour part the texel belongs to*, from that part's corner nearest the
    chest. Against the whole atlas the legs, which sit at the opposite end of the texture, would be
    permanently past the last stage and three quarters of the leggings would never light at all.
    """
    part = PART_OF.get((x, y))
    if part is None:
        return 1
    x0, y0, x1, y1 = PART_BOUNDS[part]
    ax = min(max(ORIGIN[0], x0), x1)
    ay = min(max(ORIGIN[1], y0), y1)
    cx = (x // 4) * 4 + 2
    cy = (y // 4) * 4 + 2
    d = math.hypot((cx - ax) / max(1, x1 - x0), (cy - ay) / max(1, y1 - y0))
    far = math.hypot(max(ax - x0, x1 - ax) / max(1, x1 - x0),
                     max(ay - y0, y1 - ay) / max(1, y1 - y0))
    return ACTIVE_PHASES - min(ACTIVE_PHASES - 1, int(d / max(far, 1e-6) * ACTIVE_PHASES))


def phase_frame(shell: Image.Image, active: Image.Image, mask: Image.Image, phase: int) -> Image.Image:
    if phase <= 0:
        return shell.copy()
    if phase >= PHASES:
        return Image.new("RGBA", shell.size)
    out = shell.copy() if phase <= ACTIVE_PHASES else active.copy()
    if phase <= ACTIVE_PHASES:
        for y in range(mask.height):
            for x in range(mask.width):
                if mask.getpixel((x, y))[3] and ripple_stage(x, y) <= phase:
                    out.putpixel((x, y), active.getpixel((x, y)))
        return out
    vanish = phase - ACTIVE_PHASES
    for y in range(mask.height):
        for x in range(mask.width):
            if mask.getpixel((x, y))[3] and ripple_stage(x, y) <= vanish:
                out.putpixel((x, y), (0, 0, 0, 0))
    return out


# ------------------------------------------------------------------ the wings
# Vanilla's elytra is a 10x20x2 box per wing. Counting the alpha per face of its texture:
#
#     north   0/200 opaque      south  173/200      east/west  11/40 each      up/down  5-6/20
#
# The front face is empty and the back face carries everything, so the wing is a single-sided
# membrane and the geometry never takes part in the silhouette. Reading the south face out on its
# own -- texels (36,2) to (45,21) -- gives the whole wing as a 10x20 canvas:
#
#     ######....   <- the shape is a slab with two corners cut away, nothing more
#     #######...
#     ...
#     ...#######
#
# Which means a rebuild is texture work, not a new model. Three things had to be learned the hard
# way, and all of them are about reading the canvas wrong.
#
# The canvas is not a rectangle. Its taper IS the wing: filling all 10x20 makes the box read as a
# slab, and it came back as "too thick" even though the geometry was untouched and identical to
# vanilla's. v runs root(0) -> tip(19) along the span and u runs leading edge(0) -> trailing edge(9)
# across the chord, so a wing is a shape whose chord shrinks as it goes out.
#
# Nor is the surface a grid. A full-height seam plus seams every five rows reads as a window frame,
# which is exactly what it was called. Mechanical detail belongs along the span, never across it.
#
# The four thin faces of the box are left alone entirely -- not painted at all, so only the membrane
# exists. Keeping them at vanilla's coverage still left a rim visible along the wing's tip and edge,
# which on a shell this transparent reads as a frame drawn around nothing.
#
# What is left is an aircraft planform: a spar down the leading edge, the chord tapering to the tip,
# and a sawtooth trailing edge whose teeth are the feather tips. One texel of tooth is the most this
# canvas can carry.
#
# The spar is two columns and both are shaded along the span. A single flat column was called out
# immediately -- the two long dark lines were the only solid colour left on the wing, and at twenty
# texels long a flat fill is very obviously a fill.
#
#   '=' spar outer   '+' spar inner bevel   'o' rivet   '#' glass membrane   '.' empty
WING = """
++########
=+########
=+########
=+########
=o#######.
=+#######.
=+########
=+#######.
=+#######.
=+######..
=o######..
=+#######.
=+######..
=+#####...
=o#####...
=+######..
=+####....
=+###.....
=o##......
=+........
""".strip("\n").split("\n")

WING_SOUTH = (36, 2, 46, 22)          # the membrane; the only face vanilla paints


def membrane() -> Image.Image:
    """The 10x20 wing canvas: a glass membrane behind a shaded spar.

    Every tone here falls from root to tip, the spar included, so the wing carries the same light as
    the suit and the spar is not one flat colour for twenty texels.
    """
    t = Image.new("RGBA", (10, 20))
    for v in range(20):
        fall = v / 19.0
        for u in range(10):
            ch = WING[v][u]
            if ch == ".":
                continue
            if ch == "=":
                # The leading edge. On a plain suit it is a bright ground edge and not a coloured
                # stripe: two texels of saturated blue at full coverage is the loudest thing on the
                # wing -- the shell behind it is a quarter coverage and the membrane is a wash -- so
                # the eye read the spar as painted trim and not as the edge of the glass. On an
                # imbued one the blue comes back, which is what makes the change of state legible.
                t.putpixel((u, v), texel(SPAR_OUTER[0] + SPAR_OUTER[1] * fall) + (255,))
            elif ch == "+":
                # One texel in, the groove. The edge needs something to stand against at the root,
                # where the membrane is nearly the same tone and the spar would otherwise vanish.
                t.putpixel((u, v), texel(SPAR_INNER[0] + SPAR_INNER[1] * fall) + (255,))
            elif ch == "o":
                t.putpixel((u, v), ImageColor.getrgb(GLINT) + (255,))
            else:
                # Lit at the root and falling toward the tip, so the wing reads with the same
                # light as the suit: brighter where it meets the shoulder.
                t.putpixel((u, v), texel(max(ORIENT["north"] + 1.1 - 2.0 * fall, GLASS_FLOOR))
                           + (ALPHA_WING,))
    return t


def glaze_wings() -> Image.Image:
    """Only the membrane. The box's four thin faces are left clear, so the wing is one flat plane.

    Keeping them at vanilla's coverage was not enough: a rim still showed along the tip and the outer
    edge, and against a shell this transparent a rim reads as a frame drawn around nothing.
    """
    im = Image.new("RGBA", (64, 32))
    im.paste(membrane(), (WING_SOUTH[0], WING_SOUTH[1]))
    return im


def wing_phase(shell: Image.Image, mask: Image.Image, phase: int) -> Image.Image:
    """Wings follow the suit: the same wave, driven off the wing's own distance from the shoulder."""
    if phase <= 0:
        return shell.copy()
    if phase >= PHASES:
        return Image.new("RGBA", shell.size)
    out = Image.new("RGBA", shell.size)
    pts = [(x, y) for x in range(64) for y in range(32) if mask.getpixel((x, y))[3]]
    if not pts:
        return out
    span = max(p[1] for p in pts) - min(p[1] for p in pts) or 1
    near = min(p[1] for p in pts)
    for (x, y) in pts:
        t = (y - near) / span
        if phase <= ACTIVE_PHASES:
            # Stage 1: the wave runs from shoulder to tip, thinning the wing behind itself. Scaled to
            # the wing's own coverage -- the plates' ACTIVE_FACE would make this all but invisible.
            if t <= phase / ACTIVE_PHASES:
                out.putpixel((x, y), ImageColor.getrgb(ETHER_ACTIVE) + (int(ALPHA_WING * (0.8 - 0.5 * t)),))
            else:
                out.putpixel((x, y), shell.getpixel((x, y)))
        elif t > (phase - ACTIVE_PHASES) / ACTIVE_PHASES:
            # Stage 2: and erases it in the same direction, so there is no dead frame between.
            out.putpixel((x, y), shell.getpixel((x, y)))
    return out


def item_icon(mask: Image.Image, kind: str) -> Image.Image:
    """Icons stay opaque: a see-through inventory sprite reads as a missing or broken item."""
    solid = lambda p: 0 <= p[0] < 16 and 0 <= p[1] < 16 and mask.getpixel(p)[3] > 0
    pts = [(x, y) for x in range(16) for y in range(16) if solid((x, y))]
    x0, x1 = min(p[0] for p in pts), max(p[0] for p in pts)
    y0, y1 = min(p[1] for p in pts), max(p[1] for p in pts)
    out = Image.new("RGBA", (16, 16))
    for (x, y) in pts:
        edge = {d for (dx, dy), d in STEPS.items() if not solid((x + dx, y + dy))}
        bevel = BEVEL_LIP * (len(edge & {"up", "left"}) - len(edge & {"down", "right"}))
        u = (x - x0) / max(1, x1 - x0)
        v = (y - y0) / max(1, y1 - y0)
        f = ORIENT["north"] + bevel + 0.9 - 1.8 * (0.62 * u + 0.38 * v)
        out.putpixel((x, y), texel(max(f, GLASS_FLOOR)) + (255,))
    assert out.getchannel("A").tobytes() == mask.getchannel("A").tobytes(), kind
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
            print(show(target))

        sources = {}
        for name in ARMOR:
            with jar.open(f"assets/minecraft/textures/models/armor/{name}.png") as fp:
                sources[name] = Image.open(fp).convert("RGBA")

        # Guard the property that matters rather than one exact outline: the canvas has to stay a
        # tapered wing. Filling it into a rectangle is what made the box read as a slab and the
        # player saw it at once, so the guard is "wide at the root, narrow at the tip".
        root = sum(1 for u in range(10) if WING[0][u] != ".")
        tip = sum(1 for u in range(10) if WING[-1][u] != ".")
        assert root >= 8 and tip <= 4, f"wing lost its taper: root={root} tip={tip}"

        folder = OUT / "models/armor"
        folder.mkdir(parents=True, exist_ok=True)
        for suffix, state in STATES:
            use(state)
            for name in ARMOR:
                source = sources[name]
                layer = int(name[-1])
                shell = glaze(source, layer)
                active = active_layer(shell, source)
                # The guard is that the same texels are present -- the silhouette is still vanilla
                # diamond's; the armour is only see-through.
                assert all((shell.getpixel((x, y))[3] > 0) == (source.getpixel((x, y))[3] > 0)
                           for x in range(64) for y in range(32)), f"layer {layer} changed shape"
                target = folder / f"ether_casing_layer_{layer}{suffix}.png"
                shell.save(target)
                print(show(target))
                for phase in range(PHASES + 1):
                    target = folder / f"ether_casing_layer_{layer}_phase_{phase}{suffix}.png"
                    phase_frame(shell, active, source, phase).save(target)
                    print(show(target))

            wings = glaze_wings()
            target = folder / f"ether_casing_wings{suffix}.png"
            wings.save(target)
            print(show(target))
            for phase in range(PHASES + 1):
                target = folder / f"ether_casing_wings_phase_{phase}{suffix}.png"
                wing_phase(wings, wings, phase).save(target)
                print(show(target))


if __name__ == "__main__":
    main()
