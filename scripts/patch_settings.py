"""Remove the fake RAM and storage figures from a firmware's Settings / TvSettings APKs.

Rockchip RK322x, Android 7.1 firmware: the vendor added an `if (name.equals("data"))` branch that
reports 32 GiB of storage, and overwrites the RAM it just read with 2^32 bytes (4 GiB).
Each storage patch turns that `if-eqz` into a `goto` to the original code path; the RAM patch turns
the overwrite into nops. Every byte is verified first: an unknown build aborts and writes nothing.

The result has no valid vendor signature, so it cannot replace the APK in /system. Place it in
/system/etc/aura/bind/ and scripts/preinstall.sh mounts it over the original after boot.

Usage: python -I patch_settings.py <original.apk> <patched.apk>
Then:  zipalign -f -p 4 <patched.apk> <aligned.apk>
"""
import hashlib
import struct
import sys
import zipfile
import zlib

# Offsets are file offsets in classes.dex, as printed by `dexdump -d`.
BUILDS = {
    "com.android.settings (RK322x 7.1)": {
        0x15B3C2: ("1902f041", "00000000", "MemInfo.calculateWeightInfo: drop const 2^32"),
        0x15B3C6: ("5a62ca15", "00000000", "MemInfo.calculateWeightInfo: keep the real total RAM"),
        0x17EA92: ("3816e400", "2900e400", "PrivateVolumeSettings.update: skip fake branch"),
        0x17FD88: ("380eb800", "2900b800", "PublicVolumeSettings.update: skip fake branch"),
        0x182290: ("38022500", "29002500", "StorageSettings.refresh: skip fake branch"),
        0x182C32: ("3812ce00", "2900ce00", "StorageVolumePreference.<init>: skip fake branch"),
        0x1ED8C0: ("38032200", "29002200", "PrivateStorageInfo.getPrivateStorageInfo: skip fake branch"),
    },
    "com.android.tv.settings (RK322x 7.1)": {
        0x14931C: ("38032500", "29002500", "StorageResetFragment$VolPreference.getSizeString: skip fake branch"),
    },
}


def matches(dex, patches):
    return all(
        dex[off:off + len(bytes.fromhex(old))] == bytes.fromhex(old)
        for off, (old, _new, _what) in patches.items()
    )


def fix_header(dex):
    """DEX header: adler32 at 8 covers 12.., SHA-1 at 12 covers 32.."""
    dex[12:32] = hashlib.sha1(bytes(dex[32:])).digest()
    dex[8:12] = struct.pack("<I", zlib.adler32(bytes(dex[12:])) & 0xFFFFFFFF)


def main(src, dst):
    with zipfile.ZipFile(src) as zin:
        dex = bytearray(zin.read("classes.dex"))
        build = next((b for b in BUILDS if matches(dex, BUILDS[b])), None)
        if build is None:
            sys.exit("ABORT: the expected bytes were not found (unknown build); nothing written")
        print("Build:", build)
        for off, (old, new, what) in BUILDS[build].items():
            dex[off:off + len(bytes.fromhex(new))] = bytes.fromhex(new)
            print(f"0x{off:x} {old} -> {new}  {what}")
        fix_header(dex)
        with zipfile.ZipFile(dst, "w") as zout:
            for info in zin.infolist():
                data = bytes(dex) if info.filename == "classes.dex" else zin.read(info.filename)
                zout.writestr(info, data, compress_type=info.compress_type)
    print("OK ->", dst)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
