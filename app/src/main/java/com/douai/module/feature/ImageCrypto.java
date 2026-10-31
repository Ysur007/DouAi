package com.douai.module.feature;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signed CDN answers with ciphertext. The key is expected to travel with the message, so the
 * payload is tried against a small matrix of standard layouts; the winner is reported, not guessed.
 */
final class ImageCrypto {
    private ImageCrypto() { }

    private static final String[] MODES = {
            "AES/CBC/PKCS5Padding", "AES/GCM/NoPadding", "AES/CTR/NoPadding", "AES/ECB/PKCS5Padding"
    };
    private static final int[] IV_LENGTHS = {16, 12, 0};

    static final class Result {
        final byte[] data;
        final String label;
        Result(byte[] data, String label) { this.data = data; this.label = label; }
    }

    /** Attempts every plausible key/IV/mode combination and returns the first that yields an image. */
    static Result attempt(byte[] body, List<String> candidates) {
        if (body == null || body.length < 32 || candidates == null || candidates.isEmpty()) return null;
        for (String candidate : candidates) {
            for (byte[] key : keyForms(candidate)) {
                for (String mode : MODES) {
                    for (int ivLength : IV_LENGTHS) {
                        if (mode.contains("ECB") && ivLength != 0) continue;
                        if (mode.contains("GCM") && ivLength == 0) continue;
                        byte[] plain = decrypt(body, key, mode, ivLength);
                        if (plain != null && looksLikeImage(plain)) {
                            return new Result(plain, mode + ";key=" + key.length + ";iv=" + ivLength);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static List<byte[]> keyForms(String candidate) {
        List<byte[]> forms = new ArrayList<>();
        String text = candidate == null ? "" : candidate.trim();
        if (text.isEmpty()) return forms;
        addKey(forms, text.getBytes(StandardCharsets.UTF_8));
        byte[] hex = hexDecode(text);
        if (hex != null) addKey(forms, hex);
        try {
            byte[] b64 = android.util.Base64.decode(text, android.util.Base64.DEFAULT);
            addKey(forms, b64);
        } catch (Throwable ignored) { }
        return forms;
    }

    /** Truncates to a legal AES size instead of padding with bytes the sender never used. */
    private static void addKey(List<byte[]> out, byte[] key) {
        if (key == null) return;
        for (int size : new int[]{32, 24, 16}) {
            if (key.length < size) continue;
            byte[] slice = new byte[size];
            System.arraycopy(key, 0, slice, 0, size);
            boolean known = false;
            for (byte[] existing : out) {
                if (java.util.Arrays.equals(existing, slice)) { known = true; break; }
            }
            if (!known) out.add(slice);
        }
    }

    private static byte[] decrypt(byte[] body, byte[] key, String mode, int ivLength) {
        try {
            Cipher cipher = Cipher.getInstance(mode);
            if (mode.contains("ECB")) {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            } else if (mode.contains("GCM")) {
                byte[] iv = new byte[12];
                byte[] data;
                if (ivLength > 0) {
                    if (body.length <= ivLength) return null;
                    System.arraycopy(body, 0, iv, 0, ivLength);
                    data = slice(body, ivLength);
                } else {
                    data = body;
                }
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, iv));
                return cipher.doFinal(data);
            } else {
                byte[] iv = new byte[16];
                byte[] data;
                if (ivLength > 0) {
                    if (body.length <= ivLength) return null;
                    System.arraycopy(body, 0, iv, 0, ivLength);
                    data = slice(body, ivLength);
                } else {
                    data = body;
                }
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                return cipher.doFinal(data);
            }
            return cipher.doFinal(body);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static byte[] slice(byte[] source, int from) {
        byte[] out = new byte[source.length - from];
        System.arraycopy(source, from, out, 0, out.length);
        return out;
    }

    private static byte[] hexDecode(String text) {
        if (text.length() < 32 || (text.length() & 1) != 0) return null;
        for (int i = 0; i < text.length(); i++) {
            if (Character.digit(text.charAt(i), 16) < 0) return null;
        }
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(text.charAt(i * 2), 16) << 4)
                    | Character.digit(text.charAt(i * 2 + 1), 16));
        }
        return out;
    }

    static boolean looksLikeImage(byte[] data) {
        if (data == null || data.length < 12) return false;
        int b0 = data[0] & 0xFF, b1 = data[1] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8) return true;
        if (b0 == 0x89 && b1 == 0x50) return true;
        if (b0 == 'G' && b1 == 'I') return true;
        if (b0 == 'B' && b1 == 'M') return true;
        return b0 == 'R' && b1 == 'I' && data[8] == 'W' && data[9] == 'E';
    }

    /** Leaf strings whose length matches a key/blob so the caller can attempt them all. */
    static List<String> keyCandidates(org.json.JSONObject content) {
        Set<String> out = new LinkedHashSet<>();
        collect(content, out, 0);
        return new ArrayList<>(out);
    }

    private static void collect(Object node, Set<String> out, int depth) {
        if (node == null || depth > 6 || out.size() >= 24) return;
        if (node instanceof org.json.JSONObject) {
            java.util.Iterator<String> names = ((org.json.JSONObject) node).keys();
            while (names.hasNext()) collect(((org.json.JSONObject) node).opt(names.next()), out, depth + 1);
        } else if (node instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) node;
            for (int i = 0; i < array.length(); i++) collect(array.opt(i), out, depth + 1);
        } else if (node instanceof String) {
            String text = (String) node;
            if (text.startsWith("http") || text.length() < 16 || text.length() > 128) return;
            if (text.length() == 16 || text.length() == 24 || text.length() == 32
                    || text.length() == 44 || text.length() == 48 || text.length() == 64
                    || text.length() == 88 || text.length() == 43) out.add(text);
        }
    }
}
