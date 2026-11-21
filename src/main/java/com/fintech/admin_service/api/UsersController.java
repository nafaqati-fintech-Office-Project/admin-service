package com.fintech.admin_service.api;

import com.fintech.admin_service.domain.ApiException;
import com.fintech.admin_service.downstream.Downstream;
import com.fintech.admin_service.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

/**
 * Accounts: metadata only (pseudonymous id, masked e-mail, profile status, join date, plan state, suspended, sign-in method),
 * an exact support lookup by e-mail or username, and the account
 * actions an admin may take. Each action is performed by the service that owns the data and written to the audit log, whatever
 * the outcome. An admin can never act on their own account, and admin accounts cannot be suspended or deleted from the panel.
 */
@RestController
@RequestMapping("/api/admin/users")
public class UsersController {

    private static final Logger log = LoggerFactory.getLogger(UsersController.class);

    public record StatusChange(String status) {
    }

    public record Deletion(Boolean immediate) {
    }

    public record SubscriptionEnd(String mode) {
    }

    public record PlanChange(String plan, String interval) {
    }

    public record Find(String identifier) {
    }

    private final Downstream downstream;
    private final AuditService audit;

    public UsersController(Downstream downstream, AuditService audit) {
        this.downstream = downstream;
        this.audit = audit;
    }

    /** One page of accounts, newest first, filtered by account ref prefix and profile status. */
    @GetMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
                                    @RequestParam(required = false) String ref, @RequestParam(required = false) String status,
                                    HttpServletRequest request) {
        Map<String, Object> source;
        try {
            source = downstream.userList(Math.max(page, 0), Math.min(Math.max(size, 1), 100), ref, status);
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_UNAVAILABLE", "user-service is not reachable");
        }
        List<Map<String, Object>> items = Projection.pickAll((List<Map<String, Object>>) source.get("items"),
                "id", "status", "createdAt");
        List<String> ids = items.stream().map(i -> String.valueOf(i.get("id"))).toList();

        // auth-service and billing-service are asked at the same time
        String bearer = request.getHeader(HttpHeaders.AUTHORIZATION);
        var authF = com.fintech.admin_service.service.Parallel.start(() -> ids.isEmpty() ? List.<Map<String, Object>>of()
                : attempt("auth-service", () -> downstream.authLookup(ids, bearer)));
        var billingF = com.fintech.admin_service.service.Parallel.start(() -> ids.isEmpty() ? List.<Map<String, Object>>of()
                : attempt("billing-service", () -> downstream.billingLookup(ids)));
        Map<String, Map<String, Object>> authById = byId(authF.join());
        Map<String, Map<String, Object>> billingById = byId(billingF.join());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> row = new LinkedHashMap<>(item);
            Map<String, Object> a = authById == null ? null : authById.get(String.valueOf(item.get("id")));
            Map<String, Object> b = billingById == null ? null : billingById.get(String.valueOf(item.get("id")));
            row.put("suspended", a == null ? null : a.get("suspended"));
            row.put("authProvider", a == null ? null : a.get("authProvider"));
            row.put("emailMasked", a == null ? null : a.get("emailMasked"));
            row.put("admin", a != null && Boolean.TRUE.equals(a.get("admin")));
            row.put("plan", billingById == null ? null : b == null ? "FREE" : b.get("plan"));
            row.put("subscriptionStatus", b == null ? null : b.get("status"));
            row.put("cancelAtPeriodEnd", b != null && Boolean.TRUE.equals(b.get("cancelAtPeriodEnd")));
            row.put("periodEnd", b == null ? null : b.get("periodEnd"));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows);
        out.put("total", source.get("total"));
        out.put("page", source.getOrDefault("page", page));
        out.put("size", source.getOrDefault("size", size));
        out.put("sources", Map.of("auth", authById != null, "billing", billingById != null));
        return out;
    }

    /**
     * Support lookup: the full e-mail address or username from a support request, matched exactly by auth-service. Answers with
     * that one account's row (masked e-mail, never the full address) or 404. The identifier travels in the body, is never stored,
     * and the audit log records only that a lookup happened, by which kind of identifier and which account it found.
     */
    @PostMapping("/lookup")
    @SuppressWarnings("unchecked")
    public Map<String, Object> lookup(@RequestBody Find find, HttpServletRequest request) {
        String identifier = find == null || find.identifier() == null ? "" : find.identifier().trim();
        String kind = identifier.contains("@") ? "by e-mail" : "by username";
        if (identifier.length() < 3 || identifier.length() > 255 || identifier.matches(".*[*%?\\s].*")) {
            audit.record(Actor.id(request), "ACCOUNT_LOOKUP", "-", "DENIED", "invalid identifier");
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Enter the full e-mail address or username");
        }
        String id;
        try {
            id = String.valueOf(downstream.resolveAccount(identifier, bearer(request)).get("id"));
        } catch (ApiException e) {
            audit.record(Actor.id(request), "ACCOUNT_LOOKUP", "-", e.status() == HttpStatus.NOT_FOUND ? "SUCCESS" : "FAILED",
                    kind + (e.status() == HttpStatus.NOT_FOUND ? ": no match" : ": " + e.code()));
            if (e.status() == HttpStatus.NOT_FOUND) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No account matches this e-mail address or username");
            }
            throw e;
        }
        audit.record(Actor.id(request), "ACCOUNT_LOOKUP", id, "SUCCESS", kind);
        List<Map<String, Object>> items = (List<Map<String, Object>>) list(0, 1, id, null, request).get("items");
        if (items == null || items.isEmpty()) {
            // a login without a profile (registration still being processed): nothing to act on yet
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "The account has no profile yet");
        }
        return items.get(0);
    }

    /** SUSPENDED blocks sign-in and ends every session; ACTIVE lifts it. */
    @PostMapping("/{id}/status")
    public Map<String, Object> setStatus(@PathVariable UUID id, @RequestBody StatusChange change, HttpServletRequest request) {
        String target = change == null || change.status() == null ? "" : change.status().trim().toUpperCase();
        String action = switch (target) {
            case "SUSPENDED" -> "suspend";
            case "ACTIVE" -> "reactivate";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "status must be SUSPENDED or ACTIVE");
        };
        return run(request, "ACCOUNT_" + action.toUpperCase(), id, null, () -> {
            notSelf(request, id);
            downstream.accountAction(id, action, bearer(request));
            return Map.of("ok", true, "suspended", "suspend".equals(action));
        });
    }

    @PostMapping("/{id}/force-logout")
    public Map<String, Object> forceLogout(@PathVariable UUID id, HttpServletRequest request) {
        return run(request, "ACCOUNT_FORCE_LOGOUT", id, null, () -> {
            downstream.accountAction(id, "force-logout", bearer(request));
            return Map.of("ok", true);
        });
    }

    /**
     * Deletes an account. Scheduled (immediate=false): the normal grace period, the user can still restore it, and Premium is
     * not renewed. Immediate: Premium ends now and the account is erased everywhere; this cannot be undone.
     */
    @PostMapping("/{id}/delete")
    public Map<String, Object> delete(@PathVariable UUID id, @RequestBody(required = false) Deletion deletion,
                                      HttpServletRequest request) {
        boolean immediate = deletion != null && Boolean.TRUE.equals(deletion.immediate());
        return run(request, immediate ? "ACCOUNT_DELETE_NOW" : "ACCOUNT_DELETE_SCHEDULED", id, null, () -> {
            notSelf(request, id);
            notAdmin(id, request);
            endSubscriptionQuietly(id, immediate ? "NOW" : "PERIOD_END", request);
            Map<String, Object> result = Projection.pick(downstream.deleteAccount(id, immediate, bearer(request)),
                    "id", "status", "purgeAfter", "completedAt");
            Map<String, Object> out = new LinkedHashMap<>(result);
            out.put("ok", true);
            return out;
        });
    }

    /** PERIOD_END: no renewal, Premium until the paid period ends. NOW: Premium ends immediately. No refund is made. */
    @PostMapping("/{id}/subscription/end")
    public Map<String, Object> endSubscription(@PathVariable UUID id, @RequestBody SubscriptionEnd end, HttpServletRequest request) {
        String mode = end == null || end.mode() == null ? "" : end.mode().trim().toUpperCase();
        if (!mode.equals("PERIOD_END") && !mode.equals("NOW")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "mode must be PERIOD_END or NOW");
        }
        return run(request, "SUBSCRIPTION_END_" + mode, id, mode, () -> {
            notSelf(request, id);
            Map<String, Object> out = new LinkedHashMap<>(Projection.pick(downstream.endSubscription(id, mode), "mode", "periodEnd"));
            out.put("ok", true);
            return out;
        });
    }

    /**
     * PREMIUM: complimentary Premium for one month or year (interval MONTHLY / YEARLY); nobody is charged and it is not renewed.
     * FREE: Premium ends now (same as ending the subscription now). No money is moved either way.
     */
    @PostMapping("/{id}/plan")
    public Map<String, Object> setPlan(@PathVariable UUID id, @RequestBody PlanChange change, HttpServletRequest request) {
        String plan = change == null || change.plan() == null ? "" : change.plan().trim().toUpperCase();
        String interval = change == null || change.interval() == null ? "MONTHLY" : change.interval().trim().toUpperCase();
        if (plan.equals("FREE")) {
            return run(request, "PLAN_SET_FREE", id, null, () -> {
                notSelf(request, id);
                downstream.endSubscription(id, "NOW");
                return Map.of("ok", true, "plan", "FREE");
            });
        }
        if (!plan.equals("PREMIUM") || !(interval.equals("MONTHLY") || interval.equals("YEARLY"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "plan must be PREMIUM (MONTHLY or YEARLY) or FREE");
        }
        return run(request, "PLAN_GRANT_PREMIUM", id, interval, () -> {
            notSelf(request, id);   // no free Premium for yourself
            Map<String, Object> out = new LinkedHashMap<>(Projection.pick(downstream.grantPremium(id, interval), "plan", "periodEnd"));
            out.put("ok", true);
            return out;
        });
    }

    private void endSubscriptionQuietly(UUID id, String mode, HttpServletRequest request) {
        try {
            downstream.endSubscription(id, mode);
            audit.record(Actor.id(request), "SUBSCRIPTION_END_" + mode, id.toString(), "SUCCESS", "with account deletion");
        } catch (ApiException e) {
            if (e.status() != HttpStatus.CONFLICT) {   // 409 = no active subscription: nothing to end
                throw e;
            }
        }
    }

    private void notAdmin(UUID id, HttpServletRequest request) {
        List<Map<String, Object>> found = downstream.authLookup(List.of(id.toString()), bearer(request));
        if (found != null && found.stream().anyMatch(a -> Boolean.TRUE.equals(a.get("admin")))) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_ACCOUNT", "Admin accounts cannot be deleted from the panel");
        }
    }

    private static void notSelf(HttpServletRequest request, UUID id) {
        if (id.toString().equals(Actor.id(request))) {
            throw new ApiException(HttpStatus.CONFLICT, "OWN_ACCOUNT", "You cannot do this to your own account");
        }
    }

    private static String bearer(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.AUTHORIZATION);
    }

    private Map<String, Object> run(HttpServletRequest request, String action, UUID target, String detail,
                                    Supplier<Map<String, Object>> call) {
        String actor = Actor.id(request);
        Map<String, Object> result;
        try {
            result = call.get();
        } catch (ApiException e) {
            audit.record(actor, action, target.toString(), e.status().is4xxClientError() ? "DENIED" : "FAILED", e.code());
            throw e;
        }
        audit.record(actor, action, target.toString(), "SUCCESS", detail);
        return result;
    }

    private static Map<String, Map<String, Object>> byId(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        Map<String, Map<String, Object>> out = new HashMap<>();
        rows.forEach(r -> out.put(String.valueOf(r.get("id")), r));
        return out;
    }

    private static <T> T attempt(String source, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("{} is not available for the accounts list: {}", source, e.getClass().getSimpleName());
            return null;
        }
    }
}
