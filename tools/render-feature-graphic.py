"""Render the Play Store feature graphic (1024x500) using Pillow.

Output: tools/store-assets/feature-graphic-1024x500.png

Design: dark blue diagonal gradient background, "RidePanel" wordmark
right-aligned with a tagline and subtitle in warm yellow accent.
Pillow-only (no cairosvg / Inkscape dependency).
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


WIDTH = 1024
HEIGHT = 500
GRADIENT_TOP_LEFT = (11, 25, 41)        # #0B1929
GRADIENT_BOTTOM_RIGHT = (30, 58, 95)    # #1E3A5F
ACCENT = (255, 184, 0)                  # warm yellow for subtitle
WHITE = (240, 240, 240)


def diagonal_gradient(
    width: int,
    height: int,
    c0: tuple[int, int, int],
    c1: tuple[int, int, int],
) -> Image.Image:
    img = Image.new("RGB", (width, height))
    pixels = img.load()
    max_dist = (width - 1) + (height - 1)
    for y in range(height):
        for x in range(width):
            t = (x + y) / max_dist
            r = round(c0[0] + (c1[0] - c0[0]) * t)
            g = round(c0[1] + (c1[1] - c0[1]) * t)
            b = round(c0[2] + (c1[2] - c0[2]) * t)
            pixels[x, y] = (r, g, b)
    return img


def find_font(candidates: list[str], size: int) -> ImageFont.FreeTypeFont:
    for path in candidates:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def main() -> None:
    out_path = (
        Path(__file__).parent
        / "store-assets"
        / "feature-graphic-1024x500.png"
    )
    out_path.parent.mkdir(parents=True, exist_ok=True)

    img = diagonal_gradient(
        WIDTH, HEIGHT, GRADIENT_TOP_LEFT, GRADIENT_BOTTOM_RIGHT
    )
    draw = ImageDraw.Draw(img)

    title_font = find_font([
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    ], 96)
    tagline_font = find_font([
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/System/Library/Fonts/Supplemental/Arial.ttf",
    ], 36)
    subtitle_font = find_font([
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    ], 28)

    title = "RidePanel"
    tagline = "Mirror your phone to your motorcycle"
    subtitle = "Free  •  Open source  •  No cloud"

    title_w = draw.textlength(title, font=title_font)
    tagline_w = draw.textlength(tagline, font=tagline_font)
    subtitle_w = draw.textlength(subtitle, font=subtitle_font)

    # Right-aligned column at x = WIDTH - 60
    right_x = WIDTH - 60
    draw.text(
        (right_x - title_w, 130),
        title,
        fill=WHITE,
        font=title_font,
    )
    draw.text(
        (right_x - tagline_w, 260),
        tagline,
        fill=WHITE,
        font=tagline_font,
    )
    draw.text(
        (right_x - subtitle_w, 330),
        subtitle,
        fill=ACCENT,
        font=subtitle_font,
    )

    img.save(out_path, "PNG", optimize=True)
    print(f"wrote {out_path} ({img.size[0]}x{img.size[1]})")


if __name__ == "__main__":
    main()
