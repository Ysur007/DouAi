package com.douai.module.feature;

import android.graphics.Bitmap;
import android.net.Uri;

import com.douai.module.diagnostics.DouAiLog;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * Reuses the host Fresco pipeline so encrypted IM payloads are fetched, decrypted and decoded by
 * the host itself. The module never sees a key and never persists the image.
 */
public final class HostImageBridge {
    private static final int TIMEOUT_SECONDS = 15;
    private static final int MAX_EDGE = 2048;
    private static volatile HostImageBridge instance;
    private static volatile ClassLoader hostLoader;
    private static final Map<String, Object> CAPTURED = Collections.synchronizedMap(
            new LinkedHashMap<String, Object>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    return size() > 24;
                }
            });

    private final ClassLoader loader;
    private final Object pipeline;
    private final Method fetchDecodedImage;
    private final Method fromUriUri;
    private final Method fromUriString;
    private final Method subscribe;
    private final Method close;
    private final Method isFinished;
    private final Method getResult;
    private final Class<?> subscriberClass;
    private final Method referenceGet;
    private final Method referenceClose;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "DouAi-HostImage");
        thread.setDaemon(true);
        return thread;
    });

    private HostImageBridge(ClassLoader loader, Object pipeline, Method fetchDecodedImage,
                            Method fromUriUri, Method fromUriString, Method subscribe, Method close,
                            Method isFinished, Method getResult, Class<?> subscriberClass,
                            Method referenceGet, Method referenceClose) {
        this.loader = loader;
        this.pipeline = pipeline;
        this.fetchDecodedImage = fetchDecodedImage;
        this.fromUriUri = fromUriUri;
        this.fromUriString = fromUriString;
        this.subscribe = subscribe;
        this.close = close;
        this.isFinished = isFinished;
        this.getResult = getResult;
        this.subscriberClass = subscriberClass;
        this.referenceGet = referenceGet;
        this.referenceClose = referenceClose;
    }

    /** Only remembers the host loader; Fresco may not be initialised yet at install time. */
    public static void install(ClassLoader loader, XposedInterface framework) {
        if (loader != null) hostLoader = loader;
        if (framework != null) captureRequests(framework, loader);
    }

    /** Remembers the request the host itself builds for a URI, including any decryption extras. */
    private static void captureRequests(XposedInterface framework, ClassLoader loader) {
        try {
            Class<?> requestClass = Class.forName("com.facebook.imagepipeline.request.ImageRequest", false, loader);
            Class<?> pipelineClass = Class.forName("com.facebook.imagepipeline.core.ImagePipeline", false, loader);
            Method submit = pipelineClass.getMethod("submitFetchRequest", requestClass, Object.class);
            Method getSourceUri = requestClass.getMethod("getSourceUri");
            framework.hook(submit)
                    .setId("DouAi.HostImage.RequestCapture")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        try {
                            Object request = chain.getArg(0);
                            Object uri = getSourceUri.invoke(request);
                            if (uri != null) CAPTURED.put(uri.toString(), request);
                        } catch (Throwable ignored) { }
                        return chain.proceed();
                    });
            DouAiLog.event("auto_reply", "image_bridge", "capture_installed", "method=submitFetchRequest");
        } catch (Throwable failure) {
            DouAiLog.event("auto_reply", "image_bridge", "capture_unavailable",
                    "error=" + failure.getClass().getSimpleName() + ":" + failure.getMessage());
        }
    }

    private static HostImageBridge bridge() {
        HostImageBridge current = instance;
        if (current != null) return current;
        synchronized (HostImageBridge.class) {
            if (instance != null) return instance;
            ClassLoader loader = hostLoader != null ? hostLoader : HostImageBridge.class.getClassLoader();
            try {
                instance = create(loader);
                DouAiLog.event("auto_reply", "image_bridge", "installed", "pipeline_present=true");
            } catch (Throwable failure) {
                DouAiLog.event("auto_reply", "image_bridge", "install_failed",
                        "error=" + failure.getClass().getSimpleName() + ":" + failure.getMessage());
            }
            return instance;
        }
    }

    private static HostImageBridge create(ClassLoader hostLoader) throws Exception {
        Object pipeline = null;
        try {
            Class<?> factoryClass = Class.forName("com.facebook.imagepipeline.core.ImagePipelineFactory", false, hostLoader);
            Object factory = factoryClass.getMethod("getInstance").invoke(null);
            pipeline = factoryClass.getMethod("getImagePipeline").invoke(factory);
        } catch (Throwable ignored) { }
        if (pipeline == null) {
            Class<?> fresco = Class.forName("com.facebook.drawee.backends.pipeline.Fresco", false, hostLoader);
            pipeline = fresco.getMethod("getImagePipeline").invoke(null);
        }
        if (pipeline == null) throw new IllegalStateException("pipeline_missing");

        Class<?> requestClass = Class.forName("com.facebook.imagepipeline.request.ImageRequest", false, hostLoader);
        Method fromUriUri;
        Method fromUriString;
        try { fromUriUri = requestClass.getMethod("fromUri", Uri.class); }
        catch (NoSuchMethodException missing) { fromUriUri = null; }
        try { fromUriString = requestClass.getMethod("fromUri", String.class); }
        catch (NoSuchMethodException missing) { fromUriString = null; }
        if (fromUriUri == null && fromUriString == null) throw new NoSuchMethodException("ImageRequest.fromUri");

        Method fetch = pipeline.getClass().getMethod("fetchDecodedImage", requestClass, Object.class);
        Class<?> dataSource = Class.forName("com.facebook.datasource.DataSource", false, hostLoader);
        Class<?> subscriber = Class.forName("com.facebook.datasource.DataSubscriber", false, hostLoader);
        Method subscribe = dataSource.getMethod("subscribe", subscriber, java.util.concurrent.Executor.class);
        Method close = dataSource.getMethod("close");
        Method isFinished = dataSource.getMethod("isFinished");
        Method getResult = dataSource.getMethod("getResult");

        Class<?> reference = Class.forName("com.facebook.common.references.CloseableReference", false, hostLoader);
        Method referenceGet = reference.getMethod("get");
        Method referenceClose = reference.getMethod("close");

        return new HostImageBridge(hostLoader, pipeline, fetch, fromUriUri, fromUriString,
                subscribe, close, isFinished, getResult, subscriber, referenceGet, referenceClose);
    }

    public static ChatImage load(String url, MediaReadCancellation cancellation) {
        HostImageBridge bridge = bridge();
        if (bridge == null || url == null || url.isEmpty()) return null;
        try {
            cancellation.check();
            ChatImage image = bridge.fetch(url, cancellation);
            DouAiLog.event("auto_reply", "image_bridge", image == null ? "empty" : "decoded",
                    image == null ? "result=null" : "bytes=" + image.sizeBytes());
            return image;
        } catch (Throwable failure) {
            DouAiLog.event("auto_reply", "image_bridge", "failed",
                    "error=" + failure.getClass().getSimpleName() + ":" + failure.getMessage());
            return null;
        }
    }

    private ChatImage fetch(String url, MediaReadCancellation cancellation) throws Exception {
        Object request = CAPTURED.get(url);
        if (request == null) {
            request = fromUriUri != null
                    ? fromUriUri.invoke(null, Uri.parse(url))
                    : fromUriString.invoke(null, url);
        }
        Object dataSource = fetchDecodedImage.invoke(pipeline, request, "DouAi:auto-reply");
        if (dataSource == null) throw new IllegalStateException("empty_data_source");

        CompletableFuture<Bitmap> result = new CompletableFuture<>();
        Object subscriber = Proxy.newProxyInstance(loader, new Class<?>[]{subscriberClass},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onNewResult": {
                            Object source = args == null || args.length == 0 ? dataSource : args[0];
                            if (source != null && Boolean.TRUE.equals(isFinished.invoke(source))) {
                                result.complete(copyBitmap(source));
                            }
                            return null;
                        }
                        case "onFailure": {
                            String cause = "";
                            try {
                                Object source = args == null || args.length == 0 ? null : args[0];
                                Object value = source == null ? null
                                        : source.getClass().getMethod("getFailureCause").invoke(source);
                                if (value instanceof Throwable) {
                                    cause = ((Throwable) value).getClass().getSimpleName() + ":" + ((Throwable) value).getMessage();
                                }
                            } catch (Throwable ignored) { }
                            result.completeExceptionally(new IllegalStateException("host_fetch_failed" + (cause.isEmpty() ? "" : ":" + cause)));
                            return null;
                        }
                        case "onCancellation":
                            result.cancel(false);
                            return null;
                        case "onProgressUpdate":
                            return null;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == (args == null ? null : args[0]);
                        case "toString":
                            return "DouAiHostImageSubscriber";
                        default:
                            return null;
                    }
                });

        try {
            subscribe.invoke(dataSource, subscriber, worker);
            Bitmap bitmap = result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            cancellation.check();
            if (bitmap == null) throw new IllegalStateException("bitmap_missing");
            byte[] jpeg = toJpeg(bitmap);
            return new ChatImage("image/jpeg", jpeg);
        } finally {
            try { close.invoke(dataSource); } catch (Throwable ignored) { }
        }
    }

    private Bitmap copyBitmap(Object dataSource) {
        Object reference = null;
        try {
            reference = getResult.invoke(dataSource);
            if (reference == null) return null;
            Object image = referenceGet.invoke(reference);
            if (image == null) return null;
            Method bitmapMethod = image.getClass().getMethod("getUnderlyingBitmap");
            Object value = bitmapMethod.invoke(image);
            if (!(value instanceof Bitmap)) return null;
            Bitmap source = (Bitmap) value;
            return source.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable failure) {
            DouAiLog.event("auto_reply", "image_bridge", "decode_failed",
                    "error=" + failure.getClass().getSimpleName());
            return null;
        } finally {
            if (reference != null) {
                try { referenceClose.invoke(reference); } catch (Throwable ignored) { }
            }
        }
    }

    private static byte[] toJpeg(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int edge = Math.max(width, height);
        Bitmap scaled = source;
        if (edge > MAX_EDGE) {
            float ratio = MAX_EDGE / (float) edge;
            scaled = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(width * ratio)),
                    Math.max(1, Math.round(height * ratio)), true);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, output);
        if (scaled != source) scaled.recycle();
        return output.toByteArray();
    }
}
