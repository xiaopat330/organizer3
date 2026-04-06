package com.organizer3.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.io.FilenameUtils;

import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final public class Utils {

    public static final long TIME = System.currentTimeMillis();

    public static Path workingDirectory() {
        return Path.of(System.getProperty("user.dir"));
    }
    @SneakyThrows
    public static void echo(Writer writer, final String s) {
        writer.write(s);
    }

    public static void echo(PrintStream stream, final String s) {
        stream.println(s);
    }

    public static void echo(final String s) {
        echo(System.err, s);
    }

    public static void echo(final String format, Object ... o) {
        echo(format(format, o));
    }

    public static String format(final String format, Object ...o) {
        return MessageFormat.format(format, o);
    }

    public static boolean isFile(@NonNull Path path) {
        return !isDirectory(path);
    }

    public static boolean isDirectory(@NonNull Path path) {
        return (Files.isDirectory(path));
    }

    public static String getExtension(@NonNull Path path) {
        return FilenameUtils.getExtension(path.getFileName().toString());
    }

    public static String getBaseName(@NonNull Path path) {
        return FilenameUtils.getBaseName(path.getFileName().toString());
    }

    public static boolean exists(@NonNull Path path) {
        return Files.exists(path);
    }
}
