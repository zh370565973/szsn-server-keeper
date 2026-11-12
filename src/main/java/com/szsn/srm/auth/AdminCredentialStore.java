package com.szsn.srm.auth;

import com.szsn.srm.common.SrmProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminCredentialStore {
    private final SrmProperties properties;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public AdminCredentialStore(SrmProperties properties) {
        this.properties = properties;
        String configured = configuredPassword();
        if (configured != null && (configured.length() < 12 || configured.length() > 200)) {
            throw new IllegalStateException("srm.admin.password 长度需要在 12 到 200 之间");
        }
    }

    public String username() {
        return properties.getAdmin().getUsername();
    }

    public boolean initialized() {
        return configuredPassword() != null || Files.isRegularFile(path());
    }

    public boolean matches(String rawPassword) {
        String configured = configuredPassword();
        if (configured != null) {
            return rawPassword != null && MessageDigest.isEqual(
                    configured.getBytes(StandardCharsets.UTF_8),
                    rawPassword.getBytes(StandardCharsets.UTF_8));
        }
        Optional<String> hash = readHash();
        return hash.filter(value -> encoder.matches(rawPassword, value)).isPresent();
    }

    /**
     * 配置里的口令非空时，只用这一份登录，不读口令文件。
     */
    private String configuredPassword() {
        String value = properties.getAdmin().getPassword();
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    public void setPassword(String rawPassword, boolean force) throws IOException {
        if (rawPassword == null || rawPassword.length() < 12 || rawPassword.length() > 200) {
            throw new IllegalArgumentException("口令长度需要在 12 到 200 之间");
        }
        Path file = path();
        if (Files.exists(file) && !force) {
            throw new IllegalStateException("口令文件已存在。确认覆盖时追加 --force");
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, encoder.encode(rawPassword) + System.lineSeparator());
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows 开发机没有 POSIX 权限。安装脚本在 Linux 上收紧目录权限。
        }
    }

    private Optional<String> readHash() {
        Path file = path();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String hash = Files.readString(file).trim();
            return hash.isEmpty() ? Optional.empty() : Optional.of(hash);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private Path path() {
        return Path.of(properties.getAdmin().getPasswordFile());
    }
}
