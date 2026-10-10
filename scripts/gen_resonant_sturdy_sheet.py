#!/usr/bin/env python3
"""Recolour Create's two sheet textures into the resonant quartz palette.

The whole point of this one is that it draws nothing. Every contour, groove and highlight stays
exactly where Create put it, and only the colour behind each pixel changes; that is why the results
sit correctly next to Create's own items, and why hand-drawing the same shape (which was tried twice
on other items and read as a leaf both times) is not worth attempting.

Two sprites come out of this:

  * the finished plate, from Create's sturdy sheet. Its nine greys are a metal value ramp and the
    mapping has to stay order preserving -- the moment two tones cross over the shading inverts and
    the plate stops reading as a plate. That is asserted below rather than assumed.
  * the transitional plate, from Create's *unprocessed* obsidian sheet. Same silhouette and the same
    nine greys, so it takes the same ramp; but it also carries four warm tones where the lava sits in
    the sprite, and those are a second material rather than more shading.

Those four are the interesting decision. They cannot join the grey ramp: the ramp's darkest step is
already at the bottom of the range and its lightest is nearly white, so there is no room to slot four
more tones in without either flattening the warm patch into the plate or pushing it off the top. They
are not shading anyway -- they are what is *inside* the sheet -- so they get their own family, four
steps of molten amethyst purple, which is one of the things the recipe actually puts in there. The
grey ramp keeps its ordering; the accent is checked only against itself.

Source is Create 6.0.10's assets/create/textures/item/, located through the shared concept helper.
Design notes and the exploratory copy live in docs/design/concepts/resonant-sturdy-sheet-v1/.
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/concepts"))
from create_context import CreateReferences  # noqa: E402

ITEM = ROOT / "src/main/resources/assets/distantstock/textures/item"
PREVIEW = ROOT / "build/art/resonant_sturdy_sheet_preview.png"

# Create's nine grey tones, darkest first, and what each becomes. The destinations are the project's
# own ether quartz ramp -- polished_ether_quartz.png's colours -- with two extra steps interpolated
# to bridge gaps the source has and the ramp does not. Nothing is invented beyond those two blends.
TONES = {
    (0x03, 0x05, 0x0C): (0x35, 0x53, 0x6E),
    (0x0B, 0x0D, 0x15): (0x42, 0x66, 0x82),
    (0x19, 0x1B, 0x24): (0x50, 0x7B, 0x9B),
    (0x2A, 0x2A, 0x37): (0x69, 0x9D, 0xBC),
    (0x38, 0x38, 0x46): (0x7B, 0xAC, 0xC9),
    (0x43, 0x42, 0x51): (0x8D, 0xBB, 0xD6),
    (0x52, 0x51, 0x61): (0xB6, 0xD8, 0xE8),
    (0x70, 0x6E, 0x82): (0xD9, 0xEB, 0xEE),
    (0x8E, 0x8D, 0xA1): (0xF3, 0xF6, 0xE9),
}

# The unprocessed sheet's four hot tones, darkest first. Kept as their own low-to-high ramp so the
# patch still shades, but not ordered against the blues above.
ACCENT = {
    (0x7F, 0x3E, 0x2C): (0x5A, 0x3B, 0x85),
    (0xCC, 0x46, 0x28): (0x8B, 0x5F, 0xC4),
    (0xE3, 0x8C, 0x3F): (0xB9, 0x8C, 0xE8),
    (0xE4, 0xD2, 0x5C): (0xE3, 0xCB, 0xF8),
}

LUMA = (0.2126, 0.7152, 0.0722)


def luma(rgb):
    return sum(w * c for w, c in zip(LUMA, rgb))


def ascending(table, what):
    scratch = sorted(table, key=luma)
    for dark, light in zip(scratch, scratch[1:]):
        assert luma(table[dark]) < luma(table[light]), \
            f"{what} tones crossed over at {dark} -> {light}; the shading would invert"


def recolour(source, table, what):
    """One source sprite through one tone table, with the silhouette left byte-identical."""
    unknown = {p[:3] for p in source.getdata() if p[3] and p[:3] not in table}
    assert not unknown, f"Create reshaded {what}; unmapped tones: {sorted(unknown)}"
    out = Image.new("RGBA", source.size)
    out.putdata([(*table[p[:3]], p[3]) if p[3] else p for p in source.getdata()])
    assert out.getchannel("A").tobytes() == source.getchannel("A").tobytes(), \
        f"{what}: silhouette changed"
    return out


def main() -> None:
    create = CreateReferences()
    ascending(TONES, "plate")
    ascending(ACCENT, "accent")

    plate = recolour(create.texture("create:item/sturdy_sheet").convert("RGBA"), TONES, "sturdy sheet")
    # Same nine greys, so it takes the same ramp, plus the hot patch on the accent family.
    raw = recolour(create.texture("create:item/unprocessed_obsidian_sheet").convert("RGBA"),
                   {**TONES, **ACCENT}, "unprocessed obsidian sheet")

    assert plate.size == raw.size == (16, 16)
    ITEM.mkdir(parents=True, exist_ok=True)
    for name, image in (("resonant_sturdy_sheet", plate), ("incomplete_resonant_sheet", raw)):
        target = ITEM / f"{name}.png"
        image.save(target)
        print(target.relative_to(ROOT))

    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGBA", (3 * 16 * 8 + 4 * 12, 16 * 8 + 24), "#c6c6c6")
    for i, tile in enumerate((create.texture("create:item/sturdy_sheet").convert("RGBA"),
                              raw, plate)):
        sheet.alpha_composite(tile.resize((16 * 8, 16 * 8), Image.Resampling.NEAREST),
                              (12 + i * (16 * 8 + 12), 12))
    sheet.save(PREVIEW)
    print(PREVIEW.relative_to(ROOT))


if __name__ == "__main__":
    main()
