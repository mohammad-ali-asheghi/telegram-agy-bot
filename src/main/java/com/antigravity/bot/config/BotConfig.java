package com.antigravity.bot.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class BotConfig {

    private final String botToken;
    private final Set<Long> allowedUserIds;
    private final String sshHost;
    private final int sshPort;
    private final String sshUser;
    private final String sshPassword;
    private final String sshPrivateKeyPath;
    private final String sshPassphrase;
    private final int sshTimeoutSeconds;
    private final String defaultWorkDir;
    private final String agyBinPath;
    private final String defaultMode;

    public BotConfig() {
        Properties fileProps = loadPropsFromFile();

        this.botToken = getVal("TELEGRAM_BOT_TOKEN", fileProps, null);
        if (this.botToken == null || this.botToken.isBlank()) {
            throw new IllegalArgumentException("TELEGRAM_BOT_TOKEN is required! Set it as an environment variable or in config.properties / .env");
        }

        String allowedIdsStr = getVal("ALLOWED_USER_IDS", fileProps, "");
        if (allowedIdsStr.isBlank()) {
            this.allowedUserIds = Collections.emptySet();
        } else {
            this.allowedUserIds = Arrays.stream(allowedIdsStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .collect(Collectors.toSet());
        }

        this.sshHost = getVal("SSH_HOST", fileProps, "127.0.0.1");
        this.sshPort = Integer.parseInt(getVal("SSH_PORT", fileProps, "22"));
        this.sshUser = getVal("SSH_USER", fileProps, "root");
        this.sshPassword = getVal("SSH_PASSWORD", fileProps, "");
        this.sshPrivateKeyPath = getVal("SSH_PRIVATE_KEY_PATH", fileProps, "");
        this.sshPassphrase = getVal("SSH_PASSPHRASE", fileProps, "");
        this.sshTimeoutSeconds = Integer.parseInt(getVal("SSH_TIMEOUT_SECONDS", fileProps, "300"));
        this.defaultWorkDir = getVal("DEFAULT_WORK_DIR", fileProps, "~");
        this.agyBinPath = getVal("AGY_BIN_PATH", fileProps, "agy");
        this.defaultMode = getVal("DEFAULT_MODE", fileProps, "continue");
    }

    private static String getVal(String key, Properties props, String defaultVal) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        if (props != null && props.containsKey(key)) {
            String val = props.getProperty(key);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        return defaultVal;
    }

    private static Properties loadPropsFromFile() {
        Properties props = new Properties();
        File[] candidates = new File[] {
                new File(".env"),
                new File("config.properties"),
                new File("/app/.env"),
                new File("/app/config.properties")
        };
        for (File f : candidates) {
            if (f.exists() && f.isFile()) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    props.load(fis);
                    break;
                } catch (IOException ignored) {}
            }
        }
        return props;
    }

    public boolean isUserAllowed(long userId) {
        if (allowedUserIds.isEmpty()) {
            return true; // No restriction specified (open, though warned in logs)
        }
        return allowedUserIds.contains(userId);
    }

    public String getBotToken() { return botToken; }
    public Set<Long> getAllowedUserIds() { return allowedUserIds; }
    public String getSshHost() { return sshHost; }
    public int getSshPort() { return sshPort; }
    public String getSshUser() { return sshUser; }
    public String getSshPassword() { return sshPassword; }
    public String getSshPrivateKeyPath() { return sshPrivateKeyPath; }
    public String getSshPassphrase() { return sshPassphrase; }
    public int getSshTimeoutSeconds() { return sshTimeoutSeconds; }
    public String getDefaultWorkDir() { return defaultWorkDir; }
    public String getAgyBinPath() { return agyBinPath; }
    public String getDefaultMode() { return defaultMode; }
}
