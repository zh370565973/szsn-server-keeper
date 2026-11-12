package com.szsn.srm.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.szsn.srm.common.SrmProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AdminCredentialStoreTest {
    @Test
    void configuredPasswordIsTheOnlyLoginSecret() throws Exception {
        Path file = Path.of("target", "credential-store-ignored.password");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new BCryptPasswordEncoder().encode("file-password-1"));
        SrmProperties properties = new SrmProperties();
        properties.getAdmin().setPassword("Adm#K8pQ3mN7vR2xL5");
        properties.getAdmin().setPasswordFile(file.toString());
        AdminCredentialStore store = new AdminCredentialStore(properties);

        assertTrue(store.initialized());
        assertTrue(store.matches("Adm#K8pQ3mN7vR2xL5"));
        assertFalse(store.matches("file-password-1"));
    }

    @Test
    void blankConfigPasswordFallsBackToBcryptFile() throws Exception {
        Path file = Path.of("target", "credential-store-file.password");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new BCryptPasswordEncoder().encode("file-password-1"));
        SrmProperties properties = new SrmProperties();
        properties.getAdmin().setPassword("  ");
        properties.getAdmin().setPasswordFile(file.toString());
        AdminCredentialStore store = new AdminCredentialStore(properties);

        assertTrue(store.initialized());
        assertTrue(store.matches("file-password-1"));
        assertFalse(store.matches("Adm#K8pQ3mN7vR2xL5"));
    }

    @Test
    void shortConfiguredPasswordIsRejected() {
        SrmProperties properties = new SrmProperties();
        properties.getAdmin().setPassword("short-pass");
        assertThrows(IllegalStateException.class, () -> new AdminCredentialStore(properties));
    }
}
