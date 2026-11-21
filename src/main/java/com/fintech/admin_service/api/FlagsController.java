package com.fintech.admin_service.api;

import com.fintech.admin_service.domain.ApiException;
import com.fintech.admin_service.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The flag registry. Changing a flag is audited. enforcedBy names the service that reads a flag (GET /internal/flags, within 30
 * seconds); null means no service reads it yet, so switching it changes nothing in the apps.
 */
@RestController
@RequestMapping("/api/admin/feature-flags")
public class FlagsController {

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{1,58}");

    public record FlagUpdate(@NotNull Boolean enabled) {
    }

    /** Which service reads which flag. family-premium-enforcement is a product decision that is not implemented yet. */
    static final Map<String, String> ENFORCED_BY = Map.of("paymob-checkout", "billing-service", "sms-import", "billing-service");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public FlagsController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.query("select key, summary, enabled from feature_flags order by key",
                (rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("key", rs.getString("key"));
                    m.put("summary", rs.getString("summary"));
                    m.put("enabled", rs.getBoolean("enabled"));
                    m.put("enforcedBy", ENFORCED_BY.get(rs.getString("key")));
                    return m;
                });
    }

    @PostMapping("/{key}")
    public Map<String, Object> set(@PathVariable String key, @jakarta.validation.Valid @RequestBody FlagUpdate update, HttpServletRequest request) {
        if (!KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is not valid");
        }
        String actor = Actor.id(request);
        int changed = jdbc.update("update feature_flags set enabled = ?, updated_at = ?, updated_by = ? where key = ?",
                update.enabled(), Timestamp.from(Instant.now()), actor, key);
        if (changed == 0) {
            audit.record(actor, "FEATURE_FLAG_SET", key, "FAILED", "unknown flag");
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown feature flag");
        }
        audit.record(actor, "FEATURE_FLAG_SET", key, "SUCCESS", update.enabled() ? "enabled" : "disabled");
        return Map.of("key", key, "enabled", update.enabled());
    }
}
