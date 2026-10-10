#!/usr/bin/env python3
"""Ship the three fluid tanks' artwork, and derive the two icons a fluid level needs.

The three shells already exist as accepted concept art under `docs/design/concepts/`; this script
copies them into the mod and asserts everything the code assumes about them, because the code does
assume things and a redrawn shell would break them silently:

  * the three fluid masks are byte-identical, which is what lets one pair of level textures serve
    all three tanks instead of six pairs;
  * the window is exactly 24 pixels at x 6..9, y 6..11, so "the lower half" is a defensible split;
  * every shell is transparent over exactly that window, at alphas 24/95/200, which is what makes
    the fluid visible underneath it at all.

Nothing is drawn here. The level textures are the same mask with rows removed, so the fluid's shape
is whatever the artist drew and cannot drift from it.

Outputs, all 16x16, into src/main/resources/assets/distantstock/textures/item/:

  portable_fluid_tank_copper.png         portable_fluid_tank_fluid_empty.png
  portable_fluid_tank_sturdy.png         portable_fluid_tank_fluid_level1.png
  portable_fluid_tank_resonant.png       portable_fluid_tank_fluid_level2.png
"""
from __future__ import annotations

import hashlib
import pathlib

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[1]
CONCEPTS = ROOT / "docs/design/concepts"
OUT = ROOT / "src/main/resources/assets/distantstock/textures/item"

# tier id -> (shell, mask) as accepted in the concept folders. The tier ids are the same strings the
# item enum and the model file names use, so a rename here is a rename everywhere or a failed assert.
CONCEPT = {
    "copper": ("portable-fluid-tank-v4", "portable_fluid_tank"),
    "sturdy": ("reinforced-portable-fluid-tank-v1", "reinforced_portable_fluid_tank"),
    "resonant": ("resonant-portable-fluid-tank-v1", "resonant_portable_fluid_tank"),
}

# Where the window sits in the mask, and where the fill line falls. Both are asserted, not assumed.
WINDOW = {(x, y) for y in range(6, 12) for x in range(6, 10)}
LOW_ROWS = {(x, y) for y in range(9, 12) for x in range(6, 10)}

MASK_MD5 = "1e423f9e197ba3e12b7247dec03279f6"


def concept(name: str) -> Image.Image:
    folder, stem = CONCEPT[name]
    return Image.open(CONCEPTS / folder / "assets/distantstock/textures/item" / f"{stem}.png").convert("RGBA")


def opaque(image: Image.Image) -> set[tuple[int, int]]:
    return {(x, y) for y in range(16) for x in range(16) if image.getpixel((x, y))[3] > 0}


def main() -> None:
    shells = {name: concept(name) for name in CONCEPT}
    masks = {}
    for name in CONCEPT:
        folder, stem = CONCEPT[name]
        path = CONCEPTS / folder / "assets/distantstock/textures/item" / f"{stem}_fluid_mask.png"
        masks[name] = Image.open(path).convert("RGBA")

    # One silhouette, three tanks. If this ever stops holding, the level textures have to be
    # generated per tier and the model files have to name their own -- so it is worth failing loudly.
    fingerprints = {name: hashlib.md5(pathlib.Path(
        CONCEPTS / CONCEPT[name][0] / "assets/distantstock/textures/item"
        / f"{CONCEPT[name][1]}_fluid_mask.png").read_bytes()).hexdigest()
        for name in CONCEPT}
    assert len(set(fingerprints.values())) == 1, \
        f"the three masks differ; level art can no longer be shared: {fingerprints}"
    assert next(iter(fingerprints.values())) == MASK_MD5, \
        f"the mask was redrawn (md5 {next(iter(fingerprints.values()))}); the level split needs a look"

    master = masks["copper"]
    assert opaque(master) == WINDOW, f"window moved: {sorted(opaque(master) ^ WINDOW)}"
    assert all(master.getpixel(p) == (255, 255, 255, 255) for p in WINDOW), \
        "the mask must be solid white where fluid goes"

    for name, shell in shells.items():
        assert shell.size == (16, 16), f"{name}: shell is {shell.size}"
        inside = {p for p in opaque(shell) if p in WINDOW}
        assert inside == WINDOW, f"{name}: the shell is opaque over {sorted(WINDOW - inside)}"
        alphas = {shell.getpixel(p)[3] for p in WINDOW}
        assert alphas == {24, 95, 200}, \
            f"{name}: window alphas are {sorted(alphas)}; the fluid would be hidden or hazed over"
        assert opaque(shell) - WINDOW == opaque(shells["copper"]) - WINDOW, \
            f"{name}: the silhouette outside the window differs from the copper tank's"

    empty = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    level1 = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for x, y in LOW_ROWS:
        level1.putpixel((x, y), (255, 255, 255, 255))
    assert opaque(level1) < WINDOW, "the low fill must be a strict subset of the window"

    OUT.mkdir(parents=True, exist_ok=True)
    written = []
    for name, shell in shells.items():
        target = OUT / f"portable_fluid_tank_{name}.png"
        shell.save(target)
        written.append(target)
    for name, image in (("fluid_empty", empty), ("fluid_level1", level1), ("fluid_level2", master)):
        target = OUT / f"portable_fluid_tank_{name}.png"
        image.save(target)
        written.append(target)

    for target in written:
        assert Image.open(target).size == (16, 16)
        print(target.relative_to(ROOT))


if __name__ == "__main__":
    main()
