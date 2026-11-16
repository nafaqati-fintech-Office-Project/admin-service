package com.fintech.admin_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The admin API against a throwaway PostgreSQL and a stub that plays user-service and billing-service. */
@SpringBootTest(properties = "internal.api-token=test-internal-token-0123456789")
@AutoConfigureMockMvc
@Testcontainers
class AdminApiTest {

    private static final String ADMIN = UUID.randomUUID().toString();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("admin_test_db");

    static final HttpServer STUB = startStub();

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + STUB.getAddress().getPort();
        registry.add("admin.downstream.user-service-url", () -> base);
        registry.add("admin.downstream.billing-service-url", () -> base);
        registry.add("admin.health-targets", () -> "stub=" + base + ",gone=http://127.0.0.1:1");
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String today = LocalDate.now(ZoneOffset.UTC).toString();
            server.createContext("/actuator/health", ex -> reply(ex, 200, "{\"status\":\"UP\"}"));
            server.createContext("/internal/admin/stats", ex -> {
                boolean fromAdmin = "admin-service".equals(ex.getRequestHeaders().getFirst("X-Internal-Service"))
                        && "test-internal-token-0123456789".equals(ex.getRequestHeaders().getFirst("X-Internal-Token"));
                reply(ex, fromAdmin ? 200 : 403, "{\"total\":120,\"last30\":30,\"previous30\":20,"
                        + "\"signupsByDay\":[{\"date\":\"" + today + "\",\"count\":4}],"
                        + "\"activeSubscriptions\":15,\"pastDue\":1,\"churn30d\":2.5,\"failedPayments7d\":3,"
                        + "\"byInterval\":[{\"label\":\"MONTHLY\",\"count\":10},{\"label\":\"YEARLY\",\"count\":5}],"
                        + "\"byProvider\":[{\"label\":\"paymob\",\"count\":15}]}");
            });
            server.createContext("/internal/admin/users", ex -> reply(ex, 200,
                    "{\"items\":[{\"id\":\"11111111-2222-3333-4444-555555555555\",\"status\":\"ACTIVE\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                            + "\"email\":\"leak@example.com\",\"firstName\":\"Leak\"}],\"total\":1}"));
            server.createContext("/internal/admin/deletion-requests", ex -> reply(ex, 200,
                    "[{\"id\":\"a1\",\"userRef\":\"11111111\",\"status\":\"REQUESTED\"}]"));
            server.createContext("/api/admin/deletion-requests", ex -> {
                boolean authorized = ex.getRequestHeaders().getFirst("Authorization") != null;
                reply(ex, authorized ? 200 : 403, "{}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder b) {
        return b.header("X-User-Id", ADMIN).header("X-User-Roles", "ADMIN");
    }

    // ---- access ----

    @Test
    void nothingIsServedWithoutAnAdminIdentity() throws Exception {
        mvc.perform(get("/api/admin/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/audit").header("X-User-Id", ADMIN).header("X-User-Roles", "USER"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit").header("X-User-Id", "not-a-uuid").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(asAdmin(get("/api/admin/audit"))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/audit").header("X-User-Id", ADMIN).header("X-User-Roles", "ROLE_ADMIN"))
                .andExpect(status().isOk());
    }

    // ---- aggregates ----

    @Test
    void overviewHasCountsFromTheSourcesAndNullForWhatIsNotConnected() throws Exception {
        String body = mvc.perform(asAdmin(get("/api/admin/stats/overview")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.users").value(120))
                .andExpect(jsonPath("$.totals.premium").value(15))
                .andExpect(jsonPath("$.totals.activeUsers30d").doesNotExist())
                .andExpect(jsonPath("$.deltas.users").value(50.0))
                .andExpect(jsonPath("$.signups.length()").value(30))
                .andExpect(jsonPath("$.planBreakdown[0].count").value(105))
                .andExpect(jsonPath("$.sources.users").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("email").doesNotContain("amount");
    }

    @Test
    void billingStatsAreCountsAndRates() throws Exception {
        mvc.perform(asAdmin(get("/api/admin/stats/billing")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.activeSubscriptions").value(15))
                .andExpect(jsonPath("$.totals.churn30d").value(2.5))
                .andExpect(jsonPath("$.byPlan[0].label").value("Premium monthly"));
    }

    @Test
    void accountListNeverCarriesWhatTheSourceShouldNotHaveSent() throws Exception {
        // the stub deliberately leaks an e-mail and a name; the service must still return only id, status and date
        String body = mvc.perform(asAdmin(get("/api/admin/users")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("11111111-2222-3333-4444-555555555555"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"status\":\"ACTIVE\"").doesNotContain("leak@example.com").doesNotContain("Leak")
                .doesNotContain("email").doesNotContain("firstName");
    }

    // ---- actions and audit ----

    @Test
    void anActionIsForwardedAsTheAdminAndAudited() throws Exception {
        UUID request = UUID.randomUUID();
        mvc.perform(asAdmin(post("/api/admin/deletion-requests/" + request + "/process")).header("Authorization", "Bearer abc"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'DELETION_REQUEST_PROCESS' "
                + "and target = ? and outcome = 'SUCCESS'", Long.class, request.toString())).isEqualTo(1L);
    }

    @Test
    void aRefusedActionIsAuditedAsDenied() throws Exception {
        UUID request = UUID.randomUUID();
        // no bearer token to forward: the source refuses, and the refusal is recorded
        mvc.perform(asAdmin(post("/api/admin/deletion-requests/" + request + "/cancel"))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'DELETION_REQUEST_CANCEL' "
                + "and target = ? and outcome = 'DENIED'", Long.class, request.toString())).isEqualTo(1L);
    }

    @Test
    void unknownActionsAndBadIdsAreRejected() throws Exception {
        mvc.perform(asAdmin(post("/api/admin/deletion-requests/" + UUID.randomUUID() + "/purge-everything"))
                .header("Authorization", "Bearer abc")).andExpect(status().isNotFound());
        mvc.perform(asAdmin(post("/api/admin/deletion-requests/../../x/process")).header("Authorization", "Bearer abc"))
                .andExpect(status().is4xxClientError());
        mvc.perform(asAdmin(post("/api/admin/deletion-requests/not-a-uuid/process")).header("Authorization", "Bearer abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theAuditLogCannotBeChangedOrDeleted() {
        jdbc.update("insert into audit_log (at, actor, action, outcome) values (now(), 'x', 'TEST', 'SUCCESS')");
        assertThatThrownBy(() -> jdbc.update("delete from audit_log")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("update audit_log set actor = 'y'")).hasMessageContaining("append-only");
    }

    @Test
    void accountStatusAndForceLogoutAreHonestlyNotSupportedYet() throws Exception {
        mvc.perform(asAdmin(post("/api/admin/users/" + UUID.randomUUID() + "/status")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SUSPENDED\"}")).andExpect(status().isNotImplemented());
        mvc.perform(asAdmin(get("/api/admin/capabilities"))).andExpect(jsonPath("$.accountStatus").value(false));
    }

    // ---- flags and broadcasts ----

    @Test
    void togglingAFlagIsAuditedAndUnknownFlagsAre404() throws Exception {
        mvc.perform(asAdmin(post("/api/admin/feature-flags/sms-import")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        assertThat(jdbc.queryForObject("select enabled from feature_flags where key = 'sms-import'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'FEATURE_FLAG_SET' and target = 'sms-import'",
                Long.class)).isGreaterThanOrEqualTo(1L);
        mvc.perform(asAdmin(post("/api/admin/feature-flags/nope-flag")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isNotFound());
        mvc.perform(asAdmin(post("/api/admin/feature-flags/sms-import")).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void broadcastsGoToSegmentsOnly() throws Exception {
        mvc.perform(asAdmin(post("/api/admin/broadcasts")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hello\",\"body\":\"New feature\",\"audience\":\"Premium users\",\"channel\":\"PUSH\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(asAdmin(get("/api/admin/broadcasts"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].audience").value("Premium users"));
        // a single user, or an arbitrary audience, cannot be targeted
        mvc.perform(asAdmin(post("/api/admin/broadcasts")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Hi\",\"body\":\"x\",\"audience\":\"user:" + UUID.randomUUID() + "\",\"channel\":\"PUSH\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(asAdmin(post("/api/admin/broadcasts")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\",\"body\":\"x\",\"audience\":\"All users\",\"channel\":\"PUSH\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- system ----

    @Test
    void healthShowsWhichServicesAnswer() throws Exception {
        mvc.perform(asAdmin(get("/api/admin/system/health"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.service=='stub')].status").value("UP"))
                .andExpect(jsonPath("$[?(@.service=='gone')].status").value("DOWN"))
                .andExpect(jsonPath("$[?(@.service=='admin-service')].status").value("UP"));
    }
}
