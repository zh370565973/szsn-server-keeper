package com.szsn.srm.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szsn.srm.monitor.SseHub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path file = Path.of("target", "test-admin.password");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new BCryptPasswordEncoder().encode("test-password-1"));
        registry.add("srm.admin.password-file", () -> file.toAbsolutePath().toString());
        registry.add("srm.data-dir", () -> Path.of("target", "srm-it-data").toAbsolutePath().toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:srmit;DB_CLOSE_DELAY=-1;MODE=REGULAR;AUTO_SERVER=FALSE");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SseHub sseHub;

    @Test
    void authenticationCsrfAndControlRefusal() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/monitor/current"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        MvcResult csrf = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/csrf"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        JsonNode csrfBody = objectMapper.readTree(csrf.getResponse().getContentAsString());
        String token = csrfBody.path("data").path("token").asText();
        var cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-password-1\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());

        MvcResult login = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .cookie(cookie)
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-password-1\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/monitor/stream")
                        .session(session))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted());

        SseHub.Client client = sseHub.connect(session.getId(), new SseHub.SseSink() {
            @Override
            public void send(String event, String id, Object data) {
            }

            @Override
            public void close() {
            }
        }, () -> true);
        assertNotNull(client);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/logout")
                        .session(session)
                        .cookie(cookie)
                        .header("X-XSRF-TOKEN", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertTrue(!client.isRunning());

        MvcResult relogin = loginAgain(cookie, token);
        MockHttpSession active = (MockHttpSession) relogin.getRequest().getSession(false);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM TASK_EXECUTION", Long.class);
        MvcResult denied = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/executions")
                        .session(active)
                        .cookie(cookie)
                        .header("X-XSRF-TOKEN", token)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceMode\":\"CPU\",\"cpuTargetPct\":30,\"runMode\":\"FINITE\",\"durationSeconds\":60}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                .andReturn();
        assertEquals("CONTROL_UNAVAILABLE", objectMapper.readTree(denied.getResponse().getContentAsString()).path("error").path("code").asText());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM TASK_EXECUTION", Long.class));

        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        JsonNode current = null;
        while (System.currentTimeMillis() < deadline) {
            MvcResult snapshot = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/monitor/current")
                            .session(active))
                    .andReturn();
            if (snapshot.getResponse().getStatus() == 200) {
                current = objectMapper.readTree(snapshot.getResponse().getContentAsString()).path("data");
                if (current.path("memory").path("totalBytes").asLong() > 0 && current.path("cpu").has("overallBusyPct")) {
                    break;
                }
            }
            Thread.sleep(200);
        }
        assertNotNull(current);
        assertTrue(current.path("memory").path("totalBytes").asLong() > 0);
        assertTrue(current.path("criticalComplete").asBoolean());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/system/info").session(active))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.controlCapable").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.loadEngine").value("CPU"));
    }

    @Test
    void emergencyStopRequiresSessionAndCsrfThenLatchesWithoutStartingLoad() throws Exception {
        long executions = jdbc.queryForObject("SELECT COUNT(*) FROM TASK_EXECUTION", Long.class);
        MvcResult anonymous = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/resource/emergency-stop"))
                .andReturn();
        assertTrue(anonymous.getResponse().getStatus() == 401 || anonymous.getResponse().getStatus() == 403);

        MvcResult csrf = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/csrf"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        String token = objectMapper.readTree(csrf.getResponse().getContentAsString()).path("data").path("token").asText();
        var cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        MvcResult login = loginAgain(cookie, token);
        MockHttpSession active = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/resource/emergency-stop")
                        .session(active))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM SYS_CONFIG WHERE CONFIG_KEY = 'emergency.latch'", Integer.class));

        MvcResult stopped = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/resource/emergency-stop")
                        .session(active)
                        .cookie(cookie)
                        .header("X-XSRF-TOKEN", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(stopped.getResponse().getContentAsString()).path("data");
        assertEquals("OFF", body.path("controlSwitch").asText());
        assertTrue(body.path("emergencyLatched").asBoolean());
        assertEquals("EMERGENCY_STOP", body.path("latchReason").asText());
        assertTrue(body.path("latchPersisted").asBoolean());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM SYS_CONFIG WHERE CONFIG_KEY = 'emergency.latch'", Integer.class));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/resource/emergency-stop")
                        .session(active)
                        .cookie(cookie)
                        .header("X-XSRF-TOKEN", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM SYS_CONFIG WHERE CONFIG_KEY = 'emergency.latch'", Integer.class));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/resource/control-switch")
                        .session(active)
                        .cookie(cookie)
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("CONTROL_LATCHED"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/resource/status").session(active))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.controlSwitch").value("OFF"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.emergencyLatched").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.loadEngine").value("CPU"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.cpu").exists());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/executions")
                        .session(active)
                        .cookie(cookie)
                        .header("X-XSRF-TOKEN", token)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceMode\":\"CPU\",\"cpuTargetPct\":30,\"runMode\":\"FINITE\",\"durationSeconds\":60}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());
        assertEquals(executions, jdbc.queryForObject("SELECT COUNT(*) FROM TASK_EXECUTION", Long.class));
    }

    private MvcResult loginAgain(jakarta.servlet.http.Cookie cookie, String token) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .cookie(cookie)
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-password-1\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
    }
}
