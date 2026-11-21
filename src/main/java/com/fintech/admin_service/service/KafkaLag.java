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

    public KafkaLag(AdminProperties properties) {
        this.bootstrap = properties.kafkaBootstrapServers();
    }

    public List<Map<String, Object>> lag() {
        if (bootstrap == null || bootstrap.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", "Kafka is not configured for admin-service");
        }
        try {
            Admin client = client();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ConsumerGroupListing group : client.listConsumerGroups().all().get(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Map<TopicPartition, OffsetAndMetadata> committed = client.listConsumerGroupOffsets(group.groupId())
                        .partitionsToOffsetAndMetadata().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (committed.isEmpty()) {
                    continue;
                }
                Map<TopicPartition, OffsetSpec> request = new HashMap<>();
                committed.keySet().forEach(tp -> request.put(tp, OffsetSpec.latest()));
                Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest =
                        client.listOffsets(request).all().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                Map<String, Long> byTopic = new HashMap<>();
                committed.forEach((tp, offset) -> {
                    long end = latest.containsKey(tp) ? latest.get(tp).offset() : 0;
                    long done = offset == null ? 0 : offset.offset();
                    byTopic.merge(tp.topic(), Math.max(end - done, 0), Long::sum);
                });
                byTopic.forEach((topic, lag) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("topic", topic);
                    row.put("consumerGroup", group.groupId());
                    row.put("lag", lag);
                    rows.add(row);
                });
            }
            rows.sort(Comparator.comparing((Map<String, Object> r) -> (String) r.get("topic"))
                    .thenComparing(r -> (String) r.get("consumerGroup")));
            return rows;
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
