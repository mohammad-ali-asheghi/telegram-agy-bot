package com.antigravity.bot.terminal;

import com.antigravity.bot.telegram.model.TelegramModels.InlineKeyboardButton;
import com.antigravity.bot.telegram.model.TelegramModels.InlineKeyboardMarkup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TerminalKeyboards {

    public static InlineKeyboardMarkup buildTerminalKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Row 1: Essential Terminal Controls
        rows.add(Arrays.asList(
                new InlineKeyboardButton("🛑 Ctrl+C", "term:ctrlc"),
                new InlineKeyboardButton("⏎ Enter", "term:enter"),
                new InlineKeyboardButton("↹ Tab", "term:tab"),
                new InlineKeyboardButton("⬆️ قبلی", "term:up")
        ));

        // Row 2: Selections (1 to 5) for interactive menus
        rows.add(Arrays.asList(
                new InlineKeyboardButton("1️⃣", "term:btn:1"),
                new InlineKeyboardButton("2️⃣", "term:btn:2"),
                new InlineKeyboardButton("3️⃣", "term:btn:3"),
                new InlineKeyboardButton("4️⃣", "term:btn:4"),
                new InlineKeyboardButton("5️⃣", "term:btn:5")
        ));

        // Row 3: Confirmations & common quick actions
        rows.add(Arrays.asList(
                new InlineKeyboardButton("👍 y (بله)", "term:btn:y"),
                new InlineKeyboardButton("👎 n (خیر)", "term:btn:n"),
                new InlineKeyboardButton("📂 ls", "term:cmd:ls"),
                new InlineKeyboardButton("🧹 پاکسازی", "term:clear")
        ));

        // Row 4: Session lifecycle controls
        rows.add(Arrays.asList(
                new InlineKeyboardButton("🔄 رفرش", "term:refresh"),
                new InlineKeyboardButton("📥 لاگ کامل", "term:log"),
                new InlineKeyboardButton("❌ خروج", "term:exit")
        ));

        return new InlineKeyboardMarkup(rows);
    }
}
