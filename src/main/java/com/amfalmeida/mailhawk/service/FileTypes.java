package com.amfalmeida.mailhawk.service;

import java.util.Set;

public final class FileTypes {

    public static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif");
    public static final Set<String> DOCUMENT_EXTENSIONS = Set.of("pdf");

    private FileTypes() {}

    private static String getExtension(final String filename) {
        if (filename == null) return null;
        final int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot + 1).toLowerCase();
    }

    public static boolean isSupported(final String filename) {
        final String ext = getExtension(filename);
        return ext != null && (IMAGE_EXTENSIONS.contains(ext) || DOCUMENT_EXTENSIONS.contains(ext));
    }

    public static boolean isPdf(final String filename) {
        final String ext = getExtension(filename);
        return ext != null && DOCUMENT_EXTENSIONS.contains(ext);
    }

    public static boolean isImage(final String filename) {
        final String ext = getExtension(filename);
        return ext != null && IMAGE_EXTENSIONS.contains(ext);
    }
}