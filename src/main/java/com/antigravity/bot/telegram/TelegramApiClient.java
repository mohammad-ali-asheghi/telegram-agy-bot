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

    public void sendMessage(long chatId, String text, Integer replyToMessageId) {
        String url = baseUrl + "/sendMessage";
        JsonObject json = new JsonObject();
        json.addProperty("chat_id", chatId);
        json.addProperty("text", text);
        if (replyToMessageId != null) {
            json.addProperty("reply_to_message_id", replyToMessageId);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("sendMessage failed (status {}): {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Failed to send message: {}", e.getMessage());
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
