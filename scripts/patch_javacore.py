"""Remove the hard-coded 8 GB storage spoof from this firmware's libjavacore.so (Thumb-2, ARMv7).

makeStructStatVfs() replaced f_blocks with 0x1DCD65 (8 000 000 000 bytes at 4 KiB) and shifted
f_bfree/f_bavail by the same delta. The patch keeps the real f_blocks on the stack, makes the
delta 0 and passes the real value instead of the constant.

Usage: python -I patch_javacore.py <original.so> <patched.so>
"""
import sys

from capstone import CS_ARCH_ARM, CS_MODE_THUMB, Cs
from elftools.elf.elffile import ELFFile

# Known firmware builds: (name, function vaddr, function size, {vaddr: (original, replacement, meaning)}).
BUILDS = [
    ("HiSilicon Hi3798MV300H, Android 9", 0x22714, 0x98, {
        0x2273C: (bytes.fromhex("4cf66550"), bytes.fromhex("049700bf"), "movw r0,#0xcd65 -> str r7,[sp,#0x10]; nop"),
        0x22748: (bytes.fromhex("c11b"), bytes.fromhex("0021"), "subs r1,r0,r7 -> movs r1,#0"),
        0x2277E: (bytes.fromhex("4cf66550"), bytes.fromhex("049800bf"), "movw r0,#0xcd65 -> ldr r0,[sp,#0x10]; nop"),
        0x22786: (bytes.fromhex("c0f21d00"), bytes.fromhex("00bf00bf"), "movt r0,#0x1d -> nop; nop"),
    }),
    ("Rockchip RK322x, Android 7.1", 0x1DB74, 0xC8, {
        0x1DB86: (bytes.fromhex("4cf66557"), bytes.fromhex("049100bf"), "movw r7,#0xcd65 -> str r1,[sp,#0x10]; nop"),
        0x1DB8C: (bytes.fromhex("c0f21d07"), bytes.fromhex("00bf00bf"), "movt r7,#0x1d -> nop; nop"),
        0x1DB90: (bytes.fromhex("791a"), bytes.fromhex("0021"), "subs r1,r7,r1 -> movs r1,#0"),
        0x1DC10: (bytes.fromhex("4cf66550"), bytes.fromhex("049800bf"), "movw r0,#0xcd65 -> ldr r0,[sp,#0x10]; nop"),
        0x1DC14: (bytes.fromhex("c0f21d00"), bytes.fromhex("00bf00bf"), "movt r0,#0x1d -> nop; nop"),
    }),
]


def va2off(elf, va):
    for s in elf.iter_segments():
        if s["p_type"] == "PT_LOAD" and s["p_vaddr"] <= va < s["p_vaddr"] + s["p_filesz"]:
            return va - s["p_vaddr"] + s["p_offset"]
    return None


def matches(elf, data, patches):
    for va, (old, _new, _what) in patches.items():
        off = va2off(elf, va)
        if off is None or bytes(data[off:off + len(old)]) != old:
            return False
    return True


def main(src, dst):
    data = bytearray(open(src, "rb").read())
    elf = ELFFile(open(src, "rb"))
    md = Cs(CS_ARCH_ARM, CS_MODE_THUMB)
    build = next((b for b in BUILDS if matches(elf, data, b[3])), None)
    if build is None:
        sys.exit("ABORT: the expected bytes were not found (unknown build); nothing written")
    name, func_va, func_size, patches = build
    print("Build:", name)
    for va, (old, new, what) in patches.items():
        off = va2off(elf, va)
        data[off:off + len(new)] = new
        dis = "; ".join(f"{i.mnemonic} {i.op_str}" for i in md.disasm(new, va))
        print(f"0x{va:x} {old.hex()} -> {new.hex()}  [{dis}]  {what}")
    # The constant must be gone entirely from the function.
    start = va2off(elf, func_va)
    assert bytes.fromhex("4cf665") not in data[start:start + func_size], "constant still present"
    open(dst, "wb").write(data)
    print("OK ->", dst)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
