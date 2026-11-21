package com.fintech.admin_service.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The feature flag values, for the services that enforce them (billing-service today). Read only: {key, enabled}, nothing else.
 * Not under /api/admin, so not reachable through the gateway; this service binds to loopback only.
 */
@RestController
public class InternalFlagsController {

    private final JdbcTemplate jdbc;

    public InternalFlagsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/internal/flags")
    public List<Map<String, Object>> flags() {
        return jdbc.query("select key, enabled from feature_flags order by key",
                (rs, i) -> Map.<String, Object>of("key", rs.getString("key"), "enabled", rs.getBoolean("enabled")));
    }
}
