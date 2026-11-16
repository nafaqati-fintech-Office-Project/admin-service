package com.fintech.admin_service.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Append-only log of admin actions (the table rejects UPDATE and DELETE). Never receives personal data. */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String target, String outcome, String detail) {
        jdbc.update("insert into audit_log (at, actor, action, target, outcome, detail) values (?, ?, ?, ?, ?, ?)",
                Timestamp.from(Instant.now()), actor, action, target, outcome, detail);
    }

    public List<Map<String, Object>> latest(int limit) {
        return jdbc.query("select id, at, actor, action, target, outcome from audit_log order by at desc, id desc limit ?",
                (rs, i) -> Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "at", rs.getTimestamp("at").toInstant().toString(),
                        "actor", ref(rs.getString("actor")),
                        "action", rs.getString("action"),
                        "target", rs.getString("target") == null ? "-" : rs.getString("target"),
                        "outcome", rs.getString("outcome")),
                limit);
    }

    private static String ref(String actor) {
        return actor.length() > 8 ? actor.substring(0, 8) : actor;
    }
}
