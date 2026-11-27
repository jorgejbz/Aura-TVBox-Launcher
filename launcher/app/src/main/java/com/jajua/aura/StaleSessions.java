package com.jajua.aura;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Frees the space of install sessions that root or adb left half-done. A first-boot install cut by a power cut, or an
 * adb install stuck behind the firmware's broken Play Store verifier, keeps a full copy of the APK in
 * /data/app/vmdl*.tmp (70-180 MB each). Android prunes them after 3 days, but these boxes boot without a clock, so it
 * never happens.
 *
 * Only the session's owner or root may abandon it, and an app cannot use su on these firmwares (only root and the adb
 * shell can). The adb shell of this same box can: Aura connects to it on 127.0.0.1, without the network, and runs
 * "su 0 pm install-abandon". If ADB was closed it is opened for those few seconds and closed again.
 *
 * Safe by construction: only sessions owned by root/adb (never an app's, such as Play Store's or Aura's), and only
 * ones that already existed in an earlier boot, so an install in progress right now (a box being prepared from a PC)
 * is never touched. Leftovers are therefore removed on the boot after Aura first sees them.
 * /system/bin/preinstall.sh does the same at boot on boxes prepared or updated with the PC scripts.
 */
final class StaleSessions {
    private static final String TAG = "AuraLimpieza";
    private static final String PREFS = "stale_sessions";
    private static final String CHECKED_BOOT = "checked_boot";
    private static final String SEEN = "seen.";

    private StaleSessions() {}

    /** Once per boot, on a worker thread. */
    static void run(Context c) {
        int boot = bootCount(c);
        if (boot < 0) return; // Without a boot counter "an earlier boot" cannot be told apart: do nothing.
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getInt(CHECKED_BOOT, -1) == boot) return;
        p.edit().putInt(CHECKED_BOOT, boot).apply();

        List<PackageInstaller.SessionInfo> all;
        try {
            all = c.getPackageManager().getPackageInstaller().getAllSessions();
        } catch (RuntimeException e) {
            return;
        }
        Set<String> present = new HashSet<>();
        List<Integer> stale = new ArrayList<>();
        SharedPreferences.Editor e = p.edit();
        for (PackageInstaller.SessionInfo s : all) {
            String installer = s.getInstallerPackageName();
            // Sessions created by root or the adb shell have no installer app (or the shell's package).
            if (installer != null && !"com.android.shell".equals(installer)) continue;
            String key = SEEN + s.getSessionId();
            present.add(key);
            int first = p.getInt(key, -1);
            if (first < 0) e.putInt(key, boot);
            else if (first < boot) stale.add(s.getSessionId());
        }
        for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
            if (entry.getKey().startsWith(SEEN) && !present.contains(entry.getKey())) e.remove(entry.getKey());
        }
        e.apply();
        if (stale.isEmpty()) return;

        StringBuilder cmd = new StringBuilder();
        for (int id : stale) {
            if (cmd.length() > 0) cmd.append("; ");
            cmd.append("su 0 pm install-abandon ").append(id);
        }
        Log.i(TAG, "abandoning " + stale);
        boolean opened = false;
        try {
            int port = SystemInfo.adbNetworkPort();
            if (port <= 0) {
                SystemInfo.setAdbNetwork(c, true);
                opened = true;
                for (int i = 0; i < 20 && (port = SystemInfo.adbNetworkPort()) <= 0; i++) Thread.sleep(500);
            }
            if (port <= 0) {
                Log.w(TAG, "adb did not open");
                return;
            }
            Log.i(TAG, AdbLocal.shell(port, cmd.toString(), 30000).trim());
        } catch (IOException | InterruptedException | RuntimeException ex) {
            Log.w(TAG, "cleanup failed: " + ex);
        } finally {
            if (opened) SystemInfo.setAdbNetwork(c, false);
        }
    }

    private static int bootCount(Context c) {
        if (Build.VERSION.SDK_INT < 24) return -1;
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException e) {
            return -1;
        }
    }

    /** The smallest adb client: connects to this box's own adbd and runs one shell command. */
    static final class AdbLocal {
        private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
                CLSE = 0x45534c43, WRTE = 0x45545257;
        private static final int VERSION = 0x01000000, MAX_DATA = 4096, LOCAL_ID = 1;

        private AdbLocal() {}

        static String shell(int port, String command, int timeoutMs) throws IOException {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                s.setSoTimeout(timeoutMs);
                OutputStream out = s.getOutputStream();
                DataInputStream in = new DataInputStream(s.getInputStream());
                send(out, CNXN, VERSION, MAX_DATA, "host::\0");
                int[] h = new int[6];
                byte[] data = read(in, h);
                if (h[0] == AUTH) throw new IOException("adb asks for a key");
                if (h[0] != CNXN) throw new IOException("no CNXN");
                send(out, OPEN, LOCAL_ID, 0, "shell:" + command + "\0");
                StringBuilder text = new StringBuilder();
                while (true) {
                    data = read(in, h);
                    if (h[0] == WRTE) {
                        text.append(new String(data, "UTF-8"));
                        send(out, OKAY, LOCAL_ID, h[1], "");
                    } else if (h[0] == CLSE) {
                        send(out, CLSE, LOCAL_ID, h[1], "");
                        return text.toString();
                    } else if (h[0] != OKAY) {
                        throw new IOException("unexpected " + Integer.toHexString(h[0]));
                    }
                }
            }
        }

        private static void send(OutputStream out, int cmd, int arg0, int arg1, String payload) throws IOException {
            byte[] data = payload.getBytes("UTF-8");
            int sum = 0;
            for (byte b : data) sum += b & 0xFF;
            ByteBuffer bb = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
            out.write(bb.array());
            out.flush();
        }

        private static byte[] read(DataInputStream in, int[] h) throws IOException {
            byte[] head = new byte[24];
            in.readFully(head);
            ByteBuffer bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 6; i++) h[i] = bb.getInt();
            if (h[3] < 0 || h[3] > 1024 * 1024) throw new IOException("bad length");
            byte[] data = new byte[h[3]];
            in.readFully(data);
            return data;
        }
    }
}
