"""Native 16x16 three-quarter-view aether prism optical assembly."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
TEX = HERE / "assets/distantstock/textures/item"
MODELS = HERE / "assets/distantstock/models/item"
TEX.mkdir(parents=True, exist_ok=True)
MODELS.mkdir(parents=True, exist_ok=True)


def item_sprite():
    im = Image.new("RGBA", (16, 16))
    d = ImageDraw.Draw(im)

    # Two triangular ends offset diagonally establish an unmistakable 3D prism.
    front = ((2, 10), (6, 13), (6, 7))
    rear = ((8, 6), (12, 9), (12, 3))

    # Slim calibration rail sits behind the glass and remains partially visible.
    d.line([(3, 12), (10, 7)], fill=(54, 61, 60, 255), width=2)
    d.line([(4, 11), (10, 7)], fill=(127, 133, 125, 255))
    d.point((5, 11), fill=(203, 170, 76, 255))
    d.point((10, 7), fill=(113, 197, 218, 255))

    # Three long optical faces. Transparency varies by face, like tinted glass.
    d.polygon([front[2], front[0], rear[0], rear[2]], fill=(204, 238, 247, 164))
    d.polygon([front[0], front[1], rear[1], rear[0]], fill=(135, 205, 228, 136))
    d.polygon([front[1], front[2], rear[2], rear[1]], fill=(78, 148, 182, 207))

    # Rear triangular cap and one offset internal ridge visible through the glass.
    d.polygon(rear, fill=(166, 219, 236, 184))
    d.line([(8, 6), (12, 3), (12, 9), (8, 6)], fill=(78, 143, 171, 229))
    d.line([(5, 9), (10, 5)], fill=(232, 249, 251, 142))
    d.line([(5, 11), (10, 7)], fill=(105, 181, 211, 120))

    # Long ridges: bright top edge, cool middle edge and deep lower edge.
    d.line([front[2], rear[2]], fill=(239, 251, 252, 248))
    d.line([front[0], rear[0]], fill=(156, 220, 237, 232))
    d.line([front[1], rear[1]], fill=(50, 104, 134, 246))
    d.line([front[2], front[0], front[1], front[2]], fill=(109, 178, 204, 239))
    d.point((4, 8), fill=(250, 255, 255, 255))
    d.point((7, 6), fill=(225, 247, 251, 247))

    # Brass corner shoes clamp the prism without forming a globe-like ring.
    dark = (96, 64, 30, 255)
    brass = (184, 130, 50, 255)
    light = (239, 196, 86, 255)
    # Tiny L-shaped shoes at four vertices; no large blocks obscure the glass.
    for points in (
        [(1, 9), (2, 9), (2, 10), (1, 10)],
        [(5, 13), (6, 13), (6, 14), (5, 14)],
        [(5, 6), (6, 6), (6, 7)],
        [(11, 2), (12, 2), (12, 3)],
    ):
        d.polygon(points, fill=dark)
    for x, y in ((2, 9), (6, 13), (6, 6), (12, 2)):
        d.point((x, y), fill=light)
    d.point((2, 10), fill=brass)
    d.point((5, 14), fill=brass)
    d.point((5, 7), fill=brass)
    d.point((11, 3), fill=brass)
    # One adjustment screw on the rear end gives an engineered, finished feel.
    d.rectangle((12, 8, 13, 9), fill=dark)
    d.point((12, 8), fill=light)
    return im


item = item_sprite()
item.save(TEX / "ether_calibration_prism.png")
(MODELS / "ether_calibration_prism.json").write_text(json.dumps({
    "parent": "minecraft:item/generated",
    "render_type": "minecraft:translucent",
    "textures": {"layer0": "distantstock:item/ether_calibration_prism"}
}, indent=2) + "\n")

alpha = set(item.getchannel("A").getdata())
assert item.size == (16, 16) and 0 in alpha and any(0 < a < 255 for a in alpha)

font = "/System/Library/Fonts/STHeiti Medium.ttc"
sheet = Image.new("RGBA", (800, 470), "#e9e6dc")
draw = ImageDraw.Draw(sheet)
draw.text((30, 18), "以太校准棱镜 · 立体视角", font=ImageFont.truetype(font, 27), fill="#36474a")
draw.text((31, 57), "三角端面 / 纵向棱线 / 独立角夹具", font=ImageFont.truetype(font, 17), fill="#6d7770")
for index, (background, label) in enumerate((("#d6ddda", "浅色背景"), ("#304650", "深色背景"))):
    panel = Image.new("RGBA", (360, 320), background)
    panel.alpha_composite(item.resize((288, 288), Image.Resampling.NEAREST), (36, 8))
    panel.alpha_composite(item.resize((32, 32), Image.Resampling.NEAREST), (316, 276))
    sheet.alpha_composite(panel, (30 + index * 380, 96))
    draw.text((154 + index * 380, 428), label, font=ImageFont.truetype(font, 18), fill="#526368")
sheet.save(HERE / "preview.png")
(HERE / "checks.json").write_text(json.dumps({
    "size": [16, 16], "alpha_values": sorted(alpha), "production_assets_modified": False
}, indent=2) + "\n")
print(HERE / "preview.png")
