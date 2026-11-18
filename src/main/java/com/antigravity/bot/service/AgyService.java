package com.antigravity.bot.service;

import com.antigravity.bot.config.BotConfig;
import com.antigravity.bot.ssh.SshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class AgyService {

    private static final Logger log = LoggerFactory.getLogger(AgyService.class);

    private final BotConfig config;
    private final SshService sshService;

    // Per-chat state
    private final ConcurrentMap<Long, String> chatWorkDirs = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> chatModes = new ConcurrentHashMap<>();

    public AgyService(BotConfig config, SshService sshService) {
        this.config = config;
        this.sshService = sshService;
    }

    public String getWorkDir(long chatId) {
        return chatWorkDirs.getOrDefault(chatId, config.getDefaultWorkDir());
    }

    public void setWorkDir(long chatId, String workDir) {
        chatWorkDirs.put(chatId, workDir);
    }

    public String getMode(long chatId) {
        return chatModes.getOrDefault(chatId, config.getDefaultMode());
    }

    public void setMode(long chatId, String mode) {
        chatModes.put(chatId, mode);
    }

    /**
     * Executes an Antigravity prompt.
     * @param chatId Telegram chat ID
     * @param prompt The prompt text
     * @param continueSession true to pass -c / --continue
     * @return Execution result
     */
    public SshService.ExecutionResult runPrompt(long chatId, String prompt, boolean continueSession) {
        String workDir = getWorkDir(chatId);
        String agyBin = config.getAgyBinPath();

        // Encode prompt in base64 to avoid all shell escaping issues
        String b64Prompt = Base64.getEncoder().encodeToString(prompt.getBytes(StandardCharsets.UTF_8));

        String continueFlag = continueSession ? "-c " : "";

        // bash -l executes in a login shell to ensure environment PATH and agy aliases are loaded
        String sshCommand = String.format(
                "bash -l -c 'cd %s && %s %s-p \"$(echo \"%s\" | base64 -d)\" --dangerously-skip-permissions'",
                escapePath(workDir),
                agyBin,
                continueFlag,
                b64Prompt
        );

        log.info("Executing agy prompt for chat {}. Continue: {}, WorkDir: {}", chatId, continueSession, workDir);
        return sshService.execute(sshCommand);
    }

    /**
     * Executes an arbitrary shell command in the chat's current working directory.
     */
    public SshService.ExecutionResult runShellCommand(long chatId, String command) {
        String workDir = getWorkDir(chatId);
        String sshCommand = String.format(
                "bash -l -c 'cd %s && %s'",
                escapePath(workDir),
                command
        );

        log.info("Executing shell command for chat {}: {}", chatId, command);
        return sshService.execute(sshCommand, 60); // 60s timeout for shell
    }

    public SshService.ExecutionResult checkStatus(long chatId) {
        String agyBin = config.getAgyBinPath();
        String command = String.format("bash -l -c 'which %s && %s --help | head -n 5 && uptime'", agyBin, agyBin);
        return sshService.execute(command, 20);
    }

    private String escapePath(String path) {
        if (path == null || path.isBlank()) return "~";
        // Avoid single quote escaping issues
        return "'" + path.replace("'", "'\\''") + "'";
    }
}
