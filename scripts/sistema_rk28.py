"""Builds the "sistema-rk28" pre-release folder that Aura's "Mejoras del sistema" reads (SystemUpgrade.java).

Each item: the file to download (sha256 checked by Aura), the md5 it must have once written, and in
"accept" the md5 of every version a box may have now (older preinstall.sh, the earlier bind copies,
the stock file, "absent"). Aura writes nothing over a version it does not know.

Usage: python -I sistema_rk28.py <out dir>
Needs: build/tema/*.apk (tema_aura.py / tema_teclado.py), overlay-tema/AuraTema.apk,
backups/<1080 folder>/resource-1080.img, scripts/preinstall.sh.
Then upload everything in <out dir> to the GitHub PRE-release "sistema-rk28" (never a normal release:
Aura's update check reads releases/latest).
"""
import gzip
import hashlib
import json
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIRMWARE = 'TVBOX-20260608-RK28'

# md5 of what boxes prepared before may have now.
PREINSTALL_OLD = ['93f66a7af2d2d11fe4bb03d17cae9863', '1933ee6ef0a4157985fd48a29d082fcd',
                  'd3a5230d76eb31f5bd6fc88a36074f28', '2160bc1c1b560b5c1a078c97c284b135']
TVSETTINGS_OLD = ['b2b8a7c5674dab474a61e72b5358cb5a']          # patch_settings.py copy, no theme
LATINIME_OLD = ['20233f48e01ec8b60b98b2ff87ea8686',            # stock LatinIME (no bind yet)
                '2aa127bfeb6e1beefb532c25ae1450f3']            # first recoloured test
RESOURCE_OLD = ['580853bf6c70466888520aae26712a1e',            # stock resource partition (720p logos)
                'c1bc387dcfdf240be0bbe158242ad323']            # same, from a batch whose DTB timing0 is 720p (box 5)


def digest(path, algo):
    h = hashlib.new(algo)
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def main(out):
    os.makedirs(out, exist_ok=True)
    items = []

    def add(id_, src, name, target=None, orig=None, accept=(), stop=None, partition=None, gz=False):
        dst = os.path.join(out, name)
        md5 = digest(src, 'md5')
        if gz:
            # mtime=0 and no file name: the same input always gives the same bytes (and sha256).
            with open(src, 'rb') as fi, open(dst, 'wb') as raw, gzip.GzipFile('', 'wb', 9, raw, mtime=0) as fo:
                shutil.copyfileobj(fi, fo)
        else:
            shutil.copyfile(src, dst)
        it = {'id': id_, 'file': name, 'sha256': digest(dst, 'sha256'), 'md5': md5, 'accept': list(accept)}
        for k, v in (('target', target), ('orig', orig), ('stop', stop), ('partition', partition)):
            if v:
                it[k] = v
        if gz:
            it['gz'] = True
        items.append(it)

    pre = os.path.join(out, 'preinstall.sh.lf')
    with open(os.path.join(ROOT, 'scripts', 'preinstall.sh'), 'rb') as f:
        open(pre, 'wb').write(f.read().replace(b'\r\n', b'\n'))
    add('preinstall', pre, 'preinstall.sh', target='/system/bin/preinstall.sh', accept=PREINSTALL_OLD)
    os.remove(pre)
    add('tvsettings', os.path.join(ROOT, 'build', 'tema', 'priv-app@TvSettings@TvSettings.apk'), 'TvSettings.apk',
        target='/system/etc/aura/bind/priv-app@TvSettings@TvSettings.apk', accept=TVSETTINGS_OLD,
        stop='com.android.tv.settings')
    add('latinime', os.path.join(ROOT, 'build', 'tema', 'app@LatinIME@LatinIME.apk'), 'LatinIME.apk',
        target='/system/etc/aura/bind/app@LatinIME@LatinIME.apk', orig='/system/app/LatinIME/LatinIME.apk',
        accept=LATINIME_OLD, stop='com.android.inputmethod.latin')
    add('tema', os.path.join(ROOT, 'overlay-tema', 'AuraTema.apk'), 'AuraTema.apk',
        target='/system/vendor/overlay/AuraTema.apk', accept=['absent'])
    res = [d for d in os.listdir(os.path.join(ROOT, 'backups')) if d.startswith('resolucion-1080')]
    add('resource', os.path.join(ROOT, 'backups', sorted(res)[-1], 'resource-1080.img'), 'resource-1080.img.gz',
        partition='resource', accept=RESOURCE_OLD, gz=True)

    manifest = {'firmware': FIRMWARE, 'density': '240', 'items': items}
    with open(os.path.join(out, 'sistema.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(manifest, f, indent=1)
    for it in items:
        print('%-11s %-22s md5 %s' % (it['id'], it['file'], it['md5']))


if __name__ == '__main__':
    main(sys.argv[1])
