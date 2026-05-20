"""Process raw RidePanel UI screenshots into Play Store listing assets.

Inputs (absolute paths into the carbit umbrella):
    Screenshot_20260520-084921.png  → step 1
    Screenshot_20260520-084929.png  → step 2
    Screenshot_20260520-084939.png  → step 3
    Screenshot_20260520-084948.png  → step 4

For each language and each step:
    - Mask the status bar (top 100 px) with the sampled background color
      below it, hiding clock/Wi-Fi/battery icons
    - Add a 40 px wallpaper-colored border for visual separation in the
      Play listing
    - Overlay a caption strip at the bottom (per-language step copy)

Outputs:
    tools/store-assets/screenshots/<lang>/0<step>.png
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ASSET_DIR = Path("/home/kuba/htdocs/carbit/assety")
OUTPUT_DIR = Path(__file__).parent / "store-assets" / "screenshots"

INPUT_FILES = [
    "Screenshot_20260520-084921.png",
    "Screenshot_20260520-084929.png",
    "Screenshot_20260520-084939.png",
    "Screenshot_20260520-084948.png",
]

STATUS_BAR_HEIGHT_PX = 100
BORDER_PX = 40
CAPTION_HEIGHT_PX = 140

BORDER_COLOR = (245, 240, 250)   # soft lilac matching RidePanel UI
CAPTION_BG = (11, 25, 41)        # #0B1929, same as feature graphic
CAPTION_FG = (240, 240, 240)

CAPTIONS = {
    "en": [
        "1. Scan the head-unit's QR code",
        "2. Paired — ready to ride",
        "3. Tap Start mirroring",
        "4. Open Google Maps in landscape",
    ],
    "cs": [
        "1. Naskenuj QR z head-unitu",
        "2. Spárováno — připraveno",
        "3. Spusť zrcadlení",
        "4. Otevři Google Mapy na šířku",
    ],
    "sk": [
        "1. Naskenuj QR z head-unitu",
        "2. Spárované — pripravené",
        "3. Spusť zrkadlenie",
        "4. Otvor Google Mapy na šírku",
    ],
    "pl": [
        "1. Zeskanuj QR head-unitu",
        "2. Sparowane — gotowe",
        "3. Uruchom dublowanie",
        "4. Otwórz Mapy Google poziomo",
    ],
    "de": [
        "1. QR-Code der Head-Unit scannen",
        "2. Gekoppelt — bereit",
        "3. Mirroring starten",
        "4. Google Maps im Querformat öffnen",
    ],
}


def find_font(candidates: list[str], size: int) -> ImageFont.FreeTypeFont:
    for path in candidates:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def sample_background_color(img: Image.Image) -> tuple[int, int, int]:
    """Sample three pixels just below the status bar to pick a mask color."""
    samples = [
        img.getpixel((50, STATUS_BAR_HEIGHT_PX + 5)),
        img.getpixel((img.width // 2, STATUS_BAR_HEIGHT_PX + 5)),
        img.getpixel((img.width - 50, STATUS_BAR_HEIGHT_PX + 5)),
    ]
    # getpixel may return int (grayscale) or tuple; normalize to 3-tuple
    rgb = []
    for s in samples:
        if isinstance(s, int):
            rgb.append((s, s, s))
        else:
            rgb.append(s[:3])
    r = sum(p[0] for p in rgb) // 3
    g = sum(p[1] for p in rgb) // 3
    b = sum(p[2] for p in rgb) // 3
    return (r, g, b)


def process_one(
    src_path: Path,
    caption: str,
    font: ImageFont.FreeTypeFont,
    out_path: Path,
) -> None:
    src = Image.open(src_path).convert("RGB")

    bg_color = sample_background_color(src)
    ImageDraw.Draw(src).rectangle(
        [0, 0, src.width, STATUS_BAR_HEIGHT_PX],
        fill=bg_color,
    )

    canvas_w = src.width + 2 * BORDER_PX
    canvas_h = src.height + 2 * BORDER_PX + CAPTION_HEIGHT_PX
    canvas = Image.new("RGB", (canvas_w, canvas_h), BORDER_COLOR)
    canvas.paste(src, (BORDER_PX, BORDER_PX))

    caption_top = BORDER_PX + src.height
    caption_box = [
        BORDER_PX, caption_top,
        BORDER_PX + src.width, caption_top + CAPTION_HEIGHT_PX,
    ]
    cdraw = ImageDraw.Draw(canvas)
    cdraw.rectangle(caption_box, fill=CAPTION_BG)

    text_w = cdraw.textlength(caption, font=font)
    text_x = BORDER_PX + (src.width - text_w) // 2
    text_y = caption_top + (CAPTION_HEIGHT_PX - 48) // 2
    cdraw.text(
        (text_x, text_y),
        caption,
        fill=CAPTION_FG,
        font=font,
    )

    out_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out_path, "PNG", optimize=True)
    print(f"wrote {out_path}")


def main() -> None:
    font = find_font([
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    ], 44)

    for lang, captions in CAPTIONS.items():
        for idx, filename in enumerate(INPUT_FILES, start=1):
            src_path = ASSET_DIR / filename
            if not src_path.exists():
                raise SystemExit(f"missing source: {src_path}")
            out_path = OUTPUT_DIR / lang / f"{idx:02d}.png"
            process_one(src_path, captions[idx - 1], font, out_path)


if __name__ == "__main__":
    main()
