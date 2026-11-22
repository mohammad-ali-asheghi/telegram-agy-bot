package com.antigravity.bot.telegram;

import com.antigravity.bot.config.BotConfig;
import com.antigravity.bot.service.AgyService;
import com.antigravity.bot.ssh.SshService;
import com.antigravity.bot.telegram.model.TelegramModels;
import com.antigravity.bot.terminal.TerminalKeyboards;
import com.antigravity.bot.terminal.TerminalManager;
import com.antigravity.bot.terminal.TerminalSession;
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
    private final TerminalManager terminalManager;
    private final ExecutorService commandExecutor;
    private final ScheduledExecutorService heartbeatScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private long lastUpdateId = 0;

    public TelegramBotService(BotConfig config, AgyService agyService, SshService sshService) {
        this.config = config;
        this.apiClient = new TelegramApiClient(config.getBotToken());
        this.agyService = agyService;
        this.terminalManager = new TerminalManager(sshService, this.apiClient);
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
        terminalManager.shutdown();
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
                        } else if (update.callbackQuery != null) {
                            handleCallbackQuery(update.callbackQuery);
                        }
                    }
                } else {
                    // Small backoff on error
                    //noinspection BusyWait
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Unexpected error in Telegram polling loop: {}", e.getMessage(), e);
                try {
                    //noinspection BusyWait
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

        commandExecutor.submit(() -> processMessage(chatId, messageId, text));
    }

    private void processMessage(long chatId, int messageId, String text) {
        try {
            // Check if chat is currently in interactive terminal mode
            if (terminalManager.isTerminalMode(chatId)) {
                processTerminalInput(chatId, messageId, text);
                return;
            }

            // Normal Bot Mode
            if (text.startsWith("/start") || text.startsWith("/help")) {
                sendHelpMessage(chatId, messageId);
                return;
            }

            if (text.startsWith("/term") || text.startsWith("/terminal") || text.startsWith("/bash")) {
                startOrAttachTerminal(chatId, messageId);
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

            if (text.equalsIgnoreCase("/log") || text.equalsIgnoreCase("/termlog")) {
                TerminalSession session = terminalManager.getSession(chatId);
                if (session != null) {
                    sendTerminalLog(chatId, session);
                } else {
                    apiClient.sendMessage(chatId, "⚠️ نشست ترمینالی برای این گفتگو ثبت نشده است.", messageId);
                }
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

    private void processTerminalInput(long chatId, int messageId, String text) {
        if (text.equalsIgnoreCase("/exit") || text.equalsIgnoreCase("/quit") || text.equalsIgnoreCase("/close")) {
            terminalManager.closeSession(chatId);
            apiClient.sendMessage(chatId, "🔒 نشست ترمینال بسته شد و به حالت عادی ربات برگشتید.", messageId);
            return;
        }

        if (text.equalsIgnoreCase("/detach")) {
            terminalManager.setTerminalMode(chatId, false);
            apiClient.sendMessage(chatId, "🔌 ترمینال در پس‌زمینه در حال اجرا ماند (Detached).\nبرای بازگشت و مشاهده خروجی زنده، دستور `/term` را ارسال کنید.", messageId);
            return;
        }

        if (text.equalsIgnoreCase("/ctrlc") || text.equalsIgnoreCase("/c")) {
            TerminalSession session = terminalManager.getSession(chatId);
            if (session != null && session.isActive()) {
                session.sendSignal("\u0003");
                apiClient.sendMessage(chatId, "🛑 سیگنال لغو (Ctrl+C) به ترمینال ارسال شد.", messageId);
            }
            return;
        }

        if (text.equalsIgnoreCase("/ctrld")) {
            TerminalSession session = terminalManager.getSession(chatId);
            if (session != null && session.isActive()) {
                session.sendSignal("\u0004");
                apiClient.sendMessage(chatId, "␄ کلید Ctrl+D (EOF) به ترمینال ارسال شد.", messageId);
            }
            return;
        }

        if (text.equalsIgnoreCase("/clear")) {
            TerminalSession session = terminalManager.getSession(chatId);
            if (session != null && session.isActive()) {
                session.clear();
                session.sendInput("clear");
            }
            return;
        }

        if (text.equalsIgnoreCase("/log") || text.equalsIgnoreCase("/termlog")) {
            TerminalSession session = terminalManager.getSession(chatId);
            if (session != null) {
                sendTerminalLog(chatId, session);
            }
            return;
        }

        if (text.equalsIgnoreCase("/help")) {
            sendTerminalHelp(chatId, messageId);
            return;
        }

        TerminalSession session = terminalManager.getSession(chatId);
        if (session != null && session.isActive()) {
            // Forward input straight to terminal PTY
            session.sendInput(text);
        } else {
            terminalManager.setTerminalMode(chatId, false);
            apiClient.sendMessage(chatId, "⚠️ نشست ترمینال منقضی یا متوقف شده است.\nبرای باز کردن یک ترمینال تازه دستور `/term` را بفرستید.", messageId);
        }
    }

    private void handleCallbackQuery(TelegramModels.CallbackQuery query) {
        if (query == null || query.from == null) return;
        long userId = query.from.id;
        long chatId = query.message != null && query.message.chat != null ? query.message.chat.id : -1;
        String data = query.data;

        if (!config.isUserAllowed(userId)) {
            apiClient.answerCallbackQuery(query.id, "⛔ دسترسی غیرمجاز!");
            return;
        }

        if (data != null && data.startsWith("term:")) {
            handleTerminalCallback(chatId, query.id, data);
        }
    }

    private void handleTerminalCallback(long chatId, String queryId, String data) {
        TerminalSession session = terminalManager.getSession(chatId);
        if (session == null || !session.isActive()) {
            apiClient.answerCallbackQuery(queryId, "⚠️ ترمینال فعال نیست. برای شروع /term بزنید.");
            return;
        }

        switch (data) {
            case "term:ctrlc" -> {
                session.sendSignal("\u0003");
                apiClient.answerCallbackQuery(queryId, "🛑 ارسال شد: Ctrl+C");
            }
            case "term:enter" -> {
                session.sendSignal("\n");
                apiClient.answerCallbackQuery(queryId, "⏎ ارسال شد: Enter");
            }
            case "term:tab" -> {
                session.sendSignal("\t");
                apiClient.answerCallbackQuery(queryId, "↹ ارسال شد: Tab");
            }
            case "term:up" -> {
                session.sendSignal("\033[A");
                apiClient.answerCallbackQuery(queryId, "⬆️ ارسال شد: Up Arrow");
            }
            case "term:ctrld" -> {
                session.sendSignal("\u0004");
                apiClient.answerCallbackQuery(queryId, "␄ ارسال شد: Ctrl+D");
            }
            case "term:clear" -> {
                session.clear();
                session.sendInput("clear");
                apiClient.answerCallbackQuery(queryId, "🧹 صفحه پاکسازی شد");
            }
            case "term:refresh" -> {
                session.triggerImmediateUpdate();
                apiClient.answerCallbackQuery(queryId, "🔄 رفرش شد");
            }
            case "term:log" -> {
                apiClient.answerCallbackQuery(queryId, "📥 در حال ارسال فایل لاگ...");
                sendTerminalLog(chatId, session);
            }
            case "term:exit" -> {
                terminalManager.closeSession(chatId);
                apiClient.answerCallbackQuery(queryId, "🔒 ترمینال بسته شد");
                apiClient.sendMessage(chatId, "🔒 نشست ترمینال بسته شد و به گفتگوی عادی ربات بازگشتید.", null);
            }
            default -> {
                if (data.startsWith("term:btn:")) {
                    String choice = data.substring("term:btn:".length());
                    session.sendInput(choice);
                    apiClient.answerCallbackQuery(queryId, "ارسال شد: " + choice);
                } else if (data.startsWith("term:cmd:")) {
                    String cmd = data.substring("term:cmd:".length());
                    session.sendInput(cmd);
                    apiClient.answerCallbackQuery(queryId, "اجرا شد: " + cmd);
                } else {
                    apiClient.answerCallbackQuery(queryId, "");
                }
            }
        }
    }

    private void startOrAttachTerminal(long chatId, int messageId) {
        String currentWorkDir = agyService.getWorkDir(chatId);
        TerminalSession existing = terminalManager.getSession(chatId);

        if (existing != null && existing.isActive()) {
            terminalManager.setTerminalMode(chatId, true);
            TelegramModels.Message termMsg = apiClient.sendMessage(chatId,
                    "🖥️ <b>اتصال مجدد به ترمینال فعال...</b>", messageId,
                    TerminalKeyboards.buildTerminalKeyboard(), "HTML");
            if (termMsg != null) {
                existing.setLiveMessageId(termMsg.messageId);
                existing.triggerImmediateUpdate();
            }
            return;
        }

        TelegramModels.Message termMsg = apiClient.sendMessage(chatId,
                "🖥️ <b>ترمینال زنده سرور (Interactive Shell)</b> 🟢\n" +
                        "📁 مسیر کاری: <code>" + currentWorkDir + "</code>\n\n" +
                        "<pre><code class=\"language-ansi\">[درحال راه‌اندازی شل تعاملی...]</code></pre>\n" +
                        "<i>💡 اکنون می‌توانید هر دستوری (مثل ls، اجرای اسکریپت یا انتخاب گزینه‌ها) را تایپ کنید.</i>",
                messageId, TerminalKeyboards.buildTerminalKeyboard(), "HTML");

        int initialMsgId = termMsg != null ? termMsg.messageId : -1;
        try {
            terminalManager.startSession(chatId, initialMsgId, currentWorkDir);
            terminalManager.setTerminalMode(chatId, true);
        } catch (Exception e) {
            log.error("Failed to start terminal session for chat {}: {}", chatId, e.getMessage(), e);
            apiClient.sendMessage(chatId, "❌ خطا در باز کردن ترمینال: " + e.getMessage(), messageId);
        }
    }

    private void sendTerminalLog(long chatId, TerminalSession session) {
        String logText = session.getFullLog();
        if (logText == null || logText.isBlank()) {
            apiClient.sendMessage(chatId, "⚠️ هنوز خروجی‌ای در ترمینال ثبت نشده است.", null);
            return;
        }
        byte[] bytes = logText.getBytes(StandardCharsets.UTF_8);
        String filename = "terminal_log_" + System.currentTimeMillis() + ".txt";
        apiClient.sendDocument(chatId, filename, bytes, "📄 لاگ کامل نشست ترمینال تعاملی");
    }

    private void handleStatus(long chatId, int messageId) {
        apiClient.sendMessage(chatId, "🔍 در حال بررسی ارتباط با سرور و وضعیت Antigravity CLI...", messageId);
        SshService.ExecutionResult result = agyService.checkStatus(chatId);
        if (result.isSuccess()) {
            boolean hasTerm = terminalManager.hasActiveSession(chatId);
            String msg = "✅ اتصال SSH و سرور برقرار است!\n\n" +
                    "📂 دایرکتوری کاری: `" + agyService.getWorkDir(chatId) + "`\n" +
                    "⚙️ حالت فعلی گفتگوی AI: `" + agyService.getMode(chatId) + "`\n" +
                    "💻 وضعیت ترمینال تعاملی: " + (hasTerm ? "🟢 *فعال*" : "⚪ *غیرفعال (با /term شروع کنید)*") + "\n\n" +
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
        String response = "💻 خروجی شل:\n```\n" + output.trim() + "\n```\n\n" +
                "💡 *نکته:* برای اجرای اسکریپت‌های تعاملی، انتخاب گزینه‌ها و ورودی زنده از دستور `/term` استفاده کنید.";
        sendSmartResponse(chatId, messageId, response);
    }

    private void handlePromptExecution(long chatId, int messageId, String prompt, boolean continueSession) {
        String modeLabel = continueSession ? "ادامه گفتگو (Continue)" : "گفتگوی جدید (New Session)";
        apiClient.sendMessage(chatId,
                "⏳ پرامپت دریافت شد!\n" +
                        "حالت: *" + modeLabel + "*\n" +
                        "دایرکتوری: `" + agyService.getWorkDir(chatId) + "`\n" +
                        "در حال اجرا توسط Antigravity در سرور...",
                messageId);

        ScheduledFuture<?> typingHeartbeat =
                heartbeatScheduler.scheduleAtFixedRate(() -> apiClient.sendChatAction(chatId, "typing"), 2, 4, TimeUnit.SECONDS);

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

    private void sendTerminalHelp(long chatId, int messageId) {
        String help = """
                🖥️ *راهنمای محیط ترمینال تعاملی:*
                
                در این حالت، ربات مانند یک پنجره ترمینال واقعی عمل می‌کند:
                • *ارسال هر پیام:* مستقیماً در شل اجرا شده یا به عنوان پاسخ به ورودی اسکریپت ارسال می‌شود.
                • *دکمه‌های شیشه‌ای:*
                  - `🛑 Ctrl+C`: متوقف کردن اسکریپت یا پروسه در حال اجرا
                  - `⏎ Enter`: زدن اینتر
                  - `↹ Tab`: تکمیل خودکار نام فایل‌ها و دستورات
                  - `⬆️ قبلی`: فراخوانی آخرین دستور تایپ شده
                  - `1️⃣ تا 5️⃣`: انتخاب سریع شماره گزینه‌ها در منوهای اسکریپت
                  - `👍 y` / `👎 n`: تأیید یا رد سریع در پرسش‌های [y/N]
                  - `📂 ls`: مشاهده محتویات پوشه فعلی
                  - `🧹 پاکسازی`: پاک کردن صفحه ترمینال
                  - `🔄 رفرش`: به‌روزرسانی فوری صفحه خروجی
                  - `📥 لاگ کامل`: دریافت کل تاریخچه خروجی‌ها به صورت فایل متنی
                  - `❌ خروج`: بستن ترمینال و بازگشت به حالت عادی
                
                📌 *دستورات متنی ترمینال:*
                • `/exit`: بستن ترمینال و بازگشت
                • `/detach`: خارج شدن بدون بستن پروسه‌های پس‌زمینه
                • `/ctrlc`: ارسال سیگنال لغو
                • `/clear`: پاکسازی صفحه
                • `/log`: دانلود فایل لاگ
                """;
        apiClient.sendMessage(chatId, help, messageId);
    }

    private void sendHelpMessage(long chatId, int messageId) {
        String help = """
                🤖 *بات اختصاصی Antigravity CLI و ترمینال سرور*
                
                با این بات می‌توانید از راه دور، بدون نیاز به کامپیوتر، با سرور کدنویسی کرده و به شل واقعی لینوکس متصل شوید.
                
                🖥️ *ترمینال تعاملی واقعی (Interactive Shell):*
                • `/term` یا `/terminal`: ورود به ترمینال زنده با پشتیبانی از اجرای اسکریپت‌ها، منوهای انتخابی، ورودی زنده stdin و دکمه‌های کنترل
                • پشتیبانی کامل از `ls`، اسکریپت‌های باش و پایتون، کلیدهای کنترل و انتخاب گزینه‌ها
                
                🤖 *دستورات برنامه‌نویسی با Antigravity AI:*
                • *ارسال متن ساده:* اجرای پرامپت با حالت فعلی (%s)
                • `/new <prompt>`: شروع یک گفتگوی تازه و پرامپت جدید
                • `/continue <prompt>` یا `/c <prompt>`: ادامه گفتگوی قبلی
                • `/stop` یا `/kill`: متوقف کردن فوری پروسه‌های Antigravity
                
                ⚙️ *تنظیمات و دستورات سرور:*
                • `/cd <dir>`: تغییر دایرکتوری کاری در سرور
                • `/pwd`: نمایش دایرکتوری کاری فعلی
                • `/mode <new|continue>`: تنظیم حالت پیش‌فرض برای پیام‌های عادی
                • `/sh <cmd>`: اجرای سریع یک دستور تکی در شل
                • `/status`: بررسی وضعیت اتصال SSH و سرور
                • `/help`: نمایش همین راهنما
                
                📂 *دایرکتوری فعلی:* `%s`
                """.formatted(agyService.getMode(chatId), agyService.getWorkDir(chatId));

        apiClient.sendMessage(chatId, help, messageId);
    }
}
