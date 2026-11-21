package com.fintech.admin_service.service;

import com.fintech.admin_service.config.AdminProperties;
import com.fintech.admin_service.domain.ApiException;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Consumer lag per consumer group and topic: the latest offset minus the group's committed offset, summed over partitions. Read
 * only (describe/list calls); nothing is consumed or changed. Messages are never read, so no content is ever seen.
 */
@Service
public class KafkaLag {

    private static final Logger log = LoggerFactory.getLogger(KafkaLag.class);
    private static final long TIMEOUT_MS = 4000;

    private final String bootstrap;
    private volatile Admin admin;
    private static final long CACHE_NANOS = 30_000_000_000L;
    private List<Map<String, Object>> cached;
    private long cachedAt;

    public KafkaLag(AdminProperties properties) {
        this.bootstrap = properties.kafkaBootstrapServers();
    }

    /** Answers from a 30-second cache: lag moves slowly, and each refresh costs several round trips to Kafka. */
    public synchronized List<Map<String, Object>> lag() {
        if (bootstrap == null || bootstrap.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", "Kafka is not configured for admin-service");
        }
        if (cached != null && System.nanoTime() - cachedAt < CACHE_NANOS) {
            return cached;
        }
        try {
            Admin client = client();
            // Three round trips in total, whatever the number of groups: list the groups, read all their committed offsets
            // in one request, then the latest offsets of every partition involved in one request.
            Map<String, ListConsumerGroupOffsetsSpec> specs = new HashMap<>();
            for (ConsumerGroupListing group : client.listConsumerGroups().all().get(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                specs.put(group.groupId(), new ListConsumerGroupOffsetsSpec());
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            if (!specs.isEmpty()) {
                Map<String, Map<TopicPartition, OffsetAndMetadata>> committedByGroup =
                        client.listConsumerGroupOffsets(specs).all().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                Map<TopicPartition, OffsetSpec> request = new HashMap<>();
                committedByGroup.values().forEach(m -> m.keySet().forEach(tp -> request.put(tp, OffsetSpec.latest())));
                Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest = request.isEmpty() ? Map.of()
                        : client.listOffsets(request).all().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                committedByGroup.forEach((groupId, committed) -> {
                    Map<String, Long> byTopic = new HashMap<>();
                    committed.forEach((tp, offset) -> {
                        long end = latest.containsKey(tp) ? latest.get(tp).offset() : 0;
                        long done = offset == null ? 0 : offset.offset();
                        byTopic.merge(tp.topic(), Math.max(end - done, 0), Long::sum);
                    });
                    byTopic.forEach((topic, lag) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("topic", topic);
                        row.put("consumerGroup", groupId);
                        row.put("lag", lag);
                        rows.add(row);
                    });
                });
            }
            rows.sort(Comparator.comparing((Map<String, Object> r) -> (String) r.get("topic"))
                    .thenComparing(r -> (String) r.get("consumerGroup")));
            cached = List.copyOf(rows);
            cachedAt = System.nanoTime();
            return cached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception e) {
            log.warn("Kafka lag not available: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private Admin client() {
        Admin current = admin;
        if (current == null) {
            synchronized (this) {
                if (admin == null) {
                    Properties p = new Properties();
                    p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                    p.put(AdminClientConfig.CLIENT_ID_CONFIG, "admin-service-lag");
                    p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TIMEOUT_MS);
                    p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TIMEOUT_MS);
                    admin = Admin.create(p);
                }
                current = admin;
            }
        }
        return current;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_UNAVAILABLE", "Kafka is not reachable");
    }

    @PreDestroy
    void close() {
        if (admin != null) {
            admin.close();
        }
    }
}
