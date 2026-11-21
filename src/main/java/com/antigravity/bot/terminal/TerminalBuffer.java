package com.antigravity.bot.terminal;

import java.util.ArrayList;
import java.util.List;

public class TerminalBuffer {

    private static final int MAX_HISTORY_CHARS = 150_000;
    private static final int MAX_SCREEN_LINES = 70;
    private static final int MAX_TELEGRAM_SCREEN_CHARS = 3200;

    private final StringBuilder fullHistory = new StringBuilder();
    private final List<String> screenLines = new ArrayList<>();
    private final StringBuilder currentLine = new StringBuilder();

    public synchronized void append(String text) {
        if (text == null || text.isEmpty()) return;

        // Keep raw full history for download
        if (fullHistory.length() + text.length() > MAX_HISTORY_CHARS) {
            fullHistory.delete(0, text.length());
        }
        fullHistory.append(text);

        // Process screen stream
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);

            // Handle clear screen sequences (\033[2J or \033[3J or \033c)
            if (c == '\033' && i + 3 < len && (text.startsWith("\033[2J", i) || text.startsWith("\033[3J", i))) {
                screenLines.clear();
                currentLine.setLength(0);
                i += 3;
                continue;
            }

            // OSC sequence (e.g. \033]0;title\007)
            if (c == '\033' && i + 1 < len && text.charAt(i + 1) == ']') {
                int endOsc = text.indexOf('\007', i);
                int endOsc2 = text.indexOf("\033\\", i);
                if (endOsc != -1 && (endOsc2 == -1 || endOsc < endOsc2)) {
                    i = endOsc;
                    continue;
                } else if (endOsc2 != -1) {
                    i = endOsc2 + 1;
                    continue;
                }
            }

            if (c == '\r') {
                if (i + 1 < len && text.charAt(i + 1) == '\n') {
                    // \r\n: commit line
                    commitCurrentLine();
                    i++; // skip \n
                } else {
                    // Solitary \r: carriage return to start of line (progress bars/spinners)
                    currentLine.setLength(0);
                }
            } else if (c == '\n') {
                commitCurrentLine();
            } else if (c == '\b') {
                if (currentLine.length() > 0) {
                    currentLine.setLength(currentLine.length() - 1);
                }
            } else if (c == '\007') {
                // Bell - ignore
            } else {
                currentLine.append(c);
            }
        }
    }

    private void commitCurrentLine() {
        screenLines.add(currentLine.toString());
        currentLine.setLength(0);
        if (screenLines.size() > MAX_SCREEN_LINES) {
            screenLines.remove(0);
        }
    }

    public synchronized void clear() {
        screenLines.clear();
        currentLine.setLength(0);
    }

    public synchronized String getScreenOutput() {
        StringBuilder sb = new StringBuilder();
        for (String line : screenLines) {
            sb.append(line).append("\n");
        }
        if (currentLine.length() > 0) {
            sb.append(currentLine);
        }

        String raw = sb.toString();
        String cleaned = AnsiSanitizer.cleanForTelegram(raw);

        // Telegram length constraint
        if (cleaned.length() > MAX_TELEGRAM_SCREEN_CHARS) {
            int cutIndex = cleaned.length() - MAX_TELEGRAM_SCREEN_CHARS;
            int nextNewline = cleaned.indexOf('\n', cutIndex);
            if (nextNewline != -1 && nextNewline < cutIndex + 200) {
                cutIndex = nextNewline + 1;
            }
            return "... [خروجی قبلی خلاصه شد - برای کل لاگ /log بزنید]\n" + cleaned.substring(cutIndex);
        }

        return cleaned;
    }

    public synchronized String getFullHistory() {
        return AnsiSanitizer.stripAllAnsi(fullHistory.toString());
    }
}
