package com.szsn.srm.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseMigrator implements InitializingBean {
    private static final Pattern FILE_NAME = Pattern.compile("V(\\d+)__(.+)\\.sql");

    private final JdbcTemplate jdbc;

    public DatabaseMigrator(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        migrate();
    }

    public void migrate() throws IOException {
        List<Script> scripts = loadScripts();
        ensureVersionTable(scripts);
        for (Script script : scripts) {
            apply(script);
        }
    }

    private void ensureVersionTable(List<Script> scripts) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'SCHEMA_VERSION'",
                Integer.class);
        if (count != null && count > 0) {
            return;
        }
        if (scripts.isEmpty()) {
            throw new IllegalStateException("缺少数据库迁移脚本");
        }
        jdbc.execute("""
                CREATE TABLE SCHEMA_VERSION (
                    VERSION INT PRIMARY KEY,
                    DESCRIPTION VARCHAR(200) NOT NULL,
                    CHECKSUM VARCHAR(64) NOT NULL,
                    APPLIED_AT BIGINT NOT NULL
                )
                """);
    }

    private void apply(Script script) {
        List<String> rows = jdbc.query("SELECT CHECKSUM FROM SCHEMA_VERSION WHERE VERSION = ?",
                (rs, row) -> rs.getString(1), script.version());
        if (!rows.isEmpty()) {
            if (!rows.get(0).equals(script.checksum())) {
                throw new IllegalStateException("迁移 V" + script.version() + " 校验和与已应用脚本不一致，拒绝启动");
            }
            return;
        }
        for (String statement : script.statements()) {
            if (statement.toUpperCase().startsWith("CREATE TABLE SCHEMA_VERSION")) {
                continue;
            }
            jdbc.execute(statement);
        }
        jdbc.update("INSERT INTO SCHEMA_VERSION (VERSION, DESCRIPTION, CHECKSUM, APPLIED_AT) VALUES (?, ?, ?, ?)",
                script.version(), script.description(), script.checksum(), System.currentTimeMillis());
    }

    static List<Script> loadScripts() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql");
        List<Script> scripts = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            Matcher matcher = FILE_NAME.matcher(filename == null ? "" : filename);
            if (!matcher.matches()) {
                continue;
            }
            String sql = read(resource);
            scripts.add(new Script(
                    Integer.parseInt(matcher.group(1)),
                    matcher.group(2),
                    checksum(sql),
                    statements(sql)));
        }
        scripts.sort(Comparator.comparingInt(Script::version));
        return scripts;
    }

    static List<String> statements(String sql) {
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\\R")) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : current.toString().split(";")) {
            if (!part.isBlank()) {
                statements.add(part.trim());
            }
        }
        return statements;
    }

    static String checksum(String sql) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String read(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public record Script(int version, String description, String checksum, List<String> statements) {
    }
}
