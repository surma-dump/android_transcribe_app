package dev.surma.parakeeb;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ImeProgressStatusParser {
    private static final Pattern TRANSCRIBING_PERCENT_PATTERN =
            Pattern.compile("Transcribing\\.\\.\\.\\s*(-?\\d+)%");

    private ImeProgressStatusParser() {}

    static int parseProgressPercent(String status) {
        if (status == null) {
            return -1;
        }

        Matcher matcher = TRANSCRIBING_PERCENT_PATTERN.matcher(status);
        if (!matcher.find()) {
            return -1;
        }

        try {
            int value = Integer.parseInt(matcher.group(1));
            if (value < 0) return 0;
            if (value > 100) return 100;
            return value;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
