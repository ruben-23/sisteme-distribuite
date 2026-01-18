package org.example.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {
    private static final DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    private static Level currentLevel = Level.INFO;

    public static void setLevel(Level level) {
        currentLevel = level;
    }

    private static void log(Level level, String message) {
        setLevel(level);
        if (level.ordinal() >= currentLevel.ordinal()) {
            String timestamp = LocalDateTime.now().format(formatter);
            System.out.printf("[%s] [%s] %s%n", timestamp, level, message);
        }
    }

    public static void debug(String message) { log(Level.DEBUG, message); }
    public static void info(String message) { log(Level.INFO, message); }
    public static void warn(String message) { log(Level.WARN, message); }
    public static void error(String message) { log(Level.ERROR, message); }
}