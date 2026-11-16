package com.fintech.admin_service.api;

import com.fintech.admin_service.downstream.Downstream;
import com.fintech.admin_service.domain.ApiException;
import com.fintech.admin_service.service.OverviewService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

/** Dashboard aggregates, the accounts list (id, status, join date) and what this deployment can do yet. */
@RestController
@RequestMapping("/api/admin")
public class StatsController {

    private final OverviewService overview;
    private final Downstream downstream;

    public StatsController(OverviewService overview, Downstream downstream) {
        this.overview = overview;
        this.downstream = downstream;
    }

    @GetMapping("/stats/overview")
    public Map<String, Object> overview() {
        return overview.overview();
    }

    @GetMapping("/stats/billing")
    public Map<String, Object> billing() {
        return overview.billing();
    }

    /** The newest 100 accounts. Only the pseudonymous id, status and join date: nothing that identifies the person. */
    @GetMapping("/users")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> users() {
        try {
            return Projection.pickAll((List<Map<String, Object>>) downstream.userList(0, 100).get("items"),
                    "id", "status", "createdAt");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_UNAVAILABLE", "user-service is not reachable");
        }
    }

    /** Suspending an account and signing it out live in auth-service, which has no admin API yet. */
    @PostMapping("/users/{id}/status")
    public void setStatus(@PathVariable String id) {
        throw notSupported();
    }

    @PostMapping("/users/{id}/force-logout")
    public void forceLogout(@PathVariable String id) {
        throw notSupported();
    }

    /** Tells the panel which features have a real backend, so it never offers a button that cannot work. */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("accountStatus", false);        // needs an admin API in auth-service
        c.put("forceLogout", false);          // same
        c.put("kafkaLag", false);             // not exposed yet
        c.put("broadcastDelivery", false);    // broadcasts are recorded but not yet delivered by notification-service
        c.put("featureFlagsEnforced", false); // services do not read the flags yet
        return c;
    }

    private static ApiException notSupported() {
        return new ApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_SUPPORTED_YET", "This action is not available yet");
    }
}
