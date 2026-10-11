#!/usr/bin/env python3
"""Ship the Resonant Quartz suit's four inventory icons.

The icons were drawn as concept art under `docs/design/concepts/`, in three rounds: V1 (translucent
shells), V2 (opaque, the approved read) and V3 (V2 plus a pair of folded elytra behind the
chestplate, matching the flight the chestplate actually has). V3 is what goes into the mod, and this
script copies it in rather than redrawing anything, because the shapes are the artist's.

The assertions are here for the same reason the fluid tank script has them: the mod's code and its
other art assume things about these four files, and a redrawn icon breaks those assumptions
silently. What is assumed:

  * every icon is 16x16 with alpha in {0, 255} -- item layers are cut out, and a half-transparent
    pixel is a pixel that will fringe against whatever is behind it;
  * helmet, leggings and boots are byte-identical to the approved V2 -- V3 was a chestplate change,
    so any pixel movement in the other three is a redraw nobody asked for;
  * the chestplate has strictly more pixels than V2's and none of V2's removed: the wings sit
    *behind* the chestplate, they do not reshuffle it;
  * those added pixels come in mirror pairs, because the two wings are a pair -- a one-sided wing
    is the failure this catches;
  * the four icons differ from one another, which is what a copy-paste mistake looks like.

Outputs, all 16x16, into src/main/resources/assets/distantstock/textures/item/:

  ether_casing_helmet.png   ether_casing_chestplate.png
  ether_casing_leggings.png ether_casing_boots.png
"""
from __future__ import annotations

import pathlib

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[1]
CONCEPTS = ROOT / "docs/design/concepts"
OUT = ROOT / "src/main/resources/assets/distantstock/textures/item"

# The accepted round, and the round it was built on. V3 = V2 + wings, and that is asserted rather
# than trusted.
SHIPPED = CONCEPTS / "resonant-armor-items-v3/assets/distantstock/textures/item"
BASELINE = CONCEPTS / "resonant-armor-items-v2/assets/distantstock/textures/item"

KINDS = ("helmet", "chestplate", "leggings", "boots")
# Measured: three pieces are untouched copies of V2, the chestplate gains the pair of wings. Any
# other count means the art moved and the assertions below are about to be wrong.
UNCHANGED_FROM_BASELINE = ("helmet", "leggings", "boots")
CHESTPLATE_BASELINE_PIXELS = 138
CHESTPLATE_SHIPPED_PIXELS = 178


def load(root: pathlib.Path, kind: str) -> Image.Image:
    path = root / f"ether_casing_{kind}.png"
    assert path.exists(), f"missing concept icon {path}"
    return Image.open(path).convert("RGBA")


def opaque(image: Image.Image) -> set[int]:
    return {i for i, pixel in enumerate(image.getdata()) if pixel[3] > 0}


def mirror(index: int) -> int:
    x, y = index % 16, index // 16
    return y * 16 + (15 - x)


def relative(path: pathlib.Path) -> str:
    return str(path.relative_to(ROOT))


def main() -> None:
    icons = {kind: load(SHIPPED, kind) for kind in KINDS}
    baseline = {kind: load(BASELINE, kind) for kind in KINDS}

    for kind, icon in icons.items():
        assert icon.size == (16, 16), f"{kind}: {icon.size} is not 16x16"
        alphas = set(icon.getchannel("A").getdata())
        assert alphas <= {0, 255}, f"{kind}: alpha must be cut out, got {sorted(alphas)}"

    for kind in UNCHANGED_FROM_BASELINE:
        assert list(icons[kind].getdata()) == list(baseline[kind].getdata()), (
            f"{kind}: V3 was a chestplate-only round; this piece no longer matches the approved V2")

    shipped_chest, base_chest = icons["chestplate"], baseline["chestplate"]
    added = opaque(shipped_chest) - opaque(base_chest)
    removed = opaque(base_chest) - opaque(shipped_chest)
    assert not removed, f"chestplate: {len(removed)} pixels of the approved chestplate went missing"
    assert len(opaque(base_chest)) == CHESTPLATE_BASELINE_PIXELS, (
        f"chestplate: V2 has {len(opaque(base_chest))} pixels, the script expects "
        f"{CHESTPLATE_BASELINE_PIXELS}")
    assert len(opaque(shipped_chest)) == CHESTPLATE_SHIPPED_PIXELS, (
        f"chestplate: {len(opaque(shipped_chest))} pixels, the script expects "
        f"{CHESTPLATE_SHIPPED_PIXELS}")
    assert len(added) == CHESTPLATE_SHIPPED_PIXELS - CHESTPLATE_BASELINE_PIXELS
    # Every added pixel has its mirror added too: that is what makes the wings a pair. The
    # chestplate's own pixels are already symmetric about the same axis, so this holds for the
    # whole icon and can be stated as one check.
    for index in added:
        assert mirror(index) in added, (
            f"chestplate: the wing pixel at ({index % 16}, {index // 16}) has no mirror")

    seen: dict[tuple, str] = {}
    for kind, icon in icons.items():
        key = tuple(icon.getdata())
        assert key not in seen, f"{kind} is a copy of {seen[key]}"
        seen[key] = kind

    OUT.mkdir(parents=True, exist_ok=True)
    for kind, icon in icons.items():
        icon.save(OUT / f"ether_casing_{kind}.png")
    print(f"wrote {len(icons)} ether casing item icons to {relative(OUT)}")
    print(f"  chestplate: {CHESTPLATE_BASELINE_PIXELS} -> {CHESTPLATE_SHIPPED_PIXELS} pixels, "
          f"{len(added)} of them the mirrored wing pair")


if __name__ == "__main__":
    main()
