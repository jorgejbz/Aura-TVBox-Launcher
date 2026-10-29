package com.jajua.aura;

import android.app.Activity;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStatVfs;
import android.view.Display;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/**
 * Real hardware facts, read from the kernel where possible. Some TV box firmwares patch the
 * Android framework to report fake storage, RAM or version numbers; these readings bypass it.
 */
final class SystemInfo {
    private SystemInfo() {}

    /** Derived from the API level, which apps depend on and cannot be faked without breaking them. */
    static String androidVersion() {
        String name;
        switch (Build.VERSION.SDK_INT) {
            case 21: name = "5.0"; break;
            case 22: name = "5.1"; break;
            case 23: name = "6"; break;
            case 24: name = "7.0"; break;
            case 25: name = "7.1"; break;
            case 26: name = "8.0"; break;
            case 27: name = "8.1"; break;
            case 28: name = "9"; break;
            case 29: name = "10"; break;
            case 30: name = "11"; break;
            case 31: name = "12"; break;
            case 32: name = "12L"; break;
            case 33: name = "13"; break;
            case 34: name = "14"; break;
            case 35: name = "15"; break;
            default: name = Build.VERSION.RELEASE; break;
        }
        return "Android " + name + " (API " + Build.VERSION.SDK_INT + ")";
    }

    /** What the firmware claims, shown next to the real value when they differ. */
    static String claimedRelease() {
        return Build.VERSION.RELEASE;
    }

    /**
     * {total, free} bytes of the app partition. Some firmwares patch the framework and the
     * statvfs path Java uses to inflate these numbers, so the system's own df is asked first.
     */
    static long[] storage() {
        String path = Environment.getDataDirectory().getPath();
        long[] df = dfKb(path);
        if (df != null) return df;
        try {
            StructStatVfs st = Os.statvfs(path);
            return new long[] {st.f_blocks * st.f_frsize, st.f_bavail * st.f_frsize};
        } catch (ErrnoException e) {
            StatFs fs = new StatFs(path);
            return new long[] {fs.getTotalBytes(), fs.getAvailableBytes()};
        }
    }

    /** Parses "df -k <path>": Filesystem 1K-blocks Used Available Use% Mounted. */
    private static long[] dfKb(String path) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/df", "-k", path).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line, last = null;
                while ((line = r.readLine()) != null) last = line;
                if (last == null) return null;
                String[] f = last.trim().split("\\s+");
                if (f.length < 4) return null;
                return new long[] {Long.parseLong(f[1]) * 1024, Long.parseLong(f[3]) * 1024};
            }
        } catch (IOException | NumberFormatException e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Binary units (1 GB = 1024³ bytes), as RAM and partitions are actually sized. */
    static String size(long bytes) {
        double gb = bytes / (1024.0 * 1024 * 1024);
        if (gb >= 1) return String.format(Locale.getDefault(), "%.1f GB", gb);
        return String.format(Locale.getDefault(), "%.0f MB", bytes / (1024.0 * 1024));
    }

    /** {total, available} bytes from /proc/meminfo. */
    static long[] memory() {
        long total = 0, avail = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) total = kb(line);
                else if (line.startsWith("MemAvailable:")) avail = kb(line);
            }
        } catch (IOException ignored) {
            // Leave zeros; the caller shows "desconocido".
        }
        return new long[] {total, avail};
    }

    private static long kb(String line) {
        String digits = line.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 0 : Long.parseLong(digits) * 1024;
    }

    static String chip() {
        String maker = prop("ro.product.chipset");
        String product = prop("ro.build.product");
        String s = (maker + " " + product).trim();
        return s.isEmpty() ? Build.HARDWARE : s;
    }

    static String cpu() {
        boolean is64 = Build.SUPPORTED_64_BIT_ABIS.length > 0;
        return Runtime.getRuntime().availableProcessors() + " núcleos · " + (is64 ? "64" : "32") + " bits";
    }

    /** The mode the HDMI output really runs at, not the size the firmware advertises. */
    static String display(Activity a) {
        Display d = a.getWindowManager().getDefaultDisplay();
        if (Build.VERSION.SDK_INT >= 23) {
            Display.Mode m = d.getMode();
            return m.getPhysicalWidth() + " × " + m.getPhysicalHeight()
                    + String.format(Locale.US, " · %.0f Hz", m.getRefreshRate());
        }
        android.graphics.Point p = new android.graphics.Point();
        d.getRealSize(p);
        return p.x + " × " + p.y;
    }

    static String ipAddress(android.content.Context c) {
        if (Build.VERSION.SDK_INT >= 23) {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    c.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.LinkProperties lp = cm == null ? null : cm.getLinkProperties(cm.getActiveNetwork());
            if (lp != null) {
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    if (la.getAddress() instanceof Inet4Address) {
                        return la.getAddress().getHostAddress() + " (" + lp.getInterfaceName() + ")";
                    }
                }
            }
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address) return addr.getHostAddress() + " (" + ni.getName() + ")";
                }
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        return "—";
    }

    /** android.os.SystemProperties is hidden but readable on all versions this app supports. */
    private static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
