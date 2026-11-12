package com.szsn.srm.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.szsn.srm.SrmApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class ExternalConfigFileTest {
    @Test
    void applicationStartupLoadsTheConfiguredFile() throws Exception {
        Path dir = Files.createTempDirectory("srm-config-boot");
        Path file = dir.resolve("application.yml");
        String configPath = file.toAbsolutePath().toString().replace('\\', '/');
        Files.writeString(file, """
                srm:
                  timezone: Pacific/Auckland
                  deploy:
                    config: "%s"
                """.formatted(configPath));
        SpringApplication app = new SpringApplicationBuilder(SrmApplication.class)
                .web(WebApplicationType.SERVLET)
                .build();
        try (ConfigurableApplicationContext context = app.run(
                "--srm.deploy.config=" + configPath,
                "--server.port=0",
                "--srm.data-dir=" + dir.resolve("db").toString().replace('\\', '/'),
                "--spring.datasource.url=jdbc:h2:mem:deploycfg;DB_CLOSE_DELAY=-1;AUTO_SERVER=FALSE",
                "--logging.file.name=" + dir.resolve("test.log").toString().replace('\\', '/'))) {
            assertEquals("Pacific/Auckland", context.getEnvironment().getProperty("srm.timezone"));
            assertEquals(configPath, context.getEnvironment().getProperty("srm.deploy.config"));
        }
    }
}
