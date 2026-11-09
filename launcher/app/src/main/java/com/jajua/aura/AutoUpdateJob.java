package com.jajua.aura;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Calendar;
import java.util.List;

/**
 * Keeps the box's apps current without anyone pressing a button: once a night (03:00-05:59) it asks
 * the online app folder and installs the newer versions of apps the box already has. New apps are
 * never installed here: they wait for the user on the "Actualizar" shortcut. Only runs when Aura may
 * install without Android's confirmation screen (Aura in /system/priv-app), so it never pops up.
 */
public final class AutoUpdateJob extends JobService {
    private static final int JOB_ID = 4201;
    private static final long EVERY_MS = 60L * 60 * 1000;
    private static final int FROM_HOUR = 3, TO_HOUR = 6;
    private static final String PREFS = "auto_update", LAST_RUN = "last_run", ENABLED = "enabled";

    private Thread thread;

    /** Safe to call on every start: keeps a single hourly job (it survives reboots). */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, AutoUpdateJob.class))
                .setPeriodic(EVERY_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!due(this)) return false;
        thread = new Thread(() -> {
            run(getApplicationContext());
            jobFinished(params, false);
        }, "aura-auto-update");
        thread.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (thread != null) thread.interrupt();
        return false; // The next hourly run tries again.
    }

    /** The owner's choice in Sistema (on unless turned off): install app updates at night or not. */
    static boolean enabled(Context c) {
        return prefs(c).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(ENABLED, on).apply();
    }

    private static boolean due(Context c) {
        if (!enabled(c) || AppsCatalog.source() == null || !Installer.quiet(c)) return false;
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (hour < FROM_HOUR || hour >= TO_HOUR) return false;
        long last = prefs(c).getLong(LAST_RUN, 0);
        return System.currentTimeMillis() - last > 12L * 60 * 60 * 1000;
    }

    private static void run(Context c) {
        prefs(c).edit().putLong(LAST_RUN, System.currentTimeMillis()).apply();
        try {
            AppsCatalog.checkAura(c);
        } catch (Exception ignored) {
            // Only feeds the badge; the apps below matter more.
        }
        List<AppsCatalog.Offer> offers;
        try {
            offers = AppsCatalog.check(c);
        } catch (Exception e) {
            return; // No network or GitHub down: tomorrow night.
        }
        for (AppsCatalog.Offer o : offers) {
            if (!o.update || Thread.currentThread().isInterrupted()) continue;
            try {
                List<File> apks = AppsCatalog.fetch(c, o);
                Installer.installAndWait(c, apks, o.pkg, 15);
            } catch (Exception ignored) {
                // This app stays offered on the "Actualizar" shortcut.
            } finally {
                AppsCatalog.discard(c, o);
            }
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
