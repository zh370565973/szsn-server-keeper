package com.szsn.srm.auth;

import com.szsn.srm.common.SrmProperties;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
@ConditionalOnProperty(name = "srm.mode", havingValue = "init")
public class InitAdminRunner implements ApplicationRunner {
    private final AdminCredentialStore credentials;
    private final SrmProperties properties;

    public InitAdminRunner(AdminCredentialStore credentials, SrmProperties properties) {
        this.credentials = credentials;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean force = args.containsOption("force");
        String password = readSecret();
        credentials.setPassword(password, force);
        System.out.println("管理员口令已写入配置文件 " + properties.getAdmin().getPasswordFile());
    }

    private static String readSecret() throws Exception {
        if (System.console() != null) {
            char[] chars = System.console().readPassword("请输入管理员口令：");
            return chars == null ? "" : new String(chars);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line = reader.readLine();
        return line == null ? "" : line.trim();
    }
}
