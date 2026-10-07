"""Continue casing frame rails through connected tile boundaries."""
from PIL import Image


def continue_frame(tile, mask, source):
    """Replace single-block end caps only where an outer rail continues.

    Bits: left/right/up/down, then the four diagonals. Existing concave corners
    remain untouched: those belong to two connected edges, not an outer rail.
    """
    result = tile.copy()
    left, right, up, down = (bool(mask & (1 << bit)) for bit in range(4))
    for connected, start in ((up, 0), (down, 13)):
        if connected:
            continue
        for x in range(16):
            if (x < 3 and left) or (x >= 13 and right):
                for y in range(start, start + 3):
                    result.putpixel((x, y), source.getpixel((3 + (x - 3) % 10, y)))
    for connected, start in ((left, 0), (right, 13)):
        if connected:
            continue
        for y in range(16):
            if (y < 3 and up) or (y >= 13 and down):
                for x in range(start, start + 3):
                    result.putpixel((x, y), source.getpixel((x, 3 + (y - 3) % 10)))
    return result


def repair_atlas(atlas):
    if atlas.size != (256, 256):
        raise ValueError("Expected a 16-by-16 atlas of 16px casing tiles")
    result = atlas.copy()
    source = atlas.crop((0, 0, 16, 16))
    for mask in range(256):
        x, y = mask % 16 * 16, mask // 16 * 16
        result.paste(continue_frame(atlas.crop((x, y, x + 16, y + 16)), mask, source), (x, y))
    return result


if __name__ == "__main__":
    from pathlib import Path
    root = Path(__file__).resolve().parents[1]
    preview = root / "build/art/casing-ct-fix"
    preview.mkdir(parents=True, exist_ok=True)
    production = root / "src/main/resources/assets/distantstock/textures/block/tower"
    before = Image.open(production / "ct_active.png").convert("RGBA")
    after = repair_atlas(before)
    sheet = Image.new("RGBA", (640, 352), "#e9e6dc")
    for row, atlas in enumerate((before, after)):
        strip = Image.new("RGBA", (64, 16), "#bc8a41")
        for index, mask in enumerate((2, 3, 3, 1)):
            strip.alpha_composite(atlas.crop((mask*16, 0, mask*16+16, 16)), (index*16, 0))
        sheet.paste(strip.resize((640, 160), Image.Resampling.NEAREST), (0, row*192))
    sheet.save(preview / "before-after.png")
    for directory in (production, root / "docs/design/tower-art-handoff-2026-09-15/casing/textures"):
        for name in ("ct_active.png", "ct_inactive.png"):
            path = directory / name
            atlas = Image.open(path).convert("RGBA")
            repair_atlas(atlas).save(path)
            print(path.relative_to(root))
