package com.antigravity.bot;

import com.antigravity.bot.config.BotConfig;
import com.antigravity.bot.service.AgyService;
import com.antigravity.bot.ssh.SshService;
import com.antigravity.bot.telegram.TelegramBotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("==================================================");
        log.info("  Starting Telegram Antigravity Bot (Java 17)     ");
        log.info("==================================================");

        try {
            BotConfig config = new BotConfig();

            log.info("Config loaded:");
            log.info("  SSH Host: {}:{}", config.getSshHost(), config.getSshPort());
            log.info("  SSH User: {}", config.getSshUser());
            log.info("  Allowed Telegram User IDs: {}",
                    config.getAllowedUserIds().isEmpty() ? "ALL (Warning: Open to anyone!)" : config.getAllowedUserIds());
            log.info("  Default Work Dir: {}", config.getDefaultWorkDir());
            log.info("  Default Mode: {}", config.getDefaultMode());

            SshService sshService = new SshService(config);

            log.info("Testing initial SSH connection to host...");
            boolean sshOk = sshService.testConnection();
            if (sshOk) {
                log.info("SSH connection test: SUCCESSFUL!");
            } else {
                log.warn("SSH connection test failed or could not authenticate. Bot will still run, but check your SSH credentials.");
            }

            AgyService agyService = new AgyService(config, sshService);
            TelegramBotService botService = new TelegramBotService(config, agyService);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down bot...");
                botService.stop();
            }));

            botService.start();

            log.info("Bot is active and listening for messages via Telegram Long Polling!");

        } catch (Exception e) {
            log.error("Fatal startup error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
