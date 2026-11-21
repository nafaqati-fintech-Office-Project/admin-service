package com.fintech.admin_service.service;

import com.fintech.admin_service.downstream.Downstream;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Sends a broadcast to every account of its segment, one notification per account, in the background (one broadcast at a time).
 * The account ids are fetched from user-service / billing-service and used only to address the notifications: they are never
 * stored here or shown. A repeated delivery is harmless: notification-service de-duplicates per broadcast and account.
 */
@Service
public class BroadcastDelivery {

    private static final Logger log = LoggerFactory.getLogger(BroadcastDelivery.class);

    private final Downstream downstream;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "broadcast-delivery");
        t.setDaemon(true);
        return t;
    });

    public BroadcastDelivery(Downstream downstream, JdbcTemplate jdbc, AuditService audit) {
        this.downstream = downstream;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public void deliverLater(UUID id, String title, String body, String audience, String actor) {
        worker.submit(() -> deliver(id, title, body, audience, actor));
    }

    void deliver(UUID id, String title, String body, String audience, String actor) {
        jdbc.update("update broadcasts set status = 'SENDING' where id = ?", id);
        List<String> recipients;
        try {
            recipients = recipients(audience);
        } catch (RuntimeException e) {
            log.warn("Broadcast {} not delivered: the audience could not be resolved ({})", id, e.getClass().getSimpleName());
            finish(id, "FAILED", 0, 0);
            audit.record(actor, "BROADCAST_DELIVER", id.toString(), "FAILED", "audience unavailable");
            return;
        }
        int reached = 0;
        int failed = 0;
        for (String userId : recipients) {
            try {
                downstream.notifyBroadcast(userId, id, title, body);
                reached++;
            } catch (RuntimeException e) {
                failed++;
            }
        }
        String status = failed == 0 ? "SENT" : reached == 0 ? "FAILED" : "PARTIAL";
        finish(id, status, reached, failed);
        audit.record(actor, "BROADCAST_DELIVER", id.toString(), failed == 0 ? "SUCCESS" : "FAILED",
                "reached " + reached + ", failed " + failed);
        log.info("Broadcast {} {}: reached {}, failed {}", id, status, reached, failed);
    }

    private List<String> recipients(String audience) {
        List<String> live = downstream.liveUserIds();
        if ("ALL".equals(audience)) {
            return live;
        }
        Set<String> premium = new HashSet<>(downstream.premiumUserIds());
        boolean wantPremium = "PREMIUM".equals(audience);
        return live.stream().filter(id -> premium.contains(id) == wantPremium).toList();
    }

    private void finish(UUID id, String status, int reached, int failed) {
        jdbc.update("update broadcasts set status = ?, reach = ?, failed = ?, sent_at = ? where id = ?",
                status, reached, failed, Timestamp.from(Instant.now()), id);
    }

    @PreDestroy
    void stop() {
        worker.shutdown();
    }
}
