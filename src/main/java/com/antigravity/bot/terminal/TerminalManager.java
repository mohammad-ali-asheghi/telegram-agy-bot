package com.antigravity.bot.terminal;

import com.antigravity.bot.ssh.SshService;
import com.antigravity.bot.telegram.TelegramApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TerminalManager {

    private static final Logger log = LoggerFactory.getLogger(TerminalManager.class);

    private final SshService sshService;
    private final TelegramApiClient apiClient;
    private final ScheduledExecutorService scheduler;

    private final ConcurrentHashMap<Long, TerminalSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> terminalModeActive = new ConcurrentHashMap<>();

    public TerminalManager(SshService sshService, TelegramApiClient apiClient) {
        this.sshService = sshService;
        this.apiClient = apiClient;
        this.scheduler = Executors.newScheduledThreadPool(2);

        // Periodically check for inactive sessions (30 minutes timeout)
        this.scheduler.scheduleAtFixedRate(this::reapInactiveSessions, 5, 5, TimeUnit.MINUTES);
    }

    public synchronized void startSession(long chatId, int initialMessageId, String workDir) throws Exception {
        TerminalSession existing = sessions.get(chatId);
        if (existing != null && existing.isActive()) {
            existing.setLiveMessageId(initialMessageId);
            terminalModeActive.put(chatId, true);
            existing.triggerImmediateUpdate();
            return;
        }

        if (existing != null) {
            existing.close();
        }

        TerminalSession newSession = new TerminalSession(chatId, sshService, apiClient, scheduler);
        newSession.start(initialMessageId, workDir);
        sessions.put(chatId, newSession);
        terminalModeActive.put(chatId, true);
    }

    public TerminalSession getSession(long chatId) {
        return sessions.get(chatId);
    }

    public boolean hasActiveSession(long chatId) {
        TerminalSession session = sessions.get(chatId);
        return session != null && session.isActive();
    }

    public boolean isTerminalMode(long chatId) {
        return terminalModeActive.getOrDefault(chatId, false);
    }

    public void setTerminalMode(long chatId, boolean active) {
        terminalModeActive.put(chatId, active);
    }

    public synchronized void closeSession(long chatId) {
        terminalModeActive.put(chatId, false);
        TerminalSession session = sessions.remove(chatId);
        if (session != null) {
            session.close();
        }
    }

    private void reapInactiveSessions() {
        long maxIdleMs = 30 * 60 * 1000L; // 30 minutes
        for (Map.Entry<Long, TerminalSession> entry : sessions.entrySet()) {
            long chatId = entry.getKey();
            TerminalSession session = entry.getValue();
            if (session.isInactive(maxIdleMs)) {
                log.info("Reaping inactive terminal session for chat {}", chatId);
                closeSession(chatId);
                try {
                    apiClient.sendMessage(chatId,
                            "⏰ نشست ترمینال تعاملی به دلیل عدم فعالیت (۳۰ دقیقه) به طور خودکار بسته شد.", null);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void shutdown() {
        for (TerminalSession session : sessions.values()) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
        terminalModeActive.clear();
        scheduler.shutdownNow();
    }
}
