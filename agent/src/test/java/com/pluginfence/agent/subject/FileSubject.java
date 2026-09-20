package com.pluginfence.agent.subject;

import kotlin.Unit;
import kotlin.io.FilesKt;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Controlled "third-party plugin" code. Every method here uses a JDK/Kotlin API that the agent
 * rewrites; tests verify the rewritten class behaves exactly like the original when allowed and
 * never performs the operation when blocked.
 */
public final class FileSubject {

    private FileSubject() {
    }

    public static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    public static byte[] readBytes(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    public static void write(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }

    public static String readViaStream(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static String readViaReaderWithCharset(File file) throws IOException {
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = reader.read()) >= 0) sb.append((char) c);
            return sb.toString();
        }
    }

    public static void writeViaStream(File file, boolean append, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, append)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String kotlinReadText(File file) {
        return FilesKt.readText(file, StandardCharsets.UTF_8);
    }

    public static List<String> kotlinForEachLine(File file) {
        List<String> lines = new ArrayList<>();
        FilesKt.forEachLine(file, StandardCharsets.UTF_8, line -> {
            lines.add(line);
            return Unit.INSTANCE;
        });
        return lines;
    }

    public static void kotlinWriteText(File file, String text) {
        FilesKt.writeText(file, text, StandardCharsets.UTF_8);
    }

    public static boolean deleteFile(File file) {
        return file.delete();
    }
}
