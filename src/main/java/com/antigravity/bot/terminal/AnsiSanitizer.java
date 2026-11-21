package com.antigravity.bot.terminal;

import java.util.regex.Pattern;

public class AnsiSanitizer {

    // Matches non-SGR ANSI escape sequences (cursor movements, erase line, OSC window titles, etc.)
    private static final Pattern NON_SGR_PATTERN = Pattern.compile(
            "\\x1B\\[[0-9;?]*[A-LN-Za-ln-z~]|\\x1B\\][^\u0007\\x1B]*(\\u0007|\\x1B\\\\)|\\x1B[()][AB012]"
    );

    // Matches all ANSI sequences including SGR colors
    private static final Pattern ALL_ANSI_PATTERN = Pattern.compile(
            "\\x1B\\[[0-9;?]*[a-zA-Z~]|\\x1B\\][^\u0007\\x1B]*(\\u0007|\\x1B\\\\)|\\x1B[()][AB012]"
    );

    /**
     * Cleans text for display inside Telegram's ansi code block:
     * - Retains SGR colors (\033[...m) so Telegram can render colors
     * - Strips non-visual cursor movements and control codes
     * - Neutralizes backticks to avoid breaking markdown/HTML code blocks
     */
    public static String cleanForTelegram(String text) {
        if (text == null || text.isEmpty()) return "";

        // Remove non-SGR sequences
        String cleaned = NON_SGR_PATTERN.matcher(text).replaceAll("");

        // Avoid breaking code blocks with triple backticks
        cleaned = cleaned.replace("```", "` ` `");

        return cleaned;
    }

    /**
     * Strips all ANSI sequences for plain-text logs or document exports.
     */
    public static String stripAllAnsi(String text) {
        if (text == null || text.isEmpty()) return "";
        return ALL_ANSI_PATTERN.matcher(text).replaceAll("");
    }
}
