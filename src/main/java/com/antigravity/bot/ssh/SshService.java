package com.antigravity.bot.ssh;

import com.antigravity.bot.config.BotConfig;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class SshService {

    private static final Logger log = LoggerFactory.getLogger(SshService.class);
    private final BotConfig config;
    private final JSch jsch;

    public record ExecutionResult(int exitCode, String stdout, String stderr, boolean timedOut) {

        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }
    }

    public SshService(BotConfig config) {
        this.config = config;
        this.jsch = new JSch();
        setupIdentity();
    }

    private void setupIdentity() {
        String keyPath = config.getSshPrivateKeyPath();
        if (keyPath != null && !keyPath.isBlank()) {
            File keyFile = new File(keyPath);
            if (keyFile.exists() && keyFile.isFile()) {
                try {
                    String passphrase = config.getSshPassphrase();
                    if (passphrase != null && !passphrase.isBlank()) {
                        jsch.addIdentity(keyFile.getAbsolutePath(), passphrase);
                    } else {
                        jsch.addIdentity(keyFile.getAbsolutePath());
                    }
                    log.info("Loaded SSH private key identity from {}", keyFile.getAbsolutePath());
                } catch (JSchException e) {
                    log.error("Failed to load SSH private key from {}: {}", keyPath, e.getMessage());
                }
            } else {
                log.warn("SSH private key specified at {} does not exist", keyPath);
            }
        }
    }

    private Session createSession() throws JSchException {
        Session session = jsch.getSession(config.getSshUser(), config.getSshHost(), config.getSshPort());
        if (config.getSshPassword() != null && !config.getSshPassword().isBlank()) {
            session.setPassword(config.getSshPassword());
        }
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
        session.setTimeout(15000); // 15s connection timeout
        return session;
    }

    public boolean testConnection() {
        Session session = null;
        try {
            session = createSession();
            session.connect();
            return session.isConnected();
        } catch (Exception e) {
            log.error("SSH connection test failed: {}", e.getMessage());
            return false;
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    public ExecutionResult execute(String command) {
        return execute(command, config.getSshTimeoutSeconds());
    }

    public ExecutionResult execute(String command, int timeoutSeconds) {
        Session session = null;
        ChannelExec channel = null;
        ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();

        try {
            session = createSession();
            session.connect();

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            InputStream in = channel.getInputStream();
            InputStream err = channel.getErrStream();

            channel.connect();

            byte[] buffer = new byte[1024];
            long startTime = System.currentTimeMillis();
            long maxDurationMs = timeoutSeconds * 1000L;
            boolean timedOut = false;

            while (true) {
                while (in.available() > 0) {
                    int len = in.read(buffer, 0, buffer.length);
                    if (len < 0) break;
                    stdoutStream.write(buffer, 0, len);
                }
                while (err.available() > 0) {
                    int len = err.read(buffer, 0, buffer.length);
                    if (len < 0) break;
                    stderrStream.write(buffer, 0, len);
                }

                if (channel.isClosed()) {
                    if (in.available() > 0 || err.available() > 0) continue;
                    break;
                }

                if (System.currentTimeMillis() - startTime > maxDurationMs) {
                    log.warn("Command timed out after {} seconds: {}", timeoutSeconds, command);
                    timedOut = true;
                    break;
                }

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            int exitCode = timedOut ? -1 : channel.getExitStatus();
            String stdout = stdoutStream.toString(StandardCharsets.UTF_8);
            String stderr = stderrStream.toString(StandardCharsets.UTF_8);

            return new ExecutionResult(exitCode, stdout, stderr, timedOut);

        } catch (Exception e) {
            log.error("SSH Execution error: {}", e.getMessage(), e);
            return new ExecutionResult(-1, stdoutStream.toString(StandardCharsets.UTF_8),
                    "SSH Error: " + e.getMessage(), false);
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }
}
