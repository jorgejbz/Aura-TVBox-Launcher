"""Rockchip RK322x (kernel 3.10, fb "box" policy): make the Android UI render at 1920x1080.

The kernel inherits the U-Boot logo's size for fb0 (rockchip,uboot-logo-on = 1), and this
firmware ships 1280x720 logos, so Android draws its whole UI at 1280x720 and the VOP scales it
up to the 1080p HDMI output. This rewrites the resource image with the same logos upscaled to
1920x1080.

Some batches of the same firmware also have the DTB's default mode (display-timings/timing0) at
1280x720; there it is set to the 1920x1080p60 timing the DTB already lists (timing1), value by
value in place, so the DTB keeps its size and offset. On both batches seen so far the result is
the same image (md5 343139732403a960189230966330a5fd), tested on real boxes.

Usage: python patch_resource_1080.py resource-orig.img resource-1080.img
Exit 3 = already 1920x1080 (nothing written).
"""
import io, struct, sys
from PIL import Image

W, H = 1920, 1080
BLK = 512


def entries(img):
    magic, _rv, _iv, hdr_blks, tbl_off, tbl_esz, tbl_num = struct.unpack_from('<4sHHBBBxI', img, 0)
    if magic != b'RSCE':
        sys.exit('not a Rockchip resource image (RSCE)')
    out = []
    for i in range(tbl_num):
        e = (tbl_off + i * tbl_esz) * BLK
        if img[e:e + 4] != b'ENTR':
            sys.exit('bad entry %d' % i)
        name = img[e + 4:e + 260].split(b'\0')[0].decode()
        off, size = struct.unpack_from('<II', img, e + 260)
        if any(img[e + 268:e + tbl_esz * BLK]):
            sys.exit('entry %d has extra fields (hash?); this format is not supported' % i)
        out.append([e, name, off, size])
    return out


def dtb_props(dtb):
    """{(node path, property): (offset, length)} of a flattened device tree."""
    magic, _total, off_struct, off_strings = struct.unpack_from('>IIII', dtb, 0)
    if magic != 0xd00dfeed:
        sys.exit('rk-kernel.dtb is not a device tree')
    p, stack, out = off_struct, [], {}
    while True:
        tok = struct.unpack_from('>I', dtb, p)[0]; p += 4
        if tok == 1:
            end = dtb.index(b'\0', p); stack.append(dtb[p:end].decode()); p = (end + 4) & ~3
        elif tok == 2:
            stack.pop()
        elif tok == 3:
            ln, nameoff = struct.unpack_from('>II', dtb, p); p += 8
            nm = dtb[off_strings + nameoff:dtb.index(b'\0', off_strings + nameoff)].decode()
            out[('/'.join(stack), nm)] = (p, ln)
            p = (p + ln + 3) & ~3
        elif tok == 9:
            return out


def u32(dtb, props, node, name):
    v = props.get((node, name))
    return struct.unpack_from('>I', dtb, v[0])[0] if v and v[1] == 4 else None


def default_mode_1080(dtb):
    """The DTB with display-timings/timing0 at 1920x1080 (copied from the timing that has it), or None if it already is."""
    props = dtb_props(dtb)
    t0 = '/display-timings/timing0'
    if (u32(dtb, props, t0, 'hactive'), u32(dtb, props, t0, 'vactive')) == (W, H):
        return None
    src = None
    for i in range(1, 8):
        t = '/display-timings/timing%d' % i
        if (u32(dtb, props, t, 'hactive'), u32(dtb, props, t, 'vactive')) == (W, H) \
                and u32(dtb, props, t, 'screen-type') == u32(dtb, props, t0, 'screen-type') \
                and (t, 'interlaced') not in props:
            src = t
            break
    if src is None:
        sys.exit('the DTB has no 1920x1080 progressive timing to use')
    out = bytearray(dtb)
    for (node, name), (off, ln) in props.items():
        if node != t0 or name in ('phandle', 'linux,phandle'):
            continue
        s = props.get((src, name))
        if s is None or s[1] != ln:
            sys.exit('timing0 and %s differ in %s: not patched' % (src, name))
        out[off:off + ln] = dtb[s[0]:s[0] + ln]
    print('rk-kernel.dtb: timing0 -> 1920x1080 (from %s)' % src)
    return bytes(out)


def main(src, dst):
    img = open(src, 'rb').read()
    ents = entries(img)
    names = [n for _, n, _, _ in ents]
    if names[0] != 'rk-kernel.dtb' or not all(n.endswith('.bmp') for n in names[1:]):
        sys.exit('unexpected entries: %s' % names)
    sizes = []
    for _, n, off, size in ents[1:]:
        b = img[off * BLK:off * BLK + size]
        sizes.append(struct.unpack_from('<ii', b, 18))
    _, _, doff, dsize = ents[0]
    dtb = img[doff * BLK:doff * BLK + dsize]
    new_dtb = default_mode_1080(dtb)
    if all(s == (W, H) for s in sizes) and new_dtb is None:
        print('already %dx%d' % (W, H)); sys.exit(3)

    new = bytearray(len(img))
    new[:ents[-1][0] + BLK] = img[:ents[-1][0] + BLK]       # header + table
    new[doff * BLK:doff * BLK + dsize] = new_dtb if new_dtb is not None else dtb
    pos = -(-(doff * BLK + dsize) // BLK)                    # next free block
    for i, (e, n, off, size) in enumerate(ents[1:]):
        im = Image.open(io.BytesIO(img[off * BLK:off * BLK + size])).convert('RGB')
        if im.size != (W, H):
            im = im.resize((W, H), Image.LANCZOS)
        buf = io.BytesIO(); im.save(buf, 'BMP')
        data = buf.getvalue()
        if pos * BLK + len(data) > len(new):
            sys.exit('does not fit in the partition')
        new[pos * BLK:pos * BLK + len(data)] = data
        struct.pack_into('<II', new, e + 260, pos, len(data))
        print('%s: %dx%d -> %dx%d, block %d, %d bytes' % (n, sizes[i][0], sizes[i][1], W, H, pos, len(data)))
        pos = -(-(pos * BLK + len(data)) // BLK)
    open(dst, 'wb').write(new)
    print('used %d of %d bytes' % (pos * BLK, len(new)))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
