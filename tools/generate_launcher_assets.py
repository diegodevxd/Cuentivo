"""Generate Cuentivo launcher assets from the approved source illustration."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


NAVY = (0, 29, 38)
MINT = (8, 221, 165)
LEGACY_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
ADAPTIVE_SIZES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}


def _square_source(source: Image.Image) -> Image.Image:
    rgb = source.convert("RGB")
    side = min(rgb.size)
    left = (rgb.width - side) // 2
    top = (rgb.height - side) // 2
    return rgb.crop((left, top, left + side, top + side)).resize(
        (1024, 1024), Image.Resampling.LANCZOS
    )


def extract_flat_mark(source: Image.Image) -> Image.Image:
    """Extract the mint symbol and normalize it to Cuentivo's brand color."""
    square = _square_source(source)
    alpha_values = []
    for red, green, blue in square.get_flattened_data():
        if green < 65 or green - blue < 18 or green - red < 28:
            alpha_values.append(0)
            continue
        chroma = green - blue
        brightness = green - 65
        alpha_values.append(min(255, max(0, chroma * 6, brightness * 2)))

    alpha = Image.new("L", square.size)
    alpha.putdata(alpha_values)
    alpha = alpha.filter(ImageFilter.GaussianBlur(radius=0.65))

    mark = Image.new("RGBA", square.size, (*MINT, 0))
    mark.putalpha(alpha)
    return mark


def build_flat_icon(source: Image.Image) -> Image.Image:
    icon = Image.new("RGBA", (1024, 1024), (*NAVY, 255))
    icon.alpha_composite(extract_flat_mark(source))
    return icon


def _round_icon(icon: Image.Image) -> Image.Image:
    rounded = icon.copy()
    mask = Image.new("L", icon.size, 0)
    ImageDraw.Draw(mask).ellipse(
        (1, 1, icon.width - 2, icon.height - 2),
        fill=255,
    )
    rounded.putalpha(mask)
    return rounded


def _adaptive_foreground(mark: Image.Image, size: int) -> Image.Image:
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mark_size = round(size * 0.88)
    scaled = mark.resize((mark_size, mark_size), Image.Resampling.LANCZOS)
    offset = (size - mark_size) // 2
    layer.alpha_composite(scaled, (offset, offset))
    return layer


def generate_assets(source_path: Path, res_directory: Path) -> None:
    source = Image.open(source_path)
    flat_icon = build_flat_icon(source)
    round_icon = _round_icon(flat_icon)
    mark = extract_flat_mark(source)

    for density, size in LEGACY_SIZES.items():
        output_directory = res_directory / f"mipmap-{density}"
        output_directory.mkdir(parents=True, exist_ok=True)
        flat_icon.resize((size, size), Image.Resampling.LANCZOS).save(
            output_directory / "ic_launcher.webp",
            "WEBP",
            lossless=True,
        )
        round_icon.resize((size, size), Image.Resampling.LANCZOS).save(
            output_directory / "ic_launcher_round.webp",
            "WEBP",
            lossless=True,
        )

    for density, size in ADAPTIVE_SIZES.items():
        output_directory = res_directory / f"mipmap-{density}"
        output_directory.mkdir(parents=True, exist_ok=True)
        foreground = _adaptive_foreground(mark, size)
        foreground.save(
            output_directory / "ic_launcher_foreground.webp",
            "WEBP",
            lossless=True,
        )

    verify_assets(res_directory)


def verify_assets(res_directory: Path) -> None:
    for density, size in LEGACY_SIZES.items():
        for filename in ("ic_launcher.webp", "ic_launcher_round.webp"):
            path = res_directory / f"mipmap-{density}" / filename
            with Image.open(path) as image:
                if image.size != (size, size):
                    raise ValueError(f"{path} has unexpected dimensions {image.size}")

    for density, size in ADAPTIVE_SIZES.items():
        path = (
            res_directory
            / f"mipmap-{density}"
            / "ic_launcher_foreground.webp"
        )
        with Image.open(path) as image:
            rgba = image.convert("RGBA")
            if rgba.size != (size, size):
                raise ValueError(f"{path} has unexpected dimensions {rgba.size}")
            if rgba.getpixel((0, 0))[3] != 0:
                raise ValueError(f"{path} must have transparent corners")
            if rgba.getbbox() is None:
                raise ValueError(f"{path} has no visible foreground")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--source",
        type=Path,
        default=Path("design/icon/cuentivo-launcher-source.png"),
    )
    parser.add_argument(
        "--res-directory",
        type=Path,
        default=Path("app/src/main/res"),
    )
    parser.add_argument("--verify-only", action="store_true")
    arguments = parser.parse_args()

    if arguments.verify_only:
        verify_assets(arguments.res_directory)
    else:
        generate_assets(arguments.source, arguments.res_directory)

    print("Launcher assets generated and validated")


if __name__ == "__main__":
    main()
