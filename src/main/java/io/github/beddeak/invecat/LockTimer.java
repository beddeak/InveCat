package io.github.beddeak.invecat;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LockTimer {

    static final int MIN_INTERVAL = 5;
    static final int MAX_SECONDS = 24 * 60 * 60;
    private static final Pattern PART = Pattern.compile("(\\d+)([hms]?)");

    private int interval;
    private int remaining;
    private boolean running;
    private boolean paused;

    LockTimer(int interval) {
        this.interval = clampInterval(interval);
        this.remaining = this.interval;
    }

    void start() {
        running = true;
        paused = false;
        remaining = interval;
    }

    void stop() {
        running = false;
        paused = false;
        remaining = interval;
    }

    boolean pause() {
        if (!running || paused) {
            return false;
        }
        paused = true;
        return true;
    }

    boolean resume() {
        if (!running || !paused) {
            return false;
        }
        paused = false;
        return true;
    }

    /** Advances one second and returns true when a slot should disappear. */
    boolean tick() {
        if (!running || paused) {
            return false;
        }
        remaining--;
        if (remaining <= 0) {
            remaining = interval;
            return true;
        }
        return false;
    }

    void setInterval(int seconds) {
        interval = clampInterval(seconds);
        if (remaining > interval) {
            remaining = interval;
        }
    }

    void setRemaining(int seconds) {
        remaining = Math.clamp(seconds, 1, MAX_SECONDS);
    }

    void add(int seconds) {
        setRemaining(remaining + seconds);
    }

    int interval() {
        return interval;
    }

    int remaining() {
        return remaining;
    }

    boolean running() {
        return running;
    }

    boolean paused() {
        return paused;
    }

    static int clampInterval(int seconds) {
        return Math.clamp(seconds, MIN_INTERVAL, MAX_SECONDS);
    }

    /**
     * Accepts 90s, 2m, 1m30s, 1h, 1분30초 and a leading minus sign. A bare number is counted in
     * {@code bareUnit} seconds (60 for minutes).
     */
    static OptionalInt parseSeconds(String text, int bareUnit) {
        String value = text.trim().toLowerCase(Locale.ROOT)
                .replace("시간", "h").replace("분", "m").replace("초", "s");
        boolean negative = value.startsWith("-");
        if (negative || value.startsWith("+")) {
            value = value.substring(1);
        }
        if (value.isEmpty()) {
            return OptionalInt.empty();
        }
        if (value.chars().allMatch(Character::isDigit)) {
            long amount = value.length() > 9 ? MAX_SECONDS : Long.parseLong(value) * bareUnit;
            int seconds = (int) Math.min(amount, MAX_SECONDS);
            return OptionalInt.of(negative ? -seconds : seconds);
        }
        Matcher matcher = PART.matcher(value);
        long total = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) {
                return OptionalInt.empty();
            }
            String digits = matcher.group(1);
            long amount = digits.length() > 9 ? MAX_SECONDS : Long.parseLong(digits);
            total += switch (matcher.group(2)) {
                case "h" -> amount * 3600;
                case "m" -> amount * 60;
                default -> amount;
            };
            end = matcher.end();
            if (total > MAX_SECONDS) {
                total = MAX_SECONDS;
            }
        }
        if (end != value.length()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) (negative ? -total : total));
    }

    static String clock(long seconds) {
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long rest = seconds % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }

    static String words(long seconds) {
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long rest = seconds % 60;
        StringBuilder text = new StringBuilder();
        if (hours > 0) {
            text.append(hours).append("시간");
        }
        if (minutes > 0) {
            text.append(text.isEmpty() ? "" : " ").append(minutes).append("분");
        }
        if (rest > 0 || text.isEmpty()) {
            text.append(text.isEmpty() ? "" : " ").append(rest).append("초");
        }
        return text.toString();
    }
}
