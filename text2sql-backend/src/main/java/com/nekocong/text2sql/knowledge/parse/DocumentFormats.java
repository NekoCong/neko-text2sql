package com.nekocong.text2sql.knowledge.parse;

import java.util.Locale;
import java.util.Set;

/**
 * Small, dependency-free helpers for classifying parser input by media type or filename extension.
 */
final class DocumentFormats {

    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "webp", "bmp", "tif", "tiff", "gif");

    private DocumentFormats() {
    }

    /**
     * Reports whether the input is a PDF.
     *
     * @param context the input, possibly {@code null}
     * @return {@code true} for {@code application/pdf} or a {@code .pdf} filename
     */
    static boolean isPdf(ParseContext context) {
        if (context == null) {
            return false;
        }
        return "application/pdf".equals(normalizeMime(context.mimeType()))
                || "pdf".equals(extension(context.filename()));
    }

    /**
     * Reports whether the input is an image.
     *
     * @param context the input, possibly {@code null}
     * @return {@code true} for an {@code image/*} media type or a known image extension
     */
    static boolean isImage(ParseContext context) {
        if (context == null) {
            return false;
        }
        if (normalizeMime(context.mimeType()).startsWith("image/")) {
            return true;
        }
        return IMAGE_EXTENSIONS.contains(extension(context.filename()));
    }

    /**
     * Reports whether the input should be handled as text (everything that is neither PDF nor image).
     *
     * @param context the input, possibly {@code null}
     * @return {@code true} when the input is not a PDF and not an image
     */
    static boolean isText(ParseContext context) {
        return !isPdf(context) && !isImage(context);
    }

    /**
     * Lowercases a media type and strips any parameters.
     *
     * @param mimeType the raw media type, possibly {@code null}
     * @return the normalized media type, never {@code null}
     */
    static String normalizeMime(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int semicolon = mimeType.indexOf(';');
        String value = semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Extracts the lowercased extension from a filename or path.
     *
     * @param filename the filename, possibly {@code null}
     * @return the extension without the dot, or an empty string
     */
    static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        String name = filename.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
