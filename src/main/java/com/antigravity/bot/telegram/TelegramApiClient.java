package com.antigravity.bot.telegram;

import com.antigravity.bot.telegram.model.TelegramModels;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public class TelegramApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiClient.class);
    private final String baseUrl;
    private final HttpClient httpClient;
    private final Gson gson;

    public TelegramApiClient(String botToken) {
        this.baseUrl = "https://api.telegram.org/bot" + botToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.gson = new Gson();
    }

    public TelegramModels.GetUpdatesResponse getUpdates(long offset, int timeoutSeconds) {
        String url = baseUrl + "/getUpdates?offset=" + offset + "&timeout=" + timeoutSeconds;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds + 15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return gson.fromJson(response.body(), TelegramModels.GetUpdatesResponse.class);
            } else {
                log.warn("getUpdates failed with status {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Error in getUpdates: {}", e.getMessage());
        }
        return null;
    }

    public void sendChatAction(long chatId, String action) {
        String url = baseUrl + "/sendChatAction";
        JsonObject json = new JsonObject();
        json.addProperty("chat_id", chatId);
        json.addProperty("action", action);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("Failed to send chat action: {}", e.getMessage());
        }
    }

    public TelegramModels.Message sendMessage(long chatId, String text, Integer replyToMessageId) {
        return sendMessage(chatId, text, replyToMessageId, null, null);
    }

    public TelegramModels.Message sendMessage(long chatId, String text, Integer replyToMessageId,
                                             TelegramModels.InlineKeyboardMarkup replyMarkup, String parseMode) {
        String url = baseUrl + "/sendMessage";
        JsonObject json = new JsonObject();
        json.addProperty("chat_id", chatId);
        json.addProperty("text", text);
        if (replyToMessageId != null) {
            json.addProperty("reply_to_message_id", replyToMessageId);
        }
        if (parseMode != null && !parseMode.isBlank()) {
            json.addProperty("parse_mode", parseMode);
        }
        if (replyMarkup != null) {
            json.add("reply_markup", gson.toJsonTree(replyMarkup));
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                TelegramModels.SendMessageResponse res = gson.fromJson(response.body(), TelegramModels.SendMessageResponse.class);
                return res != null ? res.result : null;
            } else {
                log.warn("sendMessage failed (status {}): {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Failed to send message: {}", e.getMessage());
        }
        return null;
    }

    public boolean editMessageText(long chatId, int messageId, String text,
                                  TelegramModels.InlineKeyboardMarkup replyMarkup, String parseMode) {
        String url = baseUrl + "/editMessageText";
        JsonObject json = new JsonObject();
        json.addProperty("chat_id", chatId);
        json.addProperty("message_id", messageId);
        json.addProperty("text", text);
        if (parseMode != null && !parseMode.isBlank()) {
            json.addProperty("parse_mode", parseMode);
        }
        if (replyMarkup != null) {
            json.add("reply_markup", gson.toJsonTree(replyMarkup));
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            } else {
                String body = response.body();
                // Ignore "message is not modified" error from Telegram API
                if (!body.contains("message is not modified")) {
                    log.warn("editMessageText failed (status {}): {}", response.statusCode(), body);
                }
            }
        } catch (Exception e) {
            log.error("Failed to edit message: {}", e.getMessage());
        }
        return false;
    }

    public void answerCallbackQuery(String callbackQueryId, String text) {
        String url = baseUrl + "/answerCallbackQuery";
        JsonObject json = new JsonObject();
        json.addProperty("callback_query_id", callbackQueryId);
        if (text != null && !text.isBlank()) {
            json.addProperty("text", text);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("Failed to answer callback query: {}", e.getMessage());
        }
    }

    public void sendDocument(long chatId, String filename, byte[] content, String caption) {
        String url = baseUrl + "/sendDocument";
        String boundary = "----BotBoundary" + UUID.randomUUID().toString().replace("-", "");

        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();

            // chat_id part
            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            os.write((chatId + "\r\n").getBytes(StandardCharsets.UTF_8));

            // caption part
            if (caption != null && !caption.isBlank()) {
                os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                os.write("Content-Disposition: form-data; name=\"caption\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                os.write((caption + "\r\n").getBytes(StandardCharsets.UTF_8));
            }

            // document file part
            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Disposition: form-data; name=\"document\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            os.write("Content-Type: text/plain; charset=utf-8\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            os.write(content);
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));

            // end boundary
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(os.toByteArray()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            response.statusCode();
        } catch (Exception e) {
            log.error("Failed to send document: {}", e.getMessage());
        }
    }
}
