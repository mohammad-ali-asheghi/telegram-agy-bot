package com.antigravity.bot.terminal;

import com.antigravity.bot.ssh.SshService;
import com.antigravity.bot.telegram.TelegramApiClient;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TerminalSession {

    private static final Logger log = LoggerFactory.getLogger(TerminalSession.class);

    private final long chatId;
    private final SshService sshService;
    private final TelegramApiClient apiClient;
    private final ScheduledExecutorService scheduler;
    private final TerminalBuffer buffer;

    private Session jschSession;
    private ChannelShell channelShell;
    private InputStream fromShell;
    private OutputStream toShell;

    private String workDir;
    private int liveMessageId;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile long lastActivityTime;
    private volatile long lastEditTime = 0;
    private ScheduledFuture<?> pendingUpdateFuture;

    public TerminalSession(long chatId, SshService sshService, TelegramApiClient apiClient,
                           ScheduledExecutorService scheduler) {
        this.chatId = chatId;
        this.sshService = sshService;
        this.apiClient = apiClient;
        this.scheduler = scheduler;
        this.buffer = new TerminalBuffer();
        this.lastActivityTime = System.currentTimeMillis();
    }

    public synchronized void start(int initialMessageId, String initialWorkDir) throws Exception {
        this.liveMessageId = initialMessageId;
        this.workDir = (initialWorkDir != null && !initialWorkDir.isBlank()) ? initialWorkDir : "~";

        log.info("Starting interactive terminal session for chat {}", chatId);
        this.jschSession = sshService.createConnectedSession();
        this.channelShell = sshService.openShell(jschSession);

        this.fromShell = channelShell.getInputStream();
        this.toShell = channelShell.getOutputStream();

        channelShell.connect(15000);

        Thread readerThread = new Thread(this::readLoop, "TermReader-" + chatId);
        readerThread.setDaemon(true);
        readerThread.start();

        // Configure shell prompt and initial directory
        String initScript = "export TERM=xterm-256color\n" +
                "export PS1='\\[\\033[01;32m\\]\\u@\\h\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ '\n" +
                "cd " + escapePath(workDir) + "\n";
        toShell.write(initScript.getBytes(StandardCharsets.UTF_8));
        toShell.flush();

        active.set(true);
        this.lastActivityTime = System.currentTimeMillis();
        scheduleLiveUpdate();
    }

    private void readLoop() {
        byte[] buf = new byte[2048];
        try {
            while (!closed.get()) {
                int read = fromShell.read(buf);
                if (read == -1) {
                    log.info("Shell stream closed for chat {}", chatId);
                    break;
                }
                String chunk = new String(buf, 0, read, StandardCharsets.UTF_8);
                buffer.append(chunk);
                scheduleLiveUpdate();
            }
        } catch (IOException e) {
            if (!closed.get()) {
                log.warn("Shell read error for chat {}: {}", chatId, e.getMessage());
            }
        } finally {
            active.set(false);
            scheduleLiveUpdate();
        }
    }

    public synchronized void sendInput(String input) {
        if (!isActive() || toShell == null) return;
        try {
            this.lastActivityTime = System.currentTimeMillis();
            toShell.write((input + "\n").getBytes(StandardCharsets.UTF_8));
            toShell.flush();
        } catch (IOException e) {
            log.error("Failed to send input to shell for chat {}: {}", chatId, e.getMessage());
            active.set(false);
        }
    }

    public synchronized void sendSignal(String signal) {
        if (!isActive() || toShell == null) return;
        try {
            this.lastActivityTime = System.currentTimeMillis();
            toShell.write(signal.getBytes(StandardCharsets.UTF_8));
            toShell.flush();
        } catch (IOException e) {
            log.error("Failed to send signal to shell for chat {}: {}", chatId, e.getMessage());
            active.set(false);
        }
    }

    public synchronized void scheduleLiveUpdate() {
        if (closed.get() || liveMessageId <= 0) return;

        long now = System.currentTimeMillis();
        long timeSinceLastEdit = now - lastEditTime;

        if (pendingUpdateFuture != null && !pendingUpdateFuture.isDone()) {
            return; // An update is already scheduled
        }

        // Debounce to respect Telegram rate limit (~1 edit per second)
        long delayMs = Math.max(0, 1000 - timeSinceLastEdit);
        pendingUpdateFuture = scheduler.schedule(this::performLiveUpdate, delayMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void triggerImmediateUpdate() {
        if (closed.get() || liveMessageId <= 0) return;
        if (pendingUpdateFuture != null) {
            pendingUpdateFuture.cancel(false);
        }
        scheduler.execute(this::performLiveUpdate);
    }

    private synchronized void performLiveUpdate() {
        if (closed.get() || liveMessageId <= 0) return;

        try {
            String screenText = buffer.getScreenOutput();
            if (screenText.isBlank()) {
                screenText = "[در انتظار دستور یا ورودی...]";
            }

            String formattedMessage = formatTerminalHtml(screenText);

            boolean ok = apiClient.editMessageText(chatId, liveMessageId, formattedMessage,
                    TerminalKeyboards.buildTerminalKeyboard(), "HTML");
            lastEditTime = System.currentTimeMillis();

            if (!ok && !closed.get()) {
                log.debug("Live update edit did not succeed for chat {}, message {}", chatId, liveMessageId);
            }
        } catch (Exception e) {
            log.error("Failed to update terminal message: {}", e.getMessage());
        }
    }

    private String formatTerminalHtml(String screenText) {
        String statusIcon = active.get() ? "🟢" : "🔴 (قطع شده)";
        return "🖥️ <b>ترمینال زنده سرور (Interactive Shell)</b> " + statusIcon + "\n" +
                "📁 دایرکتوری: <code>" + escapeHtml(workDir) + "</code>\n\n" +
                "<pre><code class=\"language-ansi\">" +
                escapeHtml(screenText) +
                "</code></pre>\n" +
                "<i>💡 می‌توانید مستقیماً تایپ کنید یا از دکمه‌های زیر استفاده کنید.</i>";
    }

    public synchronized void clear() {
        buffer.clear();
        this.lastActivityTime = System.currentTimeMillis();
    }

    public String getFullLog() {
        return buffer.getFullHistory();
    }

    public boolean isActive() {
        return active.get() && channelShell != null && channelShell.isConnected();
    }

    public boolean isInactive(long maxIdleMs) {
        return System.currentTimeMillis() - lastActivityTime > maxIdleMs;
    }

    public void setLiveMessageId(int messageId) {
        this.liveMessageId = messageId;
    }

    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            active.set(false);
            if (pendingUpdateFuture != null) {
                pendingUpdateFuture.cancel(false);
            }
            try {
                if (toShell != null) {
                    try {
                        toShell.write("exit\n".getBytes(StandardCharsets.UTF_8));
                        toShell.flush();
                    } catch (Exception ignored) {
                    }
                }
                if (channelShell != null && channelShell.isConnected()) {
                    channelShell.disconnect();
                }
                if (jschSession != null && jschSession.isConnected()) {
                    jschSession.disconnect();
                }
            } catch (Exception e) {
                log.debug("Error while closing terminal session for chat {}: {}", chatId, e.getMessage());
            }
            log.info("Terminal session closed for chat {}", chatId);
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String escapePath(String path) {
        if (path == null || path.isBlank()) return "~";
        return "'" + path.replace("'", "'\\''") + "'";
    }
}
