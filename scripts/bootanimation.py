"""Generate Aura's Android boot animation: the Aura ring fades in, then its light breathes.

Usage: python bootanimation.py <bootanimation.zip> [preview.png]   (requires Pillow)
Install on the box as /system/media/bootanimation.zip (the format requires stored entries).
"""
import io
import math
import os
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# Compact, on pure black: the box centres it without scaling, so no edge shows at 720p or 1080p.
W, H = 800, 450
FPS = 24
FADE_FRAMES = 18
BREATH_FRAMES = 48
SCALE = 3  # Supersampling, so the ring's edges stay smooth.
BG = (0, 0, 0)
RING = (245, 245, 247)
LIGHT = (142, 155, 255)
RING_CENTER = (W // 2, 170)
RING_RADIUS, RING_WIDTH, LIGHT_RADIUS = 56, 13, 20
TEXT = "AURA TVBOX"
TEXT_Y = 282
TRACKING = 14
FONTS = [r"C:\Windows\Fonts\segoeuil.ttf", r"C:\Windows\Fonts\arial.ttf",
         "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]


def font(size):
    path = next(p for p in FONTS if os.path.exists(p))
    return ImageFont.truetype(path, size)


def circle(draw, center, radius, **style):
    x, y = center
    draw.ellipse((x - radius, y - radius, x + radius, y + radius), **style)


def mark_layer():
    """The ring and the wordmark, on transparency, so each frame only has to fade them."""
    big = Image.new("RGBA", (W * SCALE, H * SCALE), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    center = (RING_CENTER[0] * SCALE, RING_CENTER[1] * SCALE)
    circle(d, center, (RING_RADIUS + RING_WIDTH // 2) * SCALE, outline=RING + (255,), width=RING_WIDTH * SCALE)

    f = font(40 * SCALE)
    widths = [d.textlength(c, font=f) for c in TEXT]
    x = (W * SCALE - sum(widths) - TRACKING * SCALE * (len(TEXT) - 1)) / 2
    for c, w in zip(TEXT, widths):
        d.text((x, TEXT_Y * SCALE), c, font=f, fill=RING + (255,))
        x += w + TRACKING * SCALE
    return big.resize((W, H), Image.LANCZOS)


def light_layer(strength):
    """The inner light with its glow; strength goes from 0 to 1."""
    big = Image.new("RGBA", (W * SCALE, H * SCALE), (0, 0, 0, 0))
    center = (RING_CENTER[0] * SCALE, RING_CENTER[1] * SCALE)
    d = ImageDraw.Draw(big)
    circle(d, center, int((LIGHT_RADIUS + 6) * SCALE), fill=LIGHT + (int(190 * strength),))
    glow = big.filter(ImageFilter.GaussianBlur(14 * SCALE))
    d = ImageDraw.Draw(glow)
    circle(d, center, LIGHT_RADIUS * SCALE, fill=LIGHT + (int(255 * strength),))
    return glow.resize((W, H), Image.LANCZOS)


def frame(mark_alpha, light):
    out = Image.new("RGBA", (W, H), BG + (255,))
    out.alpha_composite(light_layer(light))
    mark = mark_layer_cached.copy()
    mark.putalpha(mark.getchannel("A").point(lambda a: int(a * mark_alpha)))
    out.alpha_composite(mark)
    return out.convert("RGB")


def png(image):
    buffer = io.BytesIO()
    image.save(buffer, "PNG", optimize=True)
    return buffer.getvalue()


def add(zf, name, data):
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_STORED
    zf.writestr(info, data)


mark_layer_cached = mark_layer()


def main(out, preview=None):
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as zf:
        add(zf, "desc.txt", f"{W} {H} {FPS}\np 1 0 part0\np 0 0 part1\n")
        for i in range(FADE_FRAMES):
            t = (i + 1) / FADE_FRAMES
            eased = t * t * (3 - 2 * t)
            add(zf, f"part0/{i:03d}.png", png(frame(eased, 0.75 * eased)))
        for i in range(BREATH_FRAMES):
            breath = 0.75 + 0.25 * math.sin(2 * math.pi * i / BREATH_FRAMES)
            add(zf, f"part1/{i:03d}.png", png(frame(1, breath)))
    if preview:
        frame(1, 1).save(preview)
    print(f"OK -> {out} ({os.path.getsize(out) / 1024:.0f} KB)")


if __name__ == "__main__":
    main(*sys.argv[1:3])
