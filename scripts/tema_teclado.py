"""Restyle the AOSP keyboard (LatinIME, KitKat-era "holo/klp" theme) in Aura's style.

Its look lives in compiled 9-patch PNGs, not in colours. Key backgrounds are redrawn as flat
rounded rectangles (Aura greys, lavender when pressed) inside the area the stock key covered,
with the corners kept outside the stretchable bands; the rest are recoloured. The 9-patch chunks
(npTc, npLb, npOl) are copied back verbatim so everything still stretches the same way. Every other entry (classes.dex, lib/, resources.arsc) is copied byte for byte.
Mounted over the original from /system/etc/aura/bind/ by preinstall.sh, like patch_settings.py.

Usage: python -I tema_teclado.py <LatinIME.apk> <out.apk>   then zipalign -f -p 4 <out.apk> <aligned.apk>
"""
import colorsys
import io
import re
import struct
import sys
import zipfile
import zlib

from PIL import Image

BG = (0x07, 0x08, 0x0B)
ACCENT = (0x8E, 0x9B, 0xFF)


def chunks(png):
    p = 8
    while p < len(png):
        n, = struct.unpack_from('>I', png, p)
        yield png[p + 4:p + 8], png[p:p + 12 + n]
        p += 12 + n


def splice(orig, im):
    """New pixels from `im`, with the original's 9-patch chunks kept."""
    keep = [raw for t, raw in chunks(orig) if t in (b'npTc', b'npLb', b'npOl')]
    buf = io.BytesIO()
    im.save(buf, 'PNG', optimize=True)
    new = buf.getvalue()
    out = bytearray(new[:8])
    for t, raw in chunks(new):
        if t == b'IDAT' and keep:
            out += b''.join(keep)
            keep = []
        out += raw
    return bytes(out)


def accentify(r, g, b, a):
    """Saturated pixels (holo blue indicators) take Aura's accent at their own lightness."""
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    if s < 0.35 or l < 0.08:
        return None
    ah, al, as_ = colorsys.rgb_to_hls(*(c / 255 for c in ACCENT))
    nr, ng, nb = colorsys.hls_to_rgb(ah, max(l, 0.55), as_)
    return int(nr * 255), int(ng * 255), int(nb * 255), a


def cool(r, g, b, a):
    """Greys: a little darker and cooler, like Aura's raised surfaces."""
    return int(r * 0.62), int(g * 0.65), int(b * 0.78), a


KB_BG = (0x0B, 0x0C, 0x10)
LETTER = (0x26, 0x28, 0x2F)
FUNC = (0x18, 0x1A, 0x1F)
ACTIVE = (0x3C, 0x42, 0x6E)
PREVIEW = (0x2E, 0x31, 0x3A)
POPUP = (0x1C, 0x1F, 0x27)
SS = 4  # supersampling for smooth corners


def ninepatch(png):
    """(paddings, xDivs, yDivs) from a compiled 9-patch's npTc chunk."""
    for t, raw in chunks(png):
        if t == b'npTc':
            d = raw[8:-4]
            nx, ny = d[1], d[2]
            xd = struct.unpack_from('>%di' % nx, d, 32)
            yd = struct.unpack_from('>%di' % ny, d, 32 + 4 * nx)
            return xd, yd
    return None


def rounded(size, box, fill, radius, bar=None):
    """A flat rounded key inside `box` (the area the stock key covered), drawn supersampled."""
    from PIL import ImageDraw
    w, h = size
    big = Image.new('RGBA', (w * SS, h * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    l, t, r, b = box
    d.rounded_rectangle((l * SS, t * SS, r * SS - 1, b * SS - 1), radius=radius * SS, fill=fill + (255,))
    if bar:
        (bl, br, by), colour = bar
        d.rounded_rectangle((bl * SS, by * SS, br * SS - 1, (by + max(1, (b - t) // 14)) * SS - 1),
                            radius=SS, fill=colour + (255,))
    return big.resize(size, Image.LANCZOS)


def redraw(name, png):
    """New flat, rounded shapes for the key backgrounds; None for images that are only recoloured."""
    base = name.rsplit('/', 1)[-1][:-len('.9.png')]
    im = Image.open(io.BytesIO(png)).convert('RGBA')
    if base.startswith(('keyboard_background', 'keyboard_suggest_strip')):
        return Image.new('RGBA', im.size, KB_BG + (255,))
    np_ = ninepatch(png)
    box = im.getchannel('A').point(lambda a: 255 if a > 20 else 0).getbbox()
    if not np_ or not box:
        return None
    xd, yd = np_
    # Corners must stay out of the stretchable bands, or they would be stretched too.
    margins = [xd[0] - box[0], box[2] - xd[-1], yd[0] - box[1], box[3] - yd[-1]]
    radius = max(1, min(m for m in margins if m > 0) - 1)
    radius = min(radius, max(2, (box[3] - box[1]) // 4))
    if 'pressed' in base or 'popup_selected' in base:
        fill = ACCENT
    elif base.startswith('keyboard_key_feedback'):
        fill = PREVIEW
    elif base.startswith('keyboard_popup_panel'):
        fill = POPUP
    elif 'active' in base:
        fill = ACTIVE
    elif '_light_' in base:
        fill = LETTER
    elif base.startswith('btn_keyboard_key'):
        fill = FUNC
    else:
        return None
    bar = None
    if len(xd) == 6 and ('_on' in base or '_off' in base):
        # Shift / caps lock: the stock key has a light in its fixed middle band; Aura's is a small bar.
        on = '_on' in base
        bar = ((xd[1], xd[4], box[3] - max(3, (box[3] - box[1]) // 5)),
               (ACCENT if on else (0x3A, 0x3D, 0x47)) if fill != ACCENT else (0xFF, 0xFF, 0xFF))
    return rounded(im.size, box, fill, radius, bar)


def recolour(name, im):
    im = im.convert('RGBA')
    px = im.load()
    w, h = im.size
    base = name.rsplit('/', 1)[-1]
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            acc = accentify(r, g, b, a)
            if acc:
                px[x, y] = acc
            elif base.startswith('keyboard_background'):
                px[x, y] = BG + (a,)
            elif 'pressed' in base or 'popup_selected' in base:
                # Pressed / focused key: Aura's accent, as translucent as the stock grey.
                px[x, y] = ACCENT + (a,) if r > 60 else cool(r, g, b, a)
            elif '_light_' in base:
                # Letter keys: a softer white veil, close to Aura's grey tiles on black.
                px[x, y] = (r, g, b, int(a * 0.6))
            else:
                px[x, y] = cool(r, g, b, a)
    return im


TARGET = re.compile(r'res/drawable-[a-z]+-v4/(btn_keyboard_key_.*|keyboard_background_.*|keyboard_suggest_strip_.*|'
                    r'keyboard_popup_panel_background_.*|keyboard_key_feedback_.*|btn_suggestion_pressed)\.9\.png$')


def main(src, dst):
    n = 0
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
        for info in zin.infolist():
            data = zin.read(info)
            if TARGET.match(info.filename):
                new = redraw(info.filename, data)
                if new is None:
                    new = recolour(info.filename, Image.open(io.BytesIO(data)))
                data = splice(data, new)
                n += 1
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zout.writestr(zi, data)
    print('recoloured', n, 'images')
    if n == 0:
        sys.exit('no keyboard images found: not the expected LatinIME')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
