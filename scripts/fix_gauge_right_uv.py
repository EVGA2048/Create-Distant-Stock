#!/usr/bin/env python3
"""Keep the request desk's right/east material oriented correctly."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "src/main/resources/assets/distantstock/models/block/gauge.json"

# Explicit targets make this safe to run repeatedly instead of toggling the UVs.
EAST_ROTATIONS = {
    0: 180, 1: 180, 2: 180, 3: 180, 4: 180, 5: 180,
    6: 270, 8: 180, 9: 180, 10: 180, 11: 180, 12: 180,
    13: 180, 15: 180, 16: 180, 17: 270,
}


def main():
    model = json.loads(MODEL.read_text())
    for index, rotation in EAST_ROTATIONS.items():
        face = model["elements"][index]["faces"].get("east")
        if face is not None:
            face["rotation"] = rotation
    MODEL.write_text(json.dumps(model, indent=2) + "\n")
    print("Rotated the request desk's east-side UVs by 180 degrees.")


if __name__ == "__main__":
    main()
