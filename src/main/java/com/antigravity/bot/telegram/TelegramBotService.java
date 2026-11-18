package com.antigravity.bot.telegram;

import com.antigravity.bot.config.BotConfig;
import com.antigravity.bot.service.AgyService;
import com.antigravity.bot.ssh.SshService;
import com.antigravity.bot.telegram.model.TelegramModels;
import com.antigravity.bot.util.MessageSplitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class TelegramBotService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);

    private final BotConfig config;
    private final TelegramApiClient apiClient;
    private final AgyService agyService;
    private final ExecutorService commandExecutor;
    private final ScheduledExecutorService heartbeatScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private long lastUpdateId = 0;

    public TelegramBotService(BotConfig config, AgyService agyService) {
        this.config = config;
        this.apiClient = new TelegramApiClient(config.getBotToken());
        this.agyService = agyService;
        this.commandExecutor = Executors.newCachedThreadPool();
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        log.info("Starting Telegram Bot long-polling loop...");
        Thread pollThread = new Thread(this::pollLoop, "Telegram-Poller");
        pollThread.setDaemon(false);
        pollThread.start();
    }

    public void stop() {
        running.set(false);
        commandExecutor.shutdownNow();
        heartbeatScheduler.shutdownNow();
        log.info("Telegram Bot stopped.");
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                TelegramModels.GetUpdatesResponse response = apiClient.getUpdates(lastUpdateId + 1, 20);
                if (response != null && response.ok && response.result != null) {
                    for (TelegramModels.Update update : response.result) {
                        lastUpdateId = Math.max(lastUpdateId, update.updateId);
                        if (update.message != null && update.message.text != null) {
                            handleMessage(update.message);
                        }
                    }
                } else {
                    // Small backoff on error
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Unexpected error in Telegram polling loop: {}", e.getMessage(), e);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handleMessage(TelegramModels.Message message) {
        long userId = message.from != null ? message.from.id : -1;
        long chatId = message.chat != null ? message.chat.id : -1;
        String text = message.text.trim();
        int messageId = message.messageId;

        // Security check
        if (!config.isUserAllowed(userId)) {
            log.warn("Unauthorized access attempt from user ID: {}, username: {}", userId,
                    message.from != null ? message.from.username : "unknown");
            apiClient.sendMessage(chatId,
                    "⛔ دسترسی غیرمجاز!\nشناسه کاربری تلگرام شما: `" + userId + "`\nاین شناسه در لیست مجاز (ALLOWED_USER_IDS) تعریف نشده است.",
                    messageId);
            return;
        }

        commandExecutor.submit(() -> processCommand(chatId, messageId, text, userId));
    }

    private void processCommand(long chatId, int messageId, String text, long userId) {
        try {
            if (text.startsWith("/start") || text.startsWith("/help")) {
                sendHelpMessage(chatId, messageId);
                return;
            }

            if (text.startsWith("/status")) {
                handleStatus(chatId, messageId);
                return;
            }

            if (text.startsWith("/pwd")) {
                apiClient.sendMessage(chatId, "📂 دایرکتوری کاری فعلی:\n`" + agyService.getWorkDir(chatId) + "`", messageId);
                return;
            }

            if (text.startsWith("/cd")) {
                String newDir = text.length() > 3 ? text.substring(3).trim() : "";
                if (newDir.isEmpty()) {
                    apiClient.sendMessage(chatId, "⚠️ لطفاً مسیر مورد نظر را مشخص کنید:\nمثال: `/cd /root/my-project`", messageId);
                } else {
                    agyService.setWorkDir(chatId, newDir);
                    apiClient.sendMessage(chatId, "✅ دایرکتوری کاری تغییر یافت به:\n`" + newDir + "`", messageId);
                }
                return;
            }

            if (text.startsWith("/mode")) {
                String[] parts = text.split("\\s+");
                if (parts.length > 1) {
                    String mode = parts[1].toLowerCase();
                    if (mode.equals("continue") || mode.equals("new")) {
                        agyService.setMode(chatId, mode);
                        apiClient.sendMessage(chatId, "✅ حالت پیش‌فرض برای پیام‌های عادی تغییر کرد به: *" + mode + "*", messageId);
                    } else {
                        apiClient.sendMessage(chatId, "⚠️ مقدار نامعتبر است. فقط `new` یا `continue` مجاز است.", messageId);
                    }
                } else {
                    apiClient.sendMessage(chatId, "ℹ️ حالت فعلی: *" + agyService.getMode(chatId) + "*\nبرای تغییر: `/mode new` یا `/mode continue`", messageId);
                }
                return;
            }

            if (text.startsWith("/sh ")) {
                String cmd = text.substring(4).trim();
                handleShellCommand(chatId, messageId, cmd);
                return;
            }

            if (text.equalsIgnoreCase("/stop") || text.equalsIgnoreCase("/kill") || text.equalsIgnoreCase("/cancel")) {
                apiClient.sendMessage(chatId, "🛑 در حال لغو و بستن پروسه‌های در حال اجرای Antigravity...", messageId);
                agyService.runShellCommand(chatId, "pkill -9 -f agy || true");
                apiClient.sendMessage(chatId, "✅ پروسه‌های agy با موفقیت متوقف شدند.", messageId);
                return;
            }

            // Antigravity Prompt execution
            boolean continueSession;
            String prompt;

            if (text.startsWith("/new ")) {
                continueSession = false;
                prompt = text.substring(5).trim();
            } else if (text.startsWith("/continue ") || text.startsWith("/c ")) {
                continueSession = true;
                prompt = text.substring(text.indexOf(' ') + 1).trim();
            } else {
                // Regular message: uses current chat mode
                continueSession = "continue".equalsIgnoreCase(agyService.getMode(chatId));
                prompt = text;
            }

            if (prompt.isEmpty()) {
                apiClient.sendMessage(chatId, "⚠️ متن پرامپت خالی است. لطفاً پرامپت خود را بنویسید.", messageId);
                return;
            }

            handlePromptExecution(chatId, messageId, prompt, continueSession);

        } catch (Exception e) {
            log.error("Error processing command: {}", e.getMessage(), e);
            apiClient.sendMessage(chatId, "❌ خطایی رخ داد: " + e.getMessage(), messageId);
        }
    }

    private void handleStatus(long chatId, int messageId) {
        apiClient.sendMessage(chatId, "🔍 در حال بررسی ارتباط با سرور و وضعیت Antigravity CLI...", messageId);
        SshService.ExecutionResult result = agyService.checkStatus(chatId);
        if (result.isSuccess()) {
            String msg = "✅ اتصال SSH و سرور برقرار است!\n\n" +
                    "📂 دایرکتوری کاری: `" + agyService.getWorkDir(chatId) + "`\n" +
                    "⚙️ حالت فعلی: `" + agyService.getMode(chatId) + "`\n\n" +
                    "خروجی وضعیت سرور:\n```\n" + result.stdout().trim() + "\n```";
            sendSmartResponse(chatId, messageId, msg);
        } else {
            String err = "❌ خطا در بررسی وضعیت سرور:\n" +
                    (result.stderr() != null && !result.stderr().isBlank() ? result.stderr() : result.stdout());
            apiClient.sendMessage(chatId, err, messageId);
        }
    }

    private void handleShellCommand(long chatId, int messageId, String shellCmd) {
        apiClient.sendChatAction(chatId, "typing");
        SshService.ExecutionResult result = agyService.runShellCommand(chatId, shellCmd);
        String output = result.stdout();
        if (result.stderr() != null && !result.stderr().isBlank()) {
            output += (output.isEmpty() ? "" : "\n--- STDERR ---\n") + result.stderr();
        }
        if (output.isBlank()) {
            output = "(دستور با کد " + result.exitCode() + " بدون خروجی اجرا شد)";
        }
        sendSmartResponse(chatId, messageId, "💻 خروجی شل:\n```\n" + output.trim() + "\n```");
    }

    private void handlePromptExecution(long chatId, int messageId, String prompt, boolean continueSession) {
        String modeLabel = continueSession ? "ادامه گفتگو (Continue)" : "گفتگوی جدید (New Session)";
        apiClient.sendMessage(chatId,
                "⏳ پرامپت دریافت شد!\n" +
                        "حالت: *" + modeLabel + "*\n" +
                        "دایرکتوری: `" + agyService.getWorkDir(chatId) + "`\n" +
                        "در حال اجرا توسط Antigravity در سرور...",
                messageId);

        // Heartbeat typing indicator
        ScheduledFuture<?> typingHeartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            apiClient.sendChatAction(chatId, "typing");
        }, 2, 4, TimeUnit.SECONDS);

        try {
            SshService.ExecutionResult result = agyService.runPrompt(chatId, prompt, continueSession);

            if (result.timedOut()) {
                apiClient.sendMessage(chatId,
                        "⏰ اجرای پرامپت به دلیل زمان‌بندی (Timeout) متوقف شد. می‌توانید با دستور `/status` سرور را بررسی کنید.",
                        messageId);
                return;
            }

            String fullResponse = result.stdout();
            if (result.stderr() != null && !result.stderr().isBlank() && fullResponse.isBlank()) {
                fullResponse = "⚠️ خطای اجرا:\n" + result.stderr();
            }

            if (fullResponse.isBlank()) {
                fullResponse = "⚠️ پاسخی از Antigravity دریافت نشد (کد خروج: " + result.exitCode() + ").";
            }

            sendSmartResponse(chatId, messageId, fullResponse);

        } catch (Exception e) {
            log.error("Error running prompt: {}", e.getMessage(), e);
            apiClient.sendMessage(chatId, "❌ خطا در حین اجرا: " + e.getMessage(), messageId);
        } finally {
            typingHeartbeat.cancel(true);
        }
    }

    private void sendSmartResponse(long chatId, int replyToId, String text) {
        if (text.length() > 12000) {
            // Very large response: send summary + file document
            String preview = text.substring(0, 3000) + "\n\n...(ادامه پاسخ به صورت فایل ضمیمه شد)...";
            apiClient.sendMessage(chatId, preview, replyToId);

            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            apiClient.sendDocument(chatId, "antigravity_response.md", bytes, "📄 پاسخ کامل Antigravity");
            return;
        }

        List<String> chunks = MessageSplitter.split(text);
        if (chunks.size() == 1) {
            apiClient.sendMessage(chatId, chunks.get(0), replyToId);
        } else {
            for (int i = 0; i < chunks.size(); i++) {
                String header = "📄 [بخش " + (i + 1) + " از " + chunks.size() + "]\n\n";
                apiClient.sendMessage(chatId, header + chunks.get(i), i == 0 ? replyToId : null);
            }
        }
    }

    private void sendHelpMessage(long chatId, int messageId) {
        String help = """
                🤖 *بات اختصاصی Antigravity CLI روی سرور*
                
                با این بات می‌توانید بدون نیاز به روشن کردن کامپیوتر شخصی، مستقیماً از طریق تلگرام با Antigravity بر روی سرور ارتباط برقرار کرده و کدنویسی کنید.
                
                📌 *دستورات قابل استفاده:*
                • *ارسال متن ساده:* اجرای پرامپت با حالت فعلی (%s)
                • `/new <prompt>`: شروع یک گفتگوی تازه و پرامپت جدید
                • `/continue <prompt>` یا `/c <prompt>`: ادامه گفتگوی قبلی
                • `/cd <dir>`: تغییر دایرکتوری کاری در سرور
                • `/pwd`: نمایش دایرکتوری کاری فعلی
                • `/mode <new|continue>`: تنظیم حالت پیش‌فرض برای پیام‌های عادی
                • `/sh <cmd>`: اجرای دستور مستقیم شل در سرور (مانند `/sh docker ps`)
                • `/stop` یا `/kill`: متوقف کردن فوری پروسه‌های Antigravity
                • `/status`: بررسی وضعیت اتصال SSH و Antigravity CLI
                • `/help`: نمایش همین راهنما
                
                📂 *دایرکتوری فعلی:* `%s`
                """.formatted(agyService.getMode(chatId), agyService.getWorkDir(chatId));

        apiClient.sendMessage(chatId, help, messageId);
    }
}
