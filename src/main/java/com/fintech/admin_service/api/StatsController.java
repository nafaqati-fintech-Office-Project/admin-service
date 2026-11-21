package com.fintech.admin_service.api;

import com.fintech.admin_service.service.OverviewService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard aggregates and what this deployment can do. The accounts list is in UsersController. */
@RestController
@RequestMapping("/api/admin")
public class StatsController {

    private final OverviewService overview;

    public StatsController(OverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/stats/overview")
    public Map<String, Object> overview(jakarta.servlet.http.HttpServletRequest request) {
        return overview.overview(request.getHeader(org.springframework.http.HttpHeaders.AUTHORIZATION));
    }

    @GetMapping("/stats/billing")
    public Map<String, Object> billing() {
        return overview.billing();
    }

    /** Tells the panel which features have a real backend, so it never offers a button that cannot work. */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("accountStatus", true);         // auth-service /internal/admin/users/{id}/suspend|reactivate
        c.put("forceLogout", true);           // auth-service revokes every refresh token
        c.put("deleteAccount", true);         // user-service, scheduled (grace period) or immediate
        c.put("endSubscription", true);       // billing-service, at period end or now
        c.put("changePlan", true);            // billing-service: complimentary Premium, or Free now
        c.put("kafkaLag", true);              // read-only AdminClient
        c.put("broadcastDelivery", true);     // notification-service, in-app + push
        c.put("featureFlagsEnforced", true);  // per flag: see enforcedBy in GET /feature-flags
        return c;
    }

}
