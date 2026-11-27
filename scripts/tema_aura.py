"""Recolour a system APK (TvSettings, LatinIME) with Aura's palette, without touching its code.

Every literal colour value (Res_value of type #aarrggbb / #rrggbb) in resources.arsc and in the
compiled XML under res/ is looked up in PALETTE and replaced in place, so sizes, offsets and
resource IDs never change. classes.dex and every other entry are copied byte for byte.
Material blue-grey and deep-teal are the stock TV palette; Aura is black with a lavender accent.

The result has no valid vendor signature: it is mounted over the original by preinstall.sh from
/system/etc/aura/bind/ (same as patch_settings.py), after PackageManager has verified the original.

Usage: python -I tema_aura.py <in.apk> <out.apk>        then zipalign -f -p 4 <out.apk> <aligned.apk>
"""
import struct
import sys
import zipfile

AURA_BG = 0xFF07080B
AURA_PANEL = 0xFF101218
AURA_RAISED = 0xFF1C1F27
AURA_FOCUS = 0xFF2A2E3A
AURA_TEXT = 0xFFF5F5F7
AURA_ACCENT = 0xFF8E9BFF

# Stock colour (ARGB) -> Aura colour. Alpha of the stock value is kept for the translucent ones.
PALETTE = {
    # TvSettings / leanback (material blue-grey)
    0xFF263238: AURA_PANEL,   # blue_grey_900: panel list background
    0xFF37474F: AURA_BG,      # blue_grey_800: panel header
    0xFF21272B: AURA_BG,      # blue_grey_950
    0xFF1F292E: AURA_BG,      # settings_background
    0xFF384248: AURA_PANEL,   # pin dialog / media background
    0xFF4B5459: AURA_FOCUS,   # pin picker focus
    0xFF80CBC4: AURA_ACCENT,  # deep_teal_200: accent (switches, section titles)
    0xFF009688: AURA_ACCENT,  # deep_teal_500
    0xFF4DB6AC: AURA_ACCENT,  # teal_300
    0xFF26A69A: AURA_ACCENT,  # teal_400
    0xFFEEEEEE: AURA_TEXT,    # leanback primary text
    0xFFF1F1F1: AURA_TEXT,
    # LatinIME (Material dark keyboard)
    0xFF21272B: AURA_BG,
    0xFF263238: AURA_PANEL,
    0xFF3B474D: AURA_RAISED,  # key background
    0xFF323E44: AURA_RAISED,
    0xFF4E5B61: AURA_FOCUS,   # pressed / focused key
}


def remap(c):
    base = 0xFF000000 | (c & 0xFFFFFF)
    if base not in PALETTE:
        return None
    return (c & 0xFF000000) | (PALETTE[base] & 0xFFFFFF)


def patch_values(buf):
    """Replace colour Res_values (size=8, res0=0, type 0x1c..0x1f) in a binary resource file."""
    b = bytearray(buf)
    n = 0
    i = 0
    while True:
        i = b.find(b'\x08\x00\x00', i)
        if i < 0 or i + 8 > len(b):
            break
        t = b[i + 3]
        if 0x1C <= t <= 0x1F:
            c = struct.unpack_from('<I', b, i + 4)[0]
            new = remap(c)
            if new is not None and new != c:
                struct.pack_into('<I', b, i + 4, new)
                n += 1
                i += 8
                continue
        i += 1
    return bytes(b), n


def main(src, dst):
    total = 0
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
        for info in zin.infolist():
            data = zin.read(info)
            name = info.filename
            if name == 'resources.arsc' or (name.startswith('res/') and name.endswith('.xml')):
                data, n = patch_values(data)
                if n:
                    print('%-60s %4d colours' % (name, n))
                total += n
            zi = zipfile.ZipInfo(name, date_time=info.date_time)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zout.writestr(zi, data)
    print('total', total)
    if total == 0:
        sys.exit('no colours changed: not the expected APK')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
