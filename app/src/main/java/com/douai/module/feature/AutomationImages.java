package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Fetches explicit media URLs from host image models without forwarding model credentials. */
public final class AutomationImages {
    private AutomationImages() { }

    public static List<String> incoming(JSONObject content) {
        if (content == null) return Collections.emptyList();
        Set<String> result = new LinkedHashSet<>();
        collect(content, result, 0);
        if (result.isEmpty()) {
            DouAiLog.event("auto_reply", "image_payload", "no_media_url", "keys=" + keys(content));
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    /** Walks the payload instead of trusting one field name; the host schema changes between builds. */
    private static void collect(Object node, Set<String> out, int depth) {
        if (node == null || depth > 6 || out.size() >= ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            java.util.Iterator<String> names = object.keys();
            while (names.hasNext()) {
                Object value = object.opt(names.next());
                if (value instanceof String) {
                    String text = (String) value;
                    if (isMediaUrl(text)) out.add(text);
                } else {
                    collect(value, out, depth + 1);
                }
                if (out.size() >= ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) return;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof String) {
                    String text = (String) value;
                    if (isMediaUrl(text)) out.add(text);
                } else {
                    collect(value, out, depth + 1);
                }
                if (out.size() >= ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) return;
            }
        }
    }

    /** Key names only; payload values are never written to the log. */
    private static String keys(JSONObject content) {
        StringBuilder text = new StringBuilder();
        java.util.Iterator<String> names = content.keys();
        int count = 0;
        while (names.hasNext() && count < 12) {
            if (text.length() > 0) text.append(',');
            text.append(names.next());
            count++;
        }
        return text.toString();
    }

    public static List<ChatImage> load(List<String> urls) throws Exception {
        return load(urls, new MediaReadCancellation());
    }

    static List<ChatImage> load(List<String> urls, MediaReadCancellation cancellation) throws Exception {
        cancellation.start();
        if (urls.size() > ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) throw new IllegalArgumentException("图片最多 " + ChatCompletionClient.MAX_IMAGES_PER_MESSAGE + " 张");
        List<ChatImage> images = new ArrayList<>();
        for (String source : urls) images.add(fetch(source, cancellation));
        return images;
    }

    static ChatImage fetch(String source) throws Exception {
        MediaReadCancellation cancellation = new MediaReadCancellation();
        cancellation.start();
        return fetch(source, cancellation);
    }

    private static ChatImage fetch(String source, MediaReadCancellation cancellation) throws Exception {
        String current = source;
        for (int redirect = 0; redirect < 4; redirect++) {
            cancellation.check();
            if (!isMediaUrl(current)) throw new IllegalArgumentException("图片地址不受支持（仅允许公网 http/https）");
            HttpURLConnection connection = cancellation.open(URI.create(current));
            int status = -1;
            try {
                cancellation.check();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0 Mobile Safari/537.36");
                if (isByteDanceHost(hostOf(current))) connection.setRequestProperty("Referer", "https://www.douyin.com/");
                status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IllegalStateException("图片地址跳转无效");
                    current = URI.create(current).resolve(location).toString();
                    continue;
                }
                if (status != 200) throw new IllegalStateException("图片下载失败");
                if (connection.getContentLengthLong() > ChatImageLoader.MAX_SOURCE_BYTES) throw new IllegalArgumentException("图片超过 " + (ChatImageLoader.MAX_SOURCE_BYTES / (1024 * 1024)) + " MB");
                try (InputStream input = connection.getInputStream()) {
                    ChatImage image = ChatImageLoader.decode(ChatImageLoader.read(input, cancellation), cancellation);
                    cancellation.check();
                    DouAiLog.event("auto_reply", "image_fetch", "ok", "host=" + hostOf(current) + ";bytes=" + image.sizeBytes());
                    return image;
                }
            } catch (Exception failure) {
                cancellation.check();
                String detail = failure instanceof IllegalArgumentException || failure instanceof IllegalStateException
                        ? String.valueOf(failure.getMessage()) : failure.getClass().getSimpleName();
                DouAiLog.event("auto_reply", "image_fetch", "failed",
                        "host=" + hostOf(current) + ";status=" + status + ";type=" + connection.getContentType() + ";error=" + detail);
                throw failure;
            } finally {
                cancellation.close(connection);
            }
        }
        throw new IllegalStateException("图片地址跳转次数过多");
    }

    private static String hostOf(String value) {
        try { String host = URI.create(value).getHost(); return host == null ? "" : host; }
        catch (RuntimeException ignored) { return ""; }
    }

    private static boolean isByteDanceHost(String host) {
        if (host == null || host.isEmpty()) return false;
        for (String domain : new String[]{"douyinpic.com", "byteimg.com", "ibyteimg.com", "pstatp.com",
                "douyincdn.com", "snssdk.com", "bytedance.com", "bytecdn.cn", "douyinstatic.com"}) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    public static boolean isMediaUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            scheme = scheme.toLowerCase(java.util.Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) return false;
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) return false;
            return !isBlockedHost(host.toLowerCase(java.util.Locale.ROOT));
        } catch (RuntimeException ignored) { }
        return false;
    }

    /**
     * Any public host is accepted now; loopback and private literals stay blocked so a crafted
     * message cannot turn image recognition into a local-network fetch.
     */
    private static boolean isBlockedHost(String host) {
        if ("localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local")) return true;
        if (host.indexOf(':') >= 0) return true;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 0 || a == 10 || a == 127
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
