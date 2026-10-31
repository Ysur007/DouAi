package com.douai.module.feature;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Converts explicitly selected local media to bounded text or visual model input. */
public final class ChatMediaLoader {
    public static final int MAX_DOCUMENT_CHARS = 16000;
    public static final int MAX_VIDEO_BYTES = 64 * 1024 * 1024;
    private static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    private static final int MAX_DOCX_EXPANDED_BYTES = 32 * 1024 * 1024;
    private static final int MAX_DOCX_XML_BYTES = 2 * 1024 * 1024;
    private static final int MAX_EDGE = 1280;
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private ChatMediaLoader() { }

    public enum Kind { IMAGE, VIDEO, DOCUMENT }

    public static final class Document {
        public final String name;
        public final String text;
        public final List<ChatImage> images;

        private Document(String name, String text, List<ChatImage> images) {
            this.name = name;
            this.text = text;
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
        }
    }

    /** A single composer entry point. The budget includes images, PDF pages and video frames. */
    public static Document loadAttachment(Context context, Uri uri, Kind kind, int maxImages) throws Exception {
        validateLocalUri(uri);
        validateImageBudget(maxImages);
        if (kind == null) throw new IllegalArgumentException("附件类型为空");
        checkCancelled();
        switch (kind) {
            case IMAGE:
                requireImageBudget(maxImages);
                return new Document(documentName(context, uri), "",
                        Collections.singletonList(ChatImageLoader.load(context, uri)));
            case VIDEO:
                requireImageBudget(maxImages);
                return loadVideoDocument(context, uri, maxImages);
            case DOCUMENT:
                return loadDocument(context, uri, maxImages);
            default: throw new IllegalArgumentException("不支持的附件类型");
        }
    }

    /** Samples visual frames only; no audio or speech is sent to the model. */
    public static List<ChatImage> loadVideo(Context context, Uri uri, int maxFrames) throws Exception {
        return loadVideoDocument(context, uri, maxFrames).images;
    }

    /** Retains sampling provenance with the frames so later conversation rounds know their limits. */
    public static Document loadVideoDocument(Context context, Uri uri, int maxFrames) throws Exception {
        validateLocalUri(uri);
        validateFrames(maxFrames);
        String name = documentName(context, uri);
        File local = copyToCache(context, uri, ".video", MAX_VIDEO_BYTES);
        try { return loadVideoDocumentFile(local, name, maxFrames, null); }
        finally { deleteTemporary(local); }
    }

    static List<ChatImage> loadVideoFile(File file, int maxFrames) throws Exception {
        return loadVideoFile(file, maxFrames, null);
    }

    static List<ChatImage> loadVideoFile(File file, int maxFrames, MediaReadCancellation cancellation) throws Exception {
        return loadVideoDocumentFile(file, "视频", maxFrames, cancellation).images;
    }

    static Document loadVideoDocumentFile(File file, String name, int maxFrames,
                                          MediaReadCancellation cancellation) throws Exception {
        validateFrames(maxFrames);
        if (!file.isFile() || file.length() == 0 || file.length() > MAX_VIDEO_BYTES) {
            throw new IllegalArgumentException("视频为空或超过 64 MB");
        }
        checkCancelled(cancellation);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            checkCancelled(cancellation);
            if (!"yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) {
                throw new IllegalArgumentException("所选文件不包含可读取的视频画面");
            }
            long duration;
            try { duration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)); }
            catch (RuntimeException failure) { throw new IllegalArgumentException("无法读取视频时长"); }
            int width, height;
            try {
                width = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                height = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            } catch (RuntimeException failure) { throw new IllegalArgumentException("无法读取视频画面尺寸"); }
            int[] size = boundedDimensions(width, height);
            List<ChatImage> images = new ArrayList<>();
            long[] times = sampleTimes(duration, maxFrames);
            for (long time : times) {
                checkCancelled(cancellation);
                try (MediaMemoryBudget.Reservation ignored = MediaMemoryBudget.acquire((long) size[0] * size[1] * 8, cancellation)) {
                    checkCancelled(cancellation);
                    Bitmap frame = retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            size[0], size[1]);
                    if (frame == null) throw new IllegalArgumentException("无法读取视频画面");
                    try { checkCancelled(cancellation); images.add(encode(frame)); checkCancelled(cancellation); }
                    finally { frame.recycle(); }
                }
            }
            checkCancelled(cancellation);
            return new Document(safeName(name), videoDescription(duration, times), images);
        } finally { retriever.release(); }
    }

    public static Document loadDocument(Context context, Uri uri) throws Exception {
        return loadDocument(context, uri, ChatCompletionClient.MAX_DOCUMENT_PAGES);
    }

    public static Document loadDocument(Context context, Uri uri, int maxImages) throws Exception {
        validateLocalUri(uri);
        validateImageBudget(maxImages);
        String name = documentName(context, uri);
        String mime = context.getContentResolver().getType(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        if ("application/pdf".equals(mime) || lower.endsWith(".pdf")) {
            requireImageBudget(maxImages);
            File local = copyToCache(context, uri, ".pdf", MAX_DOCUMENT_BYTES);
            try { return readPdf(local, name, maxImages); }
            finally { deleteTemporary(local); }
        }
        boolean docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime)
                || lower.endsWith(".docx");
        if (!docx && !isTextDocument(name, mime)) {
            throw new IllegalArgumentException("支持 UTF-8 文本、DOCX 和 PDF 文件");
        }
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("无法读取所选文件");
            byte[] source = readBounded(input, docx ? MAX_DOCUMENT_BYTES : MAX_TEXT_BYTES);
            String text = docx ? readDocx(source) : readText(source);
            return new Document(name, text, Collections.emptyList());
        }
    }

    public static String documentName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !cursor.isNull(column)) return safeName(cursor.getString(column));
            }
        } catch (RuntimeException ignored) { }
        return safeName(uri.getLastPathSegment());
    }

    private static String safeName(String value) {
        String name = value == null ? "附件" : value.replace('\r', ' ').replace('\n', ' ');
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        return name.isEmpty() ? "附件" : name.substring(0, Math.min(160, name.length()));
    }

    static boolean isTextDocument(String name, String mime) {
        if (mime != null && (mime.startsWith("text/") || "application/json".equals(mime)
                || "application/xml".equals(mime) || "application/yaml".equals(mime))) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : new String[]{"txt", "md", "csv", "json", "xml", "yaml", "yml", "log",
                "java", "kt", "py", "js", "ts", "html", "css", "ini", "conf", "sql", "sh", "c", "cpp", "h"}) {
            if (lower.endsWith("." + extension)) return true;
        }
        return false;
    }

    static String readText(byte[] source) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            for (int i = 0; i < text.length(); i++) {
                char value = text.charAt(i);
                if (Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t') {
                    throw new IllegalArgumentException("文件不是受支持的 UTF-8 文本");
                }
            }
            return checkedText(text);
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("文本文件需要使用 UTF-8 编码");
        }
    }

    static String readDocx(byte[] source) throws Exception {
        if (source.length == 0 || source.length > MAX_DOCUMENT_BYTES) throw new IllegalArgumentException("DOCX 文件为空或过大");
        int entries = 0;
        int expanded = 0;
        byte[] document = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(source))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled();
                if (++entries > 2048) throw new IllegalArgumentException("DOCX 文件包含过多条目");
                ByteArrayOutputStream xml = "word/document.xml".equals(entry.getName()) ? new ByteArrayOutputStream() : null;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    checkCancelled();
                    expanded += count;
                    if (expanded > MAX_DOCX_EXPANDED_BYTES) throw new IllegalArgumentException("DOCX 解压内容超过 32 MB");
                    if (xml != null) {
                        if (xml.size() + count > MAX_DOCX_XML_BYTES) throw new IllegalArgumentException("DOCX 正文过大");
                        xml.write(buffer, 0, count);
                    }
                }
                if (xml != null) {
                    if (document != null) throw new IllegalArgumentException("DOCX 正文条目重复");
                    document = xml.toByteArray();
                }
                zip.closeEntry();
            }
        }
        if (document == null) throw new IllegalArgumentException("DOCX 文件缺少正文");
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new ByteArrayInputStream(document), "UTF-8");
        StringBuilder text = new StringBuilder();
        boolean inText = false;
        int event;
        while ((event = parser.nextToken()) != XmlPullParser.END_DOCUMENT) {
            checkCancelled();
            if (event == XmlPullParser.DOCDECL) {
                throw new IllegalArgumentException("DOCX 正文包含不支持的 XML 声明或实体");
            }
            if (event == XmlPullParser.ENTITY_REF) {
                String name = parser.getName();
                if (name == null || !(java.util.Arrays.asList("amp", "lt", "gt", "apos", "quot").contains(name)
                        || name.matches("#[0-9]+|#x[0-9a-fA-F]+")) || parser.getText() == null) {
                    throw new IllegalArgumentException("DOCX 正文包含不支持的 XML 实体");
                }
                if (inText) text.append(parser.getText());
            }
            boolean word = WORD_NS.equals(parser.getNamespace());
            if (event == XmlPullParser.START_TAG && word) {
                if ("t".equals(parser.getName())) inText = true;
                else if ("tab".equals(parser.getName())) text.append('\t');
                else if ("br".equals(parser.getName()) || "cr".equals(parser.getName())) text.append('\n');
            } else if ((event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) && inText) {
                text.append(parser.getText());
            } else if (event == XmlPullParser.END_TAG && word) {
                if ("t".equals(parser.getName())) inText = false;
                else if ("p".equals(parser.getName())) text.append('\n');
            }
            if (text.length() > MAX_DOCUMENT_CHARS) throw new IllegalArgumentException("文件文字不能超过 16000 个字符");
        }
        return checkedText(text.toString());
    }

    private static String checkedText(String text) {
        checkCancelled();
        if (text.trim().isEmpty()) throw new IllegalArgumentException("文件没有可读取的文字");
        if (text.length() > MAX_DOCUMENT_CHARS) throw new IllegalArgumentException("文件文字不能超过 16000 个字符");
        return text;
    }

    private static Document readPdf(File file, String name, int maxImages) throws Exception {
        List<ChatImage> images = new ArrayList<>();
        int pages;
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            pages = renderer.getPageCount();
            if (pages == 0) throw new IllegalArgumentException("PDF 没有可读取的页面");
            for (int index = 0; index < Math.min(pages, maxImages); index++) {
                checkCancelled();
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    int[] size = boundedDimensions(page.getWidth(), page.getHeight());
                    try (MediaMemoryBudget.Reservation ignored = MediaMemoryBudget.acquire((long) size[0] * size[1] * 8, null)) {
                        checkCancelled();
                        Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                        try {
                            bitmap.eraseColor(Color.WHITE);
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            images.add(encode(bitmap));
                        } finally { bitmap.recycle(); }
                    }
                }
            }
        }
        String text = "PDF 页面图片：已读取前 " + images.size() + " 页，共 " + pages + " 页。";
        return new Document(name, text, images);
    }

    private static String videoDescription(long durationMillis, long[] timesMicros) {
        StringBuilder text = new StringBuilder("视频时长：")
                .append(seconds(durationMillis * 1000)).append(" 秒。\n")
                .append("后附 ").append(timesMicros.length).append(" 张图片按视频时间顺序抽样，目标时间依次为：");
        for (int index = 0; index < timesMicros.length; index++) {
            if (index > 0) text.append("、");
            text.append(seconds(timesMicros[index])).append(" 秒");
        }
        return text.append("。实际读取的是各目标时间附近的关键帧，可能重复。")
                .append("仅提供这些抽样画面，不包含音频或语音；未提供其余视频片段。不得将抽样画面当作完整视频内容。")
                .toString();
    }

    private static String seconds(long micros) {
        return String.format(Locale.ROOT, "%.3f", micros / 1_000_000.0);
    }

    static long[] sampleTimes(long durationMillis, int frames) {
        validateFrames(frames);
        if (durationMillis <= 0 || durationMillis > 24L * 60 * 60 * 1000) {
            throw new IllegalArgumentException("视频时长无效或超过 24 小时");
        }
        int count = Math.min(frames, (int) Math.max(1, durationMillis / 1000));
        long[] times = new long[count];
        for (int i = 0; i < count; i++) times[i] = durationMillis * 1000 * (2L * i + 1) / (2L * count);
        return times;
    }

    static int[] boundedDimensions(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("媒体画面尺寸无效");
        double ratio = Math.min(1.0, (double) MAX_EDGE / Math.max(width, height));
        return new int[]{Math.max(1, (int) Math.round(width * ratio)), Math.max(1, (int) Math.round(height * ratio))};
    }

    private static ChatImage encode(Bitmap source) {
        checkCancelled();
        int[] size = boundedDimensions(source.getWidth(), source.getHeight());
        Bitmap opaque = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(opaque);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(source, null, new Rect(0, 0, size[0], size[1]), new Paint(Paint.FILTER_BITMAP_FLAG));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!opaque.compress(Bitmap.CompressFormat.JPEG, 85, bytes)) throw new IllegalArgumentException("媒体画面转换失败");
            checkCancelled();
            return new ChatImage("image/jpeg", bytes.toByteArray());
        } finally {
            opaque.recycle();
        }
    }

    private static void validateFrames(int maxFrames) {
        if (maxFrames < 1 || maxFrames > ChatCompletionClient.MAX_VIDEO_FRAMES) {
            throw new IllegalArgumentException("视频画面数量应为 1 至 " + ChatCompletionClient.MAX_VIDEO_FRAMES);
        }
    }

    private static void validateImageBudget(int maxImages) {
        if (maxImages < 0 || maxImages > ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) {
            throw new IllegalArgumentException("附件画面数量应为 0 至 " + ChatCompletionClient.MAX_IMAGES_PER_MESSAGE);
        }
    }

    private static void requireImageBudget(int maxImages) {
        if (maxImages == 0) throw new IllegalArgumentException("每条消息最多包含 " + ChatCompletionClient.MAX_IMAGES_PER_MESSAGE + " 张图片、PDF 页面或视频帧");
    }

    static void validateLocalUri(Uri uri) {
        String scheme = uri == null ? "" : uri.getScheme();
        if (!"content".equalsIgnoreCase(scheme) && !"file".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("请选择本地文件");
        }
    }

    private static File copyToCache(Context context, Uri uri, String suffix, int maximum) throws Exception {
        File local = File.createTempFile("douai-media-", suffix, context.getCacheDir());
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(local)) {
                if (input == null) throw new IllegalArgumentException("无法读取所选文件");
                copyBounded(input, output, maximum);
            }
            return local;
        } catch (Exception failure) { deleteTemporary(local); throw failure; }
    }

    static byte[] readBounded(InputStream input, int maximum) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copyBounded(input, output, maximum);
        return output.toByteArray();
    }

    static void copyBounded(InputStream input, java.io.OutputStream output, int maximum) throws Exception {
        copyBounded(input, output, maximum, null);
    }

    static void copyBounded(InputStream input, java.io.OutputStream output, int maximum,
                            MediaReadCancellation cancellation) throws Exception {
        checkCancelled(cancellation);
        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
            checkCancelled(cancellation);
            if (count > maximum - total) throw new IllegalArgumentException("媒体文件超过读取大小限制");
            total += count;
            output.write(buffer, 0, count);
        }
        checkCancelled(cancellation);
        if (total == 0) throw new IllegalArgumentException("媒体文件为空");
    }

    static void checkCancelled() { if (Thread.currentThread().isInterrupted()) throw new CancellationException("媒体读取已取消"); }

    static void checkCancelled(MediaReadCancellation cancellation) throws Exception {
        if (cancellation != null) cancellation.check(); else checkCancelled();
    }

    static void deleteTemporary(File file) { if (file != null && file.exists()) file.delete(); }
}
