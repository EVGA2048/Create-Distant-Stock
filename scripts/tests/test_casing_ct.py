import sys
import unittest
from pathlib import Path
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from casing_ct import continue_frame, repair_atlas


class CasingFrameTest(unittest.TestCase):
    def setUp(self):
        self.source = Image.new("RGBA", (16, 16))
        for y in range(16):
            for x in range(16):
                self.source.putpixel((x, y), (x, y, x+y, 255))

    def test_horizontal_rail_has_no_end_caps_at_internal_join(self):
        left = continue_frame(self.source, 2, self.source)
        right = continue_frame(self.source, 1, self.source)
        for y in (0, 1, 2, 13, 14, 15):
            self.assertEqual(left.getpixel((15, y)), self.source.getpixel((5, y)))
            self.assertEqual(right.getpixel((0, y)), self.source.getpixel((10, y)))
            self.assertEqual(left.getpixel((0, y)), self.source.getpixel((0, y)))
            self.assertEqual(right.getpixel((15, y)), self.source.getpixel((15, y)))

    def test_vertical_rail_has_no_end_caps_at_internal_join(self):
        top = continue_frame(self.source, 8, self.source)
        bottom = continue_frame(self.source, 4, self.source)
        for x in (0, 1, 2, 13, 14, 15):
            self.assertEqual(top.getpixel((x, 15)), self.source.getpixel((x, 5)))
            self.assertEqual(bottom.getpixel((x, 0)), self.source.getpixel((x, 10)))

    def test_inner_corners_single_tile_and_panel_are_preserved(self):
        for mask in (0, 15, 255):
            self.assertEqual(continue_frame(self.source, mask, self.source).tobytes(), self.source.tobytes())
        for mask in range(256):
            tile = continue_frame(self.source, mask, self.source)
            self.assertEqual(tile.crop((3, 3, 13, 13)).tobytes(), self.source.crop((3, 3, 13, 13)).tobytes())
            for a,b,box in ((1,4,(0,0,3,3)),(2,4,(13,0,16,3)),(1,8,(0,13,3,16)),(2,8,(13,13,16,16))):
                if mask & a and mask & b:
                    self.assertEqual(tile.crop(box).tobytes(), self.source.crop(box).tobytes())

    def test_regeneration_is_idempotent_and_keeps_window_alpha(self):
        root = Path(__file__).resolve().parents[2]
        for name in ("ct_active.png", "ct_inactive.png"):
            im = Image.open(root / "src/main/resources/assets/distantstock/textures/block/tower" / name).convert("RGBA")
            fixed = repair_atlas(im)
            self.assertEqual(fixed.tobytes(), repair_atlas(fixed).tobytes())
            self.assertEqual(im.getchannel("A").tobytes(), fixed.getchannel("A").tobytes())
            self.assertEqual(im.crop((0,0,16,16)).tobytes(), fixed.crop((0,0,16,16)).tobytes())


if __name__ == "__main__":
    unittest.main()
