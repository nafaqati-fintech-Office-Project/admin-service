package com.fintech.admin_service.api;

import com.fintech.admin_service.config.AdminProperties;
import com.fintech.admin_service.service.KafkaLag;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Service health (each service's own /actuator/health, probed in parallel with a short timeout) and Kafka consumer lag. */
@RestController
@RequestMapping("/api/admin/system")
public class SystemController {

    private final Map<String, String> targets;
    private final RestClient probe;
    private final KafkaLag kafkaLag;

    public SystemController(AdminProperties properties, KafkaLag kafkaLag) {
        this.kafkaLag = kafkaLag;
        this.targets = properties.healthTargetMap();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofMillis(800));
        factory.setReadTimeout(java.time.Duration.ofMillis(1200));
        this.probe = RestClient.builder().requestFactory(factory).build();
    }

    @GetMapping("/health")
    public List<Map<String, Object>> health() {
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        targets.forEach((name, url) -> futures.add(CompletableFuture.supplyAsync(() -> check(name, url))));
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(row("admin-service", 8088, true, 0));
        futures.forEach(f -> out.add(f.join()));
        return out;
    }

    /** Consumer lag per group and topic (read-only AdminClient). */
    @GetMapping("/kafka")
    public List<Map<String, Object>> kafka() {
        return kafkaLag.lag();
    }

    private Map<String, Object> check(String name, String baseUrl) {
        int port = URI.create(baseUrl).getPort();
        long started = System.nanoTime();
        try {
            probe.get().uri(baseUrl + "/actuator/health").retrieve().toBodilessEntity();
            return row(name, port, true, (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException e) {
            return row(name, port, false, 0);
        }
    }

    private static Map<String, Object> row(String service, int port, boolean up, long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", service);
        m.put("port", port);
        m.put("status", up ? "UP" : "DOWN");
        m.put("latencyMs", up ? ms : null);
        return m;
    }
}
