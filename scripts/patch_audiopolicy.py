"""Make audio device ports without a channel list report stereo (Rockchip RK322x, Android 7.1).

The firmware's AudioPolicyManager adds the HDMI, SPDIF and speaker devices without any audio profile, so
AudioDeviceInfo.getChannelCounts() is empty. Apps read an empty list as "any channel count": YouTube
(Cobalt, AudioOutputManager.getOutputDeviceInfo) then assumes 8 channels and picks 5.1 streams, which this
box cannot play smoothly (audio underruns, a few frames per second). The audio HAL only outputs stereo.

AudioPort::toAudioPort() copies sample rates, channel masks and formats into the struct audio_port.
This patch rewrites those three copy loops in less space (same behaviour) and uses the room left to
report one channel mask, AUDIO_CHANNEL_OUT_STEREO (0x3), when a port has none. Ports that already
have channel masks are unchanged. Every original byte is verified first: an unknown build aborts
and nothing is written.

Usage: python patch_audiopolicy.py <libaudiopolicymanagerdefault.so> <patched.so>
Exit codes: 0 patched, 3 already patched (nothing written), 1 unknown build (nothing written).
"""
import struct
import sys

# Known build: name, AudioPort::toAudioPort vaddr, rewritten region start/end vaddr.
BUILDS = [
    ("Rockchip RK322x, Android 7.1 (TVBOX-20260608-RK28)", 0x2D374, 0x2D4AE, 0x2D50A),
]
ORIGINAL = bytes.fromhex(
    "0a9a" "c4f88c20" "0599" "002a" "c4f8d010" "0f98" "c4f81401" "0ad0"
    "099b" "04f19007" "0026" "53f82650" "47f82650" "0136" "9642" "f8d3"
    "51b1" "049a" "04f1d403" "0027" "52f82760" "43f82760" "0137" "8f42" "f8d3"
    "50b1" "0e99" "04f58c72" "0023" "51f82370" "42f82370" "0133" "8342" "f8d3")


def cbz(pc, target, rn, nonzero=False):
    off = target - (pc + 4)
    assert 0 <= off <= 126 and off % 2 == 0, "cbz out of range"
    imm = off >> 1
    word = (0xB900 if nonzero else 0xB100) | ((imm >> 5) & 1) << 9 | (imm & 0x1F) << 3 | rn
    return struct.pack("<H", word)


def bne(pc, target):
    off = target - (pc + 4)
    assert -256 <= off <= 254 and off % 2 == 0, "bne out of range"
    return struct.pack("<H", 0xD100 | ((off >> 1) & 0xFF))


def assemble(start, end):
    """Two passes: the first finds the label addresses, the second encodes the branches."""
    labels = {}
    for _ in range(2):
        out = bytearray()
        pc = lambda: start + len(out)

        def emit(hexs):
            out.extend(bytes.fromhex(hexs))

        def label(name):
            labels[name] = pc()

        def br(kind, name, rn=0):
            t = labels.get(name, pc() + 4)
            if kind == "cbz":
                out.extend(cbz(pc(), t, rn))
            elif kind == "cbnz":
                out.extend(cbz(pc(), t, rn, nonzero=True))
            else:
                out.extend(bne(pc(), t))

        # Sample rates: count [sp,#0x28], array [sp,#0x24] -> port+0x8c / port+0x90
        emit("0a9a")          # ldr   r2, [sp, #0x28]
        emit("c4f88c20")      # str.w r2, [r4, #0x8c]
        br("cbz", "chan", 2)  # cbz   r2, chan
        emit("099b")          # ldr   r3, [sp, #0x24]
        emit("04f19007")      # add.w r7, r4, #0x90
        label("l1")
        emit("53f8045b")      # ldr.w r5, [r3], #4
        emit("47f8045b")      # str.w r5, [r7], #4
        emit("013a")          # subs  r2, #1
        br("bne", "l1")
        # Channel masks: count [sp,#0x14], array [sp,#0x10] -> port+0xd0 / port+0xd4.
        # None at all: report one mask, AUDIO_CHANNEL_OUT_STEREO (0x3), from a scratch slot at [sp,#4].
        label("chan")
        emit("0599")          # ldr   r1, [sp, #0x14]
        emit("049b")          # ldr   r3, [sp, #0x10]
        br("cbnz", "have", 1) # cbnz  r1, have
        emit("0121")          # movs  r1, #1
        emit("0325")          # movs  r5, #3
        emit("0195")          # str   r5, [sp, #4]
        emit("01ab")          # add   r3, sp, #4
        label("have")
        emit("c4f8d010")      # str.w r1, [r4, #0xd0]
        emit("04f1d407")      # add.w r7, r4, #0xd4
        label("l2")
        emit("53f8045b")      # ldr.w r5, [r3], #4
        emit("47f8045b")      # str.w r5, [r7], #4
        emit("0139")          # subs  r1, #1
        br("bne", "l2")
        # Formats: count [sp,#0x3c], array [sp,#0x38] -> port+0x114 / port+0x118
        emit("0f98")          # ldr   r0, [sp, #0x3c]
        emit("c4f81401")      # str.w r0, [r4, #0x114]
        br("cbz", "done", 0)  # cbz   r0, done
        emit("0e9b")          # ldr   r3, [sp, #0x38]
        emit("04f58c77")      # add.w r7, r4, #0x118
        label("l3")
        emit("53f8045b")      # ldr.w r5, [r3], #4
        emit("47f8045b")      # str.w r5, [r7], #4
        emit("0138")          # subs  r0, #1
        br("bne", "l3")
        while start + len(out) < end:
            emit("00bf")      # nop
        label("done")
    assert labels["done"] == end, "code does not fill the region exactly"
    return bytes(out)


def va2off(elf, va):
    for s in elf.iter_segments():
        if s["p_type"] == "PT_LOAD" and s["p_vaddr"] <= va < s["p_vaddr"] + s["p_filesz"]:
            return va - s["p_vaddr"] + s["p_offset"]
    return None


def main(src, dst):
    from capstone import CS_ARCH_ARM, CS_MODE_THUMB, Cs
    from elftools.elf.elffile import ELFFile

    data = bytearray(open(src, "rb").read())
    elf = ELFFile(open(src, "rb"))
    name, func, start, end = BUILDS[0]
    off = va2off(elf, start)
    sym = {s.name: s["st_value"] for sec in elf.iter_sections() if sec.name == ".dynsym" for s in sec.iter_symbols()}
    new = assemble(start, end)
    known = off is not None and sym.get("_ZNK7android9AudioPort11toAudioPortEP10audio_port", 0) & ~1 == func
    if known and bytes(data[off:off + len(new)]) == new:
        print("Already patched; nothing written")
        sys.exit(3)
    if not known or bytes(data[off:off + len(ORIGINAL)]) != ORIGINAL or len(ORIGINAL) != end - start:
        sys.exit("ABORT: the expected bytes were not found (unknown build); nothing written")
    print("Build:", name)
    for i in Cs(CS_ARCH_ARM, CS_MODE_THUMB).disasm(new, start):
        print("  %x: %-10s %s %s" % (i.address, i.bytes.hex(), i.mnemonic, i.op_str))
    data[off:off + len(new)] = new
    open(dst, "wb").write(data)
    print("OK ->", dst)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
