package com.fintech.admin_service.api;

import com.fintech.admin_service.domain.ApiException;
import com.fintech.admin_service.service.AuditService;
import com.fintech.admin_service.service.BroadcastDelivery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Announcements to a whole segment (all, Premium, Free). There is deliberately no way to address one person or to pick an
 * audience by financial behaviour. A broadcast is recorded, audited and then delivered in the background through
 * notification-service as an in-app notification that is also pushed to the person's phones (BroadcastDelivery). E-mail is not
 * offered: no service that may hold addresses sends announcements.
 */
@RestController
@RequestMapping("/api/admin/broadcasts")
public class BroadcastsController {

    // 200: the longest text notification-service accepts in one template parameter
    public record NewBroadcast(@NotBlank @Size(max = 80) String title, @NotBlank @Size(max = 200) String body,
                               @NotBlank String audience, @NotBlank String channel) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final BroadcastDelivery delivery;

    public BroadcastsController(JdbcTemplate jdbc, AuditService audit, BroadcastDelivery delivery) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.delivery = delivery;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.query("select id, title, audience, channel, status, sent_at, created_at, reach, failed from broadcasts "
                        + "order by created_at desc limit 50",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("title", rs.getString("title"));
                    m.put("audience", label(rs.getString("audience")));
                    m.put("channel", rs.getString("channel"));
                    m.put("status", rs.getString("status"));
                    Timestamp when = rs.getTimestamp("sent_at") != null ? rs.getTimestamp("sent_at") : rs.getTimestamp("created_at");
                    m.put("sentAt", when.toInstant().toString());
                    m.put("reach", rs.getObject("reach") == null ? 0 : rs.getInt("reach"));
                    m.put("failed", rs.getObject("failed") == null ? 0 : rs.getInt("failed"));
                    return m;
                });
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody NewBroadcast request, HttpServletRequest http) {
        String audience = audienceCode(request.audience());
        String channel = request.channel().toUpperCase();
        if (!List.of("PUSH", "IN_APP").contains(channel)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is not valid");
        }
        UUID id = UUID.randomUUID();
        String actor = Actor.id(http);
        jdbc.update("insert into broadcasts (id, title, body, audience, channel, status, created_by, created_at) "
                        + "values (?, ?, ?, ?, ?, 'QUEUED', ?, ?)",
                id, request.title().trim(), request.body().trim(), audience, channel, actor, Timestamp.from(Instant.now()));
        audit.record(actor, "BROADCAST_CREATE", id.toString(), "SUCCESS", audience + "/" + channel);
        delivery.deliverLater(id, request.title().trim(), request.body().trim(), audience, actor);
        return Map.of("id", id.toString(), "status", "QUEUED");
    }

    /** Accepts the panel's labels as well as the codes. Anything else is refused: no custom or per-person audience. */
    static String audienceCode(String value) {
        return switch (value.trim().toLowerCase()) {
            case "all users", "all" -> "ALL";
            case "premium users", "premium" -> "PREMIUM";
            case "free users", "free" -> "FREE";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is not valid");
        };
    }

    private static String label(String code) {
        return switch (code) {
            case "PREMIUM" -> "Premium users";
            case "FREE" -> "Free users";
            default -> "All users";
        };
    }
}
