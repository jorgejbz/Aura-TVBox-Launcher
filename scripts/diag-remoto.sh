#!/system/bin/sh
# Collects facts about an Android TV box. Read-only. Run by diagnostico.ps1 through adb.
# Output is split into "##SECTION" blocks that the PowerShell side parses.

echo "##ID"
for p in ro.product.manufacturer ro.product.brand ro.product.model ro.product.device \
         ro.board.platform ro.hardware ro.product.chipset ro.build.product ro.hi.product \
         ro.build.version.release ro.build.version.sdk ro.build.description ro.build.fingerprint \
         ro.build.type ro.build.tags ro.product.cpu.abilist ro.adb.secure \
         service.adb.tcp.port persist.adb.tcp.port ro.flash.size ro.memory.size \
         vendor.display-size ro.sf.lcd_density; do
    echo "$p=$(getprop $p)"
done
echo "selinux=$(getenforce 2>/dev/null)"

echo "##MEM"
grep -E 'MemTotal|MemAvailable' /proc/meminfo
tr ' ' '\n' < /proc/cmdline | grep -E '^mem='

echo "##DF"
df -k /data | tail -1

echo "##DISKSTATS"
dumpsys diskstats | grep -E 'Data-Free'

echo "##RAMROM"
cat /private/ramrom.config 2>/dev/null

echo "##DISPLAY"
wm size
dumpsys display | grep -o 'supportedModes \[[^]]*\]' | head -1

echo "##HOME"
cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1

echo "##PKGS"
pm list packages -f -i -U

echo "##INSTALLERS"
# Third-party apps allowed to install other apps silently.
for pkg in $(pm list packages -3 | cut -d: -f2); do
    dumpsys package "$pkg" | grep -q 'android.permission.INSTALL_PACKAGES: granted=true' && echo "$pkg"
done

echo "##LISTEN"
cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '$4=="0A" {print $2, $8}'

echo "##CONN"
cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '$4=="01" {print $8}' | sort | uniq -c

echo "##PREINSTALL"
ls -la /system/preinstall 2>/dev/null
ls /system/bin/ 2>/dev/null | grep -i preinstall

echo "##RUNNING"
getprop | grep 'init.svc' | grep running | sed 's/\[init.svc.//; s/\]: \[running\]//'

echo "##END"
