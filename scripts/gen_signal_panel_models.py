#!/usr/bin/env python3
"""Generate the small inheritance-only model matrix for signal panel lamps."""
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "src/main/resources/assets/distantstock/models/block/signal_panel"
ITEMS = ROOT / "src/main/resources/assets/distantstock/models/item"
TEXTURES = ROOT / "src/main/resources/assets/distantstock/textures/block"
CONCEPT = ROOT / "src/main/resources/assets/distantstock_concept/textures/block/brass_signal_lamp_off.png"


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def main():
    colors = ("cyan", "orange", "red", "green", "white")
    for color in colors:
        for state in ("off", "on"):
            write(MODELS / f"andesite_{color}_{state}.json", {
                "parent": "distantstock:block/signal_panel/andesite_base",
                "textures": {"lamp": f"distantstock:block/indicator_lamp_{color}_{state}"},
            })
            lamp_texture = (f"distantstock:block/indicator_lamp_{color}_on"
                            if state == "on" else "distantstock:block/signal_lamp_brass_off")
            write(MODELS / f"brass_{color}_{state}.json", {
                "parent": "distantstock:block/signal_panel/brass_base",
                "textures": {"lamp": lamp_texture},
            })

    write(ITEMS / "brass_signal_lamp.json", {
        "parent": "distantstock:block/signal_panel/brass_white_off",
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": [0, 3, 0], "scale": [1.2, 1.2, 1.2]},
            "fixed": {"rotation": [-90, 0, 0], "translation": [0, 0, -4], "scale": [1.2, 1.2, 1.2]}
        }
    })
    shutil.copyfile(CONCEPT, TEXTURES / "signal_lamp_brass_off.png")


if __name__ == "__main__":
    main()
