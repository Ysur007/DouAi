package com.douai.module.feature;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * The Xiaomi push framework draws Douyin notifications itself, so the app process never starts.
 * This hook notices such a notification and tries to bring Douyin back, nothing else.
 */
public final class PushWakeHook {
    private static final String TARGET = "com.ss.android.ugc.aweme";
    private static final String LAUNCH = "com.ss.android.ugc.aweme.splash.SplashActivity";
    private static final long MIN_INTERVAL_MS = 180_000L;

    private static volatile long lastWake;
    private static volatile String lastResult = "idle";

    private PushWakeHook() { }

    public static void install(XposedInterface framework) {
        hookNotify(framework, "notify", String.class, int.class, Notification.class);
        hookNotify(framework, "notify", int.class, Notification.class);
        hookNotify(framework, "notifyAsPackage", String.class, String.class, int.class, Notification.class);
        hookNotify(framework, "notifyAsPackage", String.class, int.class, Notification.class);
        android.util.Log.i("DouAi", "push_wake hooked result=" + lastResult);
    }

    private static void hookNotify(XposedInterface framework, String name, Class<?>... types) {
        try {
            Method method = NotificationManager.class.getDeclaredMethod(name, types);
            framework.hook(method)
                    .setId("DouAi.PushWake." + name + types.length)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        try { inspect(chain.getArg(types.length - 1)); } catch (Throwable ignored) { }
                        return chain.proceed();
                    });
        } catch (Throwable failure) {
            android.util.Log.w("DouAi", "push_wake hook " + name + "/" + types.length + " failed: "
                    + failure.getClass().getSimpleName());
        }
    }

    /** The channel id embeds the owner package, which is the only stable signal here. */
    private static void inspect(Object candidate) {
        if (!(candidate instanceof Notification)) return;
        Notification notification = (Notification) candidate;
        String channel = notification.getChannelId();
        android.util.Log.i("DouAi", "notify_seen channel=" + channel);
        if (channel == null || !channel.contains(TARGET)) return;
        wake(channel, notification.contentIntent);
    }

    /** Reports whether the host process is listed; the size reveals how much the system shows us. */
    private static String hostState(android.content.Context context) {
        try {
            android.app.ActivityManager manager =
                    (android.app.ActivityManager) context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes =
                    manager.getRunningAppProcesses();
            if (processes == null) return "procs=none";
            for (android.app.ActivityManager.RunningAppProcessInfo info : processes) {
                if (!TARGET.equals(info.processName)) continue;
                // A cached entry can linger after the process is gone, so only a live service
                // counts as running; anything else is treated as needing a wake.
                boolean active = info.importance
                        <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE;
                return (active ? "alive" : "idle") + ";procs=" + processes.size()
                        + ";imp=" + info.importance;
            }
            return "gone;procs=" + processes.size();
        } catch (Throwable failure) {
            return "unknown:" + failure.getClass().getSimpleName();
        }
    }

    /** LSPosed redirects module logging, so the outcome is written where it can be read. */
    private static void record(android.content.Context context, String text) {
        try {
            java.io.File file = new java.io.File(context.getFilesDir(), "douai_push_wake.log");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, false)) {
                out.write((System.currentTimeMillis() + " " + text).getBytes("UTF-8"));
            }
        } catch (Throwable ignored) { }
    }

    private static void wake(String reason, android.app.PendingIntent notificationIntent) {
        long now = System.currentTimeMillis();
        if (now - lastWake < MIN_INTERVAL_MS) return;
        lastWake = now;
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object app = thread.getMethod("currentApplication").invoke(null);
            if (app == null) { lastResult = "no_app_context"; return; }
            Intent intent = new Intent();
            intent.setClassName(TARGET, LAUNCH);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.Context context = (android.content.Context) app;

            // A live host already owns the connection, so waking it would only yank the user around.
            String alive = hostState(context);
            if (alive.startsWith("alive")) {
                lastResult = "skipped_alive:" + reason + ";" + alive;
                record(context, lastResult);
                return;
            }

            // With the screen off the device may be dozing, so the CPU is held awake for the launch.
            android.os.PowerManager power =
                    (android.os.PowerManager) context.getSystemService(android.content.Context.POWER_SERVICE);
            // Both a locked and an unlocked screen are wanted here; the only thing that stops a
            // wake is the host already running, which is checked above.
            boolean interactive = power != null && power.isInteractive();
            android.os.PowerManager.WakeLock lock = null;
            if (power != null) {
                try {
                    lock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DouAi:pushwake");
                    lock.acquire(15_000L);
                } catch (Throwable ignored) { }
            }
            // A notification's own PendingIntent is launched as the notification, which the system
            // still honours while the device sleeps; a plain startActivity is silently dropped.
            String stage = "no_pending";
            if (notificationIntent != null) {
                try {
                    notificationIntent.send();
                    lastResult = "pending_sent:" + reason;
                    record(context, lastResult);
                    return;
                } catch (Throwable failure) {
                    stage = "pending_failed:" + failure.getClass().getSimpleName();
                }
            }
            try {
                // setLaunchTaskBehind is not part of the public SDK, so it is reached reflectively.
                android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                java.lang.reflect.Method setter = android.app.ActivityOptions.class
                        .getMethod("setLaunchTaskBehind", boolean.class);
                setter.invoke(options, true);
                context.startActivity(intent, options.toBundle());
            } catch (Throwable behind) {
                stage = "behind_failed:" + behind.getClass().getSimpleName();
                try {
                    context.startActivity(intent);
                    stage = stage + ";front_ok";
                } catch (Throwable front) {
                    stage = stage + ";front_failed:" + front.getClass().getSimpleName();
                }
            }
            if (lock != null && lock.isHeld()) {
                try { lock.release(); } catch (Throwable ignored) { }
            }
            lastResult = stage + ":" + reason + ";screen_on=" + interactive;
            record(context, lastResult);
        } catch (Throwable failure) {
            lastResult = "failed:" + failure.getClass().getSimpleName();
        }
        android.util.Log.i("DouAi", "push_wake " + lastResult);
    }
}
