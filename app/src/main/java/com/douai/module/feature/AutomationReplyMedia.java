package com.douai.module.feature;

import android.content.Context;

import com.douai.module.config.LocalOptions;
import org.json.JSONObject;

import java.io.File;
import com.douai.module.diagnostics.DouAiLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Visual input for automatic replies, scoped to the incoming message or comment. */
public final class AutomationReplyMedia {
    public static final String RECOGNIZE_IMAGES = "reply_recognize_images";
    public static final String RECOGNIZE_VIDEO = "reply_recognize_video";

    /** How long a reply may wait for the full-size original before falling back. */
    private static final long ORIGINAL_BUDGET_MILLIS = 12_000;
    private static final long ORIGINAL_BUDGET_WITH_INLINE_MILLIS = 2_500;

    private AutomationReplyMedia() { }

    static List<ChatImage> comment(Context context, CommentReplyEvent event, LocalOptions options,
                                   MediaReadCancellation cancellation) throws Exception {
        cancellation.start();
        cancellation.check();
        if (!options.enabled(RECOGNIZE_IMAGES, true)) return Collections.emptyList();
        if (event == null) throw new IllegalArgumentException("评论回复素材目标为空");
        // The parent video switch applies to incoming message videos, not to the original work.
        if (hasCommentImages(event.targetComment)) {
            List<String> sources = commentImageUrls(event.targetComment);
            if (sources.isEmpty()) throw new IllegalArgumentException("对方评论附图没有可读取的地址");
            return Collections.unmodifiableList(new ArrayList<>(AutomationImages.load(sources, cancellation)));
        }
        return Collections.emptyList();
    }

    /** The label describes exactly the sources selected by comment(), not an assumed image subject. */
    static String commentSourceLabel(CommentReplyEvent event, LocalOptions options) {
        if (event == null) return "";
        return options.enabled(RECOGNIZE_IMAGES, true) && hasCommentImages(event.targetComment) ? "对方评论附图" : "";
    }

    static boolean hasCommentImages(Object comment) {
        Object value = DownloadActionHook.jsonValue(comment, "image_list");
        return value instanceof List<?> && !((List<?>) value).isEmpty();
    }

    /** Comment.image_list -> CommentImageStruct's explicit UrlModel fields, verified against the APK. */
    static List<String> commentImageUrls(Object comment) {
        Object value = DownloadActionHook.jsonValue(comment, "image_list");
        if (!(value instanceof Iterable<?>)) return Collections.emptyList();
        Set<String> result = new LinkedHashSet<>();
        for (Object image : (Iterable<?>) value) {
            String source = null;
            for (String name : new String[]{"origin_url", "download_url", "medium_url", "crop_url", "thumb_url"}) {
                source = safeImageUrl(DownloadActionHook.jsonValue(image, name));
                if (source != null) break;
            }
            if (source == null) throw new IllegalArgumentException("对方评论附图地址不受支持");
            result.add(source);
            if (result.size() == ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) break;
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    private static String safeImageUrl(Object address) {
        Object urls = address instanceof Iterable<?> ? address : DownloadActionHook.jsonValue(address, "url_list");
        if (!(urls instanceof Iterable<?>)) return null;
        for (Object value : (Iterable<?>) urls) {
            if (value instanceof String && AutomationImages.isMediaUrl((String) value)) return (String) value;
        }
        return null;
    }

    static List<String> incomingImages(JSONObject content) {
        return content == null ? Collections.emptyList() : AutomationImages.incoming(content);
    }

    /** 40.6.0 maps type 30 to StoryVideoContent; type 8 is a shared-aweme card. */
    static boolean isVideoMessageType(int type) { return type == 30; }

    /**
     * StoryVideoContent.video contains encrypted vid/tkey/skey, not a playable URL.
     * Its native resolver/decryption path is not adapted here. Never infer video_url or use the poster as video.
     */
    static String incomingVideo(JSONObject content) { return null; }

    /** Loads verified explicit sources. IM encrypted payloads cannot supply videoUrl in this version. */
    static List<ChatImage> loadIncoming(Context context, List<String> urls, String videoUrl,
                                       List<String> inlinePayloads, List<String> keyCandidates, LocalOptions options,
                                       MediaReadCancellation cancellation) throws Exception {
        cancellation.start();
        cancellation.check();
        boolean images = options.enabled(RECOGNIZE_IMAGES, true);
        boolean video = options.enabled(RECOGNIZE_VIDEO, false) && videoUrl != null && !videoUrl.isEmpty();
        // The embedded thumbnail is already decrypted by the sender, so it outranks CDN fetching.
        List<ChatImage> inline = images ? AutomationImages.decodeInline(inlinePayloads) : Collections.emptyList();
        boolean hasKeys = keyCandidates != null && !keyCandidates.isEmpty();
        // The embedded thumbnail is always usable; the encrypted CDN payload is preferred only when a
        // message-carried key is available, because it carries the full resolution.
        List<String> selected = images && urls != null && (inline.isEmpty() || hasKeys)
                ? urls : Collections.emptyList();
        int imageLimit = video ? 1 : ChatCompletionClient.MAX_IMAGES_PER_MESSAGE;
        List<ChatImage> result = new ArrayList<>(inline);
        if (!selected.isEmpty()) {
            List<ChatImage> fetched;
            try {
                // With a decoded thumbnail in hand we never wait long; without one there is no fallback.
                fetched = AutomationImages.load(
                        selected.subList(0, Math.min(imageLimit, selected.size())), keyCandidates, cancellation,
                        inline.isEmpty() ? ORIGINAL_BUDGET_MILLIS : ORIGINAL_BUDGET_WITH_INLINE_MILLIS);
            } catch (Exception failure) {
                if (inline.isEmpty()) throw failure;
                DouAiLog.event("auto_reply", "image_source", "inline_fallback",
                        "error=" + failure.getClass().getSimpleName());
                fetched = Collections.emptyList();
            }
            if (!fetched.isEmpty()) {
                DouAiLog.event("auto_reply", "image_source", "original",
                        "count=" + fetched.size() + ";replaced_inline=" + inline.size());
                result.clear();
                result.addAll(fetched);
            }
        }
        if (video) {
            File local = AutomationCommentMedia.downloadVideo(context, videoUrl, cancellation);
            try { result.addAll(ChatMediaLoader.loadVideoFile(local,
                    ChatCompletionClient.MAX_VIDEO_FRAMES, cancellation)); }
            finally { ChatMediaLoader.deleteTemporary(local); }
        }
        cancellation.check();
        return Collections.unmodifiableList(result);
    }
}
