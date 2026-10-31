package com.douai.module.feature;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Cancellation and deadline state for one automatic-comment media read. */
final class MediaReadCancellation {
    interface ConnectionFactory { HttpURLConnection open(URI uri) throws Exception; }
    private static final long DEFAULT_TIMEOUT_MILLIS = 180_000;
    private final long timeoutNanos;
    private final LongSupplier clock;
    private final ConnectionFactory connections;
    private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();
    private volatile long deadlineNanos;
    private volatile boolean cancelled;
    private volatile boolean timedOut;

    MediaReadCancellation() { this(DEFAULT_TIMEOUT_MILLIS); }

    MediaReadCancellation(long timeoutMillis) { this(timeoutMillis, System::nanoTime); }

    MediaReadCancellation(long timeoutMillis, LongSupplier clock) {
        this(timeoutMillis, clock, uri -> (HttpURLConnection) uri.toURL().openConnection());
    }

    MediaReadCancellation(long timeoutMillis, LongSupplier clock, ConnectionFactory connections) {
        if (timeoutMillis < 1) throw new IllegalArgumentException("Media timeout must be positive");
        timeoutNanos = timeoutMillis * 1_000_000L;
        this.clock = clock;
        this.connections = connections;
    }

    void start() throws Exception {
        check();
        if (deadlineNanos == 0) {
            synchronized (this) {
                if (deadlineNanos == 0) deadlineNanos = clock.getAsLong() + timeoutNanos;
            }
        }
        check();
    }

    void check() throws Exception {
        if (timedOut) throw new TimeoutException("媒体读取超时");
        if (Thread.currentThread().isInterrupted()) {
            cancel();
            throw new CancellationException("媒体读取已取消");
        }
        if (cancelled) throw new CancellationException("媒体读取已取消");
        long deadline = deadlineNanos;
        if (deadline != 0 && clock.getAsLong() - deadline >= 0) {
            timedOut = true;
            cancel();
            throw new TimeoutException("媒体读取超时");
        }
    }

    int timeoutMillis(int maximumMillis) throws Exception {
        check();
        long deadline = deadlineNanos;
        if (deadline == 0) return maximumMillis;
        long remaining = deadline - clock.getAsLong();
        if (remaining <= 0) {
            check();
            throw new TimeoutException("媒体读取超时");
        }
        return (int) Math.min(maximumMillis, Math.max(1, (remaining + 999_999L) / 1_000_000L));
    }

    HttpURLConnection open(URI uri) throws Exception {
        check();
        HttpURLConnection connection = connections.open(uri);
        try {
            connection.setConnectTimeout(timeoutMillis(10_000));
            connection.setReadTimeout(timeoutMillis(10_000));
            connection.setInstanceFollowRedirects(false);
            attach(connection);
            return connection;
        } catch (Exception failure) {
            detach(connection);
            disconnect(connection);
            throw failure;
        }
    }

    void attach(HttpURLConnection connection) throws Exception {
        activeConnection.set(connection);
        try { check(); }
        catch (Exception failure) {
            detach(connection);
            disconnect(connection);
            throw failure;
        }
    }

    void detach(HttpURLConnection connection) { activeConnection.compareAndSet(connection, null); }

    void close(HttpURLConnection connection) {
        detach(connection);
        disconnect(connection);
    }

    void cancel() {
        cancelled = true;
        disconnect(activeConnection.getAndSet(null));
    }

    private static void disconnect(HttpURLConnection connection) {
        if (connection != null) {
            try { connection.disconnect(); } catch (RuntimeException ignored) { }
        }
    }
}
