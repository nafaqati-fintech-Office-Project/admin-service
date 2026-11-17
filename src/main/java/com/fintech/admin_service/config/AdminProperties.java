package com.fintech.admin_service.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("admin")
public record AdminProperties(Downstream downstream, String healthTargets) {

    public record Downstream(String userServiceUrl, String billingServiceUrl, String familyServiceUrl, String transactionServiceUrl, Duration timeout) {
    }

    /** "name=url, name=url" as a name to base URL map, in declaration order. */
    public Map<String, String> healthTargetMap() {
        Map<String, String> out = new LinkedHashMap<>();
        if (healthTargets == null || healthTargets.isBlank()) {
            return out;
        }
        for (String pair : healthTargets.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return out;
    }
}
