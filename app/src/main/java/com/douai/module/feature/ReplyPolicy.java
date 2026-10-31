package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;

import com.douai.module.config.LocalOptions;
import android.content.ContentValues;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Random;

public final class ReplyPolicy {
    private ReplyPolicy() { }

    public static final class Incoming {
        public final String conversation;
        public final String serverId;
        public final long sender;
        public final String text;
        public final java.util.List<String> imageUrls;
        public final java.util.List<String> inlinePayloads;
        public final java.util.List<String> keyCandidates;
        public final String videoUrl;
        public final String author;
        public final boolean group;
        public final AutomationScopePolicy.Relation relation;
        Incoming(String conversation, String serverId, long sender, String text, java.util.List<String> imageUrls,
                 String author, boolean group, AutomationScopePolicy.Relation relation) {
            this(conversation, serverId, sender, text, imageUrls, "", author, group, relation, java.util.Collections.emptyList());
        }
        Incoming(String conversation, String serverId, long sender, String text, java.util.List<String> imageUrls,
                 String videoUrl, String author, boolean group, AutomationScopePolicy.Relation relation) {
            this(conversation, serverId, sender, text, imageUrls, videoUrl, author, group, relation,
                    java.util.Collections.emptyList(), java.util.Collections.emptyList());
        }
        Incoming(String conversation, String serverId, long sender, String text, java.util.List<String> imageUrls,
                 String videoUrl, String author, boolean group, AutomationScopePolicy.Relation relation,
                 java.util.List<String> inlinePayloads) {
            this(conversation, serverId, sender, text, imageUrls, videoUrl, author, group, relation,
                    inlinePayloads, java.util.Collections.emptyList());
        }
        Incoming(String conversation, String serverId, long sender, String text, java.util.List<String> imageUrls,
                 String videoUrl, String author, boolean group, AutomationScopePolicy.Relation relation,
                 java.util.List<String> inlinePayloads, java.util.List<String> keyCandidates) {
            this.conversation = conversation;
            this.serverId = serverId;
            this.sender = sender;
            this.text = text;
            this.imageUrls = imageUrls;
            this.inlinePayloads = inlinePayloads == null ? java.util.Collections.emptyList() : inlinePayloads;
            this.keyCandidates = keyCandidates == null ? java.util.Collections.emptyList() : keyCandidates;
            this.videoUrl = videoUrl == null ? "" : videoUrl;
            this.author = author;
            this.group = group;
            this.relation = relation;
        }
    }

    /** Douyin renders big stickers as their own message type; the label is all the model needs. */
    private static final int STICKER_TYPE = 5;
    /** Shared work/video card: title plus a cover image, no playable source in the payload. */
    private static final int SHARE_VIDEO_TYPE = 8;
    private static volatile boolean stickerSampled;

    private static String stickerLabel(JSONObject content) {
        String name = content.optString("display_name", "").trim();
        if (name.isEmpty()) name = content.optString("name", "").trim();
        if (name.isEmpty()) name = content.optString("danmaku_text", "").trim();
        if (!stickerSampled) {
            stickerSampled = true;
            DouAiLog.event("auto_reply", "content_probe", "sticker_fields",
                    "display_name=" + clip(content.optString("display_name", ""))
                            + ";emoji_source=" + clip(content.optString("emoji_source", ""))
                            + ";emoji_type=" + clip(content.optString("emoji_type", ""))
                            + ";image_id=" + clip(content.optString("image_id", ""))
                            + ";id=" + clip(content.optString("id", ""))
                            + ";aweType=" + clip(content.optString("aweType", "")));
        }
        return "[对方发来表情：" + (name.isEmpty() ? "表情" : name) + "]";
    }

    private static String shareLabel(JSONObject content) {
        String name = content.optString("content_name", "").trim();
        if (name.isEmpty()) name = content.optString("title", "").trim();
        return name.isEmpty() ? "[对方分享了一个视频]" : "[对方分享了一个视频：《" + clip(name) + "》]";
    }

    private static String clip(String value) {
        if (value == null) return "";
        return value.length() > 24 ? value.substring(0, 24) + "~" + value.length() : value;
    }

    private static final java.util.Set<Integer> PROBED_TYPES =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    /** One shape sample per unknown type, so a sticker payload can be mapped without guessing. */
    private static void probeUnsupportedType(Integer type, ContentValues values) {
        if (type == null || !PROBED_TYPES.add(type)) return;
        try {
            DouAiLog.event("auto_reply", "content_probe", "type_" + type,
                    AutomationImages.shape(new JSONObject(values.getAsString("content"))));
        } catch (Throwable failure) {
            DouAiLog.event("auto_reply", "content_probe", "failed", "type=" + type);
        }
    }

    public static Incoming incoming(ContentValues values, long ownUid, long startedAt, LocalOptions options) {
        if (values == null || ownUid <= 0
                || !options.enabled("native_auto_reply")) return rejected("disabled_or_account_missing", "values_present=" + (values != null) + ";account_present=" + (ownUid > 0));
        try {
            Long sender = values.getAsLong("sender");
            String conversation = values.getAsString("conversation_id");
            String id = values.getAsString("msg_server_id");
            Integer type = values.getAsInteger("type");
            Integer conversationType = values.getAsInteger("conversation_type");
            Long created = values.getAsLong("created_time");
            boolean ai = "ai".equals(options.text("reply_mode", "fixed"));
            if (created != null && created > 0 && created < 1_000_000_000_000L) created *= 1000;
            String rejected = sender == null || sender <= 0 ? "sender_missing" : sender == ownUid ? "own_message"
                    : conversation == null || conversation.isEmpty() ? "conversation_missing"
                    : id == null || !id.matches("[1-9][0-9]*") ? "server_id_missing_or_local_message"
                    : type == null ? "message_type_missing"
                    : type != 7 && !(ai && (type == 27 || STICKER_TYPE == type || SHARE_VIDEO_TYPE == type
                            || AutomationReplyMedia.isVideoMessageType(type))) ? "message_type_unsupported_in_mode"
                    : conversationType != null && conversationType != 1 && conversationType != 2 ? "conversation_type_unsupported"
                    : created == null ? "timestamp_missing" : created < startedAt - 30_000 ? "historical_message" : "";
            if ("message_type_unsupported_in_mode".equals(rejected)) probeUnsupportedType(type, values);
            if (!rejected.isEmpty()) return rejected(rejected, "type=" + type + ";conversation_type=" + conversationType + ";ai_mode=" + ai);
            boolean group = conversationType != null && conversationType == 2;
            if (group && !options.enabled("reply_group_chats", false)) return rejected("group_replies_disabled", "group=true");
            if (!group && !options.enabled("reply_private_chats", true)) return rejected("private_replies_disabled", "group=false");
            JSONObject content = new JSONObject(values.getAsString("content"));
            // A shared video carries its cover in content_thumb, which travels through the same
            // encrypted-URL path as an ordinary picture.
            boolean recognize = (type == 27 || SHARE_VIDEO_TYPE == type)
                    && options.enabled(LocalOptions.REPLY_RECOGNIZE_IMAGES, true);
            java.util.List<String> images = recognize
                    ? AutomationReplyMedia.incomingImages(content) : java.util.Collections.emptyList();
            java.util.List<String> inline = recognize
                    ? AutomationImages.inlinePayloads(content) : java.util.Collections.emptyList();
            // A picture is useless without a source; a share card still has its title to answer to.
            if (type == 27 && recognize && images.isEmpty() && inline.isEmpty()) {
                return rejected("image_source_missing", "type=27;recognize_images=true");
            }
            if (recognize) DouAiLog.event("auto_reply", "image_payload", "shape", AutomationImages.shape(content));
            boolean videoMessage = AutomationReplyMedia.isVideoMessageType(type);
            String video = videoMessage
                    && options.enabled(LocalOptions.REPLY_RECOGNIZE_VIDEO, false)
                    ? AutomationReplyMedia.incomingVideo(content) : "";
            // The verified type 30 payload is encrypted. A resolver must prove a playable source first.
            if (videoMessage && (video == null || video.isEmpty())) return rejected("video_disabled_or_playable_source_unproved", "type=" + type);
            if (video == null) video = "";
            String text = content.optString("text", "").trim();
            if (STICKER_TYPE == type) text = stickerLabel(content);
            if (SHARE_VIDEO_TYPE == type) text = shareLabel(content);
            String messageText = text;
            if (text.isEmpty() && (!images.isEmpty() || !inline.isEmpty())) text = "请根据对方发来的图片回复对方。";
            if (text.isEmpty() && !video.isEmpty()) text = "请根据对方发来的视频画面回复对方。";
            if (text.isEmpty()) return rejected("message_has_no_usable_content", "images=" + (images.size() + inline.size()) + ";video_present=" + !video.isEmpty());
            String uid = sender.toString();
            AutomationScopePolicy.Relation relation = AutomationScopePolicy.fromIncoming(values, sender);
            String author = first(values, "sender_name", "nickname", "author_name", "user_name");
            if (author.isEmpty()) author = AutomationScopePolicy.cachedName(uid);
            if (author.isEmpty()) author = NativeMessagingHooks.availableContacts().getOrDefault(uid, uid);
            if (group) {
                if (!GroupReplyPolicy.allows(values, ownUid, conversation, sender, author, options)) return rejected("group_policy_rejected", "scope_mode=" + options.text("reply_group_scope_mode", "all") + ";trigger_mode=" + options.text("reply_group_mode", "all"));
            } else if (!ReplyScopePolicy.allows(options, uid, author, messageText,
                    content.optString("title", ""), relation)) return rejected("private_scope_or_content_rejected", "group=false");
            java.util.List<String> keys = recognize
                    ? ImageCrypto.keyCandidates(content) : java.util.Collections.emptyList();
            return new Incoming(conversation, id, sender, text, images, video, author, group, relation, inline, keys);
        } catch (org.json.JSONException | RuntimeException ignored) { DouAiLog.failure("auto_reply", "receipt_parse", ignored); return rejected("receipt_parse_failed", "error_type=" + ignored.getClass().getSimpleName()); }
    }

    private static Incoming rejected(String reason, String evidence) {
        DouAiLog.event("auto_reply", "receipt_rejected", reason, evidence);
        return null;
    }

    private static String first(ContentValues values, String... keys) {
        for (String key : keys) {
            String value = values.getAsString(key);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    public static Set<String> targets(String configured) {
        Set<String> result = new HashSet<>();
        for (String value : configured.split("[,，\\s]+")) {
            if (value.matches("[0-9]{1,20}")) result.add(value);
        }
        return result;
    }

    public static boolean inWindow(String start, String end, LocalTime now) {
        if (start.trim().isEmpty() && end.trim().isEmpty()) return true;
        try {
            LocalTime first = LocalTime.parse(start.trim());
            LocalTime last = LocalTime.parse(end.trim());
            if (first.equals(last)) return true;
            return first.isBefore(last) ? !now.isBefore(first) && !now.isAfter(last)
                    : !now.isBefore(first) || !now.isAfter(last);
        } catch (RuntimeException ignored) { return false; }
    }

    public static String reply(String incoming, LocalOptions options, Random random) {
        String mode = options.text("reply_mode", "fixed");
        if ("fixed".equals(mode)) return options.text("auto_message_text", "").trim();
        try {
            JSONArray entries = new JSONArray(options.text("keyword".equals(mode)
                    ? "reply_keywords" : "reply_random", "[]"));
            if ("keyword".equals(mode)) {
                String lower = incoming.toLowerCase(Locale.ROOT);
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    String keyword = entry.optString("keyword", "").trim();
                    if (!keyword.isEmpty() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                        return entry.optString("reply", "").trim();
                    }
                }
            } else if ("random".equals(mode) && entries.length() > 0) {
                return entries.getString(random.nextInt(entries.length())).trim();
            }
        } catch (org.json.JSONException ignored) { }
        return "";
    }
}
