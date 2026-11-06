#!/system/bin/sh
# Reinstalls the user's apps after a factory reset (replaces the firmware's preinstall.sh).
# /system/preinstall/*.apk      -> single APKs
# /system/preinstall/<dir>/*.apk -> split apps (base + config splits), installed as one session
# The mark lives in /data, so it is wiped by a factory reset and the apps come back.

MARK=/data/local/symbol_thirdpart_apks_installed
PKGS=/system/preinstall

mylog() {
    log -p i -t "preinstall" "$@"
}

# Runs on every boot, after PackageManager has verified the untouched APKs in /system.
# /system/etc/aura/bind/priv-app@Foo@Foo.apk is mounted over /system/priv-app/Foo/Foo.apk: firmware
# screens that show fake data are patched without their vendor signature (see patch_settings.py).
# If anything fails the original stays in place: the box still boots, showing the old numbers.
for f in /system/etc/aura/bind/*; do
    [ -f "$f" ] || continue
    target=/system/$(basename "$f" | tr '@' '/')
    [ -f "$target" ] || continue
    mylog "bind $target: $(mount -o bind "$f" "$target" 2>&1 && echo ok)"
done
# Apps already running keep the old code; a plain kill does not leave them in the "stopped" state.
for p in com.android.settings com.android.tv.settings; do
    pid=$(pidof "$p") && kill $pid
done

[ -e $MARK ] && exit 0
mylog "first boot after reset: installing apps from $PKGS"

for f in $PKGS/*.apk; do
    [ -f "$f" ] || continue
    mylog "install $f: $(pm install -r "$f" 2>&1)"
done

for d in $PKGS/*/; do
    [ -d "$d" ] || continue
    total=0
    for a in "$d"*.apk; do total=$((total + $(stat -c %s "$a"))); done
    sid=$(pm install-create -r -S $total | sed -n 's/.*\[\([0-9]*\)\].*/\1/p')
    [ -n "$sid" ] || { mylog "no session for $d"; continue; }
    i=0
    for a in "$d"*.apk; do
        pm install-write -S $(stat -c %s "$a") $sid "split$i" "$a" >/dev/null
        i=$((i + 1))
    done
    mylog "install $d: $(pm install-commit $sid 2>&1)"
done

touch $MARK
mylog "done"
