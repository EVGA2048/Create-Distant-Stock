#!/usr/bin/env python3
"""Ship the three fluid tanks' artwork and their item models.

The three shells exist as accepted concept art under `docs/design/concepts/`; this script copies them
into the mod and asserts everything the code assumes about them, because the code does assume things
and a redrawn shell would break them silently:

  * the window is exactly 24 pixels at x 6..9, y 6..11 -- that rectangle is written into
    `FluidTankItemRenderer` as the place the fluid is drawn, so if the art moves, the fluid would be
    painted over the metal or leave a gap;
  * every shell is transparent over exactly that window, at alphas 24/95/200, which is what makes
    the fluid visible underneath it at all;
  * the silhouette outside the window is the same on all three, so a tank reads as one object at
    three sizes rather than three unrelated icons.

The models are generated here too, rather than hand-written, for one reason: all three are the same
`builtin/entity` model apart from the particle texture, and the display block has to be copied
verbatim from vanilla's `item/generated` (`ItemTransforms` fills in nothing that a child model does
not override, so a flat item that forgets it would be held at the wrong angle everywhere).

The tanks are drawn by a custom renderer, not by a baked model: see `FluidTankItemRenderer`. That is
what makes the icon show the fluid's own texture, which no model JSON can select per stack. The
`particle` texture is still declared so a dropped tank breaks into bits of itself.

Outputs, all 16x16, into src/main/resources/assets/distantstock/:
  textures/item/portable_fluid_tank_copper.png    models/item/copper_fluid_tank.json
  textures/item/portable_fluid_tank_sturdy.png    models/item/sturdy_fluid_tank.json
  textures/item/portable_fluid_tank_resonant.png  models/item/resonant_canister.json
"""
from __future__ import annotations

import json
import pathlib

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[1]
CONCEPTS = ROOT / "docs/design/concepts"
TEXTURES = ROOT / "src/main/resources/assets/distantstock/textures/item"
MODELS = ROOT / "src/main/resources/assets/distantstock/models/item"

# tier id -> (concept folder, concept stem, item model file). The tier ids are the same strings the
# item enum and the shell texture names use, so a rename here is a rename everywhere or a failed
# assert. The item model file names are the item registry paths, which cannot be moved.
CONCEPT = {
    "copper": ("portable-fluid-tank-v4", "portable_fluid_tank", "copper_fluid_tank"),
    "sturdy": ("reinforced-portable-fluid-tank-v1", "reinforced_portable_fluid_tank",
               "sturdy_fluid_tank"),
    "resonant": ("resonant-portable-fluid-tank-v1", "resonant_portable_fluid_tank",
                 "resonant_canister"),
}

# Where the window sits in the shell, in texture pixels. FluidTankItemRenderer's WIN_* constants are
# this rectangle; the assert below is the only thing keeping the two in step.
WINDOW = {(x, y) for y in range(6, 12) for x in range(6, 10)}

# Vanilla's item/generated, copied: the transforms a flat item is held and dropped with. A
# builtin/entity model inherits none of them, so they are written out here.
FLAT_DISPLAY = {
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1],
                              "scale": [0.55, 0.55, 0.55]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13],
                              "scale": [0.68, 0.68, 0.68]},
    "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
}


def concept(name: str) -> Image.Image:
    folder, stem, _ = CONCEPT[name]
    return Image.open(CONCEPTS / folder / "assets/distantstock/textures/item" / f"{stem}.png").convert("RGBA")


def opaque(image: Image.Image) -> set[tuple[int, int]]:
    return {(x, y) for y in range(16) for x in range(16) if image.getpixel((x, y))[3] > 0}


def main() -> None:
    shells = {name: concept(name) for name in CONCEPT}

    for name, shell in shells.items():
        assert shell.size == (16, 16), f"{name}: shell is {shell.size}"
        inside = {p for p in opaque(shell) if p in WINDOW}
        assert inside == WINDOW, f"{name}: the shell is opaque over {sorted(WINDOW - inside)}"
        alphas = {shell.getpixel(p)[3] for p in WINDOW}
        assert alphas == {24, 95, 200}, \
            f"{name}: window alphas are {sorted(alphas)}; the fluid would be hidden or hazed over"
        assert opaque(shell) - WINDOW == opaque(shells["copper"]) - WINDOW, \
            f"{name}: the silhouette outside the window differs from the copper tank's"

    TEXTURES.mkdir(parents=True, exist_ok=True)
    MODELS.mkdir(parents=True, exist_ok=True)
    written = []
    for name, shell in shells.items():
        target = TEXTURES / f"portable_fluid_tank_{name}.png"
        shell.save(target)
        written.append(target)

        model = MODELS / f"{CONCEPT[name][2]}.json"
        model.write_text(json.dumps({
            "parent": "builtin/entity",
            "gui_light": "front",
            "textures": {"particle": f"distantstock:item/portable_fluid_tank_{name}"},
            "display": FLAT_DISPLAY,
        }, indent=2) + "\n", encoding="utf-8")
        written.append(model)

    for target in written:
        if target.suffix == ".png":
            assert Image.open(target).size == (16, 16)
        else:
            assert json.loads(target.read_text(encoding="utf-8"))["parent"] == "builtin/entity"
        print(target.relative_to(ROOT))


if __name__ == "__main__":
    main()
