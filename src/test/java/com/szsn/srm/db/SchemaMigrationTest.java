package com.szsn.srm.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class SchemaMigrationTest {
    @Test
    void migrationIsRepeatableAndNullScheduleKeysDoNotCollide() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        DatabaseMigrator migrator = new DatabaseMigrator(jdbc.getDataSource());
        migrator.migrate();
        migrator.migrate();
        jdbc.update("INSERT INTO TASK_EXECUTION (ID, CONFIG_SNAPSHOT_JSON, STATUS, VERSION) VALUES (?,?,?,?)",
                "manual-1", "{}", "STOPPED", 1);
        jdbc.update("INSERT INTO TASK_EXECUTION (ID, CONFIG_SNAPSHOT_JSON, STATUS, VERSION) VALUES (?,?,?,?)",
                "manual-2", "{}", "STOPPED", 1);
        jdbc.update("""
                INSERT INTO TASK_EXECUTION (ID, SCHEDULE_ID, SCHEDULED_AT, CONFIG_SNAPSHOT_JSON, STATUS, VERSION)
                VALUES (?,?,?,?,?,?)
                """, "planned-1", "schedule-1", 1_700_000_000_000L, "{}", "SKIPPED", 1);
        assertThrows(Exception.class, () -> jdbc.update("""
                INSERT INTO TASK_EXECUTION (ID, SCHEDULE_ID, SCHEDULED_AT, CONFIG_SNAPSHOT_JSON, STATUS, VERSION)
                VALUES (?,?,?,?,?,?)
                """, "planned-2", "schedule-1", 1_700_000_000_000L, "{}", "SKIPPED", 1));
        Integer applied = jdbc.queryForObject("SELECT COUNT(*) FROM SCHEMA_VERSION", Integer.class);
        assertEquals(1, applied);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM TASK_EXECUTION", Integer.class) >= 2);
    }

    @Test
    void checksumMismatchRefusesStartup() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        DatabaseMigrator migrator = new DatabaseMigrator(jdbc.getDataSource());
        migrator.migrate();
        jdbc.update("UPDATE SCHEMA_VERSION SET CHECKSUM = ? WHERE VERSION = 1", "tampered");
        IllegalStateException exception = assertThrows(IllegalStateException.class, migrator::migrate);
        assertTrue(exception.getMessage().contains("校验和"));
    }

    private static SimpleDriverDataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.h2.Driver.class);
        dataSource.setUrl("jdbc:h2:mem:mig" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1;MODE=REGULAR");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
