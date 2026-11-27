"""Rockchip RK322x (kernel 3.10, fb "box" policy): make the Android UI render at 1920x1080.

The kernel inherits the U-Boot logo's size for fb0 (rockchip,uboot-logo-on = 1), and
this firmware ships 1280x720 logos, so Android draws its whole UI at 1280x720 and the
VOP scales it up to the 1080p HDMI output. This rewrites the resource image with the
same logos upscaled to 1920x1080. rk-kernel.dtb keeps its bytes and its offset.

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
    if all(s == (W, H) for s in sizes):
        print('already %dx%d' % (W, H)); sys.exit(3)

    new = bytearray(len(img))
    new[:ents[-1][0] + BLK] = img[:ents[-1][0] + BLK]       # header + table
    _, _, doff, dsize = ents[0]
    new[doff * BLK:doff * BLK + dsize] = img[doff * BLK:doff * BLK + dsize]
    pos = -(-(doff * BLK + dsize) // BLK)                    # next free block
    for e, n, off, size in ents[1:]:
        im = Image.open(io.BytesIO(img[off * BLK:off * BLK + size])).convert('RGB')
        if im.size != (W, H):
            im = im.resize((W, H), Image.LANCZOS)
        buf = io.BytesIO(); im.save(buf, 'BMP')
        data = buf.getvalue()
        if pos * BLK + len(data) > len(new):
            sys.exit('does not fit in the partition')
        new[pos * BLK:pos * BLK + len(data)] = data
        struct.pack_into('<II', new, e + 260, pos, len(data))
        print('%s: %dx%d -> %dx%d, block %d, %d bytes' % (n, *sizes[ents.index([e, n, off, size]) - 1], W, H, pos, len(data)))
        pos = -(-(pos * BLK + len(data)) // BLK)
    open(dst, 'wb').write(new)
    print('used %d of %d bytes' % (pos * BLK, len(new)))

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
