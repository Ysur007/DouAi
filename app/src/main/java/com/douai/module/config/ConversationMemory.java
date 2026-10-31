package com.douai.module.config;

import android.content.Context;

import com.douai.module.feature.ChatCompletionClient;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Long-term memory for one conversation: once N turns accumulate the model rewrites them into a
 * short background note that is prepended to the system prompt of later requests.
 */
public final class ConversationMemory {
    /** Turns that trigger a rewrite; small enough to keep context fresh, large enough to be cheap. */
    private static final int TURNS_PER_SUMMARY = 6;
    private static final int MAX_SUMMARY_CHARS = 600;
    private static final int MAX_TURN_CHARS = 200;
    private static final String INSTRUCTION =
            "你是对话记忆压缩器。把下面的聊天记录压缩成不超过 120 字的第三人称背景说明，"
                    + "只保留：对方怎么称呼、双方关系、聊过的话题、没说完的事、明确偏好。"
                    + "不要角色扮演，不要评价，不要输出格式标记，只输出这段说明。";

    private static final JSONObject STORE = new JSONObject();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DouAi-Memory");
        thread.setDaemon(true);
        return thread;
    });
    private static File file;

    private ConversationMemory() { }

    public static synchronized void init(Context context) {
        if (file != null || context == null) return;
        file = new File(context.getFilesDir(), "douai_memory.json");
        try {
            if (file.isFile()) {
                JSONObject loaded = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                java.util.Iterator<String> keys = loaded.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    STORE.put(key, loaded.optJSONObject(key));
                }
            }
        } catch (Throwable ignored) { }
    }

    /** The background note for this conversation, or an empty string when nothing is known yet. */
    public static synchronized String prefix(String conversation) {
        JSONObject entry = STORE.optJSONObject(conversation);
        return entry == null ? "" : entry.optString("summary", "");
    }

    /** Records one finished exchange and schedules a rewrite once enough of them piled up. */
    public static void record(String conversation, String userText, String replyText, AiModelSettings models) {
        if (conversation == null || conversation.isEmpty() || models == null) return;
        String transcript;
        String previous;
        synchronized (ConversationMemory.class) {
            JSONObject entry = STORE.optJSONObject(conversation);
            if (entry == null) { entry = new JSONObject(); try { STORE.put(conversation, entry); } catch (Throwable ignored) { } }
            String pending = entry.optString("pending", "")
                    + "对方：" + clip(userText) + "\n我：" + clip(replyText) + "\n";
            int turns = entry.optInt("turns", 0) + 1;
            previous = entry.optString("summary", "");
            if (turns < TURNS_PER_SUMMARY) {
                try { entry.put("pending", pending).put("turns", turns); } catch (Throwable ignored) { }
                saveLocked();
                return;
            }
            transcript = pending;
            try { entry.put("pending", "").put("turns", 0); } catch (Throwable ignored) { }
            saveLocked();
        }
        final String conversationKey = conversation;
        final String source = previous + (previous.isEmpty() ? "" : "\n") + transcript;
        WORKER.execute(() -> summarize(conversationKey, source, models));
    }

    private static void summarize(String conversation, String source, AiModelSettings models) {
        try {
            ChatCompletionClient client = new ChatCompletionClient();
            String summary = client.reply("memory:" + conversation, source, Collections.emptyList(), models,
                    INSTRUCTION, false, "", null);
            if (summary == null) return;
            String text = summary.replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) return;
            if (text.length() > MAX_SUMMARY_CHARS) text = text.substring(0, MAX_SUMMARY_CHARS);
            synchronized (ConversationMemory.class) {
                JSONObject entry = STORE.optJSONObject(conversation);
                if (entry == null) { entry = new JSONObject(); STORE.put(conversation, entry); }
                entry.put("summary", text);
                saveLocked();
            }
        } catch (Throwable ignored) { }
    }

    private static String clip(String text) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').trim();
        return flat.length() > MAX_TURN_CHARS ? flat.substring(0, MAX_TURN_CHARS) + "…" : flat;
    }

    private static void saveLocked() {
        if (file == null) return;
        try {
            Files.write(file.toPath(), STORE.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }
}
