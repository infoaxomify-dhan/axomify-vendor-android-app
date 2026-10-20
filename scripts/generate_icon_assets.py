#!/usr/bin/env python3
"""
v1.2.0 (2026-10-19) -- self-service icon/splash asset generator.

WHY THIS EXISTS: before this, changing the app icon or splash logo meant
manually generating and uploading files into FIVE separate mipmap density
folders (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi) via an external tool. Owner asked
for a "just upload and delete" experience instead (one place, one file).

HOW IT WORKS: this script runs as a GitHub Actions build step, BEFORE
Gradle compiles the app (see .github/workflows/build-apk.yml, step
"Generate icon/splash assets"). It looks for exactly two optional files:

    app/branding/icon_source.png    -- becomes the home-screen app icon
    app/branding/splash_source.png  -- becomes the splash-screen logo

To change the icon and/or splash logo, the owner only needs to replace
whichever of those two files they want to change (same filename, any
reasonably-square PNG/JPG, 512x512px or larger recommended for best
quality) and push to GitHub -- this script regenerates every Android
resource file that logo touches automatically on the next build.

SAFE BY DESIGN -- if app/branding/ doesn't exist, or doesn't contain one
or both of these files, this script changes NOTHING for that file and
exits cleanly (prints a line explaining why, doesn't fail the build).
That means adding this script to the pipeline can never break an existing
build: until the owner actually drops a file into app/branding/, every
build keeps using whatever icon/splash files are already checked into
res/ -- exactly as before this feature existed.

This is a straight port of the same generation logic used (by hand, once)
to build the app's real icon/splash in v1.1.0 -- same adaptive-icon
2-layer approach (background color + scaled foreground with transparent
padding), same circular-crop splash logo -- just automated so it can run
unattended on every build instead of being a one-off manual step.
"""

import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:
    print("Pillow not installed -- this should have been installed by the "
          "workflow's 'pip install Pillow' step. Failing the build so this "
          "doesn't silently skip icon generation.")
    sys.exit(1)

REPO_ROOT = Path(__file__).resolve().parent.parent
APP_DIR = REPO_ROOT / "app"
RES_DIR = APP_DIR / "src" / "main" / "res"
BRANDING_DIR = APP_DIR / "branding"

ICON_SOURCE = BRANDING_DIR / "icon_source.png"
SPLASH_SOURCE = BRANDING_DIR / "splash_source.png"

# density -> legacy launcher icon pixel size (square)
ICON_DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

ADAPTIVE_FOREGROUND_CANVAS = 432  # mipmap-xxxhdpi/ic_launcher_foreground.png
ADAPTIVE_FOREGROUND_SCALE = 0.75  # leave transparent padding so circular/
                                   # squircle launcher masks don't clip detail
SPLASH_LOGO_SIZE = 480            # res/drawable-nodpi/splash_logo.png


def load_square_rgba(path: Path) -> Image.Image:
    """Open an image, convert to RGBA, and letterbox it onto a transparent
    square canvas if it isn't already square -- keeps every generated size
    consistent regardless of the source image's own aspect ratio."""
    img = Image.open(path).convert("RGBA")
    if img.width == img.height:
        return img
    side = max(img.width, img.height)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(img, ((side - img.width) // 2, (side - img.height) // 2), img)
    return canvas


def circular_mask(img: Image.Image) -> Image.Image:
    """Return a copy of img with a circular alpha mask applied (used for the
    round launcher icon variant and the splash logo)."""
    size = img.size
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size[0], size[1]), fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def generate_icon(source: Image.Image) -> None:
    print(f"Icon source found ({source.width}x{source.height}) -- "
          f"regenerating all launcher icon sizes...")

    for density, px in ICON_DENSITIES.items():
        mipmap_dir = RES_DIR / f"mipmap-{density}"
        mipmap_dir.mkdir(parents=True, exist_ok=True)

        full_bleed = source.resize((px, px), Image.LANCZOS)
        full_bleed.save(mipmap_dir / "ic_launcher.png")

        round_icon = circular_mask(full_bleed)
        round_icon.save(mipmap_dir / "ic_launcher_round.png")

        print(f"  mipmap-{density}/ ic_launcher.png + ic_launcher_round.png ({px}x{px})")

    # Adaptive icon foreground layer -- scaled down with transparent padding
    # so the OS's own circular/squircle/rounded-square launcher mask never
    # clips the logo artwork.
    fg_canvas = Image.new("RGBA", (ADAPTIVE_FOREGROUND_CANVAS, ADAPTIVE_FOREGROUND_CANVAS), (0, 0, 0, 0))
    scaled_size = round(ADAPTIVE_FOREGROUND_CANVAS * ADAPTIVE_FOREGROUND_SCALE)
    scaled_logo = source.resize((scaled_size, scaled_size), Image.LANCZOS)
    offset = (ADAPTIVE_FOREGROUND_CANVAS - scaled_size) // 2
    fg_canvas.paste(scaled_logo, (offset, offset), scaled_logo)

    xxxhdpi_dir = RES_DIR / "mipmap-xxxhdpi"
    xxxhdpi_dir.mkdir(parents=True, exist_ok=True)
    fg_canvas.save(xxxhdpi_dir / "ic_launcher_foreground.png")
    print(f"  mipmap-xxxhdpi/ic_launcher_foreground.png ({ADAPTIVE_FOREGROUND_CANVAS}x{ADAPTIVE_FOREGROUND_CANVAS}, "
          f"logo at {int(ADAPTIVE_FOREGROUND_SCALE * 100)}% scale)")

    # 512x512 reference copy -- not used by the APK itself, just handy if the
    # owner ever needs a plain square icon for a Play Store listing etc.
    source.resize((512, 512), Image.LANCZOS).save(APP_DIR / "play-store-icon-512.png")
    print("  play-store-icon-512.png (reference copy, not used by the APK)")


def generate_splash(source: Image.Image) -> None:
    print(f"Splash source found ({source.width}x{source.height}) -- "
          f"regenerating splash_logo.png...")

    resized = source.resize((SPLASH_LOGO_SIZE, SPLASH_LOGO_SIZE), Image.LANCZOS)
    circular = circular_mask(resized)

    nodpi_dir = RES_DIR / "drawable-nodpi"
    nodpi_dir.mkdir(parents=True, exist_ok=True)
    circular.save(nodpi_dir / "splash_logo.png")
    print(f"  drawable-nodpi/splash_logo.png ({SPLASH_LOGO_SIZE}x{SPLASH_LOGO_SIZE}, circular crop)")


def main() -> None:
    did_anything = False

    if ICON_SOURCE.exists():
        generate_icon(load_square_rgba(ICON_SOURCE))
        did_anything = True
    else:
        print(f"No {ICON_SOURCE.relative_to(REPO_ROOT)} found -- keeping the "
              f"app icon files already checked into res/ unchanged.")

    if SPLASH_SOURCE.exists():
        generate_splash(load_square_rgba(SPLASH_SOURCE))
        did_anything = True
    else:
        print(f"No {SPLASH_SOURCE.relative_to(REPO_ROOT)} found -- keeping the "
              f"splash logo file already checked into res/ unchanged.")

    if did_anything:
        print("Icon/splash asset generation done.")
    else:
        print("Nothing in app/branding/ -- nothing to regenerate, build continues as-is.")


if __name__ == "__main__":
    main()
