package com.fintech.admin_service.downstream;

import com.fintech.admin_service.config.AdminProperties;
import com.fintech.admin_service.domain.ApiException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls to the other services. Reads use the read-only /internal/admin/** projections (ids, states, counts: no personal data).
 * <p>
 * Admin actions are performed by the service that owns the data: user-service and auth-service receive the signed-in admin's own
 * bearer token and check ROLE_ADMIN themselves; billing-service and notification-service accept admin-service by its internal
 * credentials and caller name. Every action is audited by the caller of these methods.
 */
@Component
public class Downstream {

    public static final String CALLER = "admin-service";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<String>> STRINGS = new ParameterizedTypeReference<>() { };

    private final RestClient users;
    private final RestClient billing;
    private final RestClient families;
    private final RestClient transactions;
    private final RestClient notifications;
    private final RestClient usersApi;
    private final RestClient auth;

    public Downstream(AdminProperties properties, @Value("${internal.api-token:}") String sharedToken,
                      @Value("${INTERNAL_CALLER_SECRET:}") String ownSecret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.downstream().timeout());
        factory.setReadTimeout(properties.downstream().timeout());
        String token = ownSecret != null && !ownSecret.isBlank() ? ownSecret : sharedToken == null ? "" : sharedToken;
        var d = properties.downstream();
        this.users = internalClient(d.userServiceUrl(), factory, token);
        this.billing = internalClient(d.billingServiceUrl(), factory, token);
        this.families = internalClient(d.familyServiceUrl(), factory, token);
        this.transactions = internalClient(d.transactionServiceUrl(), factory, token);
        this.notifications = internalClient(d.notificationServiceUrl(), factory, token);
        this.usersApi = RestClient.builder().baseUrl(d.userServiceUrl()).requestFactory(factory).build();
        // auth-service has no internal token: it authenticates the admin's JWT and checks the caller name
        this.auth = RestClient.builder().baseUrl(d.authServiceUrl() == null ? "http://127.0.0.1:8081" : d.authServiceUrl())
                .requestFactory(factory).defaultHeader("X-Internal-Service", CALLER).build();
    }

    private static RestClient internalClient(String baseUrl, SimpleClientHttpRequestFactory factory, String token) {
        return RestClient.builder().baseUrl(baseUrl == null ? "http://127.0.0.1:1" : baseUrl).requestFactory(factory)
                .defaultHeader("X-Internal-Service", CALLER).defaultHeader("X-Internal-Token", token).build();
    }

    // ---- aggregates ----

    public Map<String, Object> userStats() {
        return users.get().uri("/internal/admin/stats").retrieve().body(MAP);
    }

    public Map<String, Object> transactionStats() {
        return transactions.get().uri("/internal/admin/stats").retrieve().body(MAP);
    }

    public Map<String, Object> familyStats() {
        return families.get().uri("/internal/admin/stats").retrieve().body(MAP);
    }

    public Map<String, Object> billingStats() {
        return billing.get().uri("/internal/admin/stats").retrieve().body(MAP);
    }

    /** Active-account counts (sign-ins and session refreshes) from auth-service. Needs the admin's bearer token. */
    public Map<String, Object> loginActivity(String authorization) {
        requireBearer(authorization);
        return auth.get().uri("/internal/admin/activity").header("Authorization", authorization).retrieve().body(MAP);
    }

    // ---- accounts ----

    public Map<String, Object> userList(int page, int size, String ref, String status) {
        return users.get().uri(b -> {
            b.path("/internal/admin/users").queryParam("page", page).queryParam("size", size);
            if (ref != null && !ref.isBlank()) {
                b.queryParam("ref", ref.trim());
            }
            if (status != null && !status.isBlank()) {
                b.queryParam("status", status.trim());
            }
            return b.build();
        }).retrieve().body(MAP);
    }

    /** The account id for the exact e-mail or username a person gave support (auth-service, as the admin). */
    public Map<String, Object> resolveAccount(String identifier, String authorization) {
        requireBearer(authorization);
        Map<String, Object> body = call("auth-service", () -> auth.post().uri("/internal/admin/accounts/resolve")
                .header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("identifier", identifier)).retrieve().body(MAP));
        return body == null ? Map.of() : body;
    }

    /** {id, suspended, authProvider, admin, emailMasked} per known id. */
    public List<Map<String, Object>> authLookup(Collection<String> ids, String authorization) {
        requireBearer(authorization);
        return auth.post().uri("/internal/admin/accounts/lookup").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("ids", ids)).retrieve().body(LIST);
    }

    /** {id, plan, status, cancelAtPeriodEnd, periodEnd} per id that has a membership row. */
    public List<Map<String, Object>> billingLookup(Collection<String> ids) {
        return billing.post().uri("/internal/admin/memberships/lookup").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("ids", ids)).retrieve().body(LIST);
    }

    /** suspend | reactivate | force-logout, run by auth-service as the admin. */
    public void accountAction(UUID id, String action, String authorization) {
        requireBearer(authorization);
        call("auth-service", () -> auth.post().uri("/internal/admin/users/{id}/" + action, id)
                .header("Authorization", authorization).retrieve().toBodilessEntity());
    }

    /** Opens (or reuses) a deletion request for the account; {@code immediate} purges it now everywhere. */
    public Map<String, Object> deleteAccount(UUID id, boolean immediate, String authorization) {
        requireBearer(authorization);
        Map<String, Object> body = call("user-service", () -> usersApi.post()
                .uri("/api/admin/users/{id}/deletion?immediate={i}", id, immediate)
                .header("Authorization", authorization).retrieve().body(MAP));
        return body == null ? Map.of() : body;
    }

    /** PERIOD_END (no renewal) or NOW (Premium ends immediately). */
    public Map<String, Object> endSubscription(UUID id, String mode) {
        Map<String, Object> body = call("billing-service", () -> billing.post()
                .uri("/internal/admin/memberships/{id}/end?mode={m}", id, mode).retrieve().body(MAP));
        return body == null ? Map.of() : body;
    }

    /** Complimentary Premium for one MONTHLY or YEARLY period. It is never charged and ends on its own. */
    public Map<String, Object> grantPremium(UUID id, String interval) {
        Map<String, Object> body = call("billing-service", () -> billing.post()
                .uri("/internal/admin/memberships/{id}/grant?interval={i}", id, interval).retrieve().body(MAP));
        return body == null ? Map.of() : body;
    }

    // ---- request queues ----

    public List<Map<String, Object>> emailChanges() {
        return users.get().uri("/internal/admin/email-change-requests").retrieve().body(LIST);
    }

    public List<Map<String, Object>> deletionRequests() {
        return users.get().uri("/internal/admin/deletion-requests").retrieve().body(LIST);
    }

    public void emailChangeConfirm(UUID id, String authorization) {
        forward("/api/admin/email-change-requests/{id}/confirm", id, authorization);
    }

    public void deletionAction(UUID id, String action, String authorization) {
        forward("/api/admin/deletion-requests/{id}/" + action, id, authorization);
    }

    private void forward(String path, UUID id, String authorization) {
        requireBearer(authorization);
        call("user-service", () -> usersApi.post().uri(path, id).header("Authorization", authorization).retrieve().toBodilessEntity());
    }

    // ---- broadcasts ----

    /** Every live account id (paged by user-service). Used only to address notifications; never returned to the panel. */
    @SuppressWarnings("unchecked")
    public List<String> liveUserIds() {
        List<String> out = new ArrayList<>();
        for (int page = 0; page < 1000; page++) {
            Map<String, Object> body = users.get().uri("/internal/admin/user-ids?page={p}", page).retrieve().body(MAP);
            if (body == null) {
                break;
            }
            out.addAll((List<String>) body.getOrDefault("ids", List.of()));
            if (!Boolean.TRUE.equals(body.get("more"))) {
                break;
            }
        }
        return out;
    }

    public List<String> premiumUserIds() {
        List<String> ids = billing.get().uri("/internal/admin/premium-user-ids").retrieve().body(STRINGS);
        return ids == null ? List.of() : ids;
    }

    /** One in-app notification (also pushed to the person's phones by notification-service). Idempotent per broadcast and user. */
    public void notifyBroadcast(String userId, UUID broadcastId, String title, String body) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("userId", userId);
        command.put("type", "SYSTEM");
        command.put("severity", "INFO");
        command.put("templateKey", "SYSTEM_BROADCAST");
        command.put("params", Map.of("title", title, "body", body));
        command.put("dedupeKey", "broadcast:" + broadcastId);
        notifications.post().uri("/internal/notifications").contentType(MediaType.APPLICATION_JSON).body(command)
                .retrieve().toBodilessEntity();
    }

    // ---- helpers ----

    private static void requireBearer(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
        }
    }

    /** Maps the source's answer to a status without passing its body through. */
    private static <T> T call(String source, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                throw new ApiException(status, "REJECTED_BY_SOURCE", rejection(status));
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_ERROR", source + " could not complete the request");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_UNAVAILABLE", source + " is not reachable");
        }
    }

    private static String rejection(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "The account or request was not found";
            case CONFLICT -> "Not possible in the current state (" + status.value() + ")";
            case FORBIDDEN, UNAUTHORIZED -> "Your session is not allowed to do this (" + status.value() + ")";
            default -> "The request was rejected (" + status.value() + ")";
        };
    }
}
