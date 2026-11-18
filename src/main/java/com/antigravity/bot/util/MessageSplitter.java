package com.antigravity.bot.util;

import java.util.ArrayList;
import java.util.List;

public class MessageSplitter {

    public static final int MAX_CHUNK_SIZE = 3800;

    public static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        if (text.length() <= MAX_CHUNK_SIZE) {
            chunks.add(text);
            return chunks;
        }

        int start = 0;
        int totalLength = text.length();

        while (start < totalLength) {
            //noinspection ExtractMethodRecommender
            int end = Math.min(start + MAX_CHUNK_SIZE, totalLength);

            if (end < totalLength) {
                // Find a logical break point: newline or space
                int newlineIdx = text.lastIndexOf('\n', end);
                if (newlineIdx > start + 1000) {
                    end = newlineIdx + 1;
                } else {
                    int spaceIdx = text.lastIndexOf(' ', end);
                    if (spaceIdx > start + 1000) {
                        end = spaceIdx + 1;
                    }
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            start = end;
        }

        return chunks;
    }
}
