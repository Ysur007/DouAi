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
        DouAiLog.event("auto_reply", "image_payload", "dump", flatten(content, "", new StringBuilder()));
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    /**
     * Some IM payloads embed the thumbnail itself as base64 (inline_pic). Scanning for those
     * strings is cheap enough to run on the receipt thread; decoding happens on the worker.
     */
    public static List<String> inlinePayloads(JSONObject content) {
        List<String> out = new ArrayList<>();
        if (content == null) return out;
        collectBase64(content, out, 0);
        return out;
    }

    private static void collectBase64(Object node, List<String> out, int depth) {
        if (node == null || depth > 6 || out.size() >= 4) return;
        if (node instanceof JSONObject) {
            java.util.Iterator<String> names = ((JSONObject) node).keys();
            while (names.hasNext()) collectBase64(((JSONObject) node).opt(names.next()), out, depth + 1);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) collectBase64(array.opt(i), out, depth + 1);
        } else if (node instanceof String) {
            String text = (String) node;
            // Base64 of a real thumbnail is far longer than any identifier and never starts with http.
            if (text.length() >= 400 && text.length() <= 4_000_000 && !text.startsWith("http")) out.add(text);
        }
    }

    /** Decodes inline payloads into request-ready JPEGs; needs no network and no CDN key. */
    public static List<ChatImage> decodeInline(List<String> payloads) {
        List<ChatImage> images = new ArrayList<>();
        if (payloads == null) return images;
        for (String payload : payloads) {
            try {
                byte[] data = android.util.Base64.decode(payload, android.util.Base64.DEFAULT);
                if (!looksLikeImage(data)) continue;
                MediaReadCancellation cancellation = new MediaReadCancellation();
                cancellation.start();
                images.add(ChatImageLoader.decode(data, cancellation));
                DouAiLog.event("auto_reply", "image_inline", "decoded", "bytes=" + data.length);
            } catch (Throwable failure) {
                DouAiLog.event("auto_reply", "image_inline", "skipped",
                        "error=" + failure.getClass().getSimpleName());
            }
            if (images.size() >= ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) break;
        }
        return images;
    }

    private static boolean looksLikeImage(byte[] data) {
        if (data == null || data.length < 12) return false;
        int b0 = data[0] & 0xFF, b1 = data[1] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8) return true;                       // JPEG
        if (b0 == 0x89 && b1 == 0x50) return true;                       // PNG
        if (b0 == 'G' && b1 == 'I') return true;                         // GIF
        if (b0 == 'B' && b1 == 'M') return true;                         // BMP
        return b0 == 'R' && b1 == 'I' && data[8] == 'W' && data[9] == 'E'; // RIFF/WEBP
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

    /** Compact "path=value" listing so an unknown schema can be read from one log line. */
    private static String flatten(Object node, String path, StringBuilder out) {
        if (node == null || out.length() > 1400) return trim(out);
        if (node instanceof JSONObject) {
            java.util.Iterator<String> names = ((JSONObject) node).keys();
            while (names.hasNext()) {
                String name = names.next();
                flatten(((JSONObject) node).opt(name), path.isEmpty() ? name : path + "." + name, out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && i < 4; i++) {
                flatten(array.opt(i), path + "[" + i + "]", out);
            }
        } else {
            String text = String.valueOf(node);
            int cap = text.startsWith("http") ? 22 : 40;
            if (text.length() > cap) text = text.substring(0, cap) + "~" + text.length();
            if (out.length() > 0) out.append('|');
            out.append(path).append('=').append(text);
        }
        return trim(out);
    }

    private static String trim(StringBuilder out) {
        return out.length() > 1400 ? out.substring(0, 1400) : out.toString();
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
        Exception last = null;
        int attempts = 0;
        for (String source : urls) {
            if (attempts++ >= 2) break;
            try { images.add(fetch(source, cancellation)); }
            catch (Exception failure) { last = failure; }
        }
        if (images.isEmpty() && last != null) throw last;
        return images;
    }

    static ChatImage fetch(String source) throws Exception {
        MediaReadCancellation cancellation = new MediaReadCancellation();
        cancellation.start();
        return fetch(source, cancellation);
    }

    private static ChatImage fetch(String source, MediaReadCancellation cancellation) throws Exception {
        ChatImage bridged = HostImageBridge.load(source, cancellation);
        if (bridged != null) return bridged;
        String current = source;
        for (int redirect = 0; redirect < 4; redirect++) {
            cancellation.check();
            if (!isMediaUrl(current)) throw new IllegalArgumentException("图片地址不受支持（仅允许公网 http/https）");
            HttpURLConnection connection = cancellation.open(URI.create(current));
            int status = -1;
            byte[] body = null;
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
                    body = ChatImageLoader.read(input, cancellation);
                }
                byte[] data = isGzip(body) ? gunzip(body) : body;
                ChatImage image = ChatImageLoader.decode(data, cancellation);
                cancellation.check();
                DouAiLog.event("auto_reply", "image_fetch", "ok",
                        "host=" + hostOf(current) + ";bytes=" + image.sizeBytes() + ";magic=" + magic(body));
                return image;
            } catch (Exception failure) {
                cancellation.check();
                String detail = failure instanceof IllegalArgumentException || failure instanceof IllegalStateException
                        ? String.valueOf(failure.getMessage()) : failure.getClass().getSimpleName();
                DouAiLog.event("auto_reply", "image_fetch", "failed",
                        "host=" + hostOf(current) + ";status=" + status + ";type=" + connection.getContentType()
                                + ";encoding=" + connection.getContentEncoding() + ";magic=" + magic(body) + ";error=" + detail);
                throw failure;
            } finally {
                cancellation.close(connection);
            }
        }
        throw new IllegalStateException("图片地址跳转次数过多");
    }

    private static boolean isGzip(byte[] data) {
        return data != null && data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B;
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        try (java.util.zip.GZIPInputStream input = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data));
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > ChatImageLoader.MAX_SOURCE_BYTES) {
                    throw new IllegalArgumentException("图片超过 " + (ChatImageLoader.MAX_SOURCE_BYTES / (1024 * 1024)) + " MB");
                }
            }
            return output.toByteArray();
        }
    }

    private static String magic(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(4, data.length); i++) text.append(String.format("%02x", data[i] & 0xFF));
        return text.toString();
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
