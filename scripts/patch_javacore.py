"""Remove the hard-coded 8 GB storage spoof from this firmware's libjavacore.so (Thumb-2, ARMv7).

makeStructStatVfs() replaced f_blocks with 0x1DCD65 (8 000 000 000 bytes at 4 KiB) and shifted
f_bfree/f_bavail by the same delta. The patch keeps the real f_blocks on the stack, makes the
delta 0 and passes the real value instead of the constant.

Usage: python -I patch_javacore.py <original.so> <patched.so>
"""
import sys

from capstone import CS_ARCH_ARM, CS_MODE_THUMB, Cs
from elftools.elf.elffile import ELFFile

# vaddr: (expected original bytes, replacement bytes, meaning)
PATCHES = {
    0x2273C: (bytes.fromhex("4cf66550"), bytes.fromhex("049700bf"), "movw r0,#0xcd65 -> str r7,[sp,#0x10]; nop"),
    0x22748: (bytes.fromhex("c11b"), bytes.fromhex("0021"), "subs r1,r0,r7 -> movs r1,#0"),
    0x2277E: (bytes.fromhex("4cf66550"), bytes.fromhex("049800bf"), "movw r0,#0xcd65 -> ldr r0,[sp,#0x10]; nop"),
    0x22786: (bytes.fromhex("c0f21d00"), bytes.fromhex("00bf00bf"), "movt r0,#0x1d -> nop; nop"),
}


def va2off(elf, va):
    for s in elf.iter_segments():
        if s["p_type"] == "PT_LOAD" and s["p_vaddr"] <= va < s["p_vaddr"] + s["p_filesz"]:
            return va - s["p_vaddr"] + s["p_offset"]
    raise ValueError(hex(va))


def main(src, dst):
    data = bytearray(open(src, "rb").read())
    elf = ELFFile(open(src, "rb"))
    md = Cs(CS_ARCH_ARM, CS_MODE_THUMB)
    for va, (old, new, what) in PATCHES.items():
        off = va2off(elf, va)
        found = bytes(data[off:off + len(old)])
        if found != old:
            sys.exit(f"ABORT at 0x{va:x}: expected {old.hex()} found {found.hex()} (different build?)")
        data[off:off + len(new)] = new
        dis = "; ".join(f"{i.mnemonic} {i.op_str}" for i in md.disasm(new, va))
        print(f"0x{va:x} {old.hex()} -> {new.hex()}  [{dis}]  {what}")
    open(dst, "wb").write(data)
    # The constant must be gone entirely from the function.
    start = va2off(elf, 0x22714)
    assert bytes.fromhex("4cf66550") not in data[start:start + 0x98], "constant still present"
    print("OK ->", dst)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
