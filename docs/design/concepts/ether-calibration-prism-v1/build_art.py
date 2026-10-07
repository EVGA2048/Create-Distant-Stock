"""Draw a native 16x16 Create-style aether calibration prism item."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
TEX = HERE / "assets/distantstock/textures/item"
MODELS = HERE / "assets/distantstock/models/item"
TEX.mkdir(parents=True, exist_ok=True)
MODELS.mkdir(parents=True, exist_ok=True)


def draw_item():
    im = Image.new("RGBA", (16, 16))
    d = ImageDraw.Draw(im)

    # Rear brass yoke: broken rather than circular, so the glass stays dominant.
    dark_brass = (105, 70, 29, 255)
    brass = (181, 126, 47, 255)
    light_brass = (239, 195, 86, 255)
    d.line([(3, 9), (3, 5), (5, 3)], fill=dark_brass, width=2)
    d.line([(10, 2), (12, 4), (13, 8)], fill=dark_brass, width=2)
    d.line([(3, 5), (5, 3)], fill=light_brass)
    d.line([(10, 2), (12, 4)], fill=light_brass)
    d.point((2, 8), fill=brass)
    d.point((13, 7), fill=brass)

    # Thick optical prism: three large faces and a visible rear ridge.
    d.polygon([(5, 2), (9, 2), (12, 5), (10, 10), (6, 12), (3, 8), (3, 5)],
              fill=(139, 207, 229, 151))
    d.polygon([(5, 2), (9, 2), (7, 6), (3, 5)], fill=(211, 241, 248, 193))
    d.polygon([(9, 2), (12, 5), (10, 10), (7, 6)], fill=(87, 157, 190, 190))
    d.polygon([(3, 5), (7, 6), (10, 10), (6, 12), (3, 8)],
              fill=(161, 219, 237, 125))
    d.line([(5, 2), (9, 2), (12, 5), (10, 10), (6, 12), (3, 8), (3, 5), (5, 2)],
           fill=(80, 139, 165, 235))
    d.line([(3, 5), (7, 6), (9, 2)], fill=(232, 249, 252, 239))
    d.line([(7, 6), (10, 10)], fill=(113, 183, 210, 177))
    d.line([(6, 10), (8, 8)], fill=(199, 235, 245, 121))
    d.point((4, 4), fill=(249, 254, 255, 255))
    d.point((5, 3), fill=(238, 251, 253, 255))
    d.point((4, 8), fill=(217, 243, 249, 222))

    # Three brass retaining claws overlap the prism edge.
    for points in (
        [(4, 2), (5, 2), (5, 4), (4, 4)],
        [(11, 4), (13, 4), (13, 5), (11, 5)],
        [(8, 10), (10, 10), (10, 12), (9, 12)],
    ):
        d.polygon(points, fill=dark_brass)
    d.line([(4, 2), (5, 2), (5, 3)], fill=light_brass)
    d.line([(11, 4), (12, 4)], fill=light_brass)
    d.line([(9, 10), (10, 10)], fill=light_brass)
    d.point((4, 3), fill=brass)
    d.point((12, 5), fill=brass)
    d.point((9, 11), fill=brass)

    # Compact andesite adjustment foot, subordinate to the optical element.
    d.polygon([(5, 12), (10, 12), (12, 14), (4, 14), (3, 13)], fill=(58, 64, 63, 255))
    d.line([(5, 12), (10, 12), (11, 13), (4, 13)], fill=(132, 136, 126, 255))
    d.line([(4, 14), (12, 14)], fill=(39, 44, 43, 255))
    d.line([(7, 12), (7, 13)], fill=dark_brass)
    d.point((7, 12), fill=light_brass)
    d.point((10, 13), fill=(115, 196, 216, 255))
    return im


item = draw_item()
item.save(TEX / "ether_calibration_prism.png")
(MODELS / "ether_calibration_prism.json").write_text(json.dumps({
    "parent": "minecraft:item/generated",
    "render_type": "minecraft:translucent",
    "textures": {"layer0": "distantstock:item/ether_calibration_prism"}
}, indent=2) + "\n")

assert item.size == (16, 16)
alpha = set(item.getchannel("A").getdata())
assert 0 in alpha and any(0 < value < 255 for value in alpha)

font = "/System/Library/Fonts/STHeiti Medium.ttc"
sheet = Image.new("RGBA", (800, 470), "#e9e6dc")
draw = ImageDraw.Draw(sheet)
draw.text((30, 18), "以太校准棱镜", font=ImageFont.truetype(font, 28), fill="#36474a")
draw.text((31, 57), "透明棱镜 / 黄铜固定爪 / 安山调节座", font=ImageFont.truetype(font, 17), fill="#6d7770")
for index, (background, label) in enumerate((("#d6ddda", "浅色背景"), ("#304650", "深色背景"))):
    panel = Image.new("RGBA", (360, 320), background)
    panel.alpha_composite(item.resize((288, 288), Image.Resampling.NEAREST), (36, 8))
    panel.alpha_composite(item.resize((32, 32), Image.Resampling.NEAREST), (316, 276))
    sheet.alpha_composite(panel, (30 + index * 380, 96))
    draw.text((154 + index * 380, 428), label, font=ImageFont.truetype(font, 18), fill="#526368")
sheet.save(HERE / "preview.png")

(HERE / "checks.json").write_text(json.dumps({
    "size": [16, 16],
    "alpha_values": sorted(alpha),
    "production_assets_modified": False
}, indent=2) + "\n")
print(HERE / "preview.png")
