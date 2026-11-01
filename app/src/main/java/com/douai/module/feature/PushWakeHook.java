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
    private static final long MIN_INTERVAL_MS = 60_000L;

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
        wake(channel);
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

    private static void wake(String reason) {
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
            // Launch behind the current task so the user is not pulled out of whatever they were doing.
            String stage = "behind_ok";
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
            lastResult = stage + ":" + reason;
            record(context, lastResult);
        } catch (Throwable failure) {
            lastResult = "failed:" + failure.getClass().getSimpleName();
        }
        android.util.Log.i("DouAi", "push_wake " + lastResult);
    }
}
