package com.antigravity.bot.terminal;

import com.antigravity.bot.telegram.model.TelegramModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TerminalTest {

    @Test
    public void testAnsiSanitizer() {
        // Text with SGR colors and cursor movements and OSC titles
        String raw = "\033]0;My Window Title\007\033[01;32mubuntu@host\033[00m:\033[01;34m~\033[00m$ ls -la\033[?25h";
        String cleaned = AnsiSanitizer.cleanForTelegram(raw);

        // Retains SGR colors
        assertTrue(cleaned.contains("\033[01;32m"));
        assertTrue(cleaned.contains("ubuntu@host"));
        assertTrue(cleaned.contains("ls -la"));

        // Stripped OSC and cursor
        assertFalse(cleaned.contains("My Window Title"));
        assertFalse(cleaned.contains("\033[?25h"));

        // Test stripAllAnsi
        String stripped = AnsiSanitizer.stripAllAnsi(raw);
        assertEquals("ubuntu@host:~$ ls -la", stripped.trim());

        // Test backticks escaping
        String backticks = "Here is ```some code```";
        String cleanBackticks = AnsiSanitizer.cleanForTelegram(backticks);
        assertFalse(cleanBackticks.contains("```"));
    }

    @Test
    public void testTerminalBufferLinesAndCarriageReturn() {
        TerminalBuffer buffer = new TerminalBuffer();

        // 1. Normal lines
        buffer.append("line 1\r\nline 2\r\n");
        String out = buffer.getScreenOutput();
        assertTrue(out.contains("line 1"));
        assertTrue(out.contains("line 2"));

        // 2. Carriage return without newline (spinner/progress bar)
        buffer.append("Progress: 10%\rProgress: 50%\rProgress: 100%\r\nDone!\r\n");
        out = buffer.getScreenOutput();
        assertFalse(out.contains("Progress: 10%"));
        assertFalse(out.contains("Progress: 50%"));
        assertTrue(out.contains("Progress: 100%"));
        assertTrue(out.contains("Done!"));

        // 3. Backspace
        buffer.append("abc\bd\r\n");
        out = buffer.getScreenOutput();
        assertTrue(out.contains("abd"));

        // 4. Clear screen
        buffer.append("\033[2JFresh screen\r\n");
        out = buffer.getScreenOutput();
        assertTrue(out.contains("Fresh screen"));
        assertFalse(out.contains("line 1")); // Was cleared!

        // 5. Full history retains everything
        String history = buffer.getFullHistory();
        assertTrue(history.contains("Fresh screen"));
    }

    @Test
    public void testTerminalKeyboards() {
        TelegramModels.InlineKeyboardMarkup keyboard = TerminalKeyboards.buildTerminalKeyboard();
        assertNotNull(keyboard);
        assertNotNull(keyboard.inlineKeyboard);
        assertEquals(4, keyboard.inlineKeyboard.size());

        // Verify important button callback data
        boolean hasCtrlC = keyboard.inlineKeyboard.stream()
                .flatMap(List::stream)
                .anyMatch(b -> "term:ctrlc".equals(b.callbackData));
        assertTrue(hasCtrlC);

        boolean hasExit = keyboard.inlineKeyboard.stream()
                .flatMap(List::stream)
                .anyMatch(b -> "term:exit".equals(b.callbackData));
        assertTrue(hasExit);

        boolean hasNum1 = keyboard.inlineKeyboard.stream()
                .flatMap(List::stream)
                .anyMatch(b -> "term:btn:1".equals(b.callbackData));
        assertTrue(hasNum1);

        boolean hasYes = keyboard.inlineKeyboard.stream()
                .flatMap(List::stream)
                .anyMatch(b -> "term:btn:y".equals(b.callbackData));
        assertTrue(hasYes);
    }
}
