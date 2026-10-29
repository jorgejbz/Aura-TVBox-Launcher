package com.jajua.aura;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Setup baked into the system partition, so a factory reset brings back the same favourites and
 * remote buttons. Format (Java properties):
 *   favorites=pkg1,pkg2
 *   key.183=com.example.app
 */
final class Defaults {
    static final String PATH = "/system/etc/aura/defaults.properties";

    private static Properties cached;

    private Defaults() {}

    /** Null when the file is absent, so callers fall back to the built-in suggestions. */
    static List<String> favorites() {
        String v = props().getProperty("favorites");
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : Arrays.asList(v.split(","))) {
            if (!p.trim().isEmpty()) out.add(p.trim());
        }
        return out;
    }

    static Map<Integer, String> keys() {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (String name : props().stringPropertyNames()) {
            if (!name.startsWith("key.")) continue;
            try {
                out.put(Integer.parseInt(name.substring(4)), props().getProperty(name).trim());
            } catch (NumberFormatException ignored) {
                // Malformed line; skip it.
            }
        }
        return out;
    }

    private static synchronized Properties props() {
        if (cached == null) {
            cached = new Properties();
            try (FileInputStream in = new FileInputStream(PATH)) {
                cached.load(in);
            } catch (IOException ignored) {
                // No baked-in setup on this device.
            }
        }
        return cached;
    }
}
