import tempfile
import unittest
from pathlib import Path

from PIL import Image

from tools.generate_launcher_assets import (
    ADAPTIVE_SIZES,
    LEGACY_SIZES,
    NAVY,
    build_flat_icon,
    extract_flat_mark,
    generate_assets,
)


class LauncherAssetGeneratorTest(unittest.TestCase):
    def make_source(self) -> Image.Image:
        source = Image.new("RGB", (1024, 1024), (2, 31, 42))
        for x in range(250, 775):
            for y in range(220, 805):
                if (x - 510) ** 2 + (y - 510) ** 2 < 260**2:
                    source.putpixel((x, y), (12, 224, 165))
        return source

    def test_flat_icon_uses_exact_navy_background(self) -> None:
        icon = build_flat_icon(self.make_source())

        self.assertEqual((1024, 1024), icon.size)
        self.assertEqual((*NAVY, 255), icon.getpixel((0, 0)))
        self.assertNotEqual((*NAVY, 255), icon.getpixel((510, 510)))

    def test_extracted_mark_has_transparent_corners(self) -> None:
        mark = extract_flat_mark(self.make_source())

        self.assertEqual(0, mark.getpixel((0, 0))[3])
        self.assertGreater(mark.getpixel((510, 510))[3], 240)

    def test_generates_all_density_assets_at_expected_sizes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source_path = root / "source.png"
            self.make_source().save(source_path)

            generate_assets(source_path, root / "res")

            for density, size in LEGACY_SIZES.items():
                for name in ("ic_launcher.webp", "ic_launcher_round.webp"):
                    with Image.open(
                        root / "res" / f"mipmap-{density}" / name
                    ) as image:
                        self.assertEqual((size, size), image.size)
                        if name == "ic_launcher_round.webp":
                            self.assertEqual(
                                0,
                                image.convert("RGBA").getpixel((0, 0))[3],
                            )

            for density, size in ADAPTIVE_SIZES.items():
                with Image.open(
                    root
                    / "res"
                    / f"mipmap-{density}"
                    / "ic_launcher_foreground.webp"
                ) as image:
                    rgba = image.convert("RGBA")
                    self.assertEqual((size, size), rgba.size)
                    self.assertEqual(0, rgba.getpixel((0, 0))[3])


if __name__ == "__main__":
    unittest.main()
