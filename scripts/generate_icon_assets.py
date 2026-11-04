#!/usr/bin/env python3
"""
v1.2.0 (2026-10-19) -- self-service icon/splash asset generator.
v1.3.0 (2026-11-04) -- extended to ALSO self-service the full-screen splash
artwork (SplashActivity's own launch image), which until now was the one
piece that still needed a developer with Android Studio to swap by hand.

WHY THIS EXISTS: before v1.2.0, changing the app icon or splash logo meant
manually generating and uploading files into FIVE separate mipmap density
folders (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi) via an external tool. Owner asked
for a "just upload and delete" experience instead (one place, one file) --
and, after v1.2.0 shipped, pointed out it was still incomplete: the ONE
piece of artwork that actually matters most for first impressions (the
full-screen splash image SplashActivity shows on launch) still wasn't
covered -- it still required a developer to manually replace
res/drawable-nodpi/splash_fullscreen.jpg and rebuild by hand. This version
closes that gap, so icon + both splash surfaces are now all "just replace
a file and push" -- nothing left that needs Android Studio.

HOW IT WORKS: this script runs as a GitHub Actions build step, BEFORE
Gradle compiles the app (see .github/workflows/build-apk.yml, step
"Generate icon/splash assets"). It looks for exactly three optional files:

    app/branding/icon_source.png              -- home-screen app icon
    app/branding/splash_source.png             -- small circular splash logo
                                                   (shown briefly by
                                                   MainActivity while the
                                                   WebView's first page loads)
    app/branding/splash_fullscreen_source.*    -- the big, full-screen splash
                                                   image SplashActivity shows
                                                   first on every app launch
                                                   (.png, .jpg, or .jpeg --
                                                   any one of the three, first
                                                   match wins)

To change any of these, the owner only needs to replace whichever file(s)
they want to change (same filename for the first two; any of the three
extensions for the splash-fullscreen one) and push to GitHub -- this script
regenerates every Android resource file that image touches automatically on
the next build.

SAFE BY DESIGN -- if app/branding/ doesn't exist, or doesn't contain any of
these files, this script changes NOTHING for that file and exits cleanly
(prints a line explaining why, doesn't fail the build). That means adding
this script to the pipeline can never break an existing build: until the
owner actually drops a file into app/branding/, every build keeps using
whatever icon/splash files are already checked into res/ -- exactly as
before this feature existed. This applies independently to each of the
three files -- replacing only one doesn't touch the other two.

This is a straight port of the same generation logic used (by hand, once)
to build the app's real icon/splash in v1.1.0 -- same adaptive-icon
2-layer approach (background color + scaled foreground with transparent
padding), same circular-crop splash logo, and (new in v1.3.0) the same
cover-fit crop logic the SplashActivity ImageView already does at runtime
via android:scaleType="centerCrop" (see res/layout/activity_splash.xml) --
just precomputed into a fixed-size shipped asset instead of relying on the
device to scale a differently-shaped source image at runtime.
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
# v1.3.0 -- the full-screen splash image accepts any of these three
# filenames/extensions (first one found wins); unlike ICON_SOURCE/
# SPLASH_SOURCE this one is commonly a photo/illustration rather than a
# transparent-background logo mark, so .jpg is the natural default, but .png
# works identically if the owner's source happens to be a PNG.
SPLASH_FULLSCREEN_SOURCE_CANDIDATES = [
    BRANDING_DIR / "splash_fullscreen_source.png",
    BRANDING_DIR / "splash_fullscreen_source.jpg",
    BRANDING_DIR / "splash_fullscreen_source.jpeg",
]

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

# v1.3.0 -- shipped full-screen splash asset's fixed pixel size. This is a
# portrait baseline, not a device-specific size: the ImageView that displays
# it uses android:scaleType="centerCrop" at runtime (see
# res/layout/activity_splash.xml), which already scales/crops to fill ANY
# real phone's actual screen ratio with no stretching and no blank edges --
# this fixed size just keeps the shipped asset itself a consistent,
# reasonably-small file regardless of whatever resolution the owner's source
# image happens to be.
SPLASH_FULLSCREEN_TARGET_SIZE = (1080, 1920)


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


def find_splash_fullscreen_source() -> "Path | None":
    """Returns the first of the three accepted splash-fullscreen filenames
    that actually exists, or None if the owner hasn't dropped one in."""
    for candidate in SPLASH_FULLSCREEN_SOURCE_CANDIDATES:
        if candidate.exists():
            return candidate
    return None


def cover_fit(img: Image.Image, target_w: int, target_h: int) -> Image.Image:
    """Scale img up just enough to fully cover a target_w x target_h box,
    then center-crop the excess -- mirrors exactly what the splash
    ImageView's own android:scaleType="centerCrop" does at runtime (see
    SPLASH_FULLSCREEN_TARGET_SIZE's doc comment above), so the shipped file
    already looks right regardless of the source image's own aspect ratio:
    never stretched/distorted, never leaves a blank edge."""
    src_w, src_h = img.size
    scale = max(target_w / src_w, target_h / src_h)
    new_w, new_h = round(src_w * scale), round(src_h * scale)
    resized = img.resize((new_w, new_h), Image.LANCZOS)
    left = (new_w - target_w) // 2
    top = (new_h - target_h) // 2
    return resized.crop((left, top, left + target_w, top + target_h))


def generate_splash_fullscreen(source_path: Path) -> None:
    img = Image.open(source_path).convert("RGB")
    print(f"Splash full-screen source found ({source_path.name}, "
          f"{img.width}x{img.height}) -- regenerating splash_fullscreen.jpg...")

    fitted = cover_fit(img, *SPLASH_FULLSCREEN_TARGET_SIZE)

    nodpi_dir = RES_DIR / "drawable-nodpi"
    nodpi_dir.mkdir(parents=True, exist_ok=True)
    fitted.save(nodpi_dir / "splash_fullscreen.jpg", quality=92)
    print(f"  drawable-nodpi/splash_fullscreen.jpg "
          f"({SPLASH_FULLSCREEN_TARGET_SIZE[0]}x{SPLASH_FULLSCREEN_TARGET_SIZE[1]}, cover-fit crop)")


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

    splash_fullscreen_source = find_splash_fullscreen_source()
    if splash_fullscreen_source is not None:
        generate_splash_fullscreen(splash_fullscreen_source)
        did_anything = True
    else:
        print("No app/branding/splash_fullscreen_source.(png|jpg|jpeg) found -- "
              "keeping the full-screen splash file already checked into res/ "
              "unchanged.")

    if did_anything:
        print("Icon/splash asset generation done.")
    else:
        print("Nothing in app/branding/ -- nothing to regenerate, build continues as-is.")


if __name__ == "__main__":
    main()
