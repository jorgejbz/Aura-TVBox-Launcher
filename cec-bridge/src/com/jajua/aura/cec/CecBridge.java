package com.jajua.aura.cec;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets the TV's own remote (HDMI-CEC / Samsung Anynet+) drive the box on firmwares whose
 * HdmiCecLocalDevice was patched to drop <User Control Pressed>. The keys still reach the
 * framework and are logged as "Unhandled cec command"; this reads that log and injects the
 * matching Android key, the same way the stock `input` command does.
 *
 * Runs as a long-lived app_process (shell or root):
 *   app_process -Djava.class.path=/path/cecbridge.dex /system/bin com.jajua.aura.cec.CecBridge
 */
public final class CecBridge {
    // Preferred source: the HDMI debug log ("[R]:"), one line per received message.
    private static final Pattern RX_PRESSED =
            Pattern.compile("\\[R\\]:<User Control Pressed>.*params: ([0-9A-Fa-f]{2})");
    private static final String RX_RELEASED = "[R]:<User Control Release>";
    // Fallback when debug logging is off. HdmiLogger drops identical warnings for 20 s, so repeated
    // presses of the same key are lost; only used until the first "[R]" line is seen.
    private static final Pattern PRESSED =
            Pattern.compile("Unhandled cec command:<User Control Pressed>.*params: ([0-9A-Fa-f]{2})");
    private static final String RELEASED = "handleUserControlReleased return";
    private static boolean haveDebugLog;
    /** CEC sends a Release; if one is ever lost, the key is let go after this long. */
    private static final long RELEASE_TIMEOUT_MS = 550;

    private static Method inject;
    private static Object inputManager;

    private static int downCode = -1;
    private static long downTime;
    private static int repeat;
    private static long lastPressAt;

    // The TV keeps its own Home button, and Samsung sends a fixed ~210 ms pulse however long a key is
    // held, so a long press can't be detected. Instead a quick double tap on Back goes home.
    private static final int CEC_EXIT = 0x0D;
    // Samsung TVs space CEC key pulses at least ~470 ms apart however fast you tap, so the window
    // must be wider than that (measured on a UN50TU7000 with a BN59-01259B remote).
    private static final long DOUBLE_TAP_MS = 650;
    private static long lastBackAt;

    public static void main(String[] args) throws Exception {
        Class<?> im = Class.forName("android.hardware.input.InputManager");
        inputManager = im.getMethod("getInstance").invoke(null);
        inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);

        Thread watchdog = new Thread(CecBridge::releaseWatchdog, "cec-release");
        watchdog.setDaemon(true);
        watchdog.start();

        log("listening");
        while (true) {
            // -T 1: start from the newest line, so old presses are not replayed on start.
            Process p = new ProcessBuilder("logcat", "-v", "raw", "-T", "1",
                    "HDMI:D", "HdmiCecLocalDevice:W", "*:S").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) handle(line);
            }
            p.destroy();
            Thread.sleep(1000); // logcat restarted (e.g. buffer cleared): reconnect.
        }
    }

    private static synchronized void handle(String line) {
        Matcher m = RX_PRESSED.matcher(line);
        if (m.find()) {
            haveDebugLog = true;
        } else if (!haveDebugLog) {
            m = PRESSED.matcher(line);
            if (!m.find()) m = null;
        } else {
            m = null;
        }
        if (m != null) {
            int cec = Integer.parseInt(m.group(1), 16);
            int key = toAndroid(cec);
            android.util.Log.i("CecBridge", String.format("rx 0x%02x -> %s", cec,
                    key < 0 ? "(sin asignar)" : KeyEvent.keyCodeToString(key)));
            if (key < 0) return;
            long now = SystemClock.uptimeMillis();
            if (cec == CEC_EXIT) {
                // First Back goes through at once; a second one within DOUBLE_TAP_MS becomes Home.
                if (now - lastBackAt <= DOUBLE_TAP_MS) {
                    lastBackAt = 0;
                    fireHome();
                    return;
                }
                lastBackAt = now;
            }
            if (key == downCode) {
                repeat++; // held: CEC repeats Pressed without a Release
            } else {
                release();
                downCode = key;
                downTime = now;
                repeat = 0;
            }
            lastPressAt = now;
            send(KeyEvent.ACTION_DOWN, key, repeat, downTime);
        } else if (line.contains(haveDebugLog ? RX_RELEASED : RELEASED)) {
            release();
        }
    }

    private static void fireHome() {
        release();
        android.util.Log.i("CecBridge", "double Back -> HOME");
        long t = SystemClock.uptimeMillis();
        send(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0, t);
        send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0, t);
    }

    private static synchronized void release() {
        if (downCode < 0) return;
        send(KeyEvent.ACTION_UP, downCode, 0, downTime);
        downCode = -1;
    }

    private static void releaseWatchdog() {
        while (true) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (CecBridge.class) {
                if (downCode >= 0 && SystemClock.uptimeMillis() - lastPressAt > RELEASE_TIMEOUT_MS) release();
            }
        }
    }

    private static void send(int action, int code, int rep, long down) {
        KeyEvent ev = new KeyEvent(down, SystemClock.uptimeMillis(), action, code, rep, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        try {
            inject.invoke(inputManager, ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
            if (action == KeyEvent.ACTION_DOWN && rep == 0) {
                android.util.Log.i("CecBridge", "inject " + KeyEvent.keyCodeToString(code));
            }
        } catch (Exception e) {
            log("inject failed: " + e);
        }
    }

    /** CEC "UI command" codes (HDMI-CEC 1.4, table 27) to Android keys, as AOSP's HdmiCecKeycode. */
    static int toAndroid(int cec) {
        if (cec >= 0x20 && cec <= 0x29) return KeyEvent.KEYCODE_0 + (cec - 0x20);
        switch (cec) {
            case 0x00: return KeyEvent.KEYCODE_DPAD_CENTER;
            case 0x01: return KeyEvent.KEYCODE_DPAD_UP;
            case 0x02: return KeyEvent.KEYCODE_DPAD_DOWN;
            case 0x03: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 0x04: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 0x09: return KeyEvent.KEYCODE_HOME;            // Root Menu
            case 0x0A: return KeyEvent.KEYCODE_SETTINGS;        // Setup Menu
            case 0x0B: return KeyEvent.KEYCODE_MENU;            // Contents Menu
            case 0x0D: return KeyEvent.KEYCODE_BACK;            // Exit / Return
            case 0x11: return KeyEvent.KEYCODE_MENU;            // Media Context Menu
            case 0x2B: return KeyEvent.KEYCODE_ENTER;
            case 0x30: return KeyEvent.KEYCODE_CHANNEL_UP;
            case 0x31: return KeyEvent.KEYCODE_CHANNEL_DOWN;
            case 0x35: return KeyEvent.KEYCODE_INFO;            // Display Information
            // Samsung's single play/pause button alternates Play and Pause; players (Xuper, Stremio,
            // ExoPlayer apps) react most reliably to the toggle key.
            case 0x44: return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case 0x45: return KeyEvent.KEYCODE_MEDIA_STOP;
            case 0x46: return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case 0x48: return KeyEvent.KEYCODE_MEDIA_REWIND;
            case 0x49: return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            case 0x4B: return KeyEvent.KEYCODE_MEDIA_NEXT;
            case 0x4C: return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case 0x53: return KeyEvent.KEYCODE_GUIDE;
            case 0x61: return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            // Colour keys act as Menu (Aura's options, app menus).
            case 0x71:
            case 0x72:
            case 0x73:
            case 0x74: return KeyEvent.KEYCODE_MENU;
            default: return -1;
        }
    }

    private static void log(String m) {
        System.out.println("CecBridge: " + m);
    }
}
