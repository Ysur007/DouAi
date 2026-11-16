package com.douai.module.feature;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The push process cannot see whether the host still owns a live connection, so the host reports
 * itself on a fixed cadence and the push side treats silence as absence.
 */
public final class HostHeartbeat {
    public static final String ACTION = "com.douai.action.HOST_ALIVE";
    public static final String PUSH_PACKAGE = "com.xiaomi.xmsf";
    static final long INTERVAL_SECONDS = 30L;

    private static volatile boolean started;

    private HostHeartbeat() { }

    public static void start(android.content.Context context) {
        if (started || context == null) return;
        started = true;
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "DouAi-Heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                android.content.Intent intent = new android.content.Intent(ACTION);
                intent.setPackage(PUSH_PACKAGE);
                context.sendBroadcast(intent);
            } catch (Throwable ignored) { }
        }, 0L, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }
}
